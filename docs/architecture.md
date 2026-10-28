# Architecture

How `tla_archive` is built: components, data flow, persistence. Created
2026-10-28 from the API spike findings (`docs/spikes/`). Sections marked
**built** describe code on `main` or in review; sections marked **planned**
describe a later step. The owner rulings it relies on are listed in the
active `HANDOFF_ARCHIVE_MOD_*.md` → *Owner rulings* until the pack's design
document records them.

Pinned APIs: AE2 `19.2.18`, FTB Teams `2101.1.11` (`AGENTS.md` → *Fixed
facts*).

## Components

| Component | Status | Code | Responsibility |
| --- | --- | --- | --- |
| Manifest | built (test-only data) | `manifest/ArchiveManifest`, `ManifestEntry`, `ManifestLoader`, `ActiveManifest` | Versioned, data-loaded list of entries: key, display name, category, rule, quantity, notes. Quantities can be edited without touching the matcher. |
| Matcher | built | `manifest/MatchRule`, `ManifestMatcher` | Maps an `AEItemKey` to the manifest entries it satisfies (`item_id`, `banner_base_color`, `potion_effect`, `enchantment_on_book`). One key may satisfy several entries. Only reads item data. |
| Archive controller | built | `controller/ArchiveControllerBlock`, `ArchiveControllerBlockEntity`, `ArchiveState` | Block + block entity with an AE2 managed grid node. Owns the owner team and the last reading. Exposes current/required counts and a state (live / stale / unavailable / frozen). |
| Stock reader | built | `ArchiveControllerBlockEntity.Sweep` | Recalculates per-entry counts from `IStorageService.getCachedInventory()` through the matcher, in bounded sweeps. |
| Team access | built (ownership); planned (abort on team change, step 5) | `controller/TeamAccess` | Records the placer's `team.getId()`; checks the player's current team on every interaction; freezes the controller when its team is gone. |
| Display | built (minimal) | `controller/ControllerReadout` | Chat lines on right-click: state, manifest label, current/required per entry, readiness, reconstruction availability. The catalog UI is Phase 2. |
| Reconstruction journal | planned (step 5) | — | Staged multi-key extraction with compensation, persisted in world SavedData, recovered on load. Behind a test flag. |

Source root: `src/main/java/io/github/nturner03/tlaarchive/`.

## Manifest (built)

- **Where it loads from.** `ManifestLoader` is a datapack reload listener.
  It reads exactly one file, `data/tla_archive/archive_manifest/active.json`,
  on server start and on every `/reload`. The mod ships the test-only
  representative manifest at that path. A pack replaces it with a datapack
  file at the same path. Other files in that directory are ignored.
- **Format.** `id`, `version`, `test_only`, `description`, `entries`. Each
  entry has `key`, `display_name`, `category`, `rule` plus the rule's field
  (`item`, `color`, `effect` or `enchantment`), `quantity` (at least 1; a
  presence requirement is 1), and optional `quantity_note`, `edge_case`,
  `notes`. Keys and rule names follow the pack's candidate format
  (`~/tla-packwiz/docs/manifest/README.md`).
- **Test-only labelling is enforced.** In a manifest with `test_only: true`,
  every entry must carry `quantity_note: "TEST-ONLY placeholder"`, or the
  whole manifest is rejected. The label (`representative-test v1
  [TEST-ONLY]`) is shown with every readout.
- **Validation.** Unknown items, effects, colours and rules fail decoding.
  Enchantments are a datapack registry, so the loader checks their ids
  against the server's registries after decoding. Keys must be unique.
- **An invalid or missing file leaves no active manifest** (logged as an
  error or warning). Controllers then say so and keep their last reading as
  stale, instead of counting against old requirements.
- **Reload.** `ActiveManifest.generation()` changes on every load. A
  controller treats its counts as unconfirmed until it has read the grid
  against the new manifest.

## Matching rules (built)

| Rule | Candidate items | Matches | Edge case |
| --- | --- | --- | --- |
| `item_id` | the item | the item id; name, damage and other data ignored | EC-ordinary-id |
| `banner_base_color` | the banner of that colour | `BannerItem.getColor()`; patterns ignored | EC-banners |
| `potion_effect` | potion, splash potion, lingering potion | `POTION_CONTENTS` has **exactly one** effect type, and it is the entry's effect; any strength or duration | EC-potion-forms |
| `enchantment_on_book` | enchanted book | `STORED_ENCHANTMENTS` contains the enchantment at any level | EC-enchanted-books |

