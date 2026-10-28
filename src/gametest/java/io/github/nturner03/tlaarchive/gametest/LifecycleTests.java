package io.github.nturner03.tlaarchive.gametest;

import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.assertCount;
import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.assertLive;
import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.catalogReady;
import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.readout;

import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.catalog.CatalogPayloads;
import io.github.nturner03.tlaarchive.catalog.CatalogSync;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestEntry;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import io.github.nturner03.tlaarchive.manifest.MatchRule;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import io.github.nturner03.tlaarchive.team.ManifestChanges;
import io.github.nturner03.tlaarchive.team.SeenManifest;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Lifecycle rulings of 2026-10-28: several controllers of one team each count their network; controllers of different
 * teams on one network conflict; a changed manifest is shown to the team as changes until acknowledged.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleTests {
    private static final int TIMEOUT = 400;

    /** The manifest the reload test replaced, put back after its batch even if it failed. */
    @Nullable
    private static ManifestMatcher manifestBeforeReload;

    private LifecycleTests() {
    }

    @AfterBatch(batch = "manifest_reload")
    public static void restoreManifest(ServerLevel level) {
        if (manifestBeforeReload != null && ActiveManifest.matcher().orElse(null) != manifestBeforeReload) {
            ActiveManifest.setForTesting(manifestBeforeReload);
        }
        manifestBeforeReload = null;
    }

    @SuppressWarnings("removal") // NeoForge 21.1 still has it; the ownership test uses it the same way
    private static void claimAs(GameTestHelper helper, ServerPlayer player, ArchiveControllerBlockEntity controller) {
        var block = ModRegistries.ARCHIVE_CONTROLLER.get();
        block.setPlacedBy(helper.getLevel(), controller.getBlockPos(), controller.getBlockState(), player,
                new ItemStack(block));
    }

    @SuppressWarnings("removal")
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void twoControllersOfOneTeamBothCount(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var second = grid.addSecondController();
        var owner = helper.makeMockServerPlayerInLevel();
        claimAs(helper, owner, grid.controller());
        claimAs(helper, owner, second);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertLive(helper, second);
                    assertCount(helper, grid.controller(), "diamond", 64);
                    assertCount(helper, second, "diamond", 64);
                })
                .thenSucceed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void twoTeamsOnOneNetworkConflict(GameTestHelper helper) {
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var owner = helper.makeMockServerPlayerInLevel();
        claimAs(helper, owner, grid.controller());
        long[] before = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 64);
                })
                .thenExecute(() -> {
                    var rival = grid.addSecondController();
                    claimAs(helper, helper.makeMockServerPlayerInLevel(), rival);
                })
                .thenWaitUntil(() -> {
                    for (var controller : List.of(grid.controller(), helper.getBlockEntity(
                            ArchiveTestGrid.SECOND_CONTROLLER) instanceof ArchiveControllerBlockEntity c ? c : null)) {
                        helper.assertTrue(controller != null && controller.state() == ArchiveState.CONFLICT,
                                "both controllers in CONFLICT");
                    }
                })
                .thenExecute(() -> {
                    var controller = grid.controller();
                    helper.assertTrue(controller.reconstructionBlocker().filter(r -> r.contains("another team"))
                            .isPresent(), "reconstruction blocked: " + controller.reconstructionBlocker());
                    helper.assertTrue(readout(helper, controller).contains("CONFLICT"), readout(helper, controller));
                    helper.assertTrue(controller.conflictWith().filter(helper.absolutePos(
                            ArchiveTestGrid.SECOND_CONTROLLER)::equals).isPresent(), "names the rival's position");
                    before[0] = controller.readingGameTime();
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertTrue(grid.controller().readingGameTime() == before[0],
                        "no new reading while in conflict"))
                .thenExecute(() -> helper.setBlock(ArchiveTestGrid.SECOND_CONTROLLER, Blocks.AIR))
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    helper.assertTrue(grid.controller().readingGameTime() > before[0], "counting again");
                })
                .thenSucceed();
    }

    private static ManifestEntry entry(String key, MatchRule rule, long quantity) {
        return new ManifestEntry(key, key, "test", rule, quantity, Optional.of(ArchiveManifest.TEST_ONLY_NOTE),
                Optional.empty(), Optional.empty());
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void manifestChangesAreListed(GameTestHelper helper) {
        var seen = new SeenManifest("m", 1, "m v1", Map.of("a", 1L, "b", 4L, "c", 10L, "z", 2L));
        var current = new ArchiveManifest(1, "m", 2, true, ArchiveManifest.QuantitiesStatus.PLACEHOLDER, "x",
                Optional.empty(), List.of(
                        entry("new", new MatchRule.ItemId(Items.STONE), 3),
                        entry("c", new MatchRule.ItemId(Items.STONE), 5),
                        entry("a", new MatchRule.ItemId(Items.STONE), 1),
                        entry("b", new MatchRule.ItemId(Items.STONE), 8)));
        var changes = ManifestChanges.between(seen, current);
        helper.assertTrue(changes.fromLabel().equals("m v1"), "from label");
        helper.assertTrue(changes.added().equals(List.of("new")), "added: " + changes.added());
        helper.assertTrue(changes.removed().equals(List.of(new ManifestChanges.Removed("z", 2))),
                "removed: " + changes.removed());
        helper.assertTrue(changes.changed().equals(List.of(new ManifestChanges.Changed("c", 10, 5),
                new ManifestChanges.Changed("b", 4, 8))), "changed, in manifest order: " + changes.changed());
        helper.assertTrue(!changes.changed().get(0).raised() && changes.changed().get(1).raised(), "direction");
        helper.assertTrue(ManifestChanges.between(SeenManifest.of(current), current).isEmpty(), "no change");

        var payload = new CatalogPayloads.Changes(3, Optional.of(changes));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        CatalogPayloads.Changes.STREAM_CODEC.encode(buf, payload);
        helper.assertTrue(CatalogPayloads.Changes.STREAM_CODEC.decode(buf).equals(payload), "changes round trip");
        var ack = new CatalogPayloads.Acknowledge(9);
        CatalogPayloads.Acknowledge.STREAM_CODEC.encode(buf, ack);
        helper.assertTrue(CatalogPayloads.Acknowledge.STREAM_CODEC.decode(buf).equals(ack), "acknowledge");
        helper.succeed();
    }

    /**
     * The migration ruling, tested by reloading a changed manifest. It swaps the global manifest, so it runs in a batch
     * of its own and puts the original back.
     */
    @SuppressWarnings("removal")
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT, batch = "manifest_reload")
    public static void changedManifestIsShownUntilAcknowledged(GameTestHelper helper) {
        var original = ActiveManifest.matcher().orElseThrow();
        manifestBeforeReload = original;
        var grid = ArchiveTestGrid.networkA(helper, List.of(new ItemStack(Items.DIAMOND, 64)));
        var owner = helper.makeMockServerPlayerInLevel();
        claimAs(helper, owner, grid.controller());
        var team = grid.controller().ownerTeam().orElseThrow();
        var teams = ArchiveTeams.get(helper.getLevel().getServer());

        var entries = new ArrayList<ManifestEntry>();
        for (var e : original.manifest().entries()) {
            if (e.key().equals("iron_pickaxe")) {
                continue;
            }
            entries.add(e.key().equals("diamond") ? entry("diamond", e.rule(), 128) : e);
        }
        entries.add(entry("gold_ingot", new MatchRule.ItemId(Items.GOLD_INGOT), 1));
        var old = original.manifest();
        var changed = new ArchiveManifest(old.format(), old.id(), old.version() + 1, old.testOnly(),
                old.quantitiesStatus(), old.description(), old.categories(), entries);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    helper.assertTrue(catalogReady(helper, grid.controller(), "diamond"), "ready at 64 / 64");
                })
                .thenExecute(() -> helper.assertTrue(CatalogSync.changes(grid.controller(), 0)
                        .filter(ManifestChanges::isEmpty).isPresent(), "claiming acknowledged the manifest"))
                .thenExecute(() -> ActiveManifest.setForTesting(new ManifestMatcher(changed)))
                .thenWaitUntil(() -> {
                    // A 7-entry sweep restarts and completes within the reload tick, so wait for the new label.
                    assertLive(helper, grid.controller());
                    helper.assertTrue(grid.controller().readingManifest().equals(changed.label()),
                            "read against " + grid.controller().readingManifest());
                })
                .thenExecute(() -> {
                    helper.assertTrue(!catalogReady(helper, grid.controller(), "diamond"),
                            "64 diamonds no longer meet 128");
                    var changes = CatalogSync.changes(grid.controller(), 0).orElseThrow();
                    helper.assertTrue(changes.added().equals(List.of("gold_ingot"))
                            && changes.removed().equals(List.of(new ManifestChanges.Removed("iron_pickaxe", 1)))
                            && changes.changed().equals(List.of(new ManifestChanges.Changed("diamond", 64, 128)))
                            && changes.fromLabel().equals(old.label()), "changes since claiming: " + changes);
                    teams.acknowledge(team, changed, 0);
                    helper.assertTrue(CatalogSync.changes(grid.controller(), 0).filter(ManifestChanges::isEmpty)
                            .isPresent(), "acknowledged");
                    helper.assertTrue(teams.find(team).flatMap(t -> t.seen()).map(SeenManifest::version)
                            .equals(Optional.of(old.version() + 1)), "the team saw the new version");
                })
                .thenExecute(() -> ActiveManifest.setForTesting(original))
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    helper.assertTrue(grid.controller().readingManifest().equals(old.label()), "read again");
                })
                .thenExecute(() -> {
                    var back = CatalogSync.changes(grid.controller(), 0).orElseThrow();
                    helper.assertTrue(back.added().equals(List.of("iron_pickaxe"))
                            && back.changed().equals(List.of(new ManifestChanges.Changed("diamond", 128, 64))),
                            "going back is a change too: " + back);
                })
                .thenSucceed();
    }
}
