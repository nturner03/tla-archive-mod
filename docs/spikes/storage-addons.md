# Spike: storage addons (MEGA Cells, AE2 Things)

Roadmap Phase 1, archive track step 2 (`HANDOFF_ARCHIVE_MOD_API_SPIKES.md`,
spikes "AE2 Things DISKs" and "MEGA Cells"). Spike branch
`spike/storage-addons`, based on `spike/grid-extraction` (`edebecc`).
Throwaway code: `src/main/java/io/github/nturner03/tlaarchive/spike/addons/`.

**Evidence is reproducible from commit `f0afe58`** with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
(no port). Run on 2026-10-28: `All 19 required tests passed :)` (8 new tests
plus the 11 existing grid spike tests). `./gradlew build` also passes.

## Summary

| Addon | Question | Verdict |
| --- | --- | --- |
| MEGA Cells 4.11.0, normal item cells (1M–256M) | Do they count like AE2 cells? | **Works.** They are AE2 `BasicStorageCell`s with more bytes (63 types, same as AE2), mounted as `appeng.me.cells.BasicCellInventory`. |
| MEGA Cells 4.11.0, bulk cell + compression card | How do compressed amounts appear through `IStorageService`? | **Partly.** No double counting in the listed stock, but listed amounts per key do not match what can be extracted per key, and keys look like ordinary stock. Needs an owner ruling (see *Options*). |
| AE2 Things 1.4.2-beta, DISKs | Does it load on AE2 19.2.18 / NeoForge 21.1.265, and how are DISKs counted? | **Works**, with one counting hazard: two stacks carrying the same DISK UUID are counted twice. |

## Pinned coordinates and hash verification

Both are `localRuntime` only (dev runs; the pack supplies them), with
`transitive = false`, mirroring AE2 and GuideME in `build.gradle`. Versions
are in `gradle.properties`.

| Mod | Coordinates | Repository | Verification |
| --- | --- | --- | --- |
| MEGA Cells 4.11.0 | `maven.modrinth:mega:RPG4EriK` (`megacells_modrinth_version`) | Modrinth Maven, existing `exclusiveContent` for group `maven.modrinth` | Resolved jar SHA-512 `a4d35f68…bfeca0` equals the pack pin `~/tla-packwiz/mods/mega.pw.toml` and Modrinth's primary file `megacells-4.11.0.jar`. |
| AE2 Things 1.4.2-beta | `curse.maven:ae2things-609977:5637783` (`ae2things_curseforge_project`, `ae2things_curseforge_file`) | CurseMaven `https://cursemaven.com`, new `exclusiveContent` for group `curse.maven` | Resolved jar SHA-1 `593ad26ae4143f529a63cc63b96c6430ebba67c3` equals the pack pin `~/tla-packwiz/mods/ae2-things-forge.pw.toml`. |

Notes:

- The Modrinth Maven artifact name is the project **slug** `mega`, not
  `megacells`: `maven.modrinth:megacells:RPG4EriK` returns
  `{"error":"not_found"}` (Modrinth project `jjuIRIVr` has slug `mega`).
- Hashes were checked by hand against the Gradle cache. Gradle itself does not
  verify them (the project has no `gradle/verification-metadata.xml`). The
  pins are by immutable version/file id, which is what the other dependencies
  rely on too.
- CurseMaven is a third-party proxy for CurseForge files. If it goes away, the
  coordinates stop resolving (dev runs only; the shipped jar does not depend
  on it).

## Sources relied on

- MEGA Cells 4.11.0: the sources jar published with the same Modrinth version
  (`megacells-4.11.0-sources.jar`, SHA-512 `7bb4afc9…6f221a3d`); GitHub
  `62832/MEGACells` tag `4.11.0` (`f46ab8f9`). Files:
  `gripe/_90/megacells/item/cell/BulkCellInventory.java` (insert, extract,
  `getAvailableStacks`), `misc/CompressionChain.java` (`initStacks`,
  `unitFactor`, `STACK_LIMIT`), `misc/CompressionService.java` (chains built
  from recipes on `ServerStartedEvent` and `/reload`),
  `misc/DecompressionService.java` (decompression module as a crafting
  provider), `definition/MEGAItems.java`, `definition/MEGAComponents.java`
  (ids).
