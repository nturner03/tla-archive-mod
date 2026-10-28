package io.github.nturner03.tlaarchive.spike.addons;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import io.github.nturner03.tlaarchive.spike.grid.SpikeGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** SPIKE (throwaway): shared helpers for the storage-addon spikes. Findings: docs/spikes/storage-addons.md. */
final class AddonSpikes {
    static final IActionSource SRC = IActionSource.empty();

    private AddonSpikes() {
    }

    static void awaitActive(GameTestHelper helper, SpikeGrid g) {
        helper.assertTrue(g.isActive(), "grid not active yet: " + g.describe());
    }

    static long cached(SpikeGrid g, AEKey key) {
        return g.grid().getStorageService().getCachedInventory().get(key);
    }

    static long live(SpikeGrid g, AEKey key) {
        return g.grid().getStorageService().getInventory().getAvailableStacks().get(key);
    }

    static long simulate(SpikeGrid g, AEKey key, long amount) {
        return g.grid().getStorageService().getInventory().extract(key, amount, Actionable.SIMULATE, SRC);
    }

    static long extract(SpikeGrid g, AEKey key, long amount) {
        return g.grid().getStorageService().getInventory().extract(key, amount, Actionable.MODULATE, SRC);
    }

    static void awaitCached(GameTestHelper helper, SpikeGrid g, AEKey key, long expected) {
        long c = cached(g, key);
        helper.assertTrue(c == expected, "cached " + key + " = " + c + ", want " + expected);
    }

    /** Every cached entry whose item id contains {@code idPart}, including zero-amount entries. */
    static String cachedMatching(SpikeGrid g, String idPart) {
        return entries(g.grid().getStorageService().getCachedInventory(), idPart);
    }

    static String entries(KeyCounter counter, String idPart) {
        var out = new ArrayList<String>();
        for (var e : counter) {
            if (e.getKey().getId().toString().contains(idPart)) {
                out.add(e.getKey().getId() + "=" + e.getLongValue());
            }
        }
        return out.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    /** Class names of the cell inventories mounted in the drives of the grid (what a counter could inspect). */
    static List<String> mountedCellClasses(SpikeGrid g) {
        var out = new ArrayList<String>();
        for (var node : g.grid().getNodes()) {
            if (node.getOwner() instanceof IChestOrDrive drive) {
                for (int i = 0; i < drive.getCellCount(); i++) {
                    StorageCell cell = drive.getOriginalCellInventory(i);
                    if (cell != null) {
                        out.add(cell.getClass().getName());
                    }
                }
            }
        }
        return out;
    }

    static StorageCell cellInventory(ItemStack cell) {
        var inv = StorageCells.getCellInventory(cell, null);
        if (inv == null) {
            throw new IllegalStateException("no cell inventory for " + cell);
        }
        return inv;
    }

    static void insertOrFail(StorageCell inv, AEKey key, long amount) {
        long got = inv.insert(key, amount, Actionable.MODULATE, SRC);
        if (got != amount) {
            throw new IllegalStateException("cell took " + got + " of " + amount + " " + key);
        }
    }

    static AEItemKey key(ItemStack stack) {
        return AEItemKey.of(stack);
    }

    @SuppressWarnings("unchecked")
    static <T> DataComponentType<T> component(String id) {
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse(id));
        if (type == null) {
            throw new IllegalStateException("missing data component " + id);
        }
        return (DataComponentType<T>) type;
    }
}
