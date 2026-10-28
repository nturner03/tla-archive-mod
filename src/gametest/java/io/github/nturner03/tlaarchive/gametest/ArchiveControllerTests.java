package io.github.nturner03.tlaarchive.gametest;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.catalog.CatalogEntry;
import io.github.nturner03.tlaarchive.catalog.CatalogMenu;
import io.github.nturner03.tlaarchive.catalog.CatalogSync;
import io.github.nturner03.tlaarchive.catalog.CatalogView;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.controller.ControllerReadout;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The controller's counts and states on real AE2 networks (handoff {@code HANDOFF_ARCHIVE_MOD_CONTROLLER.md} →
 * <i>Done when</i>). Counts are checked through the controller, which reads the grid's cached inventory in sweeps, so
 * every check waits for the next completed sweep.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArchiveControllerTests {
    private static final int TIMEOUT = 400;

    private ArchiveControllerTests() {
    }

    static void assertCount(GameTestHelper helper, ArchiveControllerBlockEntity controller, String key,
            long expected) {
        var count = controller.count(key);
        helper.assertTrue(count.isPresent() && count.getAsLong() == expected,
                key + " count " + count + ", want " + expected + " (state " + controller.state() + ")");
    }

    static void assertLive(GameTestHelper helper, ArchiveControllerBlockEntity controller) {
        helper.assertTrue(controller.state() == ArchiveState.LIVE, "state " + controller.state() + ", want LIVE");
    }

    /** The status and milestone lines the catalog shows, as the server sends them. */
    static String readout(GameTestHelper helper, ArchiveControllerBlockEntity controller) {
        var counts = CatalogSync.counts(0, controller, helper.getLevel().getGameTime());
        return String.join("\n", Stream.concat(counts.status().stream(), counts.milestones().stream())
                .map(Component::getString).toList());
    }

    /** The catalog's view of the controller, built from the payloads the server sends. */
    static CatalogView catalog(GameTestHelper helper, ArchiveControllerBlockEntity controller) {
        var entries = ManifestTests.matcher(helper).manifest().entries().stream().map(CatalogEntry::of).toList();
        return new CatalogView(entries, CatalogSync.counts(0, controller, helper.getLevel().getGameTime()).counts());
    }

    static int indexOf(CatalogView view, String key) {
        for (int i = 0; i < view.entries().size(); i++) {
            if (view.entry(i).key().equals(key)) {
                return i;
            }
        }
        throw new GameTestAssertException("no catalog entry " + key);
    }

    static boolean catalogReady(GameTestHelper helper, ArchiveControllerBlockEntity controller, String key) {
        var view = catalog(helper, controller);
        return view.ready(indexOf(view, key));
    }

    /** Stock on the controller's own network counts across a cell and a storage bus; another network's doesn't. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void countsOnlyItsOwnNetwork(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 40)));
        grid.chest().setItem(0, new ItemStack(Items.DIAMOND, 30));
        grid.addNetworkB(List.of(new ItemStack(Items.DIAMOND, 500)));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 70);
                })
                .thenWaitUntil(() -> assertCount(helper, grid.controllerB(), "diamond", 500))
                .thenExecute(() -> {
                    var view = catalog(helper, grid.controller());
                    int diamond = indexOf(view, "diamond");
                    helper.assertTrue(view.count(diamond) == 70 && view.entry(diamond).quantity() == 64
                            && view.ready(diamond), "catalog shows 70 / 64, ready");
                    assertCount(helper, grid.controller(), "diamond", 70);
                })
                .thenSucceed();
    }

    /** A storage bus's inventory counts while the bus is connected, and stops counting once it is not. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void storageBusCreditIsLostWhenDisconnected(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 10)));
        grid.chest().setItem(0, new ItemStack(Items.DIAMOND, 64));
        grid.chest().setItem(1, Samples.damagedRenamedPickaxe());
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertCount(helper, grid.controller(), "diamond", 74);
                    assertCount(helper, grid.controller(), "iron_pickaxe", 1);
                })
                .thenExecute(() -> helper.setBlock(ArchiveTestGrid.BUS, Blocks.AIR))
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 10);
                    assertCount(helper, grid.controller(), "iron_pickaxe", 0);
                })
                .thenSucceed();
    }

    /** Taking stock out drops readiness; putting it back restores it. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void readinessFollowsWithdrawalAndRestock(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var diamond = AEItemKey.of(Items.DIAMOND);
        helper.startSequence()
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "diamond", 64))
                .thenExecute(() -> helper.assertTrue(
                        catalogReady(helper, grid.controller(), "diamond"), "diamond ready at 64"))
                .thenExecute(() -> helper.assertTrue(
                        grid.storage().extract(diamond, 1, Actionable.MODULATE, IActionSource.empty()) == 1,
                        "withdrew one diamond"))
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "diamond", 63))
                .thenExecute(() -> helper.assertTrue(
                        !catalogReady(helper, grid.controller(), "diamond"), "diamond not ready at 63"))
                .thenExecute(() -> helper.assertTrue(
                        grid.storage().insert(diamond, 1, Actionable.MODULATE, IActionSource.empty()) == 1,
                        "restocked one diamond"))
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "diamond", 64))
                .thenExecute(() -> helper.assertTrue(
                        catalogReady(helper, grid.controller(), "diamond"), "diamond ready again"))
                .thenSucceed();
    }

    /** EC-potion-forms on a network: a splash strength II satisfies strength, and not swiftness. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void splashStrengthCountsForStrengthOnly(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper,
                List.of(Samples.potion(Items.SPLASH_POTION, Potions.STRONG_STRENGTH)));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "potion:strength", 1);
                    assertCount(helper, grid.controller(), "potion:swiftness", 0);
                })
                .thenSucceed();
    }

    /** EC-enchanted-books on a network: one efficiency + fortune book satisfies both entries. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void oneBookCountsForBothEnchantments(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(Samples.efficiencyFortuneBook(helper)));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertCount(helper, grid.controller(), "enchanted_book:efficiency", 1);
                    assertCount(helper, grid.controller(), "enchanted_book:fortune", 1);
                })
                .thenSucceed();
    }

    /**
     * EC-ordinary-id and EC-banners on a network. The pickaxe also comes back out of storage with its name and damage,
     * because matching never changes item data.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void damagedPickaxeAndPatternedBannerCount(GameTestHelper helper) {
        var pickaxe = Samples.damagedRenamedPickaxe();
        var grid = ArchiveTestGrid.networkA(helper, List.of(pickaxe,
                Samples.patternedBanner(helper, Items.BLUE_BANNER),
                Samples.patternedBanner(helper, Items.RED_BANNER)));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertCount(helper, grid.controller(), "iron_pickaxe", 1);
                    assertCount(helper, grid.controller(), "blue_banner", 1);
                })
                .thenExecute(() -> {
                    var key = AEItemKey.of(pickaxe);
                    helper.assertTrue(grid.storage().extract(key, 1, Actionable.MODULATE,
                            IActionSource.empty()) == 1, "pickaxe withdrawn by its exact key");
                    var out = key.toStack();
                    helper.assertTrue(out.getDamageValue() == pickaxe.getDamageValue(), "damage kept");
                    helper.assertTrue("Old Faithful".equals(out.get(DataComponents.CUSTOM_NAME).getString()),
                            "name kept");
                })
                .thenSucceed();
    }

    /**
     * Owner ruling 1: on power loss the last counts stay, labelled stale, and reconstruction is unavailable; power back
     * returns the controller to live.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void staleOnPowerLoss(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var readingTime = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 64);
                })
                // Unowned here, so only the missing owner blocks reconstruction while live.
                .thenExecute(() -> helper.assertTrue(grid.controller().reconstructionBlocker()
                        .filter(r -> r.contains("owner team")).isPresent(),
                        "live: " + grid.controller().reconstructionBlocker()))
                .thenExecute(() -> helper.setBlock(ArchiveTestGrid.ENERGY, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertTrue(grid.controller().state() == ArchiveState.STALE,
                        "state " + grid.controller().state() + ", want STALE"))
                .thenIdle(40) // several sweep intervals: an unpowered grid reads 0, which must never be published
                .thenExecute(() -> {
                    var controller = grid.controller();
                    helper.assertTrue(controller.state() == ArchiveState.STALE, "still STALE");
                    assertCount(helper, controller, "diamond", 64);
                    helper.assertTrue(controller.reconstructionBlocker().filter(r -> r.contains("stale"))
                            .isPresent(), "reconstruction unavailable: " + controller.reconstructionBlocker());
                    var text = readout(helper, controller);
                    helper.assertTrue(text.contains("STALE (last reading") && text.contains("Reconstruction "
                            + "unavailable"), "readout labels stale counts:\n" + text);
                    readingTime[0] = controller.readingGameTime();
                })
                .thenExecute(() -> helper.setBlock(ArchiveTestGrid.ENERGY,
                        ArchiveTestGrid.block("ae2:creative_energy_cell")))
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 64);
                    helper.assertTrue(grid.controller().readingGameTime() > readingTime[0], "a new reading");
                })
                .thenSucceed();
    }

    /**
     * Owner ruling 1 for chunk unload: the controller's node goes when its chunk unloads, and a reloaded controller
     * starts stale with the saved counts and time. Real unload and reload are not run here; this calls the unload hook
     * and round-trips the block entity through NBT.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void staleAfterUnloadAndReload(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 64);
                })
                .thenExecute(() -> {
                    var controller = grid.controller();
                    var registries = helper.getLevel().registryAccess();
                    controller.onChunkUnloaded();
                    helper.assertTrue(controller.state() == ArchiveState.STALE, "STALE after unload");
                    helper.assertTrue(controller.getGridNode(Direction.UP) == null, "node gone");

                    var saved = controller.saveWithoutMetadata(registries);
                    var reloaded = new ArchiveControllerBlockEntity(controller.getBlockPos(),
                            ModRegistries.ARCHIVE_CONTROLLER.get().defaultBlockState());
                    reloaded.loadWithComponents(saved, registries);
                    helper.assertTrue(reloaded.state() == ArchiveState.STALE, "reloaded: " + reloaded.state());
                    assertCount(helper, reloaded, "diamond", 64);
                    helper.assertTrue(reloaded.readingGameTime() == controller.readingGameTime(), "time kept");
                    helper.assertTrue(reloaded.readingManifest().equals(controller.readingManifest()),
                            "manifest label kept");
                })
                .thenSucceed();
    }

    /** Owner ruling 2: the placer's team owns the controller, and nobody else may read it. Ruling 7d: freezing. */
    @SuppressWarnings("removal") // NeoForge 21.1 still has it; the FTB Teams spike used it the same way
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void placerTeamOwnsAndOthersAreDenied(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var owner = helper.makeMockServerPlayerInLevel();
        var stranger = helper.makeMockServerPlayerInLevel();
        var controller = grid.controller();
        var block = ModRegistries.ARCHIVE_CONTROLLER.get();
        block.setPlacedBy(helper.getLevel(), controller.getBlockPos(), controller.getBlockState(), owner,
                new ItemStack(block));
        helper.assertTrue(controller.ownerTeam().filter(owner.getUUID()::equals).isPresent(),
                "owner team is the placer's player team: " + controller.ownerTeam());
        helper.assertTrue(controller.isOwnedBy(owner), "placer has access");
        helper.assertTrue(!controller.isOwnedBy(stranger), "another team is denied");
        helper.assertTrue(ControllerReadout.denial(controller, owner).isEmpty(), "the owner may open the catalog");
        var strangerView = ControllerReadout.denial(controller, stranger);
        helper.assertTrue(strangerView.filter(line -> line.getString().contains("another team")).isPresent(),
                "stranger gets one line and no catalog");
        var center = helper.absolutePos(ArchiveTestGrid.CONTROLLER).getCenter();
        owner.moveTo(center.x, center.y + 1, center.z);
        stranger.moveTo(center.x, center.y + 1, center.z);
        helper.assertTrue(new CatalogMenu(1, owner, controller).stillValid(owner), "owner's catalog stays open");
        helper.assertTrue(!new CatalogMenu(1, stranger, controller).stillValid(stranger),
                "a catalog opened for another team closes at once");
        owner.moveTo(center.x + 20, center.y, center.z);
        helper.assertTrue(!new CatalogMenu(1, owner, controller).stillValid(owner), "the owner walked away");

        // A team id FTB doesn't know stands in for a deleted party (ids are never reused).
        controller.setOwnerTeam(UUID.randomUUID());
        controller.checkOwnerTeam();
        helper.assertTrue(controller.state() == ArchiveState.FROZEN, "frozen: " + controller.state());
        helper.assertTrue(controller.reconstructionBlocker().isPresent(), "frozen blocks reconstruction");
        helper.succeed();
    }
}