- AE2 Things 1.4.2-beta: GitHub `Technici4n/AE2Things-Forge` tag `1.4.2-beta`
  (its `gradle.properties` builds against AE2 `19.0.20-beta`, NeoForge
  `21.1.13`), cross-checked by decompiling the pinned jar with Vineflower
  1.10.1. Files under `io/github/projectet/ae2things/`:
  `storage/DISKCellInventory.java`, `storage/DISKCellHandler.java`,
  `util/StorageManager.java`, `util/DataStorage.java`, `item/DISKDrive.java`,
  `item/AETItems.java`, `AE2Things.java`.
- AE2 19.2.18 API: `appeng/api/ids/AEComponents.java`
  (`STORAGE_CELL_CONFIG_INV`, `UPGRADES`, used to configure cells without
  internal classes), `appeng/api/implementations/blockentities/IChestOrDrive.java`
  (`getOriginalCellInventory`).

## MEGA Cells

### Setup

`SpikeGrid` now also takes a list of prepared cell stacks, one per drive slot
(`SpikeGrid.build(helper, List<ItemStack>)`). Bulk cells are configured
through AE2 API data components only: the filter in
`ae2:storage_cell_config_inv`, the `megacells:compression_card` in
`ae2:upgrades`, and optionally `megacells:compression_cutoff` (looked up by
registry id). Contents are inserted through the cell inventory before the cell
goes into the drive. The iron chain built from vanilla recipes at server start
is `[1x iron_nugget, 9x iron_ingot, 9x iron_block]` (log:
`Gathered bulk compression chain: [1x → minecraft:iron_nugget, 9x → minecraft:iron_ingot, 9x → minecraft:iron_block]`;
32 chains in total in a vanilla + AE2 + MEGA world).

### How a bulk cell reports stock

A bulk cell stores one number, `unitCount`, in units of the chain's lowest
variant (nuggets). 9 iron blocks are 729 units. Source:
`BulkCellInventory`, `CompressionChain.initStacks`.

- **Listed stock** (`getCachedInventory()` and
  `getInventory().getAvailableStacks()`, which agree): each unit is listed
  **once**, split across the chain by the compression **cutoff**. Variants
  below the cutoff get only the remainder that does not fit into the next
  variant, and the cutoff variant gets the rest. The default cutoff is the
  highest variant. Zero-amount entries for the lower variants are present in
  the key counter.
- **Extractable stock** (simulate or real extract per key): any variant in the
  chain can be extracted up to the whole pool converted to that variant.
  Extraction subtracts `amount × factor` units. This is a real conversion of
  stored material, not autocrafting, and it happens without a crafting job.
- **Simulated extractions overlap**: simulating 81 ingots, 9 blocks, and 729
  nuggets each succeeds, but they are the same 729 units.
- Without a compression card only the stored item is listed and extractable.
- Listed amounts are clamped at 2^42 (`CompressionChain.STACK_LIMIT`). The
  `BigInteger` unit count itself is not limited.

| Gametest | Setup | Observed (log line `SPIKE …`) |
| --- | --- | --- |
| `bulkcompressiondefaultcutoff` | filter ingot, card, 9 blocks | `cached [minecraft:iron_block=9, minecraft:iron_ingot=0, minecraft:iron_nugget=0] live nugget/ingot/block=0/0/9 simulate(max) nugget/ingot/block=729/81/9`. Real extraction of 10 ingots returned 10 and of 5 nuggets returned 5. Afterwards `cached [minecraft:iron_block=7, minecraft:iron_ingot=7, minecraft:iron_nugget=4] … simulate(max) nugget/ingot/block=634/70/7`. |
| `bulkcompressioncutoffnuggets` | same, cutoff 0 | `cached [minecraft:iron_nugget=729] live nugget/ingot/block=729/0/0 simulate(max) nugget/ingot/block=729/81/9`. The same stock is listed entirely as nuggets. |
| `bulkwithoutcompressioncard` | filter block, no card, 9 blocks | `cached [minecraft:iron_block=9] … simulate(max) nugget/ingot/block=0/0/9`. |
| `bulkcompressionbesidenormalcell` | bulk (9 blocks, card) + AE2 1k cell with 5 ingots | `cached [minecraft:iron_block=9, minecraft:iron_ingot=5, …] simulate(max) nugget/ingot/block=729/86/9`. Extracting 6 ingots took them **from the bulk cell** (decompressing a block), not from the 5 real ingots: `cached [minecraft:iron_block=8, minecraft:iron_ingot=8, …] simulate(max) … 675/80/8`. (Drive slot 0 was the bulk cell; which storage AE2 drains first was not explored further.) |
| `megaitemcellcountslikeae2cell` | `megacells:item_storage_cell_1m`, 100 000 diamonds + 70 gold | `cell class=[appeng.me.cells.BasicCellInventory] sim=100000 real=99999 left=1 gold=70`. |

