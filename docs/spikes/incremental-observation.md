# Spike: incremental observation of stock changes

Handoff spike *Incremental observation* (`HANDOFF_ARCHIVE_MOD_API_SPIKES.md`,
roadmap Phase 1 archive track step 2). Throwaway code on branch
`spike/incremental-observation` (package `...spike.observe`).

## Question

How can the archive controller see stock changes for a manifest of about
1000 keys without scanning the whole network every tick, and what does it
cost? Candidates in AE2 19.2.18:

1. `IStorageWatcherNode` + `IStackWatcher` (`onStackChange(key, amount)`).
2. `IStorageService.getCachedInventory()`.
3. Grid events (`appeng.api.networking.events`) and node listener callbacks.
4. Bounded periodic reconciliation (re-check N manifest keys per tick).

For each: what it reports, when, what it misses (storage-bus edits on
external inventories, cells inserted or removed, power loss, chunk unload of
a remote part of the network), totals or deltas, and the per-tick cost at
scale.

## Verdict: partly

Incremental observation works, but **AE2 does not offer a cheap incremental
feed**. The watcher is a diff that AE2 computes by rebuilding the whole
network's cached inventory every tick, so its cost grows with the number of
distinct keys in the network, not with the manifest. On a network with tens
of thousands of key types that rebuild costs milliseconds per tick
(indicative: ~1–1.6 ms at 10k keys, ~10–15 ms at 50k).

The cheapest way to keep 1000 manifest keys current is **bounded
reconciliation by simulated extraction** (cost proportional to the keys
checked, not the network size, and it reads external inventories live),
combined with the controller node's own listener callbacks to mark readings
stale immediately on power loss, boot, or grid change. The watcher stays
useful for a small, hot key set (for example the keys of a reconstruction in
progress), and costs nothing extra on networks that already have a storage
level emitter or storage monitor.

## How AE2 implements it (source, tag `neoforge/v19.2.18`)

Source: `github.com/AppliedEnergistics/Applied-Energistics-2` at tag
`neoforge/v19.2.18`.

- `src/main/java/appeng/me/service/StorageService.java`
  - `onServerEndTick()` (line 99): if **no** watcher is registered on the
    grid, it only marks the cache stale (rebuilt lazily on the next
    `getCachedInventory()` call, line 200). If **any** watcher is
    registered (ours, a level emitter, a storage monitor), it calls
    `updateCachedStacks()` every tick.
  - `updateCachedStacks()` (line 109): clears the cache, calls
    `NetworkStorage.getAvailableStacks` over every mounted storage, then
    diffs every key against the previous amounts, posts
    `onStackChange(key, newAmount)` for changed watched keys, and copies all
    amounts into a private map. The diff and copy run on every rebuild, with
    or without watchers, so a rebuild costs the same whoever triggers it.
  - `addNode()` (line 161): registers a node's `IStorageWatcherNode` and
    calls `updateWatcher(newWatcher)`. No initial callback is posted for
    stock that already exists.
- `src/main/java/appeng/api/networking/storage/IStorageWatcherNode.java`
  line 48: `amount` is the "New amount in the network", i.e. a total.
- `src/main/java/appeng/api/networking/storage/IStorageService.java`:
  `getCachedInventory()` returns the same `KeyCounter` object every time
  ("Does not return a copy"); there is no generation counter or dirty flag,
  so "changed since last tick" cannot be detected without a watcher or your
  own diff.
- `src/main/java/appeng/parts/automation/StorageLevelEmitterPart.java`
  (lines 92–108): AE2's own watcher seeds itself from
  `getCachedInventory()` in `updateWatcher`, confirming the seeding pattern.
- Storage buses: `src/main/java/appeng/parts/storagebus/StorageBusPart.java`
  (`onNeighborChanged` line 268 alerts the bus to tick now;
  `tickingRequest` line 285), `src/main/java/appeng/me/storage/CompositeStorage.java`
  (`onTick` line 86: re-polls the external inventory, URGENT on change,
  SLOWER otherwise), `src/main/java/appeng/core/settings/TickRates.java`
  line 41 (`StorageBus(5, 60)`), `src/main/java/appeng/me/service/TickManagerService.java`
  lines 127–130 (SLOWER adds 1 tick per call, up to the 60-tick maximum).
  The grid sees an external edit only after the bus re-polls.
