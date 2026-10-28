# Handoff: manifest, catalog, persistence (roadmap Phase 2)

## Goal

Turn the Phase 1 prototype into the real archive system (design milestone
3): a manifest format the pack can own, a catalog screen, team-keyed
persistence with defined lifecycle behaviour, and a migration mechanism for
changed manifests. Behaviour is final; manifest contents and quantities are
still placeholders. Reconstruction stays behind its test flag.

Plan: `~/tla-packwiz/docs/roadmap.md` → *Phase 2*. Design:
`~/tla-packwiz/docs/the-last-archive-design.md` → *Archive controller and
current holdings* (catalog direction), *Completion model*, *Team state,
persistence, and performance*, *Required acceptance examples*, *Deferred
decisions and when they matter*. How the existing code works:
`docs/architecture.md`.

**Phase 2 exit (roadmap):** a fresh world can build a controller through
normal survival, see the catalog, and track representative entries across
restarts and a pack update. The pack track does the recipe, the manifest
file, the quests and the TLADev checks (pack handoff
`~/tla-packwiz/docs/local/handoffs/08-phase2-archive-pack-work.md`). This
track delivers the mod side and releases it.

## Current state (2026-10-28)

- **Phase 1 is closed by owner ruling** (pack `v0.2.0`). `0.3.0` (controller,
  test-only manifest, reconstruction behind the flag) is released, pinned and
  deployed on TLADev. The owner saw the readout go from zero to Ready and ran
  one reconstruction that committed (`ffeaf36f…`, 7 keys). Details:
  `~/tla-packwiz/HANDOFF_ARCHIVE_PROTOTYPE_PACK_TRACK.md` → *Integration
  log*.
- `main` is at `05036e6` (release 0.3.0). No open PRs. `feat/reconstruction`
  is merged (PR #8).
- The previous handoff, `HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md`, is
  replaced by this one (git history keeps it). Its unfinished part, the
  forced-restart proof, is parked below under *Parked: reconstruction
  hardening*.
- All owner rulings from that handoff (1–7e) are now recorded in the pack's
  design document and `docs/edge-cases.md` (pack commits `9a11419`,
  `0903941`). The design document is their home now, not a handoff.
- The 27 gametests pass; `./gradlew build` is clean.
- NeoForge 21.1 writes the `SERVER` config to
  `config/tla_archive-server.toml` in the game directory, not the world's
  `serverconfig/` (seen on TLADev). `docs/architecture.md` is corrected; the
  0.3.0 release notes still say `serverconfig/`, so mention the correction
  in the next release's notes.

What exists that Phase 2 builds on:

- Manifest loader (`manifest/`): one file,
  `data/tla_archive/archive_manifest/active.json`, loaded on start and
  `/reload`; a datapack at that path replaces the mod's own. All four rules
  the pack's candidate list uses are implemented (`item_id`,
  `banner_base_color`, `potion_effect`, `enchantment_on_book`).
- Controller (`controller/`): grid node, placer-team ownership, bounded
  sweeps (20-tick interval, 64 entries per tick), live / stale /
  unavailable / frozen, chat readout on right-click. Sneak-use with an empty
  hand is the reconstruction preview.
- Nothing is synced to the client. No menu, screen or payload exists.
- No recipe and no creative tab entry for the controller.

## Decisions already made (don't reopen without the owner)

- Rulings 1–7e (stale on power loss and unload, placer-team keying with
  allies denied, roll back interrupted consumption, hard-crash window
  accepted, MEGA compression disabled in the pack, duplicate DISKs ignored,
  team changes). Design document → *Archive controller*, *Reconstruction
  and consumption*, *Team state*.
- Reconstruction hardening is a **Phase 5 gate**, not Phase 2 work (owner
  ruling 2026-10-28). Reconstruction stays off by default in every release.
- 18 approved catalog categories: `~/tla-packwiz/docs/manifest/taxonomy.csv`
  (`category,name,description`). Category is for browsing; the balance class
  comes later and is a separate field.
