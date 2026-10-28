# Spike: FTB Teams identity

Branch `spike/ftb-teams`. Throwaway code in
`src/main/java/io/github/nturner03/tlaarchive/spike/teams/`. Written
2026-10-28.

## Question

How does the mod read a player's team through `FTBTeamsAPI.api()` (FTB Teams
`2101.1.11`)? What do team create, join, leave, and disband events look like,
along with party → player-team transitions, ownership transfer, and offline
players? What do these do to team identity? The design needs four things from
the answer:

- a stable id to key archive ownership and state on;
- what happens to that state when a player leaves or a team disbands;
- whether the events fire reliably on the server;
- that team credit never grants access to another team's inventory.

## Verdict

**Works, with caveats.** Team identity is a stable UUID that is easy to read
on the server. Every membership change fires `TeamEvent.PLAYER_CHANGED`
synchronously on the server thread, including changes to offline players.
Caveats:

- The public API is read-only for membership. Join, leave, kick, and transfer
  exist only on the implementation classes (`dev.ftb.mods.ftbteams.data.*`).
  The archive only needs to listen, so this is fine.
- `CREATED` and `PLAYER_JOINED_PARTY` don't fire when no `ServerPlayer` is
  involved (offline or console actions). Use `PLAYER_CHANGED` and `DELETED`.
- A party's id is random and dies with the party. If a party disbands,
  anything keyed on its id is orphaned. FTB moves the team file to
  `ftbteams/deleted/` and never reuses the id.
- Party creation **copies** the founder's player-team `extraData` into the
  party, so the founder's personal data and the party's data split into two
  copies from that point on.
- FTB Teams has nothing to do with AE2 grid access. Inventory isolation is
  entirely this mod's job (see *Proposed approach*).

## Identity model (from source, confirmed by the gametest)

