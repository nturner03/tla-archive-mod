package io.github.nturner03.tlaarchive.spike.addons;

import static io.github.nturner03.tlaarchive.TlaArchive.LOGGER;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.awaitActive;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.awaitCached;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.cached;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.cachedMatching;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.extract;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.live;
import static io.github.nturner03.tlaarchive.spike.addons.AddonSpikes.simulate;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.spike.grid.SpikeGrid;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * SPIKE (throwaway): how MEGA Cells 4.11.0 cells report keys and amounts through IStorageService. Cells are set up
 * with AE2 API data components only (filter = ae2:storage_cell_config_inv, upgrades = ae2:upgrades) plus MEGA's
 * megacells:compression_cutoff component, looked up by registry id. Findings: docs/spikes/storage-addons.md.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MegaCellsSpikeTests {
    static final AEItemKey NUGGET = AEItemKey.of(Items.IRON_NUGGET);
    static final AEItemKey INGOT = AEItemKey.of(Items.IRON_INGOT);
    static final AEItemKey BLOCK = AEItemKey.of(Items.IRON_BLOCK);
    static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

    private MegaCellsSpikeTests() {
    }

    /** A bulk cell filtered to {@code filter}, optionally with a compression card and an explicit cutoff index. */
    static ItemStack bulkCell(AEItemKey filter, boolean compressionCard, @Nullable Integer cutoff) {
        var cell = new ItemStack(SpikeGrid.item("megacells:bulk_item_cell"));
        cell.set(AEComponents.STORAGE_CELL_CONFIG_INV, List.of(new GenericStack(filter, 1)));
        if (compressionCard) {
            cell.set(AEComponents.UPGRADES,
                    ItemContainerContents.fromItems(List.of(new ItemStack(SpikeGrid.item("megacells:compression_card")))));
        }
        if (cutoff != null) {
            cell.set(AddonSpikes.<Integer>component("megacells:compression_cutoff"), cutoff);
        }
        return cell;
    }

    static ItemStack filledBulkCell(AEItemKey filter, boolean compressionCard, @Nullable Integer cutoff, AEItemKey what,
            long amount) {
        var cell = bulkCell(filter, compressionCard, cutoff);
        AddonSpikes.insertOrFail(AddonSpikes.cellInventory(cell), what, amount);
        return cell;
    }

    static String ironView(SpikeGrid g) {
        return "cached " + cachedMatching(g, "iron_") + " live nugget/ingot/block=" + live(g, NUGGET) + "/"
                + live(g, INGOT) + "/" + live(g, BLOCK) + " simulate(max) nugget/ingot/block="
                + simulate(g, NUGGET, Long.MAX_VALUE) + "/" + simulate(g, INGOT, Long.MAX_VALUE) + "/"
                + simulate(g, BLOCK, Long.MAX_VALUE);
    }

    /** MEGA's normal item cells are AE2 BasicStorageCells with more bytes: counted and extracted like AE2 cells. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void megaItemCellCountsLikeAe2Cell(GameTestHelper helper) {
        var cell = new ItemStack(SpikeGrid.item("megacells:item_storage_cell_1m"));
        var inv = AddonSpikes.cellInventory(cell);
        AddonSpikes.insertOrFail(inv, DIAMOND, 100_000);
        AddonSpikes.insertOrFail(inv, GOLD, 70);
        var g = SpikeGrid.build(helper, List.of(cell));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, DIAMOND, 100_000))
                .thenExecute(() -> {
                    helper.assertTrue(cached(g, GOLD) == 70, "gold");
                    helper.assertTrue(live(g, DIAMOND) == 100_000, "live diamonds");
                    long sim = simulate(g, DIAMOND, 100_000);
                    long real = extract(g, DIAMOND, 99_999);
                    helper.assertTrue(sim == 100_000 && real == 99_999, "sim " + sim + " real " + real);
                    helper.assertTrue(live(g, DIAMOND) == 1, "1 diamond left");
                    LOGGER.info("SPIKE mega 1m cell: cell class={} sim={} real={} left={} gold={}",
                            AddonSpikes.mountedCellClasses(g), sim, real, live(g, DIAMOND), cached(g, GOLD));
                })
                .thenSucceed();
    }

    /**
     * Bulk cell, compression card, default cutoff, 9 iron blocks: the network lists the whole amount once, on the
     * cutoff variant (blocks), but every variant is extractable and extraction converts units on the fly.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void bulkCompressionDefaultCutoff(GameTestHelper helper) {
        var g = SpikeGrid.build(helper, List.of(filledBulkCell(INGOT, true, null, BLOCK, 9)));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, BLOCK, 9))
                .thenExecute(() -> {
                    LOGGER.info("SPIKE bulk compress 9 blocks: {} cells={}", ironView(g),
                            AddonSpikes.mountedCellClasses(g));
                    helper.assertTrue(cached(g, INGOT) == 0 && cached(g, NUGGET) == 0, "only blocks listed");
                    helper.assertTrue(simulate(g, INGOT, Long.MAX_VALUE) == 81, "81 ingots extractable");
                    helper.assertTrue(simulate(g, NUGGET, Long.MAX_VALUE) == 729, "729 nuggets extractable");
                    long got = extract(g, INGOT, 10);
                    helper.assertTrue(got == 10, "real ingot extract " + got);
                    long nug = extract(g, NUGGET, 5);
                    helper.assertTrue(nug == 5, "real nugget extract " + nug);
                })
                // 729 - 90 - 5 = 634 nugget units = 7 blocks, 7 ingots, 4 nuggets
                .thenWaitUntil(() -> awaitCached(helper, g, BLOCK, 7))
                .thenExecute(() -> {
                    LOGGER.info("SPIKE bulk compress after extracting 10 ingots + 5 nuggets: {}", ironView(g));
                    helper.assertTrue(cached(g, INGOT) == 7 && cached(g, NUGGET) == 4,
                            "remainder split " + cachedMatching(g, "iron_"));
                })
                .thenSucceed();
    }

    /** Cutoff 0 (lowest variant): the same stock is listed entirely as nuggets. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void bulkCompressionCutoffNuggets(GameTestHelper helper) {
        var g = SpikeGrid.build(helper, List.of(filledBulkCell(INGOT, true, 0, BLOCK, 9)));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, NUGGET, 729))
                .thenExecute(() -> {
                    LOGGER.info("SPIKE bulk compress cutoff=0: {}", ironView(g));
                    helper.assertTrue(cached(g, INGOT) == 0 && cached(g, BLOCK) == 0, "only nuggets listed");
                    helper.assertTrue(simulate(g, BLOCK, Long.MAX_VALUE) == 9, "blocks still extractable");
                })
                .thenSucceed();
    }

    /** No compression card: only the stored item is listed and only it can be extracted. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void bulkWithoutCompressionCard(GameTestHelper helper) {
        var g = SpikeGrid.build(helper, List.of(filledBulkCell(BLOCK, false, null, BLOCK, 9)));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, BLOCK, 9))
                .thenExecute(() -> {
                    LOGGER.info("SPIKE bulk no card: {}", ironView(g));
                    helper.assertTrue(simulate(g, INGOT, Long.MAX_VALUE) == 0, "no ingots without card");
                })
                .thenSucceed();
    }

    /**
     * Bulk cell (9 blocks, compressed) next to an AE2 1k cell with 5 real ingots: the network lists 5 ingots, but
     * 86 can be extracted, the extra 81 coming out of the blocks.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void bulkCompressionBesideNormalCell(GameTestHelper helper) {
        var normal = new ItemStack(SpikeGrid.item("ae2:item_storage_cell_1k"));
        AddonSpikes.insertOrFail(AddonSpikes.cellInventory(normal), INGOT, 5);
        var g = SpikeGrid.build(helper, List.of(filledBulkCell(INGOT, true, null, BLOCK, 9), normal));
        helper.startSequence()
                .thenWaitUntil(() -> awaitActive(helper, g))
                .thenWaitUntil(() -> awaitCached(helper, g, BLOCK, 9))
                .thenWaitUntil(() -> awaitCached(helper, g, INGOT, 5))
                .thenExecute(() -> {
                    LOGGER.info("SPIKE bulk + 1k cell: {}", ironView(g));
                    long sim = simulate(g, INGOT, Long.MAX_VALUE);
                    helper.assertTrue(sim == 86, "ingots extractable " + sim);
                    long got = extract(g, INGOT, 6);
                    helper.assertTrue(got == 6, "extract 6 ingots");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        cached(g, INGOT) + cached(g, BLOCK) * 9 == 80, "settled " + cachedMatching(g, "iron_")))
                .thenExecute(() -> LOGGER.info("SPIKE bulk + 1k cell after extracting 6 ingots: {}", ironView(g)))
                .thenSucceed();
    }
}
