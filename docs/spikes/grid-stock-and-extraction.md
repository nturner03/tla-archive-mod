# Spike: grid stock reading and multi-key extraction

Roadmap Phase 1, archive track step 2: the spikes "Grid stock" and "Atomic
multi-key extraction" in `HANDOFF_ARCHIVE_MOD_API_SPIKES.md`. Done
2026-10-28.

- Code (throwaway): branch `spike/grid-extraction`, package
  `io.github.nturner03.tlaarchive.spike.grid`, commit `edebecc`.
- Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
  (binds no port). Result at `edebecc`: `All 11 required tests passed`.
- Sources: AE2 `neoforge/v19.2.18`
  (`db17504a86128fdf3dae31f5fb7a112a646e0b93`), paths below relative to
  `src/main/java/`.

## Test set-up

`SpikeGrid` builds a network in a gametest using only the AE2 API and
registry ids, so it compiles against the API jar: a creative energy cell, a
drive with a 1k item cell (filled through `StorageCells.getCellInventory`
before insertion), a cable with a storage bus facing a vanilla chest, and
optionally an off-world managed grid node of our own
(`GridHelper.createManagedNode` + `GridHelper.createConnection`), the way a
controller would join. Standard stock: cell 100 diamonds + 50 gold, chest
64 diamonds + 10 iron.

Two things a later gametest harness needs:

- **Force-load the test chunks.** AE2 creates grid nodes only in
  block-ticking chunks (`appeng/hooks/ticking/TickHandler.java`
  `readyBlockEntities`, `Platform.areBlockEntitiesTicking`). Gametest chunks
  have no player nearby, so without forcing them no node ever appears. AE2's
  own `appeng/server/testworld/GameTestPlotAdapter.java` forces chunks for
  the same reason.
- **Block-entity hosts return no node for side `null`.**
  `IGridConnectedBlockEntity.getGridNode(dir)` returns the node only for an
  exposed side, so look it up through a real side.

## Grid stock: verdict **works**, with two caveats

| Gametest | Evidence (log line `SPIKE ...`) |
| --- | --- |
| `stockcountscellandstoragebus` | `cached diamonds=164 iron=10 gold=50, live diamonds=164`: `getCachedInventory()` sums the cell and the storage bus. |
| `simulatematchesmodulate` | `sim150=150 sim1000=164 real150=150 left live=14 chest=14`: a simulation spanning cell and bus matches the real extraction; simulation caps at what exists; a short real extraction returns what exists. |
| `cachelagsbehindexternalchange` | `same tick cached=164 live=164 simulate=100`, then `cache caught up after 1 ticks`. |
| `removingbusdropsitscontribution` | removing the chest leaves cached diamonds 100, iron 0. |
| `powerloss` | `sameGrid=true active=false powered=false cached=0 live=0 simulate=0 storedPower=0.0` |

Caveats:

1. **Nothing in the listing API is live for storage buses.**
   `getInventory().getAvailableStacks()` returned 164 in the same tick that
   the chest was emptied. The storage bus lists its own cached view
   (`appeng/me/storage/ExternalStorageFacade.java`,
   `appeng/parts/storagebus/StorageBusPart.java`, tick rate `StorageBus(5, 60)`
   in `appeng/core/settings/TickRates.java`). Only `extract` (simulate or
   real) reads the external inventory directly. In this test the cache
   caught up after one tick, probably through the chest's neighbour update
   (`StorageBusPart.onNeighborChanged`). Changes that send no neighbour
   update wait for the bus's next poll, which is up to 60 ticks. So
   readiness display can use the cache, but **reconstruction must
   revalidate with simulated extraction**, which matches the design's
   "revalidate stock at activation".
2. **Simulation is a hint, not a promise.** It matched for cells and a
   vanilla chest. But a storage-bus simulation is a heuristic (the comment
   in `ExternalStorageFacade.ItemHandlerFacade.extractFromHandler`), and a
   third-party inventory can disagree with itself.
   `optimisticsimulationiscaughtbymodulateresult` mounts a storage whose
   real extraction gives half of what its simulation promised. The plan
   must therefore trust only the amount returned by `MODULATE`. A storage
   bus also caps each external extraction at `Integer.MAX_VALUE`
   (`Ints.saturatedCast`).