- Effectless and multi-effect potions, tipped arrows, equipment
  enchantments and the other *Deferred* cases in
  `~/tla-packwiz/docs/edge-cases.md` match nothing. Don't add rules for them
  in this phase.
- The pack owns the controller's recipe (KubeJS) and the manifest file.
  The mod ships neither as final content.
- Performance tests are deferred by the owner. Keep the bounded-sweep
  design, but don't spend this phase measuring.

## Manifest contract (shared with pack handoff 08)

The pack generates its manifest from its candidate CSVs
(`~/tla-packwiz/docs/manifest/`) and ships it at
`kubejs/data/tla_archive/archive_manifest/active.json`. Pack handoff 08 →
*Part A* documents the `0.3.0` format for the pack's generator. Keep that
format loading.

**Owner ruling 2026-10-28: all five items approved as proposed** (1 and 3
both; 2 validated when present; 4 fixed text per rule; 5 the jar ships no
manifest). Built on `feat/catalog` with gametests; the format is in
`docs/architecture.md` → *Manifest*. The pack's Phase 2 manifest is a
4-per-category subset shipped as `test_only` until a release has the
marker (pack commit `fbd6e7b`). The original proposals:

1. **Placeholder marker, separate from `test_only`.** The pack's Phase 2
   manifest is real entries with placeholder quantities, not a test
   fixture, but the design requires placeholders to be clearly marked.
   Proposal: a top-level `quantities_status` (`placeholder` | `reviewed`),
   shown in the label (`the-last-archive v3 [PLACEHOLDER QUANTITIES]`).
   `test_only` keeps its current meaning and enforcement.
2. **Category table.** Proposal: a top-level `categories` list of
   `{id, name}` in display order, filled from `taxonomy.csv`; every entry's
   `category` must be listed, or the file is rejected. The catalog uses it
   for filter names, order and totals. Without it the catalog only has raw
   ids.
3. **Format version.** `version` is the content version. Proposal: an
   optional `format` int (default 1) so a later format change can be
   detected and rejected cleanly.
4. **Matching explanations.** Proposal: the catalog derives a rule
   explanation from `rule` (fixed text per rule, matching
   `docs/edge-cases.md`), and shows `notes` under it. The pack fills
   `edge_case` per rule.
5. **The jar's own manifest.** Today the jar ships `representative-test` at
   the active path. Options: keep it as the fallback, or move it to the
   gametest resources so a release jar ships no manifest and an unloaded
   pack manifest shows "no manifest" instead of test entries. Moving it
   makes a broken pack manifest obvious. Ask the owner.

Constraints that already hold and the pack relies on: keys match
`[a-z0-9_.:/-]+` and are unique; any invalid entry rejects the whole file;
`potion_effect` takes a mob effect id (`minecraft:speed` for swiftness).

Expected scale: up to ~1,213 entries (the pack's whole candidate list, if
the owner picks that for Phase 2). At 64 entries per tick a sweep takes ~19
ticks. Design payloads and the screen for that size.

## Next steps

Order is a suggestion; 1 unblocks the pack's generator, 2–3 are the bulk.

1. **Manifest format additions.** Done on `feat/catalog` (contract items
   1–5 as ruled; 29 gametests pass).
2. **Client sync.** NeoForge 21.1 payloads (`RegisterPayloadHandlersEvent`,
   `PayloadRegistrar`, `CustomPacketPayload` with a `StreamCodec`). Only the
   owner team's players get data. Suggested shape: a manifest snapshot per
   manifest generation (keys, names, categories, rules, quantities), then
   count updates per controller reading (state, age, counts by entry index).
   Check the clientbound custom payload size limit against 1,213 entries and
   split if needed. Send updates only while a viewer has the screen open.
   Client-only classes must stay out of the dedicated server's class loading
   (`Dist.CLIENT` subscribers); `runGameTestServer` is a dedicated server,
   so it catches mistakes.
