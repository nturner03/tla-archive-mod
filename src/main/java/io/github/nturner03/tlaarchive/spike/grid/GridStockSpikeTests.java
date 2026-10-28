package io.github.nturner03.tlaarchive.spike.grid;

import static io.github.nturner03.tlaarchive.TlaArchive.LOGGER;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import io.github.nturner03.tlaarchive.TlaArchive;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SPIKE (throwaway): reading grid stock through IStorageService, and whether simulated extraction matches real
 * extraction across a drive cell and a storage bus on a chest. Findings: docs/spikes/grid-stock-and-extraction.md.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridStockSpikeTests {
    static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    static final IActionSource SRC = IActionSource.empty();

    private GridStockSpikeTests() {
    }

    /** Cell holds 100 diamonds + 50 gold; chest holds 64 diamonds + 10 iron. */
    static SpikeGrid standardGrid(GameTestHelper helper) {
        var g = SpikeGrid.build(helper, Map.of(Items.DIAMOND, 100L, Items.GOLD_INGOT, 50L));
        g.chest().setItem(0, new ItemStack(Items.DIAMOND, 64));
        g.chest().setItem(1, new ItemStack(Items.IRON_INGOT, 10));
        return g;
    }

    static void awaitActive(GameTestHelper helper, SpikeGrid g) {
        helper.assertTrue(g.isActive(), "grid not active yet: " + g.describe());
    }

    static void awaitCached(GameTestHelper helper, SpikeGrid g, AEItemKey key, long expected) {
        long cached = g.grid().getStorageService().getCachedInventory().get(key);
        helper.assertTrue(cached == expected, "cached " + key + " = " + cached + ", want " + expected);
    }

    static long live(SpikeGrid g, AEItemKey key) {
        return g.grid().getStorageService().getInventory().getAvailableStacks().get(key);
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void stockCountsCellAndStorageBus(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var cached = g.grid().getStorageService().getCachedInventory();
                    helper.assertTrue(cached.get(IRON) == 10, "iron via bus");
                    helper.assertTrue(cached.get(GOLD) == 50, "gold in cell");
                    helper.assertTrue(live(g, DIAMOND) == 164, "live diamonds");
                    LOGGER.info("SPIKE stock: cached diamonds={} iron={} gold={}, live diamonds={}",
                            cached.get(DIAMOND), cached.get(IRON), cached.get(GOLD), live(g, DIAMOND));
                })
                .thenSucceed();
    }

    /** A direct chest edit is visible to the live view immediately but to the cache only after the bus re-polls. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void cacheLagsBehindExternalChange(GameTestHelper helper) {
        var g = standardGrid(helper);
        var changedAt = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    g.chest().setItem(0, ItemStack.EMPTY); // a hopper or player takes the chest's diamonds
                    changedAt[0] = helper.getTick();
                    long cached = g.grid().getStorageService().getCachedInventory().get(DIAMOND);
                    long simulated = g.grid().getStorageService().getInventory()
                            .extract(DIAMOND, Long.MAX_VALUE, Actionable.SIMULATE, SRC);
                    LOGGER.info("SPIKE cache lag: same tick cached={} live={} simulate={}", cached,
                            live(g, DIAMOND), simulated);
                    helper.assertTrue(cached == 164, "cache still old in the same tick");
                    helper.assertTrue(simulated == 100, "simulate sees the real chest");
                })
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 100))
                .thenExecute(() -> LOGGER.info("SPIKE cache lag: cache caught up after {} ticks",
                        helper.getTick() - changedAt[0]))
                .thenSucceed();
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void simulateMatchesModulate(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    // 150 diamonds must come from both the cell and the chest.
                    long sim = inv.extract(DIAMOND, 150, Actionable.SIMULATE, SRC);
                    long simAll = inv.extract(DIAMOND, 1000, Actionable.SIMULATE, SRC);
                    long real = inv.extract(DIAMOND, 150, Actionable.MODULATE, SRC);
                    long chestLeft = g.chest().getItem(0).getCount();
                    LOGGER.info("SPIKE simulate/modulate: sim150={} sim1000={} real150={} left live={} chest={}",
                            sim, simAll, real, live(g, DIAMOND), chestLeft);
                    helper.assertTrue(sim == 150 && real == 150, "simulate == modulate");
                    helper.assertTrue(simAll == 164, "simulate caps at available");
                    helper.assertTrue(live(g, DIAMOND) == 14, "14 left");
                    long realIron = inv.extract(IRON, 20, Actionable.MODULATE, SRC);
                    helper.assertTrue(realIron == 10, "short extraction returns what exists");
                    helper.assertTrue(g.chest().getItem(1).isEmpty(), "iron gone from chest");
                })
                .thenSucceed();
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void removingBusDropsItsContribution(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> helper.setBlock(SpikeGrid.CHEST, Blocks.AIR))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 100))
                .thenExecute(() -> {
                    helper.assertTrue(g.grid().getStorageService().getCachedInventory().get(IRON) == 0, "iron gone");
                    LOGGER.info("SPIKE disconnect: chest removed, cached diamonds=100 iron=0");
                })
                .thenSucceed();
    }

    /** What the storage service reports once the network loses power. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void powerLoss(GameTestHelper helper) {
        var g = standardGrid(helper);
        var grid = new Object[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    grid[0] = g.grid();
                    helper.setBlock(SpikeGrid.ENERGY, Blocks.AIR);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    var node = g.driveNode();
                    var storage = node.getGrid().getStorageService();
                    long cached = storage.getCachedInventory().get(DIAMOND);
                    long simulated = storage.getInventory().extract(DIAMOND, 1000, Actionable.SIMULATE, SRC);
                    LOGGER.info("SPIKE power loss: sameGrid={} active={} powered={} cached={} live={} simulate={}"
                            + " storedPower={}",
                            node.getGrid() == grid[0], node.isActive(), node.isPowered(), cached, live(g, DIAMOND),
                            simulated, node.getGrid().getEnergyService().getStoredPower());
                })
                .thenSucceed();
    }
}
