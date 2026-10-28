package io.github.nturner03.tlaarchive.reconstruction;

import com.mojang.brigadier.CommandDispatcher;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.TeamAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The Phase 1 reconstruction UI: chat lines and commands. Sneak-using the controller with an empty hand shows what
 * would be consumed (design: "clearly explain the consumption consequence before commitment") and arms a confirmation
 * for that exact plan. {@code /tla_archive reconstruct confirm} then runs it. The catalog UI is Phase 2.
 *
 * <ul>
 * <li>{@code /tla_archive reconstruct confirm}: run the plan last previewed, if it is still the plan.</li>
 * <li>{@code /tla_archive overflow}: list the team's stock that a rollback could not put back.</li>
 * <li>{@code /tla_archive overflow take}: move that stock into the player's inventory, as far as it has room.</li>
 * </ul>
 */
public final class ReconstructionCommands {
    /** How long a preview can be confirmed. */
    public static final long PROMPT_TICKS = 60 * 20;

    private record Prompt(ResourceKey<Level> dimension, BlockPos pos, ExtractionPlan plan, long expiresAt) {
    }

    // Server thread only.
    private static final Map<UUID, Prompt> PROMPTS = new HashMap<>();

    private ReconstructionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tla_archive")
                .then(Commands.literal("reconstruct")
                        .then(Commands.literal("confirm")
                                .executes(ctx -> confirm(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("overflow")
                        .executes(ctx -> listOverflow(ctx.getSource().getPlayerOrException()))
                        .then(Commands.literal("take")
                                .executes(ctx -> takeOverflow(ctx.getSource().getPlayerOrException())))));
    }

    /** What sneak-use shows: the consequence and a confirm link, or why reconstruction is unavailable. */
    public static List<Component> preview(ArchiveControllerBlockEntity controller, ServerPlayer player) {
        PROMPTS.remove(player.getUUID());
        var blocker = Reconstruction.blocker(controller, player);
        if (blocker.isPresent()) {
            return List.of(Component.literal("Reconstruction unavailable: " + blocker.get() + ".")
                    .withStyle(ChatFormatting.GRAY));
        }
        var plan = Reconstruction.plan(controller).orElse(null);
        if (plan == null) {
            return List.of(Component.literal("Reconstruction unavailable: the network is not active.")
                    .withStyle(ChatFormatting.GRAY));
        }
        var out = new ArrayList<Component>();
        if (!plan.complete()) {
            out.add(Component.literal("Not ready for reconstruction. Short:").withStyle(ChatFormatting.GOLD));
            for (var s : plan.shortfalls()) {
                out.add(Component.literal(" - " + s.entry().displayName() + ": " + s.available() + " / "
                        + s.entry().quantity()));
            }
            return out;
        }
        out.add(Component.literal("TEST-ONLY PROTOTYPE. Reconstruction against " + plan.manifestLabel()
                + " PERMANENTLY removes this stock from the network:").withStyle(ChatFormatting.RED,
                        ChatFormatting.BOLD));
        plan.amounts().forEach((key, amount) -> out.add(Component.literal(" - " + amount + " x ")
                .append(key.getDisplayName())));
        out.add(Component.literal("Only these amounts are taken; excess and unrelated items stay. It can't be undone.")
                .withStyle(ChatFormatting.GOLD));
        out.add(Component.literal("[Confirm reconstruction]").withStyle(style -> style
                .withColor(ChatFormatting.RED).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tla_archive reconstruct confirm"))));
        out.add(Component.literal("(valid " + PROMPT_TICKS / 20 + " s; or run /tla_archive reconstruct confirm)")
                .withStyle(ChatFormatting.GRAY));
        var level = player.serverLevel();
        PROMPTS.put(player.getUUID(), new Prompt(level.dimension(), controller.getBlockPos(), plan,
                level.getGameTime() + PROMPT_TICKS));
        return out;
    }

    private static int confirm(ServerPlayer player) {
        var prompt = PROMPTS.remove(player.getUUID());
        var level = player.server.getLevel(prompt == null ? Level.OVERWORLD : prompt.dimension());
        if (prompt == null || level == null || level.getGameTime() > prompt.expiresAt()) {
            return fail(player, "No reconstruction to confirm. Sneak and use the controller with an empty hand first.");
        }
        if (player.level() != level || player.blockPosition().distSqr(prompt.pos()) > 16 * 16) {
            return fail(player, "Stand near the controller to confirm.");
        }
        if (!(level.getBlockEntity(prompt.pos()) instanceof ArchiveControllerBlockEntity controller)) {
            return fail(player, "The controller is gone.");
        }
        var result = Reconstruction.start(controller, player, prompt.plan(), Reconstruction.Pacing.fromConfig());
        player.sendSystemMessage(Component.literal(result.message())
                .withStyle(result.started() ? ChatFormatting.GREEN : ChatFormatting.RED));
        return result.started() ? 1 : 0;
    }

    private static int listOverflow(ServerPlayer player) {
        var team = TeamAccess.teamOf(player).orElse(null);
        var ops = team == null ? List.<ReconstructionOperation>of()
                : Reconstruction.journal(player.server).pendingFor(team).stream()
                        .filter(op -> op.state() == ReconstructionOperation.State.ROLLING_BACK).toList();
        if (ops.isEmpty()) {
            player.sendSystemMessage(Component.literal("Your team has no reconstruction overflow."));
            return 0;
        }
        for (var op : ops) {
            player.sendSystemMessage(Component.literal("Overflow from reconstruction " + op.id() + " at "
                    + op.controllerPos().toShortString() + " (" + op.outcome() + "):").withStyle(ChatFormatting.GOLD));
            for (var e : op.staged()) {
                player.sendSystemMessage(Component.literal(" - " + e.getLongValue() + " x ")
                        .append(e.getKey().getDisplayName()));
            }
        }
        player.sendSystemMessage(Component.literal("It goes back into the network when there is room, or run "
                + "/tla_archive overflow take.").withStyle(ChatFormatting.GRAY));
        return ops.size();
    }

    private static int takeOverflow(ServerPlayer player) {
        long moved = Reconstruction.takeOverflow(player);
        player.sendSystemMessage(Component.literal(moved > 0 ? "Moved " + moved + " overflow items to your inventory."
                : "Nothing moved: no overflow, or no room in your inventory."));
        return (int) Math.min(moved, Integer.MAX_VALUE);
    }

    private static int fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