3. **Catalog screen.** Design: search, category filters, a missing-only
   filter, current and required amounts, matching-rule explanations,
   category totals, and current readiness kept separate from historical
   milestones. Show the manifest label and the stale / unavailable / frozen
   state with the reading's age. Icons: the item for `item_id` and banners;
   a stack built from the entry for potions (a potion carrying that effect)
   and enchanted books. Right-click opens it for the owner team; keep the
   one-line denial for others and keep sneak-use as the reconstruction
   preview. Layout can be refined later (design). Add the controller to a
   creative tab while here.
4. **Team-keyed archive state.** `docs/architecture.md` → *Persistence*
   already plans world SavedData keyed by team UUID (not FTB `extraData`,
   which FTB copies on party creation). Contents: archive identity and
   settings, and historical milestones (for example the committed
   reconstruction, which today lives only in the journal). **Never counts**:
   counts are caches and are rebuilt from storage (design). Gametest the
   save/load round trip.
5. **Lifecycle behaviour.** The roadmap lists: joining or leaving a team,
   team deletion, relocation, removing cells, destroyed machines, multiple
   access points. Most are built (rulings 7a–7d, sweeps). Write the table in
   `docs/architecture.md` with what each does and its test. Open items:
   - **Multiple controllers** for one team, on one network or on several.
     Options: each controller shows its own network only (today); a team
     view that combines networks (must count a shared grid once); one active
     controller per team with the rest dormant. The design also requires
     that multiple controllers can't spend the same stock twice (a
     reconstruction concern, Phase 5). Present the options to the owner.
   - **Disconnected storage and changed security permissions** are still
     unspecified in the design (proposal: stale or unavailable, as for
     power). Check what access control AE2 `19.2.18` actually has before
     presenting options.
6. **Migration mechanism.** The roadmap asks for a migration policy before
   any public release; the owner rules it. Present options with what the
   mod would do. Facts to build on: credit is current stock, so adding or
   raising requirements only lowers readiness; a manifest reload already
   aborts a running reconstruction (the controller isn't `LIVE` until it
   re-reads). Open points: what a team's completed reconstruction means
   after the manifest changes (store the manifest id and version with the
   milestone); whether the catalog tells the player what changed since the
   version they last saw (needs a stored key set or hash per team); keys
   are never reused with a new meaning.
7. **Release.** With the owner's approval (`AGENTS.md` → *Releases*). A
   pre-release with the catalog is the only way the owner can see the
   screen (see *Known risks*). Say in the notes whether saved data breaks,
   the config path correction, and whether the jar still ships a manifest.

## Done when

- The manifest contract items the owner approved load, are validated, and
  are documented; `0.3.0` manifests still load.
- The catalog screen shows every design element above for a 1,000+ entry
  manifest, on the owner's client, through a pinned release.
- Team-keyed state saves and loads; lifecycle behaviour is documented with
  a test or a ruling per case; multiple-controller behaviour is ruled and
  built.
- The migration options were presented, the ruling is built, and it is
  tested by reloading a changed manifest.
- Gametests cover the format, sync codecs, team state round trip and
  lifecycle rules; `./gradlew build` is clean.
- The pack track has logged a passing *Integration log* row for the release
  (its handoff 08 → Part D).

## Questions for the owner

Present options with a recommendation. Rulings go into the pack's design
document from a pack-repo session.

- Manifest contract items 1–5.
- Multiple controllers per team (step 5).
- Disconnected storage and changed security permissions (step 5).
- Migration policy (step 6).
- Still open from Phase 1, but not needed until Phase 5: which variant to
  consume when several keys satisfy one entry, and whether extraction draws
  AE power.

## Requests to the pack track

A pack session does these and reports in
`~/tla-packwiz/HANDOFF_ARCHIVE_PROTOTYPE_PACK_TRACK.md` → *Integration log*.
Most are already planned in pack handoff 08.

1. Ship the pack's manifest at
   `kubejs/data/tla_archive/archive_manifest/active.json` and confirm from
   TLADev's log that it overrides the jar's (08 Part A).
2. Add the controller recipe in KubeJS (08 Part C).
3. Pin each release, and have the owner check the catalog on the Windows
   client (08 Part D).