- Effectless potions (water, awkward, …) and multi-effect potions (turtle
  master) match no entry until EC-potion-bases is ruled. Tipped arrows
  (EC-tipped-arrows) and enchanted equipment (EC-equipment-enchantments)
  match no potion or book entry.
- **Counting** an entry looks up each candidate item in the cache with
  `KeyCounter.findFuzzy(key, FuzzyMode.IGNORE_ALL)`, which returns every
  stored variant of that item, and sums the variants the rule accepts. The
  cost grows with the stored variants of the candidate items, not with the
  network's size.
- `ManifestMatcher.entriesSatisfiedBy(key)` gives the reverse mapping (key →
  entries) that reconstruction will need.

## Reading stock (built for display; planned for consumption)

- **Display and readiness** use `getCachedInventory()`. AE2 updates it at
  most once per tick, and it counts drive cells and storage-bus inventories
  together.
- **Sweeps.** The controller starts a sweep at most every
  `SWEEP_INTERVAL_TICKS` (20) ticks and counts at most
  `MAX_ENTRIES_PER_TICK` (64) entries per tick, so a 1,000-entry manifest is
  read in 16 ticks. Without a stack watcher on the grid, AE2 rebuilds the
  cache lazily, so the controller pays at most one rebuild per tick in which
  it reads, and only while a sweep runs. Both values are constants for now;
  they become server config when the tick cost is measured on a real
  network.
- **A reading is published only when a sweep completes on an active node.**
  A sweep is dropped if the node goes inactive, the node moves to another
  grid, or the manifest reloads. Partial sweeps are never shown.
- **Don't use a whole-grid stack watcher**: once one is attached, AE2
  rebuilds the grid's whole cached inventory every tick, which costs about
  14 ms at 50k distinct keys (indicative gametest figure).
- **Planned for step 5. Validation before consumption** uses simulated
  extraction (`Actionable.SIMULATE`). Storage-bus listings are the bus's own
  cached view and can lag by up to its poll interval, while extraction reads
  the inventory live. Only the amount a real (`MODULATE`) extraction returns
  is trusted.
- The controller extracts through its own node with
  `IActionSource.ofMachine(controller)`. It never extracts reentrantly from
  inside a storage callback, because AE2 returns 0 when it is.

## States (built; ruling 1, rulings 7a–7d)

| State | When | Display | Reconstruction |
| --- | --- | --- | --- |
| live | node active, and the last reading was taken from the grid the node is on now, against the active manifest | current counts | allowed by state (the operation is step 5, behind a test flag) |
| stale | a reading exists, but the node is inactive or unpowered, the controller was unloaded and reloaded, or the grid or manifest changed and has not been read again | last counts, labelled with the reading's age and game time | unavailable |
| unavailable | no reading has ever completed | no counts | unavailable |
| frozen | the owning team no longer exists | a notice only; counts stop updating | unavailable; a former member re-places the controller to claim it |

`ArchiveControllerBlockEntity.reconstructionBlocker()` gives the reason
reconstruction is unavailable, or empty when the state allows it. A live
controller with no owner team is also blocked.

An inactive grid reports 0 for everything. Because readings are published
only from an active node, those zeros never replace the last counts. The
node listener (`onStateChanged`, `onGridChanged`) drops the sweep in progress
at once, and every tick re-checks `isActive()`. A partly unloaded network
looks the same as removed storage, so it can only lower readiness. When the
grid changes (a split or merge), the controller is stale until a new sweep of
the new grid completes, which is the next tick that the node is active.

Chunk unload: `onChunkUnloaded` and `setRemoved` destroy the node (the
pattern `GridHelper.onFirstTick` documents). A reloaded controller starts
stale with its saved counts and becomes live after its first sweep. This is
modelled in a gametest (unload hook plus an NBT round trip), not run as a real
unload.

## Ownership (built; rulings 2, 7a–7d)

