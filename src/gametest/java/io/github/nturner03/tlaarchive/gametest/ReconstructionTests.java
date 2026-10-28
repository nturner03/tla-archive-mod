package io.github.nturner03.tlaarchive.gametest;

import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.assertCount;
import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.assertLive;
import static io.github.nturner03.tlaarchive.gametest.ArchiveControllerTests.readout;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import io.github.nturner03.tlaarchive.ArchiveConfig;
import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.reconstruction.ExtractionPlan;
import io.github.nturner03.tlaarchive.reconstruction.Reconstruction;
import io.github.nturner03.tlaarchive.reconstruction.Reconstruction.Pacing;
import io.github.nturner03.tlaarchive.reconstruction.ReconstructionOperation;
import io.github.nturner03.tlaarchive.reconstruction.ReconstructionOperation.State;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import io.github.nturner03.tlaarchive.team.TeamArchive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Reconstruction on real AE2 networks (handoff {@code HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md} → <i>Done when</i>). Each
 * test turns the test flag on, claims its controller for a fresh mock player's team, and waits for a live reading.
 * Restarts are modelled by {@link io.github.nturner03.tlaarchive.reconstruction.ReconstructionJournal#reloadForTesting},
 * which round-trips one operation through NBT and recovers it.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReconstructionTests {
    private static final int TIMEOUT = 400;
    /** Long enough that a held operation is still staged when the test acts on it. */
    private static final Pacing HOLD = new Pacing(0, 100_000);

    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

    private ReconstructionTests() {
    }

    // --- Helpers ---

    @SuppressWarnings("removal") // NeoForge 21.1 still has it; the ownership test uses it the same way
    static ServerPlayer claim(GameTestHelper helper, ArchiveControllerBlockEntity controller) {
        var player = helper.makeMockServerPlayerInLevel();
        var block = ModRegistries.ARCHIVE_CONTROLLER.get();
        block.setPlacedBy(helper.getLevel(), controller.getBlockPos(), controller.getBlockState(), player,
                new ItemStack(block));
        return player;
    }

    /** What a real extraction could take now, read live (storage buses included). */
    static long live(MEStorage storage, AEKey key) {
        return storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
    }

    static Map<AEKey, Long> snapshot(MEStorage storage, List<ItemStack> stacks) {
        var out = new LinkedHashMap<AEKey, Long>();
        for (var stack : stacks) {
            var key = AEItemKey.of(stack);
            out.put(key, live(storage, key));
        }
        return out;
    }

    static void assertStock(GameTestHelper helper, MEStorage storage, Map<AEKey, Long> expected) {
        expected.forEach((key, amount) -> {
            long now = live(storage, key);
            helper.assertTrue(now == amount, key.getDisplayName().getString() + ": " + now + ", want " + amount);
        });
    }

    static void assertState(GameTestHelper helper, ReconstructionOperation op, State expected) {
        helper.assertTrue(op.state() == expected, "operation " + op.state() + ", want " + expected + " ("
                + op.outcome() + ")");
    }

    static ReconstructionOperation started(GameTestHelper helper, Reconstruction.StartResult result) {
        helper.assertTrue(result.operation().isPresent(), "an operation started: " + result.message());
        return result.operation().get();
    }

    /** The current object for the operation (a reload replaces it). */
    static ReconstructionOperation current(GameTestHelper helper, ReconstructionOperation op) {
        return Reconstruction.journal(helper.getLevel().getServer()).find(op.id()).orElseThrow();
    }

    static void assertNotCompleted(GameTestHelper helper, ArchiveControllerBlockEntity controller) {
        var journal = Reconstruction.journal(helper.getLevel().getServer());
        var team = controller.ownerTeam().orElseThrow();
        helper.assertTrue(journal.completedFor(team).isEmpty(), "no completion recorded for " + team);
    }

    // --- Plan, consumption, refusals ---

    /**
     * Only the required quantities are consumed, after explicit activation and a fresh validation; excess and unrelated
     * stock stays. Two strength potions in different forms satisfy one entry and only one is taken. A completed team
     * can't reconstruct again.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void consumesRequiredStockOnly(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var cell = new ArrayList<ItemStack>();
        cell.add(new ItemStack(Items.DIAMOND, 70));
        cell.addAll(Samples.manifestStockWithoutDiamonds(helper));
        var splash = Samples.potion(Items.SPLASH_POTION, Potions.STRONG_STRENGTH);
        var redBanner = Samples.patternedBanner(helper, Items.RED_BANNER);
        cell.add(splash);
        cell.add(redBanner);
        cell.add(new ItemStack(Items.DIRT, 10));
        var grid = ArchiveTestGrid.networkA(helper, cell);
        grid.chest().setItem(0, new ItemStack(Items.DIAMOND, 30));
        var owner = claim(helper, grid.controller());
        var drinkable = AEItemKey.of(Samples.potion(Items.POTION, Potions.STRENGTH));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 100);
                })
                .thenExecute(() -> {
                    var controller = grid.controller();
                    helper.assertTrue(readout(helper, controller).contains("Reconstruction available"),
                            "readout offers reconstruction:\n" + readout(helper, controller));
                    var plan = Reconstruction.plan(controller).orElseThrow();
                    helper.assertTrue(plan.complete(), "plan covers the manifest: " + plan.shortfalls());
                    helper.assertTrue(plan.amounts().get(DIAMOND) == 64, "plan takes 64 diamonds: " + plan.amounts());
                    long strength = plan.amounts().getOrDefault(drinkable, 0L)
                            + plan.amounts().getOrDefault(AEItemKey.of(splash), 0L);
                    helper.assertTrue(strength == 1, "one strength potion planned, any form: " + plan.amounts());
                    // 64 diamonds, then one item each for the other six entries, the book covering two.
                    helper.assertTrue(plan.amounts().values().stream().mapToLong(Long::longValue).sum() == 64 + 5,
                            "64 diamonds and one item per other entry: " + plan.amounts());

                    var op = started(helper, Reconstruction.start(controller, owner, plan, Pacing.IMMEDIATE));
                    assertState(helper, op, State.COMMITTED);
                    var storage = grid.storage();
                    helper.assertTrue(live(storage, DIAMOND) == 36, "36 diamonds left: " + live(storage, DIAMOND));
                    helper.assertTrue(live(storage, AEItemKey.of(Items.DIRT)) == 10, "dirt untouched");
                    helper.assertTrue(live(storage, AEItemKey.of(redBanner)) == 1, "red banner untouched");
                    helper.assertTrue(live(storage, drinkable) + live(storage, AEItemKey.of(splash)) == 1,
                            "one strength potion left");
                    for (var stack : Samples.manifestStockWithoutBook(helper)) {
                        var key = AEItemKey.of(stack);
                        if (!key.equals(drinkable)) {
                            helper.assertTrue(live(storage, key) == 0, stack + " consumed");
                        }
                    }
                    helper.assertTrue(live(storage, AEItemKey.of(Samples.efficiencyFortuneBook(helper))) == 0,
                            "book consumed");

                    var journal = Reconstruction.journal(helper.getLevel().getServer());
                    helper.assertTrue(journal.completedFor(controller.ownerTeam().orElseThrow()).isPresent(),
                            "completion recorded for the team");
                    helper.assertTrue(op.staged().isEmpty(), "buffer cleared by the commit");
                    var again = Reconstruction.start(controller, owner, null, Pacing.IMMEDIATE);
                    helper.assertTrue(!again.started() && again.message().contains("already complete"),
                            "second reconstruction refused: " + again.message());
                    helper.assertTrue(readout(helper, controller).contains("Reconstruction COMPLETE"),
                            "readout shows completion:\n" + readout(helper, controller));

                    // The team's archive keeps the milestone with the manifest it was achieved against.
                    var team = controller.ownerTeam().orElseThrow();
                    var milestone = ArchiveTeams.get(helper.getLevel().getServer()).find(team)
                            .flatMap(TeamArchive::reconstructionComplete).orElseThrow();
                    helper.assertTrue(milestone.operation().equals(Optional.of(op.id()))
                            && milestone.manifestId().equals(Optional.of("representative-test"))
                            && milestone.manifestVersion().equals(Optional.of(1))
                            && milestone.manifestLabel().equals(op.manifestLabel())
                            && milestone.gameTime() == op.finishedAt(), "milestone: " + milestone);
                    // A journal from before team state backfills a label-only milestone, once.
                    var fresh = new ArchiveTeams();
                    fresh.recordReconstruction(op, Optional.empty());
                    fresh.recordReconstruction(op, Optional.empty());
                    var backfilled = fresh.find(team).orElseThrow();
                    helper.assertTrue(backfilled.milestones().size() == 1
                            && backfilled.milestones().get(0).manifestId().isEmpty(), "backfill: " + backfilled);
                })
                .thenSucceed();
    }

    /**
     * EC-enchanted-books: the efficiency + fortune book is taken once and credited to both entries. The single-
     * enchantment books stay, because the plan prefers the key that satisfies more unmet entries.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void multiEnchantmentBookIsConsumedOnce(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var efficiencyOnly = Samples.book(helper, Enchantments.EFFICIENCY, 5);
        var fortuneOnly = Samples.book(helper, Enchantments.FORTUNE, 1);
        var cell = new ArrayList<>(Samples.manifestStock(helper));
        cell.add(efficiencyOnly);
        cell.add(fortuneOnly);
        var grid = ArchiveTestGrid.networkA(helper, cell);
        var owner = claim(helper, grid.controller());
        var both = AEItemKey.of(Samples.efficiencyFortuneBook(helper));
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    var plan = Reconstruction.plan(grid.controller()).orElseThrow();
                    helper.assertTrue(plan.amounts().getOrDefault(both, 0L) == 1, "the two-enchantment book once");
                    helper.assertTrue(!plan.amounts().containsKey(AEItemKey.of(efficiencyOnly))
                            && !plan.amounts().containsKey(AEItemKey.of(fortuneOnly)), "no single books planned");
                    helper.assertTrue(plan.credit().get("enchanted_book:efficiency") == 1
                            && plan.credit().get("enchanted_book:fortune") == 1, "credited to both entries");

                    var op = started(helper, Reconstruction.start(grid.controller(), owner, plan, Pacing.IMMEDIATE));
                    assertState(helper, op, State.COMMITTED);
                    var storage = grid.storage();
                    helper.assertTrue(live(storage, both) == 0, "two-enchantment book consumed");
                    helper.assertTrue(live(storage, AEItemKey.of(efficiencyOnly)) == 1, "efficiency book stays");
                    helper.assertTrue(live(storage, AEItemKey.of(fortuneOnly)) == 1, "fortune book stays");
                })
                .thenSucceed();
    }

    /**
     * Stock taken after the preview: a changed plan is refused, and a chest emptied in the same tick (before the
     * storage bus's listing catches up) is caught by the simulated extraction. Nothing is taken either way.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void refusedWhenStockChangesBeforeActivation(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStockWithoutDiamonds(helper));
        grid.chest().setItem(0, new ItemStack(Items.DIAMOND, 64));
        var owner = claim(helper, grid.controller());
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertCount(helper, grid.controller(), "diamond", 64);
                })
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    // The bus lists its own cached view; the cached plan still sees 64 this tick.
                    grid.chest().setItem(0, new ItemStack(Items.DIAMOND, 63));
                    var plan = Reconstruction.plan(grid.controller()).orElseThrow();
                    var result = Reconstruction.start(grid.controller(), owner, plan, Pacing.IMMEDIATE);
                    helper.assertTrue(result.operation().isEmpty() && result.message().contains("can be extracted"),
                            "simulation catches the emptied chest: " + result.message());
                    before.put(DIAMOND, 63L);
                    assertStock(helper, grid.storage(), before);
                })
                // Once the cache has caught up, the plan itself is short.
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "diamond", 63))
                .thenExecute(() -> {
                    var result = Reconstruction.start(grid.controller(), owner, null, Pacing.IMMEDIATE);
                    helper.assertTrue(result.operation().isEmpty() && result.message().contains("not every entry"),
                            "incomplete stock refused: " + result.message());
                    assertStock(helper, grid.storage(), before);
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    /** The preview's plan must still be the plan: a different variant chosen now is refused. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void refusedWhenPlanDiffersFromPreview(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        var splash = Samples.potion(Items.SPLASH_POTION, Potions.STRENGTH);
        var previewed = new ExtractionPlan[1];
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    previewed[0] = Reconstruction.plan(grid.controller()).orElseThrow();
                    // More splash potions than drinkable ones: the plan now prefers the splash form.
                    grid.storage().insert(AEItemKey.of(splash), 5, Actionable.MODULATE, IActionSource.empty());
                })
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "potion:strength", 6))
                .thenExecute(() -> {
                    var result = Reconstruction.start(grid.controller(), owner, previewed[0], Pacing.IMMEDIATE);
                    helper.assertTrue(result.operation().isEmpty() && result.message().contains("changed since"),
                            "refused: " + result.message());
                    helper.assertTrue(live(grid.storage(), DIAMOND) == 64, "nothing taken");
                })
                .thenSucceed();
    }

    /** A stale controller (power lost) refuses, and its stock is untouched. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void refusedWhileStale(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> helper.setBlock(ArchiveTestGrid.ENERGY, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertTrue(grid.controller().state() == ArchiveState.STALE, "stale"))
                .thenExecute(() -> {
                    var result = Reconstruction.start(grid.controller(), owner, null, Pacing.IMMEDIATE);
                    helper.assertTrue(result.operation().isEmpty() && result.message().contains("stale"),
                            "refused while stale: " + result.message());
                    helper.setBlock(ArchiveTestGrid.ENERGY, ArchiveTestGrid.block("ae2:creative_energy_cell"));
                })
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    helper.assertTrue(live(grid.storage(), DIAMOND) == 64, "nothing taken");
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    /** Off by default, and refused while the test flag is off. Its own batch, because the flag is global. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT, batch = "reconstruction_flag_off")
    public static void refusedWhileTestFlagIsOff(GameTestHelper helper) {
        helper.assertTrue(!ArchiveConfig.RECONSTRUCTION_ENABLED.getDefault(), "the flag defaults to off");
        ArchiveConfig.overrideReconstructionEnabled(false);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    var result = Reconstruction.start(grid.controller(), owner, null, Pacing.IMMEDIATE);
                    helper.assertTrue(result.operation().isEmpty() && result.message().contains("disabled"),
                            "refused: " + result.message());
                    helper.assertTrue(readout(helper, grid.controller()).contains("disabled by the test flag"),
                            "readout says so:\n" + readout(helper, grid.controller()));
                    helper.assertTrue(live(grid.storage(), DIAMOND) == 64, "nothing taken");
                    ArchiveConfig.overrideReconstructionEnabled(null);
                })
                .thenSucceed();
    }

    // --- Rollback and overflow ---

    /**
     * A storage whose simulation is optimistic: validation passes, the real extraction of the last key comes up short,
     * and every key already extracted goes back in the same tick. Nothing is consumed or completed.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void shortExtractionRollsBackEverything(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var cell = new ArrayList<ItemStack>(Samples.manifestStockWithoutBook(helper));
        cell.add(new ItemStack(Items.DIAMOND, 64));
        var grid = ArchiveTestGrid.networkA(helper, cell);
        var book = AEItemKey.of(Samples.efficiencyFortuneBook(helper));
        var flaky = new FlakyStorage();
        flaky.seed(book, 1);
        var owner = claim(helper, grid.controller());
        var mounted = new IManagedGridNode[1];
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> mounted[0] = grid.attachStorage(flaky))
                .thenWaitUntil(() -> assertCount(helper, grid.controller(), "enchanted_book:fortune", 1))
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    var result = Reconstruction.start(grid.controller(), owner, null, Pacing.IMMEDIATE);
                    var op = started(helper, result);
                    helper.assertTrue(!result.started(), "not started: " + result.message());
                    assertState(helper, op, State.ROLLED_BACK);
                    helper.assertTrue(op.outcome().contains("could be extracted"), "short key named: " + op.outcome());
                    assertStock(helper, grid.storage(), before);
                    helper.assertTrue(flaky.get(book) == 1, "the book never left");
                    assertNotCompleted(helper, grid.controller());
                    mounted[0].destroy();
                })
                .thenSucceed();
    }

    /**
     * Rollback into full storage: what doesn't fit stays in the journal as overflow (conserved, never voided), the
     * readout says so, another team can't take it, and the owner can move it to their inventory.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel, as in claim()
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void overflowThatWontFitStaysInTheJournal(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        var stranger = helper.makeMockServerPlayerInLevel();
        var op = new ReconstructionOperation[1];
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    op[0] = started(helper, Reconstruction.start(grid.controller(), owner, null, HOLD));
                    assertState(helper, op[0], State.STAGED);
                    // Fill all free space: the chest with dirt, the cell with other item types.
                    for (int slot = 0; slot < grid.chest().getContainerSize(); slot++) {
                        grid.chest().setItem(slot, new ItemStack(Items.DIRT, 64));
                    }
                    for (var item : new Item[] {Items.STONE, Items.COBBLESTONE, Items.ANDESITE, Items.DIORITE,
                            Items.GRANITE, Items.SAND, Items.GRAVEL, Items.OAK_LOG}) {
                        grid.storage().insert(AEItemKey.of(item), 1_000_000, Actionable.MODULATE,
                                IActionSource.empty());
                    }
                    Reconstruction.onTeamChanged(op[0].team(), "test: abort into full storage");
                })
                .thenWaitUntil(() -> {
                    assertState(helper, op[0], State.ROLLING_BACK);
                    helper.assertTrue(op[0].stagedTotal() > 0, "overflow kept");
                })
                .thenExecute(() -> {
                    before.forEach((key, amount) -> {
                        long sum = live(grid.storage(), key) + op[0].staged().get(key);
                        helper.assertTrue(sum == amount, key.getDisplayName().getString() + " conserved: " + sum
                                + ", want " + amount);
                    });
                    var text = readout(helper, grid.controller());
                    helper.assertTrue(text.contains("overflow"), "readout shows overflow:\n" + text);
                    helper.assertTrue(Reconstruction.takeOverflow(stranger) == 0, "another team can't take it");
                    long overflow = op[0].stagedTotal();
                    long moved = Reconstruction.takeOverflow(owner);
                    helper.assertTrue(moved == overflow, "moved " + moved + " of " + overflow);
                    assertState(helper, op[0], State.ROLLED_BACK);
                    before.forEach((key, amount) -> {
                        long inInventory = 0;
                        for (var stack : owner.getInventory().items) {
                            if (AEItemKey.matches(key, stack)) {
                                inInventory += stack.getCount();
                            }
                        }
                        long sum = live(grid.storage(), key) + inInventory;
                        helper.assertTrue(sum == amount, key.getDisplayName().getString() + " conserved after take: "
                                + sum + ", want " + amount);
                    });
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    // --- Recovery after a restart (ruling 3: always roll back) ---

    /** Interrupted mid-extraction (one key per tick): the reload rolls it back; nothing duplicated or lost. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void recoversFromExtracting(GameTestHelper helper) {
        recoverFrom(helper, new Pacing(1, 0), State.EXTRACTING);
    }

    /** Interrupted while fully staged: rolled back, never committed. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void recoversFromStaged(GameTestHelper helper) {
        recoverFrom(helper, HOLD, State.STAGED);
    }

    private static void recoverFrom(GameTestHelper helper, Pacing pacing, State interruptedIn) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        var op = new ReconstructionOperation[1];
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    op[0] = started(helper, Reconstruction.start(grid.controller(), owner, null, pacing));
                    assertState(helper, op[0], interruptedIn);
                    helper.assertTrue(op[0].stagedTotal() > 0, "stock staged before the restart");
                    var reloaded = Reconstruction.journal(helper.getLevel().getServer())
                            .reloadForTesting(op[0].id(), helper.getLevel().registryAccess());
                    helper.assertTrue(reloaded != op[0], "a new object, as after a restart");
                    helper.assertTrue(reloaded.stagedTotal() == op[0].stagedTotal(), "buffer saved and loaded");
                    assertState(helper, reloaded, State.ROLLING_BACK);
                    op[0] = reloaded;
                })
                .thenWaitUntil(() -> assertState(helper, current(helper, op[0]), State.ROLLED_BACK))
                .thenExecute(() -> {
                    assertStock(helper, grid.storage(), before);
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    /**
     * Interrupted while rolling back: power lost while staged aborts the operation, nothing can go back while the grid
     * is down, the reload keeps it rolling back, and power returning puts everything back.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void recoversFromRollingBack(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        var op = new ReconstructionOperation[1];
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    op[0] = started(helper, Reconstruction.start(grid.controller(), owner, null, HOLD));
                    assertState(helper, op[0], State.STAGED);
                    helper.setBlock(ArchiveTestGrid.ENERGY, Blocks.AIR);
                })
                .thenWaitUntil(() -> assertState(helper, op[0], State.ROLLING_BACK))
                .thenIdle(Reconstruction.ROLLBACK_RETRY_TICKS * 2)
                .thenExecute(() -> {
                    assertState(helper, op[0], State.ROLLING_BACK);
                    helper.assertTrue(op[0].stagedTotal() == 64 + 5, "all of it still in the journal: "
                            + op[0].stagedTotal());
                    op[0] = Reconstruction.journal(helper.getLevel().getServer())
                            .reloadForTesting(op[0].id(), helper.getLevel().registryAccess());
                    assertState(helper, op[0], State.ROLLING_BACK);
                    helper.setBlock(ArchiveTestGrid.ENERGY, ArchiveTestGrid.block("ae2:creative_energy_cell"));
                })
                .thenWaitUntil(() -> assertState(helper, current(helper, op[0]), State.ROLLED_BACK))
                .thenExecute(() -> {
                    assertStock(helper, grid.storage(), before);
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    // --- Teams ---

    /** Owner ruling 7e: the owner creating a party (a real FTB Teams event) aborts and rolls back the operation. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void teamChangeAbortsAndRollsBack(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        var owner = claim(helper, grid.controller());
        var op = new ReconstructionOperation[1];
        var before = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> assertLive(helper, grid.controller()))
                .thenExecute(() -> {
                    before.putAll(snapshot(grid.storage(), Samples.manifestStock(helper)));
                    op[0] = started(helper, Reconstruction.start(grid.controller(), owner, null, HOLD));
                    assertState(helper, op[0], State.STAGED);
                    try {
                        FTBTeamsAPI.api().getManager().createPartyTeam(owner, "Reconstruction test party", null,
                                null);
                    } catch (Exception e) {
                        throw new IllegalStateException("could not create a party", e);
                    }
                    assertState(helper, op[0], State.ROLLING_BACK);
                    helper.assertTrue(op[0].outcome().contains("joined or left"), "reason: " + op[0].outcome());
                })
                .thenWaitUntil(() -> assertState(helper, op[0], State.ROLLED_BACK))
                .thenExecute(() -> {
                    assertStock(helper, grid.storage(), before);
                    assertNotCompleted(helper, grid.controller());
                })
                .thenSucceed();
    }

    /**
     * Two teams on separate networks: one can't reconstruct at the other's controller, and one team's operation never
     * touches the other's stock or journal. Both then complete independently.
     */
    @GameTest(template = ArchiveTestGrid.TEMPLATE, timeoutTicks = TIMEOUT)
    public static void twoTeamsStayIsolated(GameTestHelper helper) {
        ArchiveConfig.overrideReconstructionEnabled(true);
        var grid = ArchiveTestGrid.networkA(helper, Samples.manifestStock(helper));
        grid.addNetworkB(Samples.manifestStock(helper));
        var ownerA = claim(helper, grid.controller());
        var ownerB = claim(helper, grid.controllerB());
        var beforeB = new LinkedHashMap<AEKey, Long>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertLive(helper, grid.controller());
                    assertLive(helper, grid.controllerB());
                })
                .thenExecute(() -> {
                    var teamA = grid.controller().ownerTeam().orElseThrow();
                    var teamB = grid.controllerB().ownerTeam().orElseThrow();
                    helper.assertTrue(!teamA.equals(teamB), "two teams");
                    beforeB.putAll(snapshot(grid.storageB(), Samples.manifestStock(helper)));

                    var denied = Reconstruction.start(grid.controller(), ownerB, null, Pacing.IMMEDIATE);
                    helper.assertTrue(denied.operation().isEmpty() && denied.message().contains("owns this archive"),
                            "team B refused at A's controller: " + denied.message());

                    var op = started(helper, Reconstruction.start(grid.controller(), ownerA, null, Pacing.IMMEDIATE));
                    assertState(helper, op, State.COMMITTED);
                    helper.assertTrue(op.team().equals(teamA), "operation belongs to team A");
                    helper.assertTrue(live(grid.storage(), DIAMOND) == 0, "A's diamonds consumed");
                    assertStock(helper, grid.storageB(), beforeB);
                    var journal = Reconstruction.journal(helper.getLevel().getServer());
                    helper.assertTrue(journal.completedFor(teamB).isEmpty() && journal.pendingFor(teamB).isEmpty(),
                            "nothing in the journal for team B");

                    var opB = started(helper, Reconstruction.start(grid.controllerB(), ownerB, null,
                            Pacing.IMMEDIATE));
                    assertState(helper, opB, State.COMMITTED);
                    helper.assertTrue(live(grid.storageB(), DIAMOND) == 0, "B's diamonds consumed");
                })
                .thenSucceed();
    }
}