4. Record the owner's rulings on the contract (items 1–5 approved as
   proposed, 2026-10-28), multiple controllers,
   storage disconnection and migration in the design document.
5. Once a release with the new format is pinned: have
   `scripts/build_manifest.py` emit `format: 1`, `quantities_status:
   placeholder`, `test_only: false` and the `categories` table from
   `taxonomy.csv`, and drop the `TEST-ONLY placeholder` notes. Until then
   keep shipping as `test_only` (a `0.3.0` jar rejects nothing new, but it
   would show a non-test file without any marker).
6. **Deferred, by the owner's ruling on performance tests:** a spark
   baseline of AE2's cache rebuild on TLADev
   (`docs/spikes/incremental-observation.md`). Ask when optimisation starts.

## Parked: reconstruction hardening (Phase 5 gate)

Not this phase's work. Kept here so it isn't lost; move it to its own
handoff when Phase 5 starts. The pack side is
`~/tla-packwiz/docs/local/handoffs/09-archive-acceptance-and-hardening.md`.

**Forced-restart proof** (old step 7). Show on a real dev server that an
operation killed between staging and commit is rolled back after restart,
with nothing duplicated, lost or voided:

1. Allocate a game port for `runServer` in
   `/home/nickt/stacks/docs/docs/04-networking/ports.md` (not
   `18000`–`18002`), following `~/.claude/CLAUDE.md`. Set it in the ignored
   `run/server.properties`.
2. A dev server has no player and `Reconstruction.start` needs one. Either
   join with `runClient` (needs a display; this host has none), or add
   dev-only op commands in the `gametest` source set that build the
   `ArchiveTestGrid` network with manifest stock and start an operation as a
   named player. The second is scriptable from the console.
3. `enabled_for_testing = true`, `debug_hold_staged_ticks = 6000`. Start,
   confirm `STAGED`, `save-all flush`, `kill -9` the JVM.
4. Restart. Expect `Reconstruction <id> was STAGED when the world loaded;
   rolling it back (…)`, then `rolled back`. Network totals equal the
   pre-start totals; the team has no completion. Repeat with
   `debug_keys_per_tick = 1` to kill during `EXTRACTING`.
5. Record the run in `docs/spikes/forced-restart.md`.

Other known gaps from Phase 1: real chunk unload/reload has only been
modelled; returned stock can land in a different storage; the sweep
constants are unmeasured; `makeMockServerPlayerInLevel` is marked for
removal in NeoForge.

## Checks to run

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build`
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
  (binds no port): all gametests pass, which also proves the dedicated
  server loads no client classes.
- The release jar holds no gametest classes, structures or spike packages
  (`unzip -l build/libs/tla_archive-*.jar`).
- `git status` shows no jars apart from the wrapper and no `run/` data.

## Known risks

- **No display on this host** (`$DISPLAY` is empty), so `runClient` won't
  start, and gametests can't test a screen. Test sync and screen logic as
  plain code where possible (snapshot builder, filters, totals, codecs). The
  owner sees the real screen only through a pinned release on their Windows
  client, so expect a review round per catalog release.
- A large manifest makes payload size and screen performance real concerns;
  test with a generated 1,200-entry manifest, not just the 7-entry one.
- A team-state format change is a saved-data break before `1.0.0`; say so in
  release notes. TLADev's world is disposable, but the pack track backs up
  before every jar deploy.
- Pack and mod agents work in parallel. A format change the pack's generator
  doesn't know about rejects the whole manifest on TLADev: change the
  contract only by agreement, and keep old formats loading.

## Next handoff file

Phase 2 is one step on this track. When *Done when* is met, write the
Phase 3/4 handoff the roadmap then calls for (Phase 3 has little mod work;
check `~/tla-packwiz/docs/roadmap.md` then), and move *Parked:
reconstruction hardening* into `HANDOFF_ARCHIVE_MOD_RECONSTRUCTION_GATE.md`
when Phase 5 starts.
