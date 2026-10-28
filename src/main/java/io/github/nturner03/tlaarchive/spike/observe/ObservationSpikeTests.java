package io.github.nturner03.tlaarchive.spike.observe;

import static io.github.nturner03.tlaarchive.TlaArchive.LOGGER;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.events.statistics.GridChunkEvent;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.spike.grid.SpikeGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SPIKE (throwaway): incremental observation of manifest keys through {@link IStorageWatcherNode}, compared with
 * {@code getCachedInventory()} and live simulated extraction, and what each costs at scale. Timings are
 * {@code System.nanoTime()} inside a gametest server and only indicative. Findings:
 * docs/spikes/incremental-observation.md.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObservationSpikeTests {
    static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    static final AEItemKey EMERALD = AEItemKey.of(Items.EMERALD);
    static final IActionSource SRC = IActionSource.empty();

    record ChunkEventSeen(String type, ChunkPos chunk) {
    }

    static final List<ChunkEventSeen> CHUNK_EVENTS = new CopyOnWriteArrayList<>();

    static {
        GridHelper.addEventHandler(GridChunkEvent.GridChunkAdded.class,
                (grid, e) -> CHUNK_EVENTS.add(new ChunkEventSeen("added", e.getChunkPos())));
        GridHelper.addEventHandler(GridChunkEvent.GridChunkRemoved.class,
                (grid, e) -> CHUNK_EVENTS.add(new ChunkEventSeen("removed", e.getChunkPos())));
    }

    private ObservationSpikeTests() {
    }

    /** Cell holds 100 diamonds + 50 gold; chest holds 64 diamonds + 10 iron. */
    static SpikeGrid standardGrid(GameTestHelper helper) {
        var g = SpikeGrid.build(helper, Map.of(Items.DIAMOND, 100L, Items.GOLD_INGOT, 50L));
        g.chest().setItem(0, new ItemStack(Items.DIAMOND, 64));
        g.chest().setItem(1, new ItemStack(Items.IRON_INGOT, 10));
        return g;
    }

    static void awaitSettled(GameTestHelper helper, SpikeGrid g) {
        helper.assertTrue(g.isActive(), "grid not active yet: " + g.describe());
        long cached = g.grid().getStorageService().getCachedInventory().get(DIAMOND);
        helper.assertTrue(cached == 164, "cached diamonds " + cached);
    }

    static ManifestObserver observer(GameTestHelper helper, Set<AEKey> keys) {
        return new ManifestObserver(keys, helper::getTick);
    }

    static IManagedGridNode attach(SpikeGrid g, IGridNode target, ManifestObserver obs) {
        return g.attachNode(target, n -> n.addService(IStorageWatcherNode.class, obs), obs);
    }

    static void awaitLatest(GameTestHelper helper, ManifestObserver obs, AEKey key, long want) {
        helper.assertTrue(obs.latest(key) == want, "latest " + key + " = " + obs.latest(key) + ", want " + want);
    }

    /**
     * Does a watcher attached to a settled network get an initial report? Does onStackChange carry totals or deltas?
     * Are unwatched keys filtered out? How many ticks after a network insert does the callback arrive?
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void watcherReportsTotalsNotDeltas(GameTestHelper helper) {
        var g = standardGrid(helper);
        var obs = observer(helper, Set.of(DIAMOND, IRON, GOLD));
        var node = new IManagedGridNode[1];
        var t0 = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = attach(g, g.driveNode(), obs))
                .thenIdle(10)
                .thenExecute(() -> {
                    LOGGER.info("SPIKE observe attach: after 10 ticks changes={} nodeEvents={} sameGrid={}",
                            obs.changes, obs.nodeEvents, node[0].getNode().getGrid() == g.grid());
                    var inv = g.grid().getStorageService().getInventory();
                    long in = inv.insert(DIAMOND, 10, Actionable.MODULATE, SRC);
                    inv.insert(EMERALD, 5, Actionable.MODULATE, SRC);
                    long out = inv.extract(GOLD, 4, Actionable.MODULATE, SRC);
                    t0[0] = helper.getTick();
                    helper.assertTrue(in == 10 && out == 4, "insert/extract");
                    helper.assertTrue(obs.changes.isEmpty() || obs.changes.getLast().tick() < t0[0],
                            "no callback inside the inserting call");
                })
                .thenWaitUntil(() -> awaitLatest(helper, obs, DIAMOND, 174))
                .thenWaitUntil(() -> awaitLatest(helper, obs, GOLD, 46))
                .thenIdle(3)
                .thenExecute(() -> {
                    LOGGER.info("SPIKE observe totals: changes after insert at tick {}: {} (emerald reports={})",
                            t0[0], obs.changes, obs.changesFor(EMERALD));
                    helper.assertTrue(obs.changesFor(EMERALD) == 0, "unwatched key not reported");
                    helper.assertTrue(obs.changesFor(IRON) == 0, "unchanged key not reported");
                    node[0].destroy();
                })
                .thenSucceed();
    }

    /**
     * Latency for edits made to the chest behind the storage bus, after the bus has backed off to its slowest poll:
     * (1) a normal Container edit that notifies neighbours, (2) an edit without neighbour notification.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 2000)
    public static void storageBusEditLatency(GameTestHelper helper) {
        var g = standardGrid(helper);
        var obs = observer(helper, Set.of(DIAMOND, IRON));
        var node = new IManagedGridNode[1];
        var t = new long[4];
        var silent = new ArrayList<Long>();
        var seq = helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = attach(g, g.driveNode(), obs))
                .thenIdle(200) // let the storage bus back off to its slowest tick rate
                .thenExecute(() -> {
                    g.chest().setItem(0, ItemStack.EMPTY); // notifies neighbours via setChanged()
                    t[0] = helper.getTick();
                    long sim = g.grid().getStorageService().getInventory()
                            .extract(DIAMOND, Long.MAX_VALUE, Actionable.SIMULATE, SRC);
                    LOGGER.info("SPIKE observe bus: notified edit at tick {}, live simulate same tick={}", t[0], sim);
                })
                .thenWaitUntil(() -> awaitLatest(helper, obs, DIAMOND, 100))
                .thenExecute(() -> t[1] = helper.getTick())
                .thenIdle(300);
        // Silent edits: shrink the iron stack in place (no setChanged(), no neighbour update), five times at
        // intervals that are not a multiple of the bus's poll interval.
        for (int i = 0; i < 5; i++) {
            long want = 9 - i;
            seq = seq.thenIdle(97 + 13 * i)
                    .thenExecute(() -> {
                        g.chest().getItem(1).shrink(1);
                        t[2] = helper.getTick();
                    })
                    .thenWaitUntil(() -> awaitLatest(helper, obs, IRON, want))
                    .thenExecute(() -> silent.add(helper.getTick() - t[2]));
        }
        seq.thenExecute(() -> {
            LOGGER.info("SPIKE observe bus: notified edit seen after {} ticks; silent edits seen after {} ticks",
                    t[1] - t[0], silent);
            node[0].destroy();
        }).thenSucceed();
    }

    /** Pulling the cell out of the drive and putting it back. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void cellRemovedAndReinserted(GameTestHelper helper) {
        var g = standardGrid(helper);
        var obs = observer(helper, Set.of(DIAMOND, GOLD));
        var node = new IManagedGridNode[1];
        var cell = new ItemStack[1];
        var t = new long[2];
        helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = attach(g, g.driveNode(), obs))
                .thenIdle(5)
                .thenExecute(() -> {
                    var driveInv = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(SpikeGrid.DRIVE), null);
                    cell[0] = driveInv.extractItem(0, 1, false);
                    helper.assertTrue(!cell[0].isEmpty(), "cell came out");
                    t[0] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    awaitLatest(helper, obs, DIAMOND, 64);
                    awaitLatest(helper, obs, GOLD, 0);
                })
                .thenExecute(() -> {
                    LOGGER.info("SPIKE observe cell: removal seen after {} ticks: {}", helper.getTick() - t[0],
                            obs.changes);
                    var driveInv = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            helper.absolutePos(SpikeGrid.DRIVE), null);
                    helper.assertTrue(driveInv.insertItem(0, cell[0], false).isEmpty(), "cell back in");
                    t[1] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    awaitLatest(helper, obs, DIAMOND, 164);
                    awaitLatest(helper, obs, GOLD, 50);
                })
                .thenExecute(() -> {
                    LOGGER.info("SPIKE observe cell: reinsert seen after {} ticks", helper.getTick() - t[1]);
                    node[0].destroy();
                })
                .thenSucceed();
    }

    /** Power loss and restoration: what the watcher and the node listener report, and in which order. */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void powerLossAndRestore(GameTestHelper helper) {
        var g = standardGrid(helper);
        var obs = observer(helper, Set.of(DIAMOND, IRON, GOLD));
        var node = new IManagedGridNode[1];
        var t = new long[2];
        var mark = new int[2];
        helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = attach(g, g.driveNode(), obs))
                .thenIdle(5)
                .thenExecute(() -> {
                    mark[0] = obs.changes.size();
                    mark[1] = obs.nodeEvents.size();
                    helper.setBlock(SpikeGrid.ENERGY, Blocks.AIR);
                    t[0] = helper.getTick();
                })
                .thenWaitUntil(() -> awaitLatest(helper, obs, DIAMOND, 0))
                .thenIdle(5)
                .thenExecute(() -> {
                    var n = node[0].getNode();
                    LOGGER.info("SPIKE observe power loss at tick {}: changes={} nodeEvents={} active={} powered={}"
                            + " cachedDiamonds={}", t[0], obs.changes.subList(mark[0], obs.changes.size()),
                            obs.nodeEvents.subList(mark[1], obs.nodeEvents.size()), n.isActive(), n.isPowered(),
                            n.getGrid().getStorageService().getCachedInventory().get(DIAMOND));
                    mark[0] = obs.changes.size();
                    mark[1] = obs.nodeEvents.size();
                    helper.setBlock(SpikeGrid.ENERGY, SpikeGrid.block("ae2:creative_energy_cell"));
                    t[1] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    awaitLatest(helper, obs, DIAMOND, 164);
                    awaitLatest(helper, obs, GOLD, 50);
                    awaitLatest(helper, obs, IRON, 10);
                })
                .thenExecute(() -> {
                    LOGGER.info("SPIKE observe power restore at tick {}: seen after {} ticks changes={} nodeEvents={}",
                            t[1], helper.getTick() - t[1], obs.changes.subList(mark[0], obs.changes.size()),
                            obs.nodeEvents.subList(mark[1], obs.nodeEvents.size()));
                    node[0].destroy();
                })
                .thenSucceed();
    }

    /**
     * Simulated chunk unload of the part of the network that holds the drive and bus: calls the drive block entity's
     * {@code onChunkUnloaded()} (what the level does to AE2 block entities on a real unload; the chunk itself stays
     * loaded here). The observer hangs off the energy cell, so it ends up on the energy cell's side of the split. Which
     * side keeps the old grid object (and so whether the watcher sees zero callbacks or just a new watcher) depends on
     * AE2's grid pivot, so the same scenario runs three times.
     */
    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void simulatedRemoteChunkUnloadA(GameTestHelper helper) {
        simulatedRemoteChunkUnload(helper, "A");
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void simulatedRemoteChunkUnloadB(GameTestHelper helper) {
        simulatedRemoteChunkUnload(helper, "B");
    }

    @GameTest(template = "spike_grid", timeoutTicks = 200)
    public static void simulatedRemoteChunkUnloadC(GameTestHelper helper) {
        simulatedRemoteChunkUnload(helper, "C");
    }

    static void simulatedRemoteChunkUnload(GameTestHelper helper, String trial) {
        var g = standardGrid(helper);
        var obs = observer(helper, Set.of(DIAMOND, IRON, GOLD));
        var node = new IManagedGridNode[1];
        var t = new long[1];
        var mark = new int[4];
        var driveChunk = new ChunkPos(helper.absolutePos(SpikeGrid.DRIVE));
        helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = attach(g, g.findNode(SpikeGrid.ENERGY), obs))
                .thenIdle(5)
                .thenExecute(() -> {
                    mark[0] = obs.changes.size();
                    mark[1] = obs.nodeEvents.size();
                    mark[2] = CHUNK_EVENTS.size();
                    mark[3] = obs.watcherUpdates;
                    helper.getLevel().getBlockEntity(helper.absolutePos(SpikeGrid.DRIVE)).onChunkUnloaded();
                    t[0] = helper.getTick();
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    var n = node[0].getNode();
                    var chunkEvents = new ArrayList<>(CHUNK_EVENTS.subList(mark[2], CHUNK_EVENTS.size()));
                    chunkEvents.removeIf(e -> !e.chunk().equals(driveChunk));
                    long cachedNow = n.getGrid().getStorageService().getCachedInventory().get(DIAMOND);
                    LOGGER.info("SPIKE observe unload {} at tick {}: 10 ticks later newWatcher={} changes={}"
                            + " nodeEvents={} observerActive={} gridSize={} observerGridCachedDiamonds={}"
                            + " latestDiamonds={} chunkEventsForDriveChunk={} sameChunkAsEnergy={}",
                            trial, t[0], obs.watcherUpdates > mark[3], obs.changes.subList(mark[0], obs.changes.size()),
                            obs.nodeEvents.subList(mark[1], obs.nodeEvents.size()), n.isActive(), n.getGrid().size(),
                            cachedNow, obs.latest(DIAMOND), chunkEvents,
                            driveChunk.equals(new ChunkPos(helper.absolutePos(SpikeGrid.ENERGY))));
                    helper.assertTrue(cachedNow == 0, "drive's stock gone from the observer's grid");
                    helper.assertTrue(n.isActive(), "the observer's part of the grid is still powered and active");
                    node[0].destroy();
                })
                .thenSucceed();
    }

    @GameTest(template = "spike_grid", timeoutTicks = 400)
    public static void costWith1kKeys(GameTestHelper helper) {
        cost(helper, 1_000);
    }

    @GameTest(template = "spike_grid", timeoutTicks = 400)
    public static void costWith10kKeys(GameTestHelper helper) {
        cost(helper, 10_000);
    }

    @GameTest(template = "spike_grid", timeoutTicks = 400)
    public static void costWith50kKeys(GameTestHelper helper) {
        cost(helper, 50_000);
    }

    static AEItemKey distinctKey(int i) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("k" + i));
        return AEItemKey.of(stack);
    }

    /**
     * A network with {@code distinct} extra keys (in a mounted {@link BulkStorage}) and a 1000-key watch list. Times
     * the cache rebuild AE2 runs every tick once any watcher exists, a full {@code getAvailableStacks} scan, 1000
     * cached lookups, 1000 live simulated extractions, and a tick in which all 1000 watched keys change.
     */
    static void cost(GameTestHelper helper, int distinct) {
        var g = standardGrid(helper);
        var bulk = new BulkStorage();
        var keys = new AEItemKey[distinct];
        for (int i = 0; i < distinct; i++) {
            keys[i] = distinctKey(i);
            bulk.stock.add(keys[i], i + 1);
        }
        var watched = Arrays.copyOf(keys, 1000);
        var obs = observer(helper, Set.of(watched));
        var node = new IManagedGridNode[1];
        helper.startSequence()
                .thenWaitUntil(() -> awaitSettled(helper, g))
                .thenExecute(() -> node[0] = g.attachNode(g.driveNode(), n -> {
                    n.addService(IStorageProvider.class, mounts -> mounts.mount(bulk));
                    n.addService(IStorageWatcherNode.class, obs);
                }, obs))
                .thenWaitUntil(() -> {
                    int size = g.grid().getStorageService().getCachedInventory().size();
                    helper.assertTrue(size >= distinct + 3, "cached size " + size);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    var storage = g.grid().getStorageService();
                    var inv = storage.getInventory();
                    int reps = 15;
                    long[] rebuild = new long[reps];
                    long[] scan = new long[reps];
                    long[] lookup = new long[reps];
                    long[] simulate = new long[reps];
                    long sink = 0;
                    for (int r = 0; r < reps; r++) {
                        storage.invalidateCache();
                        long s = System.nanoTime();
                        storage.getCachedInventory();
                        rebuild[r] = System.nanoTime() - s;

                        var kc = new KeyCounter();
                        s = System.nanoTime();
                        inv.getAvailableStacks(kc);
                        scan[r] = System.nanoTime() - s;
                        sink += kc.size();

                        var cached = storage.getCachedInventory();
                        s = System.nanoTime();
                        for (var k : watched) {
                            sink += cached.get(k);
                        }
                        lookup[r] = System.nanoTime() - s;

                        s = System.nanoTime();
                        for (var k : watched) {
                            sink += inv.extract(k, Long.MAX_VALUE, Actionable.SIMULATE, SRC);
                        }
                        simulate[r] = System.nanoTime() - s;
                    }
                    // A tick in which every watched key changes: AE2 diffs and posts 1000 callbacks.
                    int before = obs.changes.size();
                    for (var k : watched) {
                        bulk.stock.add(k, 1);
                    }
                    storage.invalidateCache();
                    long s = System.nanoTime();
                    storage.getCachedInventory();
                    long burst = System.nanoTime() - s;
                    int burstCallbacks = obs.changes.size() - before;
                    LOGGER.info("SPIKE observe cost distinct={} cachedSize={} (us, median/min of {}): rebuild={}"
                            + " fullScan={} cachedLookup1000={} simulate1000={}; burst(1000 watched changed)={}us"
                            + " callbacks={} sink={}", distinct, storage.getCachedInventory().size(), reps,
                            stat(rebuild), stat(scan), stat(lookup), stat(simulate), burst / 1000, burstCallbacks,
                            sink % 7);
                    helper.assertTrue(burstCallbacks == 1000, "one callback per changed watched key");
                    node[0].destroy();
                })
                .thenSucceed();
    }

    static String stat(long[] nanos) {
        var sorted = nanos.clone();
        Arrays.sort(sorted);
        return (sorted[sorted.length / 2] / 1000) + "/" + (sorted[0] / 1000);
    }
}
