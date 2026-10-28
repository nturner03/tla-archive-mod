# Handoff: reconstruction prototype (roadmap Phase 1, archive track step 5)

## Goal

Prototype safe final consumption behind a dev/test flag. When a player
starts it, the controller revalidates, extracts exactly the manifest's
required stock in one journalled operation, and commits it. An interrupted
operation is rolled back on load and never duplicates, loses or voids
stock. Plan: `~/tla-packwiz/docs/roadmap.md` → *Phase 1* → *Archive track*
step 5. Design: *Reconstruction and consumption* in
`~/tla-packwiz/docs/the-last-archive-design.md`. How the existing pieces
fit: `docs/architecture.md`.

## Current state (2026-10-28)

**Next steps 1–6 are built** on branch `feat/reconstruction` (PR to `main`,
not merged yet). How it works: `docs/architecture.md` → *Reconstruction*.
Only next step 7 (the forced-restart proof) and step 8 remain.

- Code: `ArchiveConfig` (server config test flag
  `reconstruction.enabled_for_testing`, default off, plus the debug pacing
  `debug_keys_per_tick` and `debug_hold_staged_ticks`) and the package
  `reconstruction/`: `ExtractionPlan`, `ReconstructionOperation` (reviewed
  port of the spike's `StagedExtraction`), `ReconstructionJournal`
  (overworld SavedData), `Reconstruction` (start, tick, abort, recovery,
  overflow), `ReconstructionCommands` (sneak-use preview,
  `/tla_archive reconstruct confirm`, `/tla_archive overflow [take]`). The
  controller is an `IActionHost`, ticks its operations, and aborts them on
  unload or removal. FTB `PLAYER_CHANGED`/`DELETED` listeners and the
  `ServerStartedEvent` recovery are registered in `TlaArchive`.
- `./gradlew runGameTestServer`: **all 27 tests pass** (14 earlier plus 13
  in `ReconstructionTests`, one of them in its own batch
  `reconstruction_flag_off`). `./gradlew build` is clean, and the jar holds
  no gametest or spike classes.
- *Done when* status: test flag ✔; operation persisted ✔, recovery modelled
  by an NBT reload per operation ✔, **real forced restart not yet run**;
  multi-enchantment book consumed once ✔; two teams isolated ✔; gametests
  for plan, shortfall rollback, overflow, recovery from each non-terminal
  state and team-change abort ✔; `docs/architecture.md` ✔.
- Variant choice is still unruled. The plan's provisional order (most entries
  still short first, then most plentiful, then key text) is documented as
  not a ruling.

### Next subtask: forced-restart proof (next step 7)

Goal: show on a real dev server that an operation killed between staging
and commit is rolled back after the restart, with nothing duplicated, lost
or voided. Suggested route:

1. Allocate a game port for `runServer` in
   `/home/nickt/stacks/docs/docs/04-networking/ports.md`, following the
   rules in `~/.claude/CLAUDE.md` (not `18000`–`18002`). Set it in the dev
   run's `run/server.properties` (ignored), with `online-mode=false` if a
   client has to join.
