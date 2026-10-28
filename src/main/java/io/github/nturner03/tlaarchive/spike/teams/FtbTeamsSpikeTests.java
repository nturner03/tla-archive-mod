package io.github.nturner03.tlaarchive.spike.teams;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.ServerTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.github.nturner03.tlaarchive.TlaArchive;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * SPIKE (throwaway): walks one FTB Teams 2101.1.11 lifecycle on the gametest server and logs what
 * each step does to team identity, extra data and events. Findings: docs/spikes/ftb-teams.md.
 *
 * Calls into dev.ftb.mods.ftbteams.data (PartyTeam, TeamManagerImpl) where the public API has no
 * method (join, leave, kick, transfer, offline "login"); production code should only listen.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FtbTeamsSpikeTests {
    private static final String KEY = "tla_archive_spike_progress";

    private FtbTeamsSpikeTests() {}

    @GameTest(template = "empty")
    public static void teamLifecycle(GameTestHelper helper) {
        try {
            run(helper);
            helper.succeed();
        } catch (GameTestAssertException e) {
            throw e;
        } catch (Exception e) {
            TlaArchive.LOGGER.error("[spike/ftb-teams] unexpected", e);
            throw new GameTestAssertException("unexpected " + e);
        }
    }

    private static void run(GameTestHelper helper) throws Exception {
        TeamEventRecorder.ensureRegistered();
        TeamEventRecorder.drain();
        MinecraftServer server = helper.getLevel().getServer();
        String thread = Thread.currentThread().getName();

        check(FTBTeamsAPI.api().isManagerLoaded(), "team manager loaded on gametest server");
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        log("0 manager id=" + mgr.getId() + " teams=" + mgr.getTeams().size());

        // 1. Mock player login -> FTB creates a personal (player) team whose id is the player UUID.
        ServerPlayer a = helper.makeMockServerPlayerInLevel();
        UUID aId = a.getUUID();
        List<String> ev = step("1 mock player A login", thread);
        Team teamA = mgr.getTeamForPlayer(a).orElseThrow(() -> fail("A has no team after login"));
        check(teamA.isPlayerTeam(), "A's team is a player team");
        check(teamA.getId().equals(aId), "player team id == player UUID");
        check(teamA.getTeamId().equals(aId), "player team effective id == player UUID while solo");
        check(TeamEventRecorder.any(ev, "CREATED", aId), "CREATED fired for A's player team");
        check(TeamEventRecorder.any(ev, "PLAYER_LOGGED_IN", aId), "PLAYER_LOGGED_IN fired");
        teamA.getExtraData().putString(KEY, "A-solo");
        teamA.markDirty();

        // 2. A creates a party through the public API: new random id; extra data is copied.
        Team party = mgr.createPartyTeam(a, "Spike Party", null, null);
        UUID partyId = party.getId();
        ev = step("2 A creates party", thread);
        check(party.isPartyTeam() && !partyId.equals(aId), "party id is new and differs from A's UUID");
        check(mgr.getTeamForPlayer(a).orElseThrow().getId().equals(partyId), "getTeamForPlayer(A) == party");
        Team personalA = mgr.getPlayerTeamForPlayerID(aId).orElseThrow();
        check(personalA.getId().equals(aId) && personalA.getTeamId().equals(partyId),
                "A's personal team still exists, effective teamId -> party");
        check("A-solo".equals(party.getExtraData().getString(KEY)), "party extra data copied from A's player team");
        party.getExtraData().putString(KEY, "party-progress");
        party.markDirty();
        check("A-solo".equals(personalA.getExtraData().getString(KEY)), "copy, not shared: personal data unchanged");
        check(TeamEventRecorder.any(ev, "CREATED", partyId), "CREATED fired for party");
        check(TeamEventRecorder.any(ev, "PLAYER_JOINED_PARTY", partyId), "PLAYER_JOINED_PARTY fired for creator");

        // 3. B logs in and joins (no public API for join: impl call, same path as /ftbteams party join).
        ServerPlayer b = helper.makeMockServerPlayerInLevel();
        UUID bId = b.getUUID();
        step("3a mock player B login", thread);
        ((PartyTeam) party).join(b);
        ev = step("3b B joins party", thread);
        check(mgr.arePlayersInSameTeam(aId, bId), "A and B in same team");
        check(TeamEventRecorder.any(ev, "PLAYER_JOINED_PARTY", partyId), "PLAYER_JOINED_PARTY for B");

        // 4. Ownership transfer A -> B: party id unchanged.
        ((PartyTeam) party).transferOwnership(server.createCommandSourceStack(), b.getGameProfile());
        ev = step("4 transfer ownership A -> B", thread);
        check(party.getOwner().equals(bId) && party.getId().equals(partyId), "owner B, id unchanged");
        check(TeamEventRecorder.any(ev, "OWNERSHIP_TRANSFERRED", partyId), "OWNERSHIP_TRANSFERRED fired");

        // 5. A leaves: A falls back to the same personal team (id == A UUID), with its pre-party data.
        ((PartyTeam) party).leave(aId);
        ev = step("5 A leaves party", thread);
        Team aNow = mgr.getTeamForPlayerID(aId).orElseThrow();
        check(aNow.isPlayerTeam() && aNow.getId().equals(aId), "A back on personal team, id == A UUID");
        check("A-solo".equals(aNow.getExtraData().getString(KEY)), "personal team kept pre-party data, not party's");
        check(!mgr.arePlayersInSameTeam(aId, bId), "A and B no longer share a team");
        check(TeamEventRecorder.any(ev, "PLAYER_LEFT_PARTY", partyId) && ev.stream().anyMatch(l -> l.contains("teamDeleted=false")),
                "PLAYER_LEFT_PARTY teamDeleted=false");

        // 6. Offline players. C is known to FTB only offline (same path as /ftbteams_add_fake_player).
        UUID cId = UUID.nameUUIDFromBytes("tla-spike-offline-c".getBytes());
        GameProfile cProfile = new GameProfile(cId, "spike_offline_c");
        TeamManagerImpl.INSTANCE.playerLoggedIn(null, cId, cProfile.getName());
        ev = step("6a offline C gets a player team", thread);
        check(mgr.getTeamForPlayerID(cId).map(t -> t.getId().equals(cId)).orElse(false), "offline C has player team id == UUID");
        check(!TeamEventRecorder.any(ev, "CREATED", cId), "CREATED NOT fired when no ServerPlayer (creator null)");
        ((PartyTeam) party).join(null, cProfile);
        ev = step("6b offline C force-added to party", thread);
        check(ev.stream().noneMatch(l -> l.startsWith("PLAYER_JOINED_PARTY")), "PLAYER_JOINED_PARTY NOT fired for offline join");
        check(ev.stream().anyMatch(l -> l.startsWith("PLAYER_CHANGED") && l.contains("player=" + cId)), "PLAYER_CHANGED fired for offline join");
        ((PartyTeam) party).kick(server.createCommandSourceStack(), List.of(cProfile));
        ev = step("6c offline C kicked", thread);
        check(ev.stream().anyMatch(l -> l.startsWith("PLAYER_LEFT_PARTY") && l.contains("online=false")), "PLAYER_LEFT_PARTY fired for offline kick");

        // 7. B (owner, last member) logs out, then leaves while offline -> party deleted.
        server.getPlayerList().remove(b);
        step("7a B logs out", thread);
        check(mgr.getTeamForPlayerID(bId).map(t -> t.getId().equals(partyId)).orElse(false), "team lookup works for offline B");
        ((PartyTeam) party).leave(bId);
        ev = step("7b B leaves -> party disbanded", thread);
        check(mgr.getTeamByID(partyId).isEmpty(), "party gone from manager");
        check(!party.isValid(), "cached party object isValid() == false");
        check(mgr.getTeamForPlayerID(bId).orElseThrow().getId().equals(bId), "B back on personal team, id == B UUID");
        check(TeamEventRecorder.any(ev, "DELETED", partyId), "DELETED fired for party");
        check(ev.stream().anyMatch(l -> l.startsWith("PLAYER_LEFT_PARTY") && l.contains("teamDeleted=true")), "teamDeleted=true");
        Path deleted = server.getWorldPath(new LevelResource("ftbteams")).resolve("deleted").resolve(partyId + ".snbt");
        log("7c party file moved to deleted/: " + Files.exists(deleted) + " (" + deleted + ")");
        check(mgr.getPlayerTeamForPlayerID(bId).orElseThrow().getExtraData().getString(KEY).isEmpty(),
                "party's extra data is NOT handed to the remaining owner's personal team");

        // 8. Server teams: created from console (no player) -> no CREATED event; delete -> DELETED.
        Team serverTeam = mgr.createServerTeam(server.createCommandSourceStack(), "SpikeServerTeam", null, null);
        ev = step("8a server team created from console", thread);
        check(!TeamEventRecorder.any(ev, "CREATED", serverTeam.getId()), "no CREATED for console-created server team");
        ((ServerTeam) serverTeam).delete(server.createCommandSourceStack());
        ev = step("8b server team deleted", thread);
        check(TeamEventRecorder.any(ev, "DELETED", serverTeam.getId()), "DELETED for server team");

        server.getPlayerList().remove(a);
        step("9 A logs out", thread);
        log("done: all checks passed");
    }

    private static List<String> step(String name, String thread) {
        List<String> ev = TeamEventRecorder.drain();
        log("step " + name + ": " + ev.size() + " event(s)");
        for (String l : ev) {
            log("    " + l);
            check(l.endsWith("thread=" + thread), "event fired synchronously on the server thread: " + l);
        }
        return ev;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw fail(what);
        log("  ok: " + what);
    }

    private static GameTestAssertException fail(String what) {
        TlaArchive.LOGGER.error("[spike/ftb-teams] FAILED: {}", what);
        return new GameTestAssertException("FAILED: " + what);
    }

    private static void log(String msg) {
        TlaArchive.LOGGER.info("[spike/ftb-teams] {}", msg);
    }
}
