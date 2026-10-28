package io.github.nturner03.tlaarchive.spike.addons;

import static io.github.nturner03.tlaarchive.TlaArchive.LOGGER;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.awaitActive;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.awaitCached;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.cached;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.extract;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.key;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.simulate;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.spike.grid.SpikeGrid;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SPIKE (throwaway): AE2 Things 1.4.2-beta DISK cells. The mod is only on the dev runtime classpath, so its
 * StorageManager / DISKCellInventory are reached by reflection. Findings: docs/spikes/storage-addons.md.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Ae2ThingsSpikeTests {
    static final String STORAGE_MANAGER = "io.github.projectet.ae2things.util.StorageManager";
    static final String DISK_INVENTORY = "io.github.projectet.ae2things.storage.DISKCellInventory";

    private Ae2ThingsSpikeTests() {
    }

    record Samples(AEItemKey diamond, AEItemKey damagedSword, AEItemKey freshSword, AEItemKey book,
            AEItemKey swiftness, AEItemKey splashSwiftness) {
    }

    static Samples samples(GameTestHelper helper) {
        var enchants = helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var damaged = new ItemStack(Items.IRON_SWORD);
        damaged.set(DataComponents.DAMAGE, 100);
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        var stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(enchants.getHolderOrThrow(Enchantments.SHARPNESS), 5);
        stored.set(enchants.getHolderOrThrow(Enchantments.UNBREAKING), 3);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        return new Samples(AEItemKey.of(Items.DIAMOND), key(damaged), AEItemKey.of(Items.IRON_SWORD), key(book),
                key(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)),
                key(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.SWIFTNESS)));
    }

    /** A 1k DISK holding 100 diamonds, a damaged sword, a 2-enchantment book, a drinkable and a splash potion. */
    static ItemStack filledDisk(Samples s) {
        var disk = new ItemStack(SpikeGrid.item("ae2things:disk_drive_1k"));
        var inv = AddonSpikes.cellInventory(disk);
        AddonSpikes.insertOrFail(inv, s.diamond(), 100);
        AddonSpikes.insertOrFail(inv, s.damagedSword(), 1);
        AddonSpikes.insertOrFail(inv, s.book(), 1);
        AddonSpikes.insertOrFail(inv, s.swiftness(), 2);
        AddonSpikes.insertOrFail(inv, s.splashSwiftness(), 3);
        return disk;
    }

    static Object storageManager(GameTestHelper helper) throws ReflectiveOperationException {
        return Class.forName(STORAGE_MANAGER).getMethod("getInstance", net.minecraft.server.MinecraftServer.class)
                .invoke(null, helper.getLevel().getServer());
    }

    /** Reads a DISK's contents through a given StorageManager (not the mod's static one). */
    static KeyCounter readDisk(ItemStack disk, Object manager) throws ReflectiveOperationException {
        var create = Class.forName(DISK_INVENTORY).getMethod("createInventory", ItemStack.class, ISaveProvider.class,
                Class.forName(STORAGE_MANAGER));
        var cell = (StorageCell) create.invoke(null, disk, null, manager);
        var out = new KeyCounter();
        cell.getAvailableStacks(out);
        return out;
    }

    static void assertSamples(GameTestHelper helper, KeyCounter c, Samples s, String where) {
        helper.assertTrue(c.get(s.diamond()) == 100, where + ": diamonds " + c.get(s.diamond()));
        helper.assertTrue(c.get(s.damagedSword()) == 1, where + ": damaged sword");
        helper.assertTrue(c.get(s.freshSword()) == 0, where + ": no undamaged sword");
        helper.assertTrue(c.get(s.book()) == 1, where + ": book");
        helper.assertTrue(c.get(s.swiftness()) == 2 && c.get(s.splashSwiftness()) == 3, where + ": potions");
    }

    /** Loads with AE2 19.2.18; the network counts DISK contents per exact key, item data included. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void diskCountsExactKeys(GameTestHelper helper) {
        var s = samples(helper);
        var disk = filledDisk(s);
        var g = SpikeGrid.build(helper, List.of(disk));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, s.diamond(), 100))
                .thenExecute(() -> {
                    var c = g.grid().getStorageService().getCachedInventory();
                    assertSamples(helper, c, s, "network");
                    long sim = simulate(g, s.book(), 1);
                    long simFresh = simulate(g, s.freshSword(), 1);
                    long real = extract(g, s.diamond(), 40);
                    helper.assertTrue(sim == 1 && simFresh == 0 && real == 40, "extract");
                    LOGGER.info("SPIKE ae2things disk: cells={} keys={} book sim={} undamaged-sword sim={} "
                                    + "diamond real={} diskComponents={}",
                            AddonSpikes.mountedCellClasses(g), c.size(), sim, simFresh, real,
                            disk.getComponentsPatch());
                })
                .thenWaitUntil(() -> awaitCached(helper, g, s.diamond(), 60))
                .thenSucceed();
    }

    /**
     * Contents live in the overworld SavedData "disk_manager" (keyed by the DISK's UUID), not on the item. Saves
     * the level's data storage (NeoForge writes it on an IO worker, so poll), reads data/disk_manager.dat back with
     * a fresh StorageManager, and reads the DISK through it.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 400)
    public static void diskContentsSurviveSavedDataRoundTrip(GameTestHelper helper) {
        var s = samples(helper);
        var disk = filledDisk(s);
        UUID id = disk.get(AddonSpikes.<UUID>component("ae2things:disk_id"));
        helper.assertTrue(id != null, "disk has a UUID");
        var level = helper.getLevel();
        var file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("disk_manager.dat");
        KeyCounter[] fromFile = new KeyCounter[1];
        helper.startSequence()
                .thenExecute(() -> level.getDataStorage().save())
                .thenWaitUntil(() -> {
                    KeyCounter read;
                    try {
                        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                        var reloaded = Class.forName(STORAGE_MANAGER)
                                .getMethod("readNbt", CompoundTag.class, HolderLookup.Provider.class)
                                .invoke(null, root.getCompound("data"), level.registryAccess());
                        // A freshly read manager only gets its registries reference from getInstance(); set it.
                        var regField = Class.forName(STORAGE_MANAGER).getDeclaredField("registries");
                        regField.setAccessible(true);
                        regField.set(reloaded, new WeakReference<HolderLookup.Provider>(level.registryAccess()));
                        read = readDisk(disk, reloaded);
                    } catch (Exception e) {
                        read = null; // not written yet, or mid-write
                    }
                    helper.assertTrue(read != null && read.get(s.diamond()) == 100, "disk not in " + file + " yet");
                    fromFile[0] = read;
                })
                .thenExecute(() -> {
                    assertSamples(helper, fromFile[0], s, "reloaded from " + file.getFileName());
                    try {
                        // The item carries only the UUID and a byte count; a copy of the stack sees the same contents.
                        var viaCopy = readDisk(disk.copy(), storageManager(helper));
                        assertSamples(helper, viaCopy, s, "copied stack");
                        LOGGER.info("SPIKE ae2things save/reload: uuid={} file={} bytes={} reloaded keys={} "
                                        + "itemComponents={}",
                                id, file, Files.size(file), fromFile[0].size(), disk.getComponentsPatch());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .thenSucceed();
    }

    /**
     * Two stacks with the same DISK UUID in one drive (e.g. a copied item): each mounts its own view of the shared
     * SavedData entry, so the network double-counts.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void duplicatedDiskUuidDoubleCounts(GameTestHelper helper) {
        var s = samples(helper);
        var disk = filledDisk(s);
        var g = SpikeGrid.build(helper, List.of(disk, disk.copy()));
        long[] extracted = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> helper.assertTrue(cached(g, s.diamond()) > 0, "diamonds visible"))
                .thenIdle(5)
                .thenExecute(() -> {
                    long listed = cached(g, s.diamond());
                    long sim = simulate(g, s.diamond(), Long.MAX_VALUE);
                    extracted[0] = extract(g, s.diamond(), Long.MAX_VALUE);
                    LOGGER.info("SPIKE ae2things same-UUID twice: listed diamonds={} simulate={} real extract={}",
                            listed, sim, extracted[0]);
                    // 100 real diamonds: the second mounted view of the same UUID lists and yields another 100.
                    helper.assertTrue(listed == 200 && extracted[0] == 200, "double count observed");
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    try {
                        long persisted = readDisk(disk, storageManager(helper)).get(s.diamond());
                        LOGGER.info("SPIKE ae2things same-UUID twice after extract: cached={} persisted={}",
                                cached(g, s.diamond()), persisted);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                })
                .thenSucceed();
    }
}
