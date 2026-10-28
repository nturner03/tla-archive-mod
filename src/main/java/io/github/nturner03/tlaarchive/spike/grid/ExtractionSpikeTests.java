package io.github.nturner03.tlaarchive.spike.grid;

import static io.github.nturner03.tlaarchive.TlaArchive.LOGGER;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.DIAMOND;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.GOLD;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.IRON;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.SRC;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.awaitActive;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.awaitCached;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.live;
import static io.github.nturner03.tlaarchive.spike.grid.GridStockSpikeTests.standardGrid;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.spike.grid.StagedExtraction.State;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SPIKE (throwaway): multi-key extraction. AE2 has no atomic multi-key extraction, so these tests exercise
 * {@link StagedExtraction} (journal + staging buffer + compensation). Findings:
 * docs/spikes/grid-stock-and-extraction.md.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExtractionSpikeTests {
    static final AEItemKey EMERALD = AEItemKey.of(Items.EMERALD);

    private ExtractionSpikeTests() {
    }

    /** 120 diamonds (cell + chest), 5 iron (chest), 20 gold (cell). */
    static Map<AEKey, Long> plan() {
        var plan = new LinkedHashMap<AEKey, Long>();
        plan.put(DIAMOND, 120L);
        plan.put(IRON, 5L);
        plan.put(GOLD, 20L);
        return plan;
    }

    static void assertTotals(GameTestHelper helper, SpikeGrid g, long diamonds, long iron, long gold) {
        long d = live(g, DIAMOND), i = live(g, IRON), au = live(g, GOLD);
        helper.assertTrue(d == diamonds && i == iron && au == gold,
                "totals d/i/g = " + d + "/" + i + "/" + au + ", want " + diamonds + "/" + iron + "/" + gold);
    }

    static StagedExtraction reload(GameTestHelper helper, StagedExtraction op) {
        var registries = helper.getLevel().registryAccess();
        // Round-trip through NBT and drop the in-memory object, as a server restart would.
        return StagedExtraction.load(op.save(registries).copy(), registries);
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void commitConsumesExactlyThePlan(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var op = new StagedExtraction(UUID.randomUUID(), plan());
                    helper.assertTrue(op.validate(inv, SRC) == null, "plan available");
                    op.step(inv, SRC, Integer.MAX_VALUE);
                    helper.assertTrue(op.state() == State.STAGED, "staged, got " + op.state());
                    op.commit();
                    assertTotals(helper, g, 44, 5, 30);
                })
                .thenSucceed();
    }

    /**
     * Stock disappears between validation and extraction (another player, another controller). The short key causes
     * a rollback that returns everything already staged. A second operation cannot spend the same stock.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void shortStockRollsBackEverything(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var op = new StagedExtraction(UUID.randomUUID(), plan());
                    helper.assertTrue(op.validate(inv, SRC) == null, "plan available");
                    g.chest().setItem(1, new ItemStack(Items.IRON_INGOT, 2)); // someone takes 8 iron
                    op.step(inv, SRC, Integer.MAX_VALUE);
                    helper.assertTrue(op.state() == State.ROLLING_BACK, "short -> rolling back, got " + op.state());
                    op.rollback(inv, SRC);
                    helper.assertTrue(op.state() == State.ROLLED_BACK, "rolled back, got " + op.state());
                    assertTotals(helper, g, 164, 2, 50);

                    // Two controllers on one network: the first stages, the second's validation then fails.
                    inv.insert(IRON, 8, Actionable.MODULATE, SRC);
                    var first = new StagedExtraction(UUID.randomUUID(), plan());
                    var second = new StagedExtraction(UUID.randomUUID(), plan());
                    first.step(inv, SRC, Integer.MAX_VALUE);
                    helper.assertTrue(first.state() == State.STAGED, "first staged");
                    helper.assertTrue(second.validate(inv, SRC) == DIAMOND, "second sees the stock is gone");
                    first.rollback(inv, SRC);
                    assertTotals(helper, g, 164, 10, 50);
                })
                .thenSucceed();
    }

    /** One key per tick; the "server" restarts after the first key. Recovery puts the staged key back. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void interruptedExtractionRecoversAfterReload(GameTestHelper helper) {
        var g = standardGrid(helper);
        var holder = new StagedExtraction[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    holder[0] = new StagedExtraction(UUID.randomUUID(), plan());
                    holder[0].step(inv, SRC, 1);
                    helper.assertTrue(holder[0].state() == State.EXTRACTING, "mid-extraction");
                    assertTotals(helper, g, 44, 10, 50);
                    holder[0] = reload(helper, holder[0]);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var op = holder[0];
                    helper.assertTrue(op.staged().get(DIAMOND) == 120, "journal kept the staged diamonds");
                    op.recover(inv, SRC, false);
                    helper.assertTrue(op.state() == State.ROLLED_BACK, "recovered, got " + op.state());
                    assertTotals(helper, g, 164, 10, 50);
                    op.recover(inv, SRC, false); // recovery is idempotent
                    assertTotals(helper, g, 164, 10, 50);
                })
                .thenSucceed();
    }

    /** Interrupted final consumption: everything staged, restart before commit. Both rulings are safe. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void interruptedFinalConsumptionRollsEitherWay(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var forward = new StagedExtraction(UUID.randomUUID(), plan());
                    forward.step(inv, SRC, Integer.MAX_VALUE);
                    forward = reload(helper, forward);
                    forward.recover(inv, SRC, true);
                    helper.assertTrue(forward.state() == State.COMMITTED, "rolled forward");
                    assertTotals(helper, g, 44, 5, 30); // consumed once, nothing duplicated

                    inv.insert(DIAMOND, 120, Actionable.MODULATE, SRC);
                    inv.insert(IRON, 5, Actionable.MODULATE, SRC);
                    inv.insert(GOLD, 20, Actionable.MODULATE, SRC);
                    assertTotals(helper, g, 164, 10, 50);
                    var back = new StagedExtraction(UUID.randomUUID(), plan());
                    back.step(inv, SRC, Integer.MAX_VALUE);
                    back = reload(helper, back);
                    back.recover(inv, SRC, false);
                    helper.assertTrue(back.state() == State.ROLLED_BACK, "rolled back");
                    assertTotals(helper, g, 164, 10, 50);
                })
                .thenSucceed();
    }

    /** Compensation when the stock no longer fits back: the remainder stays in the journal instead of being voided. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void rollbackKeepsWhatDoesNotFit(GameTestHelper helper) {
        var g = standardGrid(helper);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 164))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var op = new StagedExtraction(UUID.randomUUID(), plan());
                    op.step(inv, SRC, Integer.MAX_VALUE);
                    // Fill all free space: the chest with dirt, the cell with distinct item types.
                    for (int slot = 0; slot < g.chest().getContainerSize(); slot++) {
                        if (g.chest().getItem(slot).isEmpty()) {
                            g.chest().setItem(slot, new ItemStack(Items.DIRT, 64));
                        }
                    }
                    long filler = 0;
                    for (var item : new Item[] {Items.STONE, Items.COBBLESTONE,
                            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.SAND, Items.GRAVEL, Items.OAK_LOG}) {
                        filler += inv.insert(AEItemKey.of(item), 1_000_000, Actionable.MODULATE, SRC);
                    }
                    op.rollback(inv, SRC);
                    long kept = op.staged().get(DIAMOND) + op.staged().get(IRON) + op.staged().get(GOLD);
                    LOGGER.info("SPIKE rollback when full: filler={} state={} kept in journal={} live d/i/g={}/{}/{}",
                            filler, op.state(), kept, live(g, DIAMOND), live(g, IRON), live(g, GOLD));
                    helper.assertTrue(op.state() == State.ROLLING_BACK, "still rolling back");
                    helper.assertTrue(kept > 0, "remainder kept");
                    helper.assertTrue(live(g, DIAMOND) + op.staged().get(DIAMOND) == 164, "no diamond lost");
                    helper.assertTrue(live(g, IRON) + op.staged().get(IRON) == 10, "no iron lost");
                    helper.assertTrue(live(g, GOLD) + op.staged().get(GOLD) == 50, "no gold lost");
                })
                .thenSucceed();
    }

    /** A storage whose simulation is optimistic: validation passes, the real extraction comes up short. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void optimisticSimulationIsCaughtByModulateResult(GameTestHelper helper) {
        var g = standardGrid(helper);
        var flaky = new FlakyStorage();
        flaky.seed(EMERALD, 40);
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenExecute(() -> g.attachController(flaky))
                .thenWaitUntil(() -> awaitCached(helper, g, EMERALD, 40))
                .thenExecute(() -> {
                    var inv = g.grid().getStorageService().getInventory();
                    var op = new StagedExtraction(UUID.randomUUID(), Map.of(EMERALD, 30L));
                    helper.assertTrue(op.validate(inv, SRC) == null, "simulation promises 30");
                    op.step(inv, SRC, Integer.MAX_VALUE);
                    LOGGER.info("SPIKE optimistic simulate: state={} staged={}", op.state(), op.staged().get(EMERALD));
                    helper.assertTrue(op.state() == State.ROLLING_BACK, "short real extraction detected");
                    op.rollback(inv, SRC);
                    long inFlaky = flaky.get(EMERALD);
                    long onNetwork = live(g, EMERALD);
                    LOGGER.info("SPIKE optimistic simulate: after rollback flaky={} network={}", inFlaky, onNetwork);
                    helper.assertTrue(onNetwork == 40, "all emeralds back on the network");
                    g.destroy();
                })
                .thenSucceed();
    }
}
