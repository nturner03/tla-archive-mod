# Spike: AE2 19.2.18 access control, disconnected storage and multiple controllers

Recorded 2026-10-28 for Phase 2 step 5 (`HANDOFF_ARCHIVE_MOD_CATALOG.md`). Source reading only; nothing here ran in game. The options built on it are put to the owner; rulings go to the pack design document.

Source checked: `AppliedEnergistics/Applied-Energistics-2`, tag **`neoforge/v19.2.18`** (commit
`db17504a86128fdf3dae31f5fb7a112a646e0b93`; a plain `v19.2.18` tag does not exist). I also checked the pinned jar
(`appliedenergistics2-19.2.18.jar`) for the presence or absence of classes and assets. Paths below are relative to
`src/main/java/` at that tag unless stated otherwise. NeoForge checks use `neoforge-21.1.265-sources.jar`.
"Unconfirmed" means I did not verify the claim against 19.2.18.

## 1. Access control

**Findings**

- **19.2.18 has no security station and no permission system.**
  - `appeng/api/networking/security/` contains only `IActionHost.java` and `IActionSource.java`.
  - There is no `ISecurityService`, `SecurityPermissions` or security block in `appeng/core/definitions/AEBlocks.java` or
    `AEItems.java`, in either the source or the jar.
  - The only leftover is the orphan model `assets/ae2/models/item/security_station.json`.
  - Grepping all non-client code for `permission` finds only `Platform.hasPermissions` (see below).
  - Addon pages also describe the security terminal as an older-version feature (AE2 Web Integration says networks are
    identified "through wireless access points (or security terminal on older mc versions)"). I did not confirm which
    AE2 version removed it.
- **`IActionSource` is advisory.** Its javadoc says "In most cases this is used for security checks"
  (`appeng/api/networking/security/IActionSource.java`), but no code checks it.
  - `NetworkStorage.extract/insert/getAvailableStacks` (`appeng/me/storage/NetworkStorage.java` L173–220) iterate the
    mounted storages and pass the source through. They never check it.
  - The only source-dependent refusal I found is `InterfaceLogic.InterfaceInventory.extract` (`appeng/helpers/InterfaceLogic.java`
    L589–599), an anti-ping-pong rule between interfaces on the same grid.
  - `NetworkStorage` also returns 0 or an empty listing on reentrant use (`mountsInUse`). This is already documented.
- **The owning player is only metadata.** `IManagedGridNode.setOwningPlayer` / `setOwningPlayerId`
  (`appeng/api/networking/IManagedGridNode.java` L188–203) still has javadoc warning that skipping it "may result in
  in-compatibility with the security system", but that system no longer exists.
  - The value is saved as `"p"` in the node NBT (`appeng/me/GridNode.java` `saveToNBT`/`loadFromNBT` L450–503).
  - It is read through `IGridNode.getOwningPlayerId()` / `getOwningPlayerProfileId()`
    (`appeng/api/networking/IGridNode.java` L159–170). `IPlayerRegistry` maps the int id to a UUID
    (`appeng/api/features/IPlayerRegistry.java`).
  - Its only uses:
    - fake-player identity for annihilation and formation planes (`appeng/parts/automation/AnnihilationPlanePart.java`
      L175, `FormationPlanePart.java` L109);
    - the owner of a spatial-storage transition (`appeng/blockentity/spatial/SpatialIOPortBlockEntity.java` L171);
    - advancement triggers (`appeng/me/service/PathingService.java` L273, `appeng/parts/storagebus/StorageBusPart.java`
      L466).
- **The only player permission check is `Platform.hasPermissions`**, which calls `level.mayInteract(player, pos)`
  (`appeng/util/Platform.java` L212–217). It gates only world-editing tools: the wrench (`appeng/hooks/WrenchHook.java`),
  network tool, color applicator, matter cannon and conversion monitor. It never gates terminals or storage.
- **Wireless terminals** find their grid only from a linked access point `GlobalPos`. No player check is made
  (`appeng/items/tools/powered/WirelessTerminalItem.java` `getLinkedGrid`, L150–180).
