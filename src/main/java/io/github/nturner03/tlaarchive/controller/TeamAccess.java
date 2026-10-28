package io.github.nturner03.tlaarchive.controller;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reads FTB Teams identity (owner ruling 2, {@code docs/spikes/ftb-teams.md}). Archives are keyed on the effective
 * team's {@link Team#getId()}. Allies are not members, so they never match. The team id is only an ownership label:
 * nothing here finds or reads another team's storage.
 */
public final class TeamAccess {
    private TeamAccess() {
    }

    /** The player's effective team id (their party if they are in one), or empty if FTB Teams isn't ready. */
    public static Optional<UUID> teamOf(ServerPlayer player) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return Optional.empty();
        }
        return FTBTeamsAPI.api().getManager().getTeamForPlayer(player).map(Team::getId);
    }

    /** Whether the team still exists; empty if FTB Teams isn't ready to say. Deleted ids are never reused. */
    public static Optional<Boolean> teamExists(UUID teamId) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return Optional.empty();
        }
        return Optional.of(FTBTeamsAPI.api().getManager().getTeamByID(teamId).filter(Team::isValid).isPresent());
    }
}
