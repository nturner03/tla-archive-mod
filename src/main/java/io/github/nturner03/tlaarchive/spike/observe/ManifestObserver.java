package io.github.nturner03.tlaarchive.spike.observe;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import io.github.nturner03.tlaarchive.spike.grid.SpikeGrid;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * SPIKE (throwaway). An {@link IStorageWatcherNode} that watches a fixed manifest key set and records every callback
 * with the game tick it arrived on, plus the node listener's state and grid changes. Stands in for the archive
 * controller's observation layer. Findings: docs/spikes/incremental-observation.md.
 */
final class ManifestObserver implements IStorageWatcherNode, IGridNodeListener<SpikeGrid> {
    record Change(long tick, AEKey key, long amount) {
    }

    record NodeEvent(long tick, String what) {
    }

    private final Set<AEKey> manifest;
    private final LongSupplier clock;
    final List<Change> changes = new ArrayList<>();
    final List<NodeEvent> nodeEvents = new ArrayList<>();
    /** Last amount AE2 reported per key (absent = never reported). */
    final Object2LongOpenHashMap<AEKey> latest = new Object2LongOpenHashMap<>();
    int watcherUpdates;
    @Nullable
    private IStackWatcher watcher;

    ManifestObserver(Set<AEKey> manifest, LongSupplier clock) {
        this.manifest = new LinkedHashSet<>(manifest);
        this.clock = clock;
        latest.defaultReturnValue(-1);
    }

    @Override
    public void updateWatcher(IStackWatcher newWatcher) {
        watcher = newWatcher;
        watcherUpdates++;
        nodeEvents.add(new NodeEvent(clock.getAsLong(), "updateWatcher#" + watcherUpdates));
        for (var key : manifest) {
            newWatcher.add(key);
        }
    }

    @Override
    public void onStackChange(AEKey what, long amount) {
        changes.add(new Change(clock.getAsLong(), what, amount));
        latest.put(what, amount);
    }

    long latest(AEKey key) {
        return latest.getLong(key);
    }

    long changesFor(AEKey key) {
        return changes.stream().filter(c -> c.key().equals(key)).count();
    }

    @Override
    public void onSaveChanges(SpikeGrid owner, IGridNode node) {
    }

    @Override
    public void onStateChanged(SpikeGrid owner, IGridNode node, State state) {
        nodeEvents.add(new NodeEvent(clock.getAsLong(), "state " + state + " active=" + node.isActive()
                + " powered=" + node.isPowered()));
    }

    @Override
    public void onGridChanged(SpikeGrid owner, IGridNode node) {
        nodeEvents.add(new NodeEvent(clock.getAsLong(), "gridChanged size=" + node.getGrid().size()));
    }
}