- **Per-storage access settings are the only built-in "permissions".** A storage bus has `Settings.ACCESS`
  (`AccessRestriction` NO_ACCESS/READ/WRITE/READ_WRITE, default READ_WRITE), `STORAGE_FILTER` (NONE/EXTRACTABLE_ONLY,
  default EXTRACTABLE_ONLY) and `FILTER_ON_EXTRACT` (default YES) (`appeng/parts/storagebus/StorageBusPart.java`
  L152–159, L384–393; `appeng/api/config/AccessRestriction.java`, `StorageFilter.java`).
  - `MEInventoryHandler.extract` refuses only when `filterOnExtraction && !canExtract` (`appeng/me/storage/MEInventoryHandler.java`
    L90–96).
  - The listing hides non-extractable contents only when `filterAvailableContents`, which is
    `EXTRACTABLE_ONLY && FILTER_ON_EXTRACT` (L99–122).
  - With the defaults, a WRITE-only bus is hidden from the listing and extraction returns 0, so the listing and the
    extraction agree.
  - With `STORAGE_FILTER=NONE`, a WRITE-only bus is **listed** in `getCachedInventory()` but **cannot be extracted**.
    The display then overstates usable stock, which is the "display-only view" case in the design.
  - Reading the source, `FILTER_ON_EXTRACT=NO` would allow extraction even through a WRITE-only bus. I did not test
    this.
  - Partition and inverter cards also filter the listing and extraction (`StorageBusPart.createFilter`, L425–434).
  - Changing any of these settings calls `updateTarget(true)` and remounts the storage. No grid event is posted.
- **Nothing in AE2 can stop our controller from reading `getCachedInventory()` or extracting through its node.** The
  only things that remove stock from what the controller sees are:
  - the node going inactive (power, booting, channels; see section 2);
  - the controller's own reentrancy;
  - per-storage access, partition and filter settings;
  - interface anti-ping-pong.
- **Addons:** claim or protection mods may act through `mayInteract`. That affects only AE2's world tools, and the
  link to claim mods is unconfirmed. AE2 Web Integration has its own admin and network-identification layer. I found
  no AE2 permission API in 19.2.18 for an addon to hook into, so an addon permission system would be addon-specific
  and invisible to us.

**What "changed security permissions" can mean for us in 19.2.18:** (a) a storage bus's access, filter or partition
settings change; (b) a protection or claim mod changes the player's rights; (c) the pack later adds an addon with its
own permission layer. Only (a) changes what the controller can count or extract, and it does so silently.

## 2. Disconnected storage

**How the cache works.** `StorageService.getCachedInventory()` rebuilds from `NetworkStorage.getAvailableStacks` when
it is dirty. With no watchers, `onServerEndTick` marks it dirty every tick (`appeng/me/service/StorageService.java`
L63–72, L170–176).
- `mount`/`unmount` change `NetworkStorage.priorityInventory` but post **no event and no "changed" signal**
  (`NetworkStorage.java` L62–90, `StorageService.ProviderState`).
- `appeng/api/networking/events/` has only BootingStatusChange, ChannelRequirementChanged, ControllerChange,
  CraftingCpuChange, PowerIdleChange, PowerStatusChange, PowerStorageStateChanged, Spatial and statistics/Chunk events.
  **There is no `GridStorageEvent`.**
- `IStorageService` (`appeng/api/networking/storage/IStorageService.java`) exposes `getInventory`, `getCachedInventory`,
  add/remove/refresh providers and `invalidateCache`. **It has no API to list mounts or providers.** `nodeProviders`
  and the mount table are private.

**A workaround (internal, not recommended).** Iterate `grid.getNodes()` and call `node.getService(IStorageProvider.class)`
(`IGridNode.getService`, `appeng/api/networking/IGridNode.java` L56–57). That finds every storage-providing node.
Calling `provider.mountInventories(ourMounts)` would show what each node would mount, but it has side effects (for
example `DriveBlockEntity.mountInventories` calls `updateState()`). Identifying bus targets would need internal classes
such as `StorageBusPart.getConnectedToDescription()`.

**Per case** ("indistinguishable" means the counts drop exactly as if the items had been withdrawn):

