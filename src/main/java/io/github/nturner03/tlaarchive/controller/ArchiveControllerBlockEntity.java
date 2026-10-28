package io.github.nturner03.tlaarchive.controller;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import io.github.nturner03.tlaarchive.reconstruction.Reconstruction;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The archive controller: an AE2 grid node that counts the stock of the network it is physically on against the active
 * manifest. How it reads stock and when its counts are live or stale: {@code docs/architecture.md}.
 *
 * <p>Counts come from {@code getCachedInventory()} in sweeps. A sweep starts at most every
 * {@link #SWEEP_INTERVAL_TICKS} ticks and counts at most {@link #MAX_ENTRIES_PER_TICK} entries per tick; it is
 * published only when it completes on an active node, so zeros from an unpowered grid never replace the last
 * reading (owner ruling 1). The saved counts are a display cache, never earned credit.
 */
public final class ArchiveControllerBlockEntity extends BlockEntity implements IInWorldGridNodeHost, IActionHost {
    /** Ticks between sweep starts. Each sweep reads the grid's cache, which AE2 rebuilds lazily when it is stale. */
    public static final int SWEEP_INTERVAL_TICKS = 20;
    /** Most manifest entries counted in one tick; a larger manifest's sweep spans several ticks. */
    public static final int MAX_ENTRIES_PER_TICK = 64;

    private static final IGridNodeListener<ArchiveControllerBlockEntity> NODE_LISTENER = new IGridNodeListener<>() {
        @Override
        public void onSaveChanges(ArchiveControllerBlockEntity owner, IGridNode node) {
            owner.setChanged();
        }

        @Override
        public void onStateChanged(ArchiveControllerBlockEntity owner, IGridNode node, State state) {
            if (!node.isActive()) {
                owner.interrupt();
            }
        }

        @Override
        public void onGridChanged(ArchiveControllerBlockEntity owner, IGridNode node) {
            // A split can move the controller to a new, empty grid without any other signal.
            owner.interrupt();
        }
    };

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setInWorldNode(true)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            .setTagName("archive_node")
            .setVisualRepresentation(ModRegistries.ARCHIVE_CONTROLLER_ITEM.get());

    @Nullable
    private UUID ownerTeam;
    private boolean frozen;

    // The last complete reading (saved).
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private String readingManifest = "";
    private long readingGameTime = -1;

    // Runtime only: whether the last reading was taken from the grid the node is on now, without interruption since.
    private boolean confirmed;
    // Runtime only: another team's controller on the same grid, checked every tick.
    @Nullable
    private BlockPos conflictWith;
    private int confirmedGeneration;
    @Nullable
    private Sweep sweep;
    private long nextSweepTime;

    public ArchiveControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.ARCHIVE_CONTROLLER_BE.get(), pos, state);
    }

    // --- AE2 node lifecycle (the pattern GridHelper.onFirstTick documents) ---

    @Override
    @Nullable
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    /** The controller extracts as a machine through its own node ({@code IActionSource.ofMachine}). */
    @Override
    @Nullable
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    /** The grid the node is on, if the node is active (powered and connected). */
    public Optional<IGrid> activeGrid() {
        var node = mainNode.getNode();
        return node != null && node.isActive() ? Optional.of(node.getGrid()) : Optional.empty();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, be -> be.mainNode.create(be.getLevel(), be.getBlockPos()));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Reconstruction.controllerGone(this);
        mainNode.destroy();
        interrupt();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        Reconstruction.controllerGone(this);
        mainNode.destroy();
        interrupt();
    }

    // --- Ownership (owner rulings 2, 7a–7d) ---

    /** Records the placer's effective team. Ownership is set only here; nothing transfers it later. */
    public void claimFor(ServerPlayer placer) {
        mainNode.setOwningPlayer(placer);
        setOwnerTeam(TeamAccess.teamOf(placer).orElse(null));
        if (ownerTeam != null) {
            long gameTime = placer.serverLevel().getGameTime();
            ArchiveTeams.get(placer.server).claim(ownerTeam, gameTime);
            ActiveManifest.matcher().ifPresent(matcher ->
                    ArchiveTeams.get(placer.server).seeFirst(ownerTeam, matcher.manifest(), gameTime));
        }
    }

    @VisibleForTesting
    public void setOwnerTeam(@Nullable UUID teamId) {
        ownerTeam = teamId;
        frozen = false;
        setChanged();
    }

    public Optional<UUID> ownerTeam() {
        return Optional.ofNullable(ownerTeam);
    }

    /** True only for a player whose current effective team is the owner. Allies and former members don't match. */
    public boolean isOwnedBy(ServerPlayer player) {
        return ownerTeam != null && !frozen && TeamAccess.teamOf(player).filter(ownerTeam::equals).isPresent();
    }

    /** Freezes the controller if its team was deleted (ruling 7d). Lazy, so it also catches missed events. */
    public void checkOwnerTeam() {
        if (ownerTeam == null || frozen) {
            return;
        }
        if (TeamAccess.teamExists(ownerTeam).filter(exists -> !exists).isPresent()) {
            frozen = true;
            interrupt();
            setChanged();
            TlaArchive.LOGGER.info("Archive controller at {} frozen: owner team {} no longer exists",
                    getBlockPos(), ownerTeam);
        }
    }

    // --- Counting ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, ArchiveControllerBlockEntity be) {
        be.tick(level.getGameTime());
    }

    private void tick(long gameTime) {
        // Before the frozen check: a frozen controller still puts its team's stock back.
        Reconstruction.tick(this, gameTime);
        if (frozen) {
            return;
        }
        var node = mainNode.getNode();
        var matcher = ActiveManifest.matcher().orElse(null);
        if (node == null || !node.isActive() || matcher == null) {
            conflictWith = null;
            interrupt();
            return;
        }
        conflictWith = rivalOn(node.getGrid());
        if (conflictWith != null) {
            interrupt();
            return;
        }
        int generation = ActiveManifest.generation();
        if (sweep != null && (sweep.grid != node.getGrid() || sweep.generation != generation)
                || confirmed && confirmedGeneration != generation) {
            interrupt();
        }
        if (sweep == null) {
            if (confirmed && gameTime < nextSweepTime) {
                return;
            }
            checkOwnerTeam();
            if (frozen) {
                return;
            }
            sweep = new Sweep(matcher, node.getGrid(), generation);
            nextSweepTime = gameTime + SWEEP_INTERVAL_TICKS;
        }
        if (sweep.step(MAX_ENTRIES_PER_TICK)) {
            publish(sweep, gameTime);
            sweep = null;
        }
    }

    private void publish(Sweep done, long gameTime) {
        var entries = done.matcher.manifest().entries();
        var reading = new LinkedHashMap<String, Long>();
        for (int i = 0; i < entries.size(); i++) {
            reading.put(entries.get(i).key(), done.counts[i]);
        }
        var label = done.matcher.manifest().label();
        boolean changed = !reading.equals(counts) || !label.equals(readingManifest);
        counts.clear();
        counts.putAll(reading);
        readingManifest = label;
        readingGameTime = gameTime;
        confirmed = true;
        confirmedGeneration = done.generation;
        if (changed) {
            setChanged();
        }
    }

    /**
     * The position of a controller of another team on {@code grid}, or null if every owned, unfrozen controller there
     * belongs to one team (owner ruling 2026-10-28). Controllers with no owner team or a deleted one don't conflict,
     * but a controller with no owner team still sees a conflict between two others.
     */
    @Nullable
    private BlockPos rivalOn(IGrid grid) {
        UUID firstTeam = null;
        BlockPos firstPos = null;
        for (var other : grid.getMachines(ArchiveControllerBlockEntity.class)) {
            if (other.frozen || other.ownerTeam == null) {
                continue;
            }
            if (firstTeam == null) {
                firstTeam = other.ownerTeam;
                firstPos = other.getBlockPos();
            } else if (!firstTeam.equals(other.ownerTeam)) {
                // Name a controller that isn't this one's team, so the message points somewhere useful.
                return other.ownerTeam.equals(ownerTeam) ? firstPos : other.getBlockPos();
            }
        }
        return null;
    }

    /** Where the conflicting controller is, while the state is {@link ArchiveState#CONFLICT}. */
    public Optional<BlockPos> conflictWith() {
        return Optional.ofNullable(conflictWith);
    }

    /** Drops any sweep in progress and marks the counts unconfirmed; the counts themselves are kept. */
    private void interrupt() {
        sweep = null;
        confirmed = false;
    }

    // --- What the controller shows ---

    public ArchiveState state() {
        if (frozen) {
            return ArchiveState.FROZEN;
        }
        if (conflictWith != null) {
            return ArchiveState.CONFLICT;
        }
        var node = mainNode.getNode();
        if (confirmed && node != null && node.isActive()) {
            return ArchiveState.LIVE;
        }
        return readingGameTime >= 0 ? ArchiveState.STALE : ArchiveState.UNAVAILABLE;
    }

    /** The last reading's count for a manifest entry key, if that reading included it. */
    public OptionalLong count(String entryKey) {
        var count = counts.get(entryKey);
        return count == null ? OptionalLong.empty() : OptionalLong.of(count);
    }

    /** Game time of the last complete reading, or -1 if there never was one. */
    public long readingGameTime() {
        return readingGameTime;
    }

    /** Label of the manifest the last reading was taken against. */
    public String readingManifest() {
        return readingManifest;
    }

    /**
     * Why the controller's state forbids reconstruction, or empty if the state allows it. {@link Reconstruction#blocker}
     * adds the test flag, the player's team and the journal on top of this.
     */
    public Optional<String> reconstructionBlocker() {
        return switch (state()) {
            case FROZEN -> Optional.of("the owning team no longer exists");
            case CONFLICT -> Optional.of("another team's archive controller is on this network");
            case UNAVAILABLE -> Optional.of("no reading of the network yet");
            case STALE -> Optional.of("counts are stale until the network is active and read again");
            case LIVE -> ownerTeam == null ? Optional.of("the controller has no owner team") : Optional.empty();
        };
    }

    // --- Persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        mainNode.saveToNBT(tag);
        if (ownerTeam != null) {
            tag.putUUID("owner_team", ownerTeam);
        }
        tag.putBoolean("frozen", frozen);
        if (readingGameTime >= 0) {
            var reading = new CompoundTag();
            reading.putString("manifest", readingManifest);
            reading.putLong("game_time", readingGameTime);
            var countsTag = new CompoundTag();
            counts.forEach(countsTag::putLong);
            reading.put("counts", countsTag);
            tag.put("reading", reading);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mainNode.loadFromNBT(tag);
        ownerTeam = tag.hasUUID("owner_team") ? tag.getUUID("owner_team") : null;
        frozen = tag.getBoolean("frozen");
        counts.clear();
        readingManifest = "";
        readingGameTime = -1;
        if (tag.contains("reading", CompoundTag.TAG_COMPOUND)) {
            var reading = tag.getCompound("reading");
            readingManifest = reading.getString("manifest");
            readingGameTime = reading.getLong("game_time");
            var countsTag = reading.getCompound("counts");
            for (var key : countsTag.getAllKeys()) {
                counts.put(key, countsTag.getLong(key));
            }
        }
        // A loaded reading is never live: it has to be confirmed against the grid again.
        interrupt();
    }

    /** One pass over the manifest against one grid's cached inventory. */
    private static final class Sweep {
        final ManifestMatcher matcher;
        final IGrid grid;
        final int generation;
        final long[] counts;
        int next;

        Sweep(ManifestMatcher matcher, IGrid grid, int generation) {
            this.matcher = matcher;
            this.grid = grid;
            this.generation = generation;
            this.counts = new long[matcher.manifest().entries().size()];
        }

        /** Counts up to {@code budget} entries; returns true once every entry is counted. */
        boolean step(int budget) {
            var entries = matcher.manifest().entries();
            var stock = grid.getStorageService().getCachedInventory();
            int end = Math.min(entries.size(), next + budget);
            for (; next < end; next++) {
                counts[next] = ManifestMatcher.count(entries.get(next), stock);
            }
            return next >= entries.size();
        }
    }
}