- `ArchiveControllerBlock.setPlacedBy` records the placer's effective team id
  (`TeamAccess.teamOf`, the party if they are in one) and sets the AE2 owning
  player. Ownership is set only at placement; nothing transfers it (7a, 7b).
  Breaking drops a plain controller item, and re-placing claims it for the
  placer's current team.
- Right-clicking shows counts only to a player whose current effective team
  is the owner. Allies, other teams and former members get one line saying
  the archive belongs to another team.
- `checkOwnerTeam()` freezes the controller when FTB Teams no longer knows
  its owner team (7d). It runs lazily: at the start of each sweep and on
  every right-click, so a missed `DELETED` event is still caught. Ids are
  never reused, so a frozen controller stays frozen.
- A controller placed by something other than a player has no owner team.
  It still counts, but shows no counts to anyone and can't reconstruct.
- The team id is only a label. A controller counts only the AE2 grid it is
  physically connected to.

## Reconstruction (planned: step 5, test flag)

1. Revalidate: live state, team authority, every planned key simulates in
   full.
2. In one tick, extract each key into the journal's staging buffer. Any
   shortfall rolls back everything in the same tick.
3. Commit: record completion and clear the buffer in one journal write.
4. On load, any journal that isn't terminal is **rolled back** (ruling 3).
   Stock that won't fit back stays in the journal as visible overflow and is
   never voided.
5. A team change during an operation aborts it and rolls it back
   (ruling 7e).

Compensation conserves the network total, but returned stock may land in a
different storage from the one it came from. A hard crash between autosaves
can still leave the journal and the storages out of step. The owner
accepted this, the same as AE2's crafting CPU (ruling 4).

## Persistence

| Data | Status | Where | Notes |
| --- | --- | --- | --- |
| Owner team id, frozen flag | built | controller block entity NBT (`owner_team`, `frozen`) | Preserved through power loss and chunk unload. |
| AE2 node data | built | controller block entity NBT (`archive_node`, via `IManagedGridNode.saveToNBT`) | |
| Last reading: counts by entry key, manifest label, game time | built | controller block entity NBT (`reading`) | A cache, never earned credit. A loaded reading is stale until the grid is read again. Marked dirty only when the counts or manifest change. |
| Reconstruction journal | planned (step 5) | world SavedData, keyed by operation UUID | Keys via `AEKey.toTagGeneric`/`fromTagGeneric`. |
| Team-keyed archive state | planned (Phase 2) | world SavedData keyed by team UUID, not FTB `extraData` | FTB copies `extraData` when a party is created. |

Nothing is synced to the client; the readout is built on the server.

## Storage addons

- MEGA Cells normal cells count like AE2 cells. Bulk-cell compression is
  disabled in the pack (ruling 5), so the mod doesn't special-case it.
- AE2 Things DISKs count by exact key, with item data kept. Duplicated
  DISK UUIDs double-count, and the owner accepted that (ruling 6).

## Gametests (built)

Production gametests live in the `gametest` source set
(`src/gametest/`), which only the dev runs load (ModDevGradle
`mods { sourceSet(...) }`, `gameTestServer` run with
`sourceSet = sourceSets.gametest`). The release jar is built from `main`
alone. Run them with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
(binds no port).

- `ArchiveTestGrid` builds networks in the `tla_archive:archive_grid`
  template (8×4×8, empty) from AE2 registry ids: network A (creative energy
  cell, drive with a 4k cell, storage bus on a chest, controller) and an
  unconnected network B with its own controller. It is a reviewed port of
  the spike harness. It force-loads the template's chunks, because AE2 only
  creates nodes in block-ticking chunks, and looks up block-entity nodes
  through a real side.
- `ManifestTests`: the shipped manifest loads as test-only with every
  quantity labelled; an unlabelled quantity in a test-only manifest is
  rejected; potion, enchanted-book, pickaxe and banner matching, including
  the deferred cases that must match nothing.
- `ArchiveControllerTests`: own-network counting across a cell and a storage
  bus while another network is ignored; storage-bus credit lost on
  disconnect; readiness lost on withdrawal and restored on restock; splash
  strength II, the efficiency + fortune book, the damaged renamed pickaxe
  (which keeps its name and damage when withdrawn) and the patterned blue
  banner counted on a network; stale on power loss and live again on power
  return; stale after unload and an NBT reload; placer-team ownership,
  denial for another team, and freezing.