Power loss: the grid object survives, but its nodes go inactive, and the
cache, the listing and simulation all return 0. An unpowered network looks
exactly like an empty one, so the controller must check
`node.isActive()`/`isPowered()` to tell "unavailable" from "missing".

Chunk unload: not tested in a gametest. From source, an AE2 block entity
destroys its grid node on chunk unload
(`appeng/blockentity/grid/AENetworkedBlockEntity.java` `onChunkUnloaded`;
`CableBusBlockEntity.onChunkUnloaded` removes its parts). Storage in an
unloaded part of a network therefore disappears the way a disconnected
chest does, and a network whose controller chunk is unloaded doesn't exist
at all.

Other notes:

- `getInventory().extract` does not draw AE power. `StorageHelper.poweredExtraction`
  (API) does. Which one the controller uses is part of the deferred
  power-consumption decision.
- `NetworkStorage.extract` returns 0 when it is called reentrantly from
  inside another extract (`appeng/me/storage/NetworkStorage.java`,
  `mountsInUse`). The controller must never extract from inside a storage
  callback.
- Extraction walks storage from highest priority down
  (`priorityInventory.descendingMap()`).

## Atomic multi-key extraction: verdict **doesn't exist; staging with compensation works**

AE2 19.2.18 has no multi-key or transactional extraction. `MEStorage` has
only the per-key `extract(key, amount, mode, source)`
(`appeng/api/storage/MEStorage.java`). AE2's own crafting CPU solves the same
problem by staging: `CraftingCpuHelper.tryExtractInitialItems`
(`appeng/crafting/execution/CraftingCpuHelper.java`) extracts each key for
real into the CPU's persisted inventory. If any key comes up short, it
reinserts everything, and voids whatever doesn't fit back in
(`TODO: maybe voiding items that fail to re-insert is not the best thing to do?`).

The prototype `StagedExtraction` (spike only) follows the same pattern but
never voids anything:

- a plan `key → amount`; `validate` simulates every key;
- `step` extracts keys for real into a staging buffer that the record owns,
  and trusts only the amount `MODULATE` returns. A short key switches the
  operation to `ROLLING_BACK`;
- `rollback` reinserts the buffer, and whatever doesn't fit stays in the
  buffer, still persisted, for a retry;
- `commit` consumes the buffer. It is allowed only in `STAGED`;
- `save`/`load` serialise state, plan, progress and buffer to NBT (keys via
  `AEKey.toTagGeneric`/`fromTagGeneric`);
- `recover` after a load rolls back `EXTRACTING`/`ROLLING_BACK`, and rolls
  `STAGED` either forward or back depending on a flag (the owner's ruling,
  below). It is idempotent.

| Gametest | Shows |
| --- | --- |
| `commitconsumesexactlytheplan` | 120 diamonds, 5 iron, 20 gold consumed across the cell and the chest; the excess (44/5/30) stays. |
| `shortstockrollsbackeverything` | Stock taken between validation and extraction causes a full rollback (totals restored). A second operation on the same network fails validation while the first holds the stock staged, so two controllers cannot spend the same stock twice. |
| `interruptedextractionrecoversafterreload` | One key per step. After the first key, the record is round-tripped through NBT and the object dropped (standing in for a restart). The journal still holds 120 staged diamonds; recovery puts them back; a second recovery changes nothing. |
| `interruptedfinalconsumptionrollseitherway` | Fully staged, reload, then recovery: roll forward consumes exactly once (no duplicates); roll back restores everything. |
| `rollbackkeepswhatdoesnotfit` | With all storage filled, rollback put iron and gold back and kept the 120 diamonds in the journal: `kept in journal=120 live d/i/g=64/10/30`, conserved. |
| `optimisticsimulationiscaughtbymodulateresult` | Validation passed, the real extraction came up short, and the operation rolled back. `after rollback flaky=25 network=40`: compensation conserves the **network** total, but items can land in a different storage from the one they came from (here the drive cell). |

