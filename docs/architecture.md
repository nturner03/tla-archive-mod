# Architecture

How `tla_archive` is built: components, data flow, persistence. Created
2026-10-28 from the API spike findings (`docs/spikes/`), before any
production code. Every section is **planned** until the step that builds it
updates the section to describe what exists. The owner rulings it relies on
are listed in `HANDOFF_ARCHIVE_MOD_CONTROLLER.md` → *Owner rulings*; the
pack's design document is the long-term home for them.

Pinned APIs: AE2 `19.2.18`, FTB Teams `2101.1.11` (`AGENTS.md` → *Fixed
facts*).

## Components

| Component | Responsibility | Step | Based on |
| --- | --- | --- | --- |
| Manifest | Versioned, data-loaded list of entries: key, category, rule, quantity, notes. Quantities can be edited without touching the matcher. | 3 (test-only), Phase 2 (real) | design *Completion model*; pack `docs/manifest/` |
| Matcher | Maps an `AEItemKey` to the manifest entries it satisfies (`item_id`, `banner_base_color`, `potion_effect`, `enchantment_on_book`). One key may satisfy several entries. Never changes item data. | 3 | pack `docs/edge-cases.md` |
| Archive controller | Block + block entity with an AE2 managed grid node. Owns the identity, the owner team and the settings. Exposes current/required counts and a state (live / stale / unavailable / frozen). | 4 | `grid-stock-and-extraction.md`, `incremental-observation.md` |
| Stock reader | Recalculates per-entry counts from `IStorageService.getCachedInventory()` through the matcher, in bounded slices per tick. | 4 | `incremental-observation.md` |
| Team access | Records the placer's `team.getId()`; checks the player's current team on every interaction; reacts to `PLAYER_CHANGED` / `DELETED`. | 4 (ownership), 5 (abort on team change) | `ftb-teams.md` |
| Reconstruction journal | Staged multi-key extraction with compensation, persisted in world SavedData, recovered on load. Behind a test flag. | 5 | `grid-stock-and-extraction.md` |

## Reading stock

- **Display and readiness** use `getCachedInventory()`. AE2 updates it at
  most once per tick, and it counts drive cells and storage-bus inventories
  together. The controller recalculates a bounded number of manifest
  entries per tick and never scans the whole network every tick.
- **Don't use a whole-grid stack watcher**: once one is attached, AE2
  rebuilds the grid's whole cached inventory every tick, which costs about
  14 ms at 50k distinct keys (indicative gametest figure).
- **Validation before consumption** uses simulated extraction
  (`Actionable.SIMULATE`). Storage-bus listings are the bus's own cached
  view and can lag by up to its poll interval, while extraction reads the
  inventory live. Only the amount a real (`MODULATE`) extraction returns is
  trusted.
- The controller extracts through its own node with
  `IActionSource.ofMachine(controller)`. It never extracts reentrantly from
  inside a storage callback, because AE2 returns 0 when it is.

## States (ruling 1, rulings 7a–7d)

| State | When | Display | Reconstruction |
| --- | --- | --- | --- |
| live | node active and powered | current counts | allowed (step 5, test flag) |
| stale | node inactive or unpowered, or controller chunk unloaded | last counts, labelled stale with a time | disabled |
| unavailable | never had a valid reading (new controller, empty grid after a split) | no counts | disabled |
| frozen | owning team deleted | last counts, read-only | disabled; a former member can re-place the controller to claim it |

An inactive grid reports 0 for everything, so readings taken while the node
is not active are discarded, never cached. A partly unloaded network looks
the same as removed storage, so it can only lower readiness. When the grid
changes (a split or merge), counts are reset and the sweep restarts.

## Reconstruction (step 5, test flag)

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

| Data | Where | Notes |
| --- | --- | --- |
| Controller identity, owner team id, settings | controller block entity NBT (and a world SavedData index if catalog access away from the block needs it) | Preserved through power loss and chunk unload. |
| Cached counts and last-valid timestamp | controller block entity | A cache, never earned credit. Rebuilt from storage after a restart. |
| Reconstruction journal | world SavedData, keyed by operation UUID | Keys via `AEKey.toTagGeneric`/`fromTagGeneric`. |
| Team-keyed archive state | world SavedData keyed by team UUID, not FTB `extraData` | FTB copies `extraData` when a party is created. |

## Storage addons

- MEGA Cells normal cells count like AE2 cells. Bulk-cell compression is
  disabled in the pack (ruling 5), so the mod doesn't special-case it.
- AE2 Things DISKs count by exact key, with item data kept. Duplicated
  DISK UUIDs double-count, and the owner accepted that (ruling 6).

## Gametests

Production gametests live outside the release jar (planned: a `gametest`
source set loaded only by dev runs). The harness must force-load its test
chunks, because AE2 only creates nodes in block-ticking chunks, and must
look up block-entity nodes through a real side.