- Grid splits: `src/main/java/appeng/me/GridNode.java` `validateGrid()`
  (line 199) gives the side without the pivot a brand-new `Grid` (and a
  brand-new, empty `StorageService`); `src/main/java/appeng/me/Grid.java`
  lines 119–124 pick a new pivot from a set iterator when the pivot leaves,
  so which side keeps the old grid is effectively arbitrary.
- Chunk unload: `src/main/java/appeng/blockentity/grid/AENetworkedBlockEntity.java`
  line 75, `onChunkUnloaded()` destroys the block entity's grid node (same
  path as removal from the grid's point of view).
- Drive cells are only mounted while the drive is online:
  `src/main/java/appeng/blockentity/storage/DriveBlockEntity.java` line 385.
- Grid events in `src/main/java/appeng/api/networking/events/`: power,
  booting, channels, controller, crafting CPU, spatial, and
  `statistics/GridChunkEvent` (whose base class says it is "still subject to
  change"). **There is no storage-change or cell-array event in 19.2.18.**
  Per-node, `IGridNodeListener` gives `onStateChanged(POWER | CHANNEL |
  GRID_BOOT)` and `onGridChanged`.

## Evidence

Gametests in
`src/main/java/io/github/nturner03/tlaarchive/spike/observe/ObservationSpikeTests.java`,
commit `5270c12` on `spike/incremental-observation` (branched from
`spike/grid-extraction` at `edebecc`). Run
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`:
all 21 tests (11 existing + 10 new) pass. The observer
(`ManifestObserver`) is an off-world managed node with an
`IStorageWatcherNode` service, attached through the shared `SpikeGrid`
harness (energy cell, drive with a 1k cell of 100 diamonds and 50 gold,
storage bus on a chest with 64 diamonds and 10 iron). Log lines below are
from the third run (11:51) unless noted.

| Test | What it shows | Log |
| --- | --- | --- |
| `watcherReportsTotalsNotDeltas` | Attaching to a settled network gives **no initial callbacks** (two `updateWatcher` calls: own one-node grid, then the merged grid). After inserting 10 diamonds and extracting 4 gold, callbacks carry **totals** (174, 46) at the **end of the same tick**. Unwatched (emerald) and unchanged (iron) keys are not reported. | `attach: after 10 ticks changes=[] nodeEvents=[updateWatcher#1, gridChanged size=1, updateWatcher#2, gridChanged size=5, ...]`; `totals: changes after insert at tick 43: [diamond 174 @43, gold_ingot 46 @43] (emerald reports=0)` |
| `storageBusEditLatency` | Live simulated extraction sees a chest edit in the same tick. A normal chest edit (notifies neighbours) reaches the watcher **2 ticks** later. Five silent edits (stack shrunk in place, no `setChanged`) after the bus had backed off reached it after **4, 16, 3, 7, 12 ticks** (bound: the bus's 60-tick maximum poll). | `bus: notified edit at tick 233, live simulate same tick=100`; `notified edit seen after 2 ticks; silent edits seen after [4, 16, 3, 7, 12] ticks` |
| `cellRemovedAndReinserted` | Removing the cell from the drive: callbacks diamond→64, gold→0 **1 tick** later; reinserting: back to 164/50 after 1 tick. | `cell: removal seen after 1 ticks: [diamond 64 @38, gold_ingot 0 @38]`; `reinsert seen after 1 ticks` |
| `powerLossAndRestore` | Removing the energy cell: every watched key is reported as **0** in the same tick as the node's `POWER active=false` event; cache is 0, simulate is 0. Restoring power: totals come back after the grid reboots (33 ticks here). Power loss looks exactly like "everything withdrawn" unless the controller checks its node state. | `power loss at tick 38: changes=[gold 0, iron 0, diamond 0 @38] nodeEvents=[..., state POWER active=false powered=false]`; `power restore ... seen after 33 ticks` |
| `simulatedRemoteChunkUnloadA/B/C` | Calling the drive block entity's `onChunkUnloaded()` splits the grid (observer stays with the energy cell). Run 3, all three trials: the observer landed on a **new grid**: `updateWatcher` with a new watcher, **no zero callbacks at all**, `latestDiamonds=-1` (never reported), though the new grid's cache says 0. Run 2 (single trial, same scenario): the observer kept the old grid and got zero callbacks for all three keys. The observer's part stays active (`observerActive=true`), so nothing marks the drop as an unload. No `GridChunkRemoved` for the drive's chunk (other nodes of the grid remain in it); the new grid posted `GridChunkAdded`. | run 3: `unload A ... newWatcher=true changes=[] nodeEvents=[updateWatcher#3, gridChanged size=2, state POWER ...] observerActive=true gridSize=2 observerGridCachedDiamonds=0 latestDiamonds=-1 chunkEventsForDriveChunk=[added ...]`; run 2: `unload ... changes=[diamond 0, iron 0, gold 0 @38] ... observerActive=true gridSize=2` |
| `costWith1kKeys`, `costWith10kKeys`, `costWith50kKeys` | Indicative costs, below. 1000 watched keys all changing in one tick produce exactly 1000 callbacks. | `cost distinct=... rebuild=... fullScan=... cachedLookup1000=... simulate1000=...; burst(...)` |

### Indicative cost (µs, `System.nanoTime()` in the gametest server)

Network: the harness's cell and storage bus plus a mounted in-memory storage
with N distinct item keys (paper with distinct custom names). Median of 15
repetitions in one test, range over three runs. Gametests run in parallel
batches on the server thread, JIT warm-up varies, and the CPU is shared, so
treat these as orders of magnitude only.

| Distinct keys in network | Cache rebuild (`invalidateCache` + `getCachedInventory`) = AE2's per-tick cost once any watcher exists | Full `getAvailableStacks` scan alone | 1000 cached lookups | 1000 live simulated extractions | Rebuild in a tick where all 1000 watched keys changed (incl. 1000 callbacks) |
| --- | --- | --- | --- | --- | --- |
| 1 000 | 92–177 | 23–26 | 26–32 | 196–463 | 257–347 |
| 10 000 | 1 018–1 640 | 251–268 | 42–56 | 322–817 | 1 291–2 072 |
| 50 000 | 14 115–15 037 | 3 448–3 879 | 109–113 | 274–308 | 12 353–13 606 |

Reading: the rebuild is roughly 4–5× the raw scan (the diff and amount copy
in `updateCachedStacks`), and grows faster than linearly in this setup.
Callbacks themselves are cheap (the burst row is about the rebuild cost).
Simulated extraction of 1000 keys costs a few hundred µs regardless of how
many key types the network holds; here it traverses three mounts, one of
them a storage bus reading a 27-slot chest live.

## Answers per candidate

| | Reports | Latency | Misses / pitfalls | Cost per tick |
| --- | --- | --- | --- | --- |
| `IStorageWatcherNode` | Totals per watched key, only on change | End of the same tick for network-side changes and cells; storage-bus edits after the bus re-polls (2 ticks notified; 3–16 measured silent; up to 60) | No initial snapshot: seed from `getCachedInventory()` on every `updateWatcher`. On a grid split the observer may get a fresh empty grid and **no zero callbacks** for stock that left. Power loss reports 0 for everything (indistinguishable from withdrawal without node state). Remote chunk unload is indistinguishable from removal. | Forces a full cache rebuild on its grid every tick: proportional to distinct keys in the whole network (~0.1 ms at 1k, ~1–1.6 ms at 10k, ~10–15 ms at 50k). Zero extra if the network already has a level emitter or storage monitor. |
| `getCachedInventory()` | Snapshot of all available stacks (not craftables) | Updated at most once per tick; same storage-bus lag as above | No change detection: same `KeyCounter` object, no version. Rebuild is lazy without watchers, so reading it every tick costs a full rebuild every tick. | Lookups are cheap (1000 keys ~30–110 µs) once built; the first read after a stale tick pays the rebuild. |
| Grid events / node listener | Power, boot, channel, grid change; `GridChunkEvent` | Same tick | No storage-change event exists. `GridChunkRemoved` only fires when the grid's last node in a chunk leaves, and a split shows up as `GridChunkAdded` on the new grid. | Negligible |
| Bounded reconciliation (simulated extraction of N manifest keys per tick) | Extractable amount, read live (storage buses read the external inventory directly) | Full manifest sweep every 1000/N ticks; no storage-bus lag | Only extractable stock (a read-only or insert-only mount would differ from what the cache shows); virtual/infinite sources return huge amounts (same open ruling as counting); cost per key grows with the number of mounts and the size of external inventories behind buses. | ~0.2–0.8 µs per key here; 50 keys per tick ≈ 10–40 µs, independent of network key count |

## Recommended observation design

1. **Controller node listener is the stale signal.** On `onStateChanged`
   (POWER, GRID_BOOT) and `onGridChanged`, check `node.isActive()`. While
   inactive or booting, mark the archive *unavailable/stale* and keep the
   last counts as a cache (the design's "cached progress, temporarily
   inaccessible storage"); do not report them as missing, and do not treat
   the zeros AE2 reports during power loss as withdrawals.
2. **Bounded reconciliation is the main counting path.** Each tick (or each
   few ticks), simulate-extract the next N manifest keys (`Long.MAX_VALUE`,
   `Actionable.SIMULATE`, the controller's action source) and update the
   cached count. Default N so that a 1000-key manifest is swept about once a
   second (N≈50 at 20 TPS). Make N a server config and add a per-tick time
   budget. This reads storage buses live, so it has no bus lag and does not
   depend on the network's total key count.
3. **On grid change, restart the sweep** (and, if the watcher is used,
   reseed it from the new grid's cache). A split can silently move the
   controller to an empty grid.
4. **Optional watcher for hot keys only.** Use `IStorageWatcherNode` for the
   small set of keys that need prompt updates (a reconstruction in
   progress, or the keys a player is looking at), with `setWatchAll(false)`.
   Accept that it forces AE2's per-tick rebuild on that grid; consider
   enabling it only while needed, since removing all of the grid's watchers
   returns AE2 to lazy rebuilding.
5. **Readiness is always re-verified at the action.** Reconstruction already
   simulates before it extracts (grid-extraction spike); observation drives
   the display and "ready" hints, never consumption by itself.
6. **Remote chunk unload:** AE2 gives no reliable signal. A drop in counts
   while the controller stays active is shown as current holdings (the
   design treats saved counts as caches, not credit). Whether to show
   "possibly unloaded" needs the owner's ruling (deferred decision *power and
   chunk interruption*); one option is to remember which chunks the grid
   spans (`GridChunkEvent`, flagged unstable in the API) and flag the archive
   when one of them is not loaded.

## Open questions

- Owner ruling (deferred decision *power and chunk interruption*): show last
  known counts as stale, or zero, while the network is unpowered or partly
  unloaded? This spike shows AE2 itself reports zero in both cases.
- Real chunk unload was simulated by calling `onChunkUnloaded()` on the
  drive's block entity; a real unload of a distant chunk, and its reload,
  were not tested (the simulated reload via `onLoad()` did not recreate the
  node and was dropped).
- Simulated-extraction cost against large external inventories (drawer
  controllers, Sophisticated Storage, many storage buses) and against MEGA
  Cells / AE2 Things storage is not measured. Neither mod is on the dev
  classpath.
- Whether craftable-only keys ever appear in the cache or in simulated
  extraction was not checked here (the cache is documented as available
  stacks; the grid-stock spike covers what simulate returns).
- The rebuild's superlinear growth (4–5× the raw scan) was seen with
  component-bearing keys (custom-named paper); plain items may hash faster.

## Requests to the pack track

For the controller handoff to add under *Requests to the pack track* (do not
act on this from the mod track):

- **Spark baseline of AE2's cache rebuild on TLADev** (no tla_archive
  needed): on a populated test network, record the number of distinct key
  types (for example from a terminal), then run
  `/spark profiler start --thread "Server thread" --timeout 120` twice: once
  with no storage level emitter or storage monitor on the network, once with
  one level emitter attached (which forces a rebuild every tick). Report the
  time in `appeng.me.service.StorageService.updateCachedStacks` and
  `onServerEndTick`, plus MSPT from `/spark tps` or `/spark tickmonitor`.
  This calibrates the indicative numbers above at real scale.
- **Later, once a controller release exists:** the same profile with an
  archive controller and a 1000-key test manifest attached, looking at the
  `io.github.nturner03.tlaarchive` frames (reconciliation budget) and at
  storage buses on large external inventories.