| Case | What happens in AE2 19.2.18 | Signal to our node |
| --- | --- | --- |
| Storage bus target **removed** | NeoForge invalidates capabilities on BE destruction (`IBlockCapabilityProvider` javadoc, NeoForge 21.1.265). The bus's `capabilityListener` → `onCapabilityInvalidation` sets the delegate to `NullInventory` immediately and schedules an update (`StorageBusPart.java` L131–140, L318–324). `updateTarget` remounts when the registered state flips (L366, L414–416). | None. The stock is gone from the next cache rebuild. Indistinguishable. |
| Storage bus target **chunk unloads** | NeoForge invalidates capabilities on chunk unload (`net/neoforged/neoforge/capabilities/CapabilityHooks.java` `invalidateCapsOnChunkUnload`), which leads to the same `NullInventory`. While the target is not ticking, `updateTarget` keeps `NullInventory` and retries slowly (`StorageBusPart.java` L335–352). | None. Indistinguishable. |
| Storage bus onto **another ME network** (via an interface, `AECapabilities.ME_STORAGE`) whose **power goes** | The interface exposes `networkStorage = grid.getStorageService().getInventory()` (or only its local slots if it has config) (`InterfaceLogic.java` L228–264). On the unpowered subnet, drives unmount because `mountInventories` requires `isOnline()` (`appeng/blockentity/storage/DriveBlockEntity.java` L313–317, L385–394). The subnet's buses do the same (`StorageBusPart.java` L171–178, L305–307), so the bus sees an empty `NetworkStorage`. | None on our grid. Indistinguishable. |
| ...whose **chunk unloads** | The interface BE unloads, which invalidates its capability, so the bus gets `NullInventory` as in the row above. | None. Indistinguishable. |
| **Drive cell removed** | `DriveBlockEntity.onChangeInventory` → `IStorageProvider.requestUpdate` → `refreshNodeStorageProvider` (unmount, then mount) (`DriveBlockEntity.java` L333–342; `IStorageProvider.java` L48–53). No node is added or removed. | None. Indistinguishable. |
| **Cable cut splits the grid** | `GridConnection.destroy`/`GridNode.destroy` → `validateGrid()`. The side that still reaches the pivot **keeps the same `IGrid` object**, and only the other side gets `Grid.create` + `setGrid` → `onGridChanged` (`appeng/me/GridNode.java` L198–210, L320–337, L342–380; `GridConnection.java` L94–98). The pivot is moved toward side A "closer to the controller", so the ME controller's side usually keeps the grid. | **Our node gets `onGridChanged` only if it is on the side that loses the pivot.** If it stays on the kept side, it sees only storage vanish, plus a repath (next row). |
| **Part of the network unloads** | Networked BEs destroy their node on `onChunkUnloaded`/`setRemoved` (`appeng/blockentity/grid/AENetworkedBlockEntity.java` L75, L113). This is node removal, so it can split the grid as in the row above and unmount the storage. If an ME controller or energy is lost, the result is a power or channel change. | As for a split; otherwise only storage loss. |
| **Channel shortage** | Drives and buses have `REQUIRE_CHANNEL`. `isOnline()` = `isPowered() && meetsChannelRequirements()` (`IGridNode.java` L117–130; `GridNode.meetsChannelRequirements` L507–509), so a channel-less drive or bus unmounts. **Our node has no `REQUIRE_CHANNEL` flag** (`ArchiveControllerBlockEntity.java` L67–71), so it stays active. | None. Indistinguishable. |

**Repath side effect** (confirmed in source, not tested at runtime):
- Any node joining or leaving a grid calls `PathingService.repath()` (`PathingService.java` L150–186, L322–329;
  `GridNode.java` L384; `GridConnection.java` L92, L222).
- On the next server end tick, `booting=true` → `notifyAllNodes(GRID_BOOT)` → recompute channels → `booting=false` →
  a second `GRID_BOOT` (L91–141).
- `isActive()` includes `hasGridBooted()`, so our `onStateChanged` sees `isActive()==false` during the first
  notification and calls `interrupt()`.
- So **any topology change on our grid (machine placed or broken, chunk unload, cut) interrupts the sweep**. Cell swaps
  and storage-bus target changes do **not**, because they add or remove no node.