| Call | Returns |
| --- | --- |
| `FTBTeamsAPI.api().isManagerLoaded()` / `getManager()` | the server `TeamManager`. It is created at `SERVER_BEFORE_START` and loaded from `<world>/ftbteams/`. It is null on the client and before server start. |
| `mgr.getTeamForPlayer(sp)` / `getTeamForPlayerID(uuid)` | the player's **effective** team: their party if they are in one, otherwise their player team. Works for offline players FTB has seen. |
| `mgr.getPlayerTeamForPlayerID(uuid)` | always the personal player team, even while the player is in a party |
| `team.getId()` | player team: the player's UUID (stable forever). Party or server team: a random UUID set at creation, gone after disband. |
| `team.getTeamId()` | on a *player* team, the id of the team that player is currently in (the party's id while they are in one). Misleading, so don't key on it. |
| `team.isValid()` | false once the team is deleted. Check it on any cached `Team`. |
| `team.getMembers()` | rank MEMBER or higher. Allies and invitees are excluded. |
| `team.getExtraData()` + `markDirty()` | per-team NBT that FTB saves with the team |

A player is in exactly one effective team at a time. Players can't join a
party while they are already in one (`ALREADY_IN_PARTY`). The party owner
can't leave while other members remain (`OWNER_CANT_LEAVE`). The owner has to
transfer ownership first, or an operator runs `/ftbteams force-disband`, which
kicks everyone else and then makes the owner leave.

## Evidence

Gametest `FtbTeamsSpikeTests.teamLifecycle` (template `tla_archive:empty`).
Reproduce it from commit `61dd626` on `spike/ftb-teams`:

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer
```

The result was `All 1 required tests passed`, with every `check(...)` logged as
`ok:`. `makeMockServerPlayerInLevel()` goes through
`PlayerList.placeNewPlayer`, so FTB's real login hook creates the mock
players' teams. No manual setup was needed. The offline player "C" was created
the same way `/ftbteams_add_fake_player` does it
(`TeamManagerImpl.playerLoggedIn(null, …)`). The events, trimmed (A, B, C are
players and P is the party). Every event fired on `thread=Server thread`,
synchronously inside the call that caused it:

```
1  A login        CREATED player:A creatorOnline=true; PLAYER_CHANGED player:A prev=none; PLAYER_LOGGED_IN player:A
2  A creates P    CREATED party:P; PLAYER_CHANGED party:P prev=player:A; PLAYER_JOINED_PARTY party:P
                  ok: party extra data copied from A's player team; copy, not shared
3  B joins P      PLAYER_CHANGED party:P prev=player:B; PLAYER_JOINED_PARTY party:P
4  owner A -> B   OWNERSHIP_TRANSFERRED party:P to=B toOnline=true      (party id unchanged)
5  A leaves P     PLAYER_CHANGED player:A prev=party:P; PLAYER_LEFT_PARTY party:P teamDeleted=false
                  ok: A back on personal team, id == A UUID; personal team kept pre-party data
6a offline C seen PLAYER_CHANGED player:C online=false prev=none          (no CREATED)
6b C force-added  PLAYER_CHANGED party:P online=false                      (no PLAYER_JOINED_PARTY)
6c C kicked       PLAYER_CHANGED player:C prev=party:P; PLAYER_LEFT_PARTY online=false teamDeleted=false
7a B logs out     (no team events; getTeamForPlayerID(B) still returns P)
7b B (last) leaves PLAYER_CHANGED player:B prev=party:P(valid=false);
                  PLAYER_LEFT_PARTY party:P teamDeleted=true; DELETED party:P(valid=false)
7c                party file moved to deleted/: true (./world/ftbteams/deleted/<P>.snbt)
8a server team created from console: 0 events (no CREATED)
8b server team deleted: DELETED server:<id>
```

Full log: `run/logs/latest.log`, lines tagged `[spike/ftb-teams]`.

## FTB Teams source relied on (tag `v2101.1.11`)

`common/src/main/java/dev/ftb/mods/ftbteams/`:

- `api/FTBTeamsAPI.java`, `api/TeamManager.java`, `api/Team.java`: the read
  API and the javadoc on `getId` and `getTeamId`.
- `api/event/TeamEvent.java`: the list of Architectury events. Also
  `PlayerChangedTeamEvent`, `PlayerJoinedPartyTeamEvent`,
  `PlayerLeftPartyTeamEvent`, `PlayerTransferredTeamOwnershipEvent`,
  `TeamCreatedEvent`, and `TeamManagerEvent`.
- `data/AbstractTeam.java:76` `onCreated`: `CREATED` fires only when
  `player != null`. `:89` `onPlayerChangeTeam`: `PLAYER_CHANGED` always fires,
  `PLAYER_JOINED_PARTY` only when the player is online, and `DELETED` fires
  when the party is deleted. `:280` `copyExtraData`.
- `data/TeamManagerImpl.java:274` `playerLoggedIn`: the player team is created
  with id = player UUID. `:416` `createParty`: random UUID, with
  `copyExtraData` at `:430`. `:466` `deleteTeam`, and `:473` moves the team
  file to `deleted/`.
- `data/PartyTeam.java:74` `join`, `:155` `kick`, `:239` `transferOwnership`
  (the id doesn't change), `:283` `leave` (`OWNER_CANT_LEAVE`; the last member
  leaving deletes the team), `:397` `forceDisband`, `:414`
  `kickPlayerForcibly` (limited-lives mode).
- `data/ServerTeam.java:25` `delete`: fires `DELETED` directly.
- `FTBTeams.java:46,52`: the manager is created at `SERVER_BEFORE_START`, and
  the login hook is Architectury `PLAYER_JOIN`.

## Proposed approach (for the controller step; not a ruling)

1. **Key on `Team.getId()` of the effective team**, which is
   `mgr.getTeamForPlayer(player).map(Team::getId)`. Never key on `getTeamId()`,
   `getShortName()`, the display name, or the owner's UUID. Ownership transfer
   doesn't change the key.
2. **Store archive state in the mod's own world `SavedData`, keyed by team
   UUID**, not in FTB `extraData`. FTB copies `extraData` on party creation and
   moves it to `deleted/` on disband, and neither is a policy the archive
   should inherit by accident. Owning the data also keeps the team-change
   policy explicit. This fits the design: saved counts are caches, and the
   lasting state is archive identity, settings, and reconstruction state.
3. **The controller records `ownerTeamId` when it is placed** (the placer's
   effective team). Every interaction (opening the screen, reading progress,
   activating) resolves the player's effective team id and compares it with
   `ownerTeamId`. If they differ, access is denied. Allies don't get access,
   because `getMembers()` excludes them.
4. **Isolation:** the team id is only a label for ownership and credit. A
   controller counts only the AE2 grid it is physically connected to. The
   team id is never used to find, load, or read another inventory or network.
   AE2 19 has no security terminal, so "who can touch this grid" stays a
   physical matter that FTB Teams doesn't control.
5. **Events:** register Architectury listeners in the mod constructor (common
   code, no NeoForge bus):
   - `TeamEvent.PLAYER_CHANGED` covers every join, leave, kick, and party
     creation, online or offline. Use it to refresh cached access and close
     open screens.
   - `TeamEvent.DELETED` marks archives owned by that id as orphaned.

   Don't depend on `CREATED` or `PLAYER_JOINED_PARTY`. Events can also be
   missed, for example if the mod was absent or someone hand-edited
   `ftbteams/`, so also check `getTeamByID(owner).isPresent()` lazily when a
   controller loads or is used.
6. **Guard availability:** `FTBTeamsAPI.api().isManagerLoaded()` before any
   lookup, and server side only.

## Open questions for the owner (deferred decision: team changes)

The design says to settle these before multiplayer reconstruction. These are
the options, not rulings:

1. **Solo → party.** A singleplayer owns an archive through their player team
   (id = their UUID). If they later create or join a party, their effective id
   changes, and under rule 3 they lose access to their own controller.
   Options:
   - (a) Move the founder's archives to the new party, which mirrors FTB's
     `extraData` copy.
   - (b) Keep them on the player team, so the player has to leave the party to
     use them.
   - (c) Let the controller accept the owner's player team *or* the effective
     team the owner is now in.
2. **A player who joins an existing party** while owning a personal archive:
   merge it, ignore it, or keep it dormant until they leave.
3. **A member leaves.** They lose access to the party archive and go back to
   their personal team's state. Options for the personal side: (a) the
   pre-party state, which is FTB's behaviour for `extraData`; (b) a copy of
   the party's state (duplicate completion credit); (c) empty.
4. **A party disbands** (the last member leaves, or force-disband). Its
   archives are orphaned. Options:
   - (a) Freeze them, so an operator can recover them.
   - (b) Transfer them to the last owner's player team.
   - (c) Release them for re-claiming.
   - (d) Delete their state.

   Physical items stay in the AE2 network either way. Only ownership and
   reconstruction state are at stake.
5. **Allies:** confirm that they get no access or view.
6. **Disband or leave while a reconstruction is running.** This ties into
   recovery after interrupted extraction (roadmap step 5).

## Not verified

- Real network login and logout, and persistence of team identity across a
  restart. The load path (`TeamManagerImpl.load`) was only read in source, not
  run across a restart. This needs a dev run: `runServer` binds a TCP game
  port, so allocate a free one under `/home/nickt/stacks/docs/docs/04-networking/ports.md`
  (not TLADev's `18000`–`18002`), set `server-port` in `run/server.properties`,
  and connect a client to create a party, restart, and re-check ids. The test
  didn't launch it.
- FTB Quests (not on the dev classpath). Whether it keys quest progress by the
  same effective team id, and whether it migrates progress on party creation,
  is still to be checked in its source. The design wants a shared identity.
- The limited-lives auto-kick path (`kickPlayerForcibly`), which is not
  enabled in the pack as far as known.