### Are compressed amounts distinguishable from real stock?

**Not through `IStorageService`.** The keys are plain `AEItemKey`s
(`minecraft:iron_ingot`), the same as an ingot in any other cell, and the
storage service merges all storages. They can be told apart only by
inspecting the storages behind the network:

- For cells in drives and ME chests, `IChestOrDrive.getOriginalCellInventory(slot)`
  (AE2 API) returns the cell inventory. In the tests its class was
  `gripe._90.megacells.item.cell.BulkCellInventory`. The cell item
  (`megacells:bulk_item_cell`) and its `ae2:upgrades` component (compression
  card) show the same thing without MEGA classes. `isCompressionEnabled()` and
  the chain are MEGA internals (`gripe._90.megacells.misc`), not API.
- **Not verified, from reading the code:** stock reached through a storage bus
  on another network's ME interface, or a portable cell, does not expose the
  backing cell, so its compression would not be detectable that way.
- **Not verified:** the MEGA decompression module
  (`megacells:decompression_module`) adds decompression patterns as a global
  crafting provider (`DecompressionService`). These show up as craftable
  through the crafting service, not as stock in `getCachedInventory()`. The
  archive should not count craftables anyway.

### Options for the owner's ruling (virtual / compressed counting)

The design says reconstruction must not count mutually exclusive
representations of the same material as available at the same time
(*Storage addon research and recommendation*; *Deferred decisions*:
"Addon virtual compression and infinite-source counting"). Bulk compression is
not infinite. The material really exists, but one stockpile can be read as
several keys. These are options only. The ruling belongs in the design
document.

1. **Count listed stock only** (`getCachedInventory()` per key, as for every
   other storage). Nothing is counted twice. But the result depends on the
   player's cutoff setting: 9 iron blocks do not satisfy "9 iron ingots"
   unless the player moves the cutoff, and real extraction may still take
   from the bulk cell. This is the simplest option and needs no
   MEGA-specific code.
2. **Count extractable stock per key** (simulate per key). This matches what
   one extraction can deliver, but it overcounts mutually exclusive variants
   (81 ingots and 9 blocks and 729 nuggets at once). It is only acceptable if
   the final check is a single joint extraction of the complete requirement
   set (the staged extraction from the `spike/grid-extraction` spike), so
   that overlapping requirements fail there. Readiness shown before then
   would be optimistic.
3. **Chain-aware pooling**: treat a compression chain as one material pool
   in base units and satisfy chain members jointly (for example 9 ingots +
   1 block needs 162 nugget units). This is accurate and independent of the
   cutoff, but it needs chain data: either MEGA internals
   (`CompressionService.getChain`, not API, can change between versions) or
   our own derivation from 3×3/2×2 reversible recipes. It only applies to
   cells we can see (drives), not to stock behind storage buses.
4. **Exclude compression from victory-valid counting**: detect bulk cells
   with a compression card in drives and credit only their stored item
   (`megacells:bulk_item` component) at its own amount, or not at all. This
   is conservative and easy to explain, but it penalises an in-pack storage
   feature. It cannot see bulk cells behind storage buses or interfaces
   (unverified), so it is a policy rather than a guarantee.
5. **Remove the compression card or bulk cells from the pack**, or disable
   them for archive networks. This is pack-track work and avoids the
   question entirely.