**Summary:** AE2 19.2.18 gives no per-storage signal that a mounted inventory disappeared. From `getCachedInventory()`,
unloaded or disconnected storage cannot be told apart from withdrawn items. The only coarse signals are:
- `onGridChanged` (only when the node moves to another grid);
- `GRID_BOOT`/`POWER`/`CHANNEL` state changes (only for node topology, power or channel changes);
- the cached-inventory refresh time in `debugDump`, which is not useful.

## 3. Multiple controllers

- **Same grid?** Compare the `IGrid` objects by identity (`node.getGrid() == other.getGrid()`). The internal
  `appeng.me.Grid` has a `serialNumber` from a **static counter** (`appeng/me/Grid.java` L71, L79, L295, L392), which
  `IGrid` does not expose. It restarts at 0 every JVM start and is not saved.
- **Grids are not persisted.** Nodes save only `"p"` plus per-service node data (`GridNode.saveToNBT`), and grids are
  rebuilt when loaded. **AE2 has no stable grid id.**
- **On a merge** (`GridConnection.mergeGrids`, `appeng/me/GridConnection.java` L231–258), the "better" grid survives
  (`isGridABetterThanGridB`, using power/controller importance flags, `Grid.setImportantFlag` L258) and the other
  side's nodes move to it, each receiving `onGridChanged`.
