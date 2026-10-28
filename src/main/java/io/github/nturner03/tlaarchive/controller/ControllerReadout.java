package io.github.nturner03.tlaarchive.controller;

import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The Phase 1 display: chat lines with the state and current/required counts. The catalog UI is Phase 2. */
public final class ControllerReadout {
    private ControllerReadout() {
    }

    /** What {@code player} may see: the counts for the owner team, otherwise only why access is denied. */
    public static List<Component> lines(ArchiveControllerBlockEntity controller, ServerPlayer player, long gameTime) {
        controller.checkOwnerTeam();
        if (controller.state() == ArchiveState.FROZEN) {
            return List.of(Component.literal("Archive controller: FROZEN. Its team no longer exists. A former member "
                    + "can break and re-place it to claim it.").withStyle(ChatFormatting.GRAY));
        }
        if (controller.ownerTeam().isEmpty()) {
            return List.of(Component.literal("This archive controller has no owner team. Break and re-place it to "
                    + "claim it.").withStyle(ChatFormatting.GRAY));
        }
        if (!controller.isOwnedBy(player)) {
            return List.of(Component.literal("This archive belongs to another team.").withStyle(ChatFormatting.RED));
        }
        return lines(controller, gameTime);
    }

    /** The owner's view. */
    public static List<Component> lines(ArchiveControllerBlockEntity controller, long gameTime) {
        var out = new ArrayList<Component>();
        var state = controller.state();
        out.add(Component.literal("Archive controller: " + state + " " + describe(controller, state, gameTime))
                .withStyle(state == ArchiveState.LIVE ? ChatFormatting.GREEN : ChatFormatting.GOLD));

        var matcher = ActiveManifest.matcher().orElse(null);
        if (matcher == null) {
            out.add(Component.literal("No valid archive manifest is loaded.").withStyle(ChatFormatting.RED));
        } else {
            var manifest = matcher.manifest();
            out.add(Component.literal("Manifest " + manifest.label()).withStyle(ChatFormatting.GRAY));
            if (controller.readingGameTime() >= 0 && !manifest.label().equals(controller.readingManifest())) {
                out.add(Component.literal("Counts were read against " + controller.readingManifest())
                        .withStyle(ChatFormatting.GRAY));
            }
            int ready = 0;
            for (var entry : manifest.entries()) {
                var count = controller.count(entry.key());
                boolean entryReady = count.isPresent() && count.getAsLong() >= entry.quantity();
                if (entryReady) {
                    ready++;
                }
                var have = count.isPresent() ? Long.toString(count.getAsLong()) : "?";
                var note = entry.quantityNote().map(n -> " (" + n + ")").orElse("");
                out.add(Component.literal((entryReady ? " [x] " : " [ ] ") + entry.displayName() + " ("
                        + entry.key() + "): " + have + " / " + entry.quantity() + note)
                        .withStyle(entryReady ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            }
            out.add(Component.literal("Ready: " + ready + " / " + manifest.entries().size()));
        }

        out.add(Component.literal(controller.reconstructionBlocker()
                .map(reason -> "Reconstruction unavailable: " + reason)
                .orElse("Reconstruction: allowed by state (the operation itself arrives in roadmap step 5)"))
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