Whatever the ruling, the final consumption step should extract the whole
requirement set jointly (staged/compensated) rather than trusting
independent simulations. The bulk-cell tests show why: independent
simulations all succeed against the same 729 units.

## AE2 Things

### Load check

**It loads** with AE2 19.2.18 and NeoForge 21.1.265 even though it was built
against AE2 19.0.20-beta. Log: `Found valid mod file ae2things-609977-5637783.jar with {ae2things} mods - versions {1.4.2-beta}`.
Its one mixin (`CursedInternalSlotMixin`, creative middle-click clone of a
DISK) applied without error. No errors from `ae2things` in the gametest log.
It uses some AE2 internals (`appeng.util.ConfigInventory`,
`appeng.core.AELog`, `appeng.core.definitions.AEItems`,
`BasicCellHandler`), so a future AE2 update could break it. This was not
exercised beyond insert, extract, and listing. Only `disk_drive_1k` was
tested, and no client was run (models and GUI untested).

### How DISKs count

Source: `DISKCellInventory`, `StorageManager`, `DISKDrive`.

- **Types are unlimited**, and one item uses one byte. `disk_drive_<n>k`
  holds `n × 1000` items (`DISKDrive`: `kilobytes * 1000`).
- **Keys are exact `AEKey`s with their data kept.** A damaged sword, an
  enchanted book with two enchantments, a drinkable potion, and a splash
  potion of the same effect were each stored and listed as their own key, and
  the undamaged sword key reads 0.
- **Extraction behaves like an AE2 cell.** Simulate and real extract agreed.
  One extract call is capped at `Integer.MAX_VALUE`.
- **Contents are not on the item.** The item carries only
  `ae2things:disk_id` (UUID) and `ae2things:disk_item_count`. Contents live
  in the overworld `SavedData` `disk_manager` (`world/data/disk_manager.dat`),
  keyed by UUID, with each key serialised through `AEKey.toTagGeneric`.

| Gametest | Observed |
| --- | --- |
| `diskcountsexactkeys` | `cells=[io.github.projectet.ae2things.storage.DISKCellInventory] keys=5 book sim=1 undamaged-sword sim=0 diamond real=40 diskComponents={ae2things:disk_id=>…, ae2things:disk_item_count=>107}`. The cache then shows 60 diamonds. |
| `diskcontentssurvivesaveddataroundtrip` | After `level.getDataStorage().save()`, it polls `disk_manager.dat` (NeoForge writes saved data on an IO worker), reads it back into a **fresh** `StorageManager` (`readNbt`), and reads the DISK through it: all 5 keys with the same amounts and data (`reloaded keys=5`, file 556 bytes). A copy of the item stack sees the same contents. |
| `duplicateddiskuuiddoublecounts` | The same DISK and `disk.copy()` (same UUID) in one drive: `listed diamonds=200 simulate=200 real extract=200` from **100** stored diamonds. Each mounted stack keeps its own in-memory view of the shared entry. Afterwards both views and the saved entry are empty (`persisted=0`). |

**Save/reload: partly verified.** The on-disk round trip of the saved data
and the item-to-UUID lookup are proven. A real server restart (stop, start,
`StorageManager.getInstance` from disk, drive reload) was not run, because a
gametest cannot restart its own server.

### Counting hazard for the archive

Any way of duplicating a DISK item stack (an item-dupe bug, or a mod that
copies stacks with their components) makes the network list and deliver the
contents twice, which is real duplication. AE2 Things' own creative clone
(the mixin) assigns a new UUID and copies the contents, so it does not
trigger this. The archive could detect two mounted DISKs with the same
`ae2things:disk_id` and refuse to count either. Whether that is worth doing
is for the owner. It is not a counting-policy question like compression.

## Not verified

- Real server restart with DISKs (see above).
- MEGA decompression module and crafting service, and bulk cells or DISKs
  seen through storage buses, interfaces, or portable cells.
- Larger DISK tiers, the DISK partition/fuzzy/inverter cards, MEGA cells
  other than `item_storage_cell_1m` and `bulk_item_cell`.
- Client side (models, tooltips, GUIs) of either addon. Only
  `runGameTestServer` was run.
- AE2 MEGA Things (not in the pack).