2. A dev server has no player, and `Reconstruction.start` needs one (team
   authority). Options: join with `runClient` (a dev client is offline-mode),
   or add a dev-only op command in the `gametest` source set (dev runs load
   it; the release jar doesn't) that builds the `ArchiveTestGrid` network
   with manifest stock at a position, and one that starts an operation as a
   named online player. The second is scriptable over the server console.
3. Config: `enabled_for_testing = true`, `debug_hold_staged_ticks = 6000`.
   Start the operation, confirm `STAGED` (the readout or `/tla_archive
   overflow` is empty while staged; the log line `Reconstruction ... started`),
   run `save-all flush`, then `kill -9` the server JVM.
4. Restart. Expect the log line `Reconstruction <id> was STAGED when the
   world loaded; rolling it back (69 items staged)`, then `rolled back`
   once the controller's chunk ticks. Check that network totals equal the
   pre-start totals exactly, and that the team has no completion. Repeat
   with `debug_keys_per_tick = 1` to kill during `EXTRACTING`.
5. Record the run (commands, log lines, totals) in
   `docs/spikes/forced-restart.md` or in `docs/architecture.md`, and mark
   the *Done when* item.

### Earlier state

- Steps 3–4 (representative manifest and minimal controller) are merged to
  `main` (PR #5) and released as `0.2.0` (below). What exists, with code paths: `docs/architecture.md`. In short:
  - The test-only manifest `representative-test v1` ships at
    `data/tla_archive/archive_manifest/active.json`. A test-only manifest is
    rejected unless every entry carries `quantity_note: "TEST-ONLY
    placeholder"`. A pack replaces it with a datapack file at that path.
  - The matcher implements `item_id`, `banner_base_color`, `potion_effect`
    (exactly one effect type) and `enchantment_on_book`.
    `ManifestMatcher.entriesSatisfiedBy(key)` gives key → entries.
  - The controller block (`tla_archive:archive_controller`) has an AE2 grid
    node, records the placer's team, counts in bounded sweeps of
    `getCachedInventory()`, and has live / stale / unavailable / frozen
    states. `reconstructionBlocker()` says why the state forbids
    reconstruction. Right-click prints the counts to the owner team.
  - 14 gametests in the `gametest` source set pass
    (`./gradlew runGameTestServer`). The release jar contains no gametest
    classes.
- The skeleton `0.1.0` (no features) was released on 2026-10-28 with the
  owner's approval:
  <https://github.com/nturner03/tla-archive-mod/releases/tag/v0.1.0>,
  SHA-256 in `README.md`.
- The controller was released as `0.2.0` (pre-release) on 2026-10-28 with
  the owner's approval:
  <https://github.com/nturner03/tla-archive-mod/releases/tag/v0.2.0>,
  SHA-256 `c46c129b7ebcd298d18dbf0efb161cfa942cd49073d2eb032d567d417ccc2a35`.
  It ships the test-only manifest and no reconstruction. Pinning it is
  pack-track work (request 5).
- Spike code for this step: `StagedExtraction` on `spike/grid-extraction`
  (`edebecc`), findings in `docs/spikes/grid-stock-and-extraction.md`. It
  is throwaway: review it before any of it moves to production.

## Owner rulings (2026-10-28)

Given in a mod-track session after the owner read the spike findings. They
still have to be added to the pack's design document from a pack-repo
session (request 1). Until then, this table is the record.

| # | Decision | Ruling | What it means for the code |
| --- | --- | --- | --- |
| 1 | Power loss and chunk unload | **Stale.** Show the last known counts labelled stale with a time, and disable reconstruction until the network is active again. | Built: readings are published only from an active node. Reconstruction must require `state() == LIVE`. |
| 2 | Team keying and ally access | **Team id of the placer; allies denied.** | Built. A controller counts only the AE2 network it is physically on. |
| 3 | Interrupted final consumption | **Roll back.** A fully staged operation found on load is returned to storage, and the player must start again. | `recover` always rolls back. Stock that won't fit back stays in the journal as a visible overflow and is never voided. |
| 4 | Hard-crash window | **Accept** the risk, the same as AE2's crafting CPU. No forced world saves. | The journal is ordinary SavedData, saved with the world. |
| 5 | MEGA Cells compression | **Disable compression in the pack.** | The mod does not special-case bulk cells. |
| 6 | Duplicated AE2 Things DISKs | **Ignore.** | No duplicate-UUID guard. |
| 7a | Solo player joins or creates a party | The archive stays with the personal team. To move it, break and re-place it while in the party. | Built: ownership is set only at placement. |
| 7b | Player who has an archive joins another party | Theirs goes dormant until they leave or re-place it. No merging. | as 7a |
| 7c | Someone leaves a party | They get back their personal team with its pre-party state. They lose no party archive data. | Nothing to do beyond FTB's behaviour. |
| 7d | Party disbands | Its controllers freeze: read-only, reconstruction off. A former member can claim one by breaking and re-placing it. | Built: a lazy `checkOwnerTeam()` at each sweep and right-click. |
| 7e | Team change during a reconstruction | Abort and roll back. | This step: listen to FTB `TeamEvent.PLAYER_CHANGED` and `DELETED`. |

## Next steps

1. **Test flag.** Reconstruction is off unless a dev/test flag is on
   (`AGENTS.md` → *Rules*). A server config value defaulting to off is the
   simplest choice. Gametests turn it on.
2. **Extraction plan.** From the active manifest and the grid's cached
   inventory, choose the actual keys and amounts that cover each entry's
   quantity. A key that satisfies several entries (a multi-enchantment book)
   is extracted once and credited to each (design; EC-enchanted-books).
   Interchangeable potion forms are not charged separately. Consume
   required quantities only. Which variant to take when several match is not
   ruled (see *Questions for the owner*).
3. **Journal.** Review `StagedExtraction` and port it into a production
   package: plan, staging buffer, states (`EXTRACTING`, `STAGED`,
   `ROLLING_BACK`, terminal), persisted in world SavedData keyed by
   operation UUID, keys via `AEKey.toTagGeneric`/`fromTagGeneric`.
4. **Operation.** Revalidate (state `LIVE` via `reconstructionBlocker()`,
   the player's team is the owner, every planned key simulates in full),
   extract every key in one tick through the controller's node with
   `IActionSource.ofMachine(...)`, trusting only `MODULATE` amounts. Any
   shortfall rolls everything back in the same tick. Commit records
   completion and clears the buffer in one journal write. Explain the
   consequence to the player before they commit (design).
5. **Recovery (ruling 3).** On server start, roll back every non-terminal
   journal. Stock that won't fit back stays in the journal as visible
   overflow the player can empty, never voided.
6. **Team change (ruling 7e).** Register Architectury listeners for
   `TeamEvent.PLAYER_CHANGED` and `DELETED` (`docs/spikes/ftb-teams.md`) and
   abort plus roll back an operation whose team changed.
7. **Forced restart proof.** The *Done when* needs recovery after a real
   forced restart mid-extraction, which a gametest can't do. It needs a dev
   server run (`runServer` binds a TCP port: allocate one in
   `/home/nickt/stacks/docs/docs/04-networking/ports.md` first, never
   `18000`–`18002`), killed between staging and commit, then restarted.
   Spreading the extraction over ticks behind a debug switch may be needed
   to stop it at the right point.
8. Write `HANDOFF_ARCHIVE_MOD_PHASE1_EXIT.md` when *Done when* is met.

## Done when

- Reconstruction exists only behind the test flag.
- The operation is persisted, and recovery after a forced restart
  mid-extraction is proven (rolled back, nothing duplicated, lost or
  voided).
- A multi-enchantment book is consumed once while satisfying both of its
  entries.
- Two teams' controllers on separate networks stay isolated: one team's
  operation never touches the other's stock or journal.
- Gametests cover the plan, the rollback on shortfall, overflow that won't
  fit back, recovery from each non-terminal state, and abort on team change.
- `docs/architecture.md` describes the reconstruction that was built.

## Questions for the owner

Present the options; the ruling goes into the pack's design document from a
pack-repo session.

- **Which variant to consume** when several stored keys satisfy one entry
  (for example a plain and a splash strength potion, or a damaged and an
  undamaged pickaxe). Options: the most plentiful key first, the least
  valuable by some measure, or a player choice in the confirmation step.
- **Power draw** of the controller node. It currently draws no idle AE
  power; whether extraction draws power (`StorageHelper.poweredExtraction`)
  is the design's deferred power-consumption decision.

## Requests to the pack track

Things this track needs done in `~/tla-packwiz` or on TLADev. A pack
session does them and reports in the pack handoff's *Integration log*.

1. **Record the owner rulings above in the design document** (and in
   `docs/edge-cases.md` where they touch matching): power/chunk policy,
   team keying, interrupted final consumption, the hard-crash window, MEGA
   compression, AE2 Things duplicates, and team changes 7a–7e. Also note
   that the matcher treats effectless and multi-effect potions as matching
   nothing until EC-potion-bases is ruled.
2. **Disable MEGA Cells compression in the pack** (ruling 5). Find out
   whether MEGA Cells `4.11.0` can turn it off by config, or whether the
   compression card's recipe has to be removed. Record how it was done in
   `setup-notes.md`.
3. **Pin the `0.1.0` skeleton release**
   (<https://github.com/nturner03/tla-archive-mod/releases/tag/v0.1.0>,
   released 2026-10-28). See the pack's
   `docs/local/handoffs/04-archive-mod-integration.md`.
4. **Deferred, by the owner's ruling on performance tests:** a spark
   baseline of AE2's cache rebuild on TLADev
   (`docs/spikes/incremental-observation.md` → *Requests to the pack
   track*). Ask for it when optimisation work begins.
5. **Pin the `0.2.0` controller release**
   (<https://github.com/nturner03/tla-archive-mod/releases/tag/v0.2.0>,
   SHA-256 `c46c129b7ebcd298d18dbf0efb161cfa942cd49073d2eb032d567d417ccc2a35`;
   it supersedes request 3). Then place a controller (`/give @s
   tla_archive:archive_controller`; no recipe yet) on a TLADev test network
   and log whether the readout shows `representative-test v1 [TEST-ONLY]`
   counts, and whether it goes stale when the network loses power.

## Checks to run

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build`
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
  (no port): all gametests pass.
- The release jar contains no gametest classes, structures or spike
  packages (`unzip -l build/libs/tla_archive-*.jar`).
- `git status` shows no jars apart from the wrapper and no `run/` data.

## Known risks

- Storage-bus listings lag behind the external inventory, so the plan built
  from the cache can be wrong. Revalidate with simulated extraction, and
  trust only what `MODULATE` returns.
- Real chunk unload and reload, and a real server restart, have only been
  modelled (unload hook plus NBT round trip), not run. The forced-restart
  proof in this step is the first real restart.
- Compensation conserves the network total, but returned stock can land in
  a different storage from the one it came from.
- The sweep constants (20-tick interval, 64 entries per tick) are not
  measured on a real network. They become server config once measured.
- `makeMockServerPlayerInLevel` is marked for removal in NeoForge; the
  ownership gametest uses it.

## Next handoff file

`HANDOFF_ARCHIVE_MOD_PHASE1_EXIT.md` (roadmap step 6): packaging and Phase 1
exit. Its *Done when*: a release is pinned by the pack track, and the pack
track has run the prototype's acceptance examples on TLADev and logged them
as passing.