### What staging cannot fix: the hard-crash window

Inside one server tick the operation is atomic with respect to all other
game logic, because the server thread is single-threaded. Players, hoppers
and other controllers can't interleave with it. The remaining risk is
persistence. Minecraft has no atomic write across files. The journal (world
`SavedData`) and the storages (a cell in a drive's chunk, an external chest
in another chunk) are saved separately:

- Both are saved together at each autosave and at `/stop`. A **clean
  restart** at any point is therefore safe in principle. The reload
  gametests model only the journal's half (an NBT round-trip of the
  record). They did not restart a server. Proving recovery after a real
  forced restart is roadmap step 5.
- A **hard crash** (kill -9, power cut) between autosaves rolls everything
  back to the last autosave. That is consistent, unless a source chunk was
  saved in between, for example because it unloaded. A chunk saved after the
  extraction, with the journal not saved: staged items are lost. Journal
  saved, chunk not: rollback duplicates them. Neither write order closes this
  gap. AE2's crafting CPU has the same exposure.

Shrinking the window: a network's chunks are loaded while the extraction
runs (they have to be, for the grid to be active). If the controller asks
for a full world save (`MinecraftServer.saveEverything`) at the end of the
tick that stages everything, and again after commit, then journal and chunks
are saved at the same tick. The window is then limited to a crash during
the save itself. Reconstruction happens about once per world, so the
save-time stutter is acceptable. This is a proposal and has not been
measured.

### Proposed reconstruction approach (for the owner)

**Staging with compensation, journalled in world SavedData, never voiding:**

1. Revalidate: the controller node is active and powered, team authority
   holds, and every planned key simulates in full.
2. In the **same tick**, extract every key for real into the journal's
   staging buffer, trusting only the amounts `MODULATE` returns. Any
   shortfall rolls everything back in the same tick, and the player sees
   which key was short.
3. Mark the journal dirty and request a world save (see above).
4. Commit: record completion and clear the buffer in a single journal write.
   Save again.
5. On server start, recover any journal not in a terminal state. Leftover
   stock that won't fit back stays visible in the journal (for example as a
   controller "overflow" the player can empty), never voided.

Doing it in one tick avoids the multi-tick cases (a chunk unloading
mid-operation, an operation spanning a save). That is fine for a manifest
of about 1,000 keys (one `extract` call per key; tick cost to be measured
in step 5). If measurement shows one tick is too slow, the journal already
supports spreading the work over several steps.

## Options for deferred decisions (owner to rule)

**Power loss** (verified: an unpowered grid reports 0 everywhere):

- A. Show the last known counts, labelled *stale* with a timestamp, and
  disable reconstruction until the node is active again (the design's
  proposed policy).
- B. Show "unavailable" with no counts.
- In both cases, never write the zeros into saved progress.

**Chunk unload** (from source: unloaded parts leave the grid):

- A. Same as power loss when the controller's own chunk is loaded but part
  of the network is not. Contributions from parts that have gone are
  indistinguishable from removed storage, so readiness can only drop.
  Accept it, as the design does ("never force-load").
- B. Remember which storage was seen before and label the drop as possibly
  temporary. The API gives no clean signal for this, so it would be
  heuristic. Not recommended.
- If the controller's own chunk unloads, the catalog shows cached data
  labelled stale.

**Interrupted final consumption** (both are proven safe for clean restarts):

- A. **Roll forward**: a fully staged operation found on load is committed.
  The player's intent is honoured; the stock is already out of storage.
- B. **Roll back**: a fully staged operation is returned to storage, and
  the player must start again. More conservative; never grants completion
  without a live, observed commit.
- An operation still extracting is always rolled back.

**Hard-crash window**: accept AE2-equivalent risk, or add the save-on-stage
mitigation above (recommended).

Virtual and compressed counting is covered by the storage-addon spike
(`docs/spikes/storage-addons.md`).