- **On a split**, one side keeps the object (section 2).
- **Subnetworks share storage.** A storage bus on grid A facing an interface on grid B mounts **B's entire
  `NetworkStorage`** (or only the interface's local slots if the interface has config) into A
  (`InterfaceLogic.getInventory`, L254–259; `StorageBusPart` `adjacentStorageAccessor`, L145, L341).
  - B's stock then appears in both A's and B's `getCachedInventory()`. Summing per-grid readings double-counts it.
  - Loops (A→B→A) are cut by the `mountsInUse` reentrancy guard (`NetworkStorage.java`). Depending on the entry
    point, this can produce partial listings.
  - Quartz fiber shares only power between separate grids (`appeng/parts/networking/QuartzFiberPart.java`). It does
    not share storage, so it does not cause double counting (I did not examine it in detail).
  - **Detecting this is only possible by inspecting internals:** find `StorageBusPart` machines
    (`grid.getMachines(StorageBusPart.class)`, an internal class) whose adjacent block is an `InterfaceLogicHost` on
    another grid. There is no public API. A sound combined view could instead dedupe by **physical storage**
    (drive/cell positions), which needs internals too. It could also sum only one controller's grid per team, so that
    it never sums grids at all.
- **AE2's "one controller multiblock" rule.** `PathingService.addNode/removeNode` tracks `ControllerBlockEntity`
  instances by an `instanceof` check (L150–186). On the next tick, `ControllerValidator.calculateState` (`appeng/me/pathfinding/ControllerValidator.java`)
  checks three things:
  1. every controller is reachable from the first through adjacent controllers only;
  2. the bounding box is under `MAX_SIZE`;
  3. there is no "cross" shape.

  Otherwise the state is `CONTROLLER_CONFLICT`. Conflict sets every node to 0 channels (`AdHocChannelUpdater(0)`) and
  posts `GridControllerChange` (`PathingService.java` L120–125, L209–217). This is internal service code. The
  **reusable public pieces** are:
  - `IGrid.getMachines(Class)` / `getActiveMachines(Class)` / `getMachineNodes(Class)` (`appeng/api/networking/IGrid.java`
    L57–86), keyed by the node owner's class. The query `grid.getMachines(ArchiveControllerBlockEntity.class)` lists
    every archive controller on the grid.
  - `GridServices.register(publicInterface, implClass)` (`appeng/api/networking/GridServices.java`), an
    `IGridServiceProvider` with `addNode`/`removeNode`/`onServerEndTick`/`saveNodeData` (`IGridServiceProvider.java`).
    A custom grid service can track archive controllers per grid as nodes join or leave, and elect or flag one each
    tick, the same way `PathingService` does for controllers.
  - Custom `GridEvent`s through `grid.postEvent`.

## 4. Other lifecycle facts

- **Relocation (break and re-place):** the node is destroyed (`setRemoved`) and a new node is created on `onFirstTick`.
  The node joins whatever grid it touches, which triggers a repath on that grid. Nothing in AE2 carries identity across
  the move. The owning player is set again at placement (`setOwningPlayer`, used before `create`,
  `appeng/me/ManagedGridNode.java` L239–255).
- **Removing cells** and **swapping cells between drives** produce only a remount (section 2). The same cell moved to a
  drive on another grid moves its whole contents. AE2 Things DISK and MEGA cells behave the same way, since they are
  mounted through the drive's `IStorageProvider` (unconfirmed for the addons).
- **Destroyed machines:**
  - A broken drive drops its cells with their contents intact.
  - A broken storage-bus target drops its contents in the world (vanilla behaviour).
  - Node removal causes a repath, so the sweep is interrupted (section 2).
- **Multiple access points:** terminals, wireless terminals (any holder of a linked terminal within range of the
  access point), export, IO and pattern providers and crafting all extract through the same `NetworkStorage` with no
  owner check. AE2 offers **no extraction veto or reservation**, so stock the controller counted can be withdrawn
  between validation and extraction. This is already handled by trusting only the `MODULATE` result.
- **Power for extraction:** `NetworkStorage.extract` uses no AE power. `StorageHelper.poweredExtraction(IEnergySource, MEStorage, ...)`
  exists if the owner wants extraction to cost power (`appeng/api/storage/StorageHelper.java` L69–84). This is relevant
  to the deferred decision "whether extraction draws AE power".
- **Craftable items** are not listed. `CraftingServiceStorage` mounts an `MEStorage` that only intercepts inserts and
  does not override `getAvailableStacks` (`appeng/me/service/helpers/CraftingServiceStorage.java`). This is consistent
  with "no credit for craftable".

## Implications for the options

Recommendations are labelled.

- **Changed security permissions:** in base AE2 19.2.18, the only practical meaning is storage-bus
  access/filter/partition settings. Any policy should be written in those terms.
  - *Recommendation:* the existing rule already covers it: display is the listing, and readiness for reconstruction is
    proven only by `SIMULATE`, then trusting `MODULATE`.
  - The one gap is a WRITE-only bus with `STORAGE_FILTER=NONE`, which inflates the display. Pre-reconstruction
    validation catches it, but the catalog can show "ready" while extraction would fail.
  - *Option:* a periodic or on-demand `SIMULATE` check of the shortfall entries.
  - Addon permission layers are out of scope until the pack adds one.
- **Disconnected storage:** AE2 can't tell it apart from withdrawal through the public API.
  - Option A (cheapest, consistent with the "may only lower" ruling): treat every drop as real. A lower count is safe
    because it never over-credits. Accept that a reading can fall while part of the network is unloaded.
  - Option B: treat `onGridChanged` and the topology `GRID_BOOT` interrupts as "storage may have changed". A reading
    then shows "network changed since last reading" until the next sweep. The controller already goes stale in that
    case, and this would only label it.
  - Option C (internal, fragile): enumerate `IStorageProvider` nodes per sweep and flag a drop in their count. This
    still misses cell swaps, bus-target loss and subnet power loss.
  - *Recommendation:* A plus B. Don't build C.
- **Multiple controllers:** a per-grid rule is enforceable through public API (`getMachines` or a custom grid service).
  - A per-team rule needs our own registry (the team SavedData), because grids have no stable id.
  - A team view that sums several grids double-counts subnet storage. There is no public way to detect that.
  - *Recommendation:* if teams get one "active" archive, give them one controller whose single grid is the source of
    counts, rather than a sum across grids.
  - "One active controller per grid" can follow AE2's controller-conflict pattern: on conflict, all controllers on that
    grid show a conflict state and reconstruction is disabled, rather than electing one. Election needs a stable
    tiebreak (for example lowest position plus dimension), because the grid id isn't stable.
  - Two teams' controllers on one shared grid would each count the same stock. Team credit never grants access, but a
    shared grid inherently shares stock. The owner has to rule on this.

Sources (web, leads only): [AE2 Web Integration (CurseForge)](https://www.curseforge.com/minecraft/mc-mods/ae2-web-integration),
[AE2 Web Integration (Mado Hosting)](https://www.mado-hosting.com/minecraft-mods/ae2-web-integration),
[RandomComplement (modpackindex)](https://www.modpackindex.com/mod/72602/randomcomplement).
