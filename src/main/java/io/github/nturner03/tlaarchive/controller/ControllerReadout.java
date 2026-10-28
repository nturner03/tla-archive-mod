package io.github.nturner03.tlaarchive.controller;

import io.github.nturner03.tlaarchive.ArchiveConfig;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.reconstruction.Reconstruction;
import io.github.nturner03.tlaarchive.reconstruction.ReconstructionOperation;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import io.github.nturner03.tlaarchive.team.TeamArchive;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The words the catalog and chat use for a controller: who may see it, its state and manifest, and its reconstruction
 * status. The per-entry counts are only in the catalog screen ({@code catalog/}), so a large manifest never floods
 * chat.
 */
public final class ControllerReadout {
    private ControllerReadout() {
    }

    /** Why {@code player} may not open the controller, or empty if they may (their current team owns it). */
    public static Optional<Component> denial(ArchiveControllerBlockEntity controller, ServerPlayer player) {
        controller.checkOwnerTeam();
        if (controller.state() == ArchiveState.FROZEN) {
            return Optional.of(Component.literal("Archive controller: FROZEN. Its team no longer exists. A former "
                    + "member can break and re-place it to claim it.").withStyle(ChatFormatting.GRAY));
        }
        if (controller.ownerTeam().isEmpty()) {
            return Optional.of(Component.literal("This archive controller has no owner team. Break and re-place it "
                    + "to claim it.").withStyle(ChatFormatting.GRAY));
        }
        if (!controller.isOwnedBy(player)) {
            return Optional.of(Component.literal("This archive belongs to another team.")
                    .withStyle(ChatFormatting.RED));
        }
        return Optional.empty();
    }

    /** The state with the reading's age, the manifest label, and the label the counts were read against if older. */
    public static List<Component> status(ArchiveControllerBlockEntity controller, long gameTime) {
        var out = new ArrayList<Component>();
        var state = controller.state();
        out.add(Component.literal("Archive controller: " + state + " " + describe(controller, state, gameTime))
                .withStyle(state == ArchiveState.LIVE ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        var matcher = ActiveManifest.matcher().orElse(null);
        if (matcher == null) {
            out.add(Component.literal("No valid archive manifest is loaded.").withStyle(ChatFormatting.RED));
        } else {
            var label = matcher.manifest().label();
            out.add(Component.literal("Manifest " + label).withStyle(ChatFormatting.GRAY));
            if (controller.readingGameTime() >= 0 && !label.equals(controller.readingManifest())) {
                out.add(Component.literal("Counts were read against " + controller.readingManifest())
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return out;
    }

    /** Completion, a running operation, overflow still to go back, or whether reconstruction is available. */
    public static List<Component> reconstructionLines(ArchiveControllerBlockEntity controller) {
        var out = new ArrayList<Component>();
        var team = controller.ownerTeam().orElse(null);
        if (team != null && controller.getLevel() instanceof ServerLevel level) {
            var journal = Reconstruction.journal(level.getServer());
            ArchiveTeams.get(level.getServer()).find(team).flatMap(TeamArchive::reconstructionComplete)
                    .ifPresent(milestone -> out.add(Component.literal("Reconstruction COMPLETE (against "
                            + milestone.manifestLabel() + ", game time " + milestone.gameTime() + ")")
                            .withStyle(ChatFormatting.GREEN)));
            for (var op : journal.pendingFor(team)) {
                if (op.state() == ReconstructionOperation.State.ROLLING_BACK) {
                    out.add(Component.literal("Reconstruction rolled back (" + op.outcome() + "); " + op.stagedTotal()
                            + " items are overflow waiting to go back. Free space in the network, or run "
                            + "/tla_archive overflow take.").withStyle(ChatFormatting.GOLD));
                } else {
                    out.add(Component.literal("Reconstruction in progress: " + op.state() + " ("
                            + op.stagedTotal() + " items staged)").withStyle(ChatFormatting.GOLD));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        var blocker = controller.reconstructionBlocker();
        if (blocker.isEmpty() && !ArchiveConfig.reconstructionEnabled()) {
            blocker = Optional.of("disabled by the test flag (server config "
                    + "reconstruction.enabled_for_testing)");
        }
        out.add(Component.literal(blocker.map(reason -> "Reconstruction unavailable: " + reason)
                .orElse("Reconstruction available (TEST-ONLY prototype): sneak and use the controller with an "
                        + "empty hand to preview it."))
                .withStyle(ChatFormatting.GRAY));
        return out;
    }

    private static String describe(ArchiveControllerBlockEntity controller, ArchiveState state, long gameTime) {
        return switch (state) {
            case LIVE -> "(network active)";
            case UNAVAILABLE -> "(no reading of the network yet)";
            case STALE, FROZEN -> {
                long ageTicks = Math.max(0, gameTime - controller.readingGameTime());
                yield "(last reading " + ageTicks / 20 + " s ago, at game time " + controller.readingGameTime() + ")";
            }
        };
    }
}
