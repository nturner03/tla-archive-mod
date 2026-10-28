package io.github.nturner03.tlaarchive.spike.teams;

import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import io.github.nturner03.tlaarchive.TlaArchive;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SPIKE (throwaway): records every server-side FTB Teams event the archive might care about, as
 * one-line strings, so the gametest can assert on order and payload and the log shows what fired.
 */
public final class TeamEventRecorder {
    private static boolean registered;
    private static final List<String> EVENTS = new ArrayList<>();

    private TeamEventRecorder() {}

    public static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        TeamEvent.CREATED.register(e -> record("CREATED", e.getTeam(),
                "creator=" + e.getCreatorId() + " creatorOnline=" + (e.getCreator() != null)));
        TeamEvent.DELETED.register(e -> record("DELETED", e.getTeam(), ""));
        TeamEvent.PLAYER_LOGGED_IN.register(e -> record("PLAYER_LOGGED_IN", e.getTeam(),
                "player=" + e.getPlayer().getUUID()));
        TeamEvent.PLAYER_CHANGED.register(e -> record("PLAYER_CHANGED", e.getTeam(),
                "player=" + e.getPlayerId() + " online=" + (e.getPlayer() != null)
                        + " prev=" + e.getPreviousTeam().map(TeamEventRecorder::describe).orElse("none")));
        TeamEvent.PLAYER_JOINED_PARTY.register(e -> record("PLAYER_JOINED_PARTY", e.getTeam(),
                "player=" + e.getPlayer().getUUID() + " prev=" + describe(e.getPreviousTeam())));
        TeamEvent.PLAYER_LEFT_PARTY.register(e -> record("PLAYER_LEFT_PARTY", e.getTeam(),
                "player=" + e.getPlayerId() + " online=" + (e.getPlayer() != null)
                        + " newTeam=" + describe(e.getPlayerTeam()) + " teamDeleted=" + e.getTeamDeleted()));
        TeamEvent.OWNERSHIP_TRANSFERRED.register(e -> record("OWNERSHIP_TRANSFERRED", e.getTeam(),
                "to=" + e.getToProfile().getId() + " toOnline=" + (e.getTo() != null)));
    }

    private static synchronized void record(String kind, Team team, String detail) {
        String line = kind + " team=" + describe(team) + " " + detail + " thread=" + Thread.currentThread().getName();
        EVENTS.add(line);
        TlaArchive.LOGGER.info("[spike/ftb-teams] event {}", line);
    }

    static String describe(Team team) {
        String type = team.isPlayerTeam() ? "player" : team.isPartyTeam() ? "party" : team.isServerTeam() ? "server" : "?";
        return type + ":" + team.getId() + "(teamId=" + team.getTeamId() + ",valid=" + team.isValid() + ")";
    }

    /** Returns and clears the events recorded since the last drain. */
    public static synchronized List<String> drain() {
        List<String> copy = List.copyOf(EVENTS);
        EVENTS.clear();
        return copy;
    }

    static boolean any(List<String> events, String kind, UUID teamId) {
        return events.stream().anyMatch(l -> l.startsWith(kind + " ") && l.contains(":" + teamId + "("));
    }
}
