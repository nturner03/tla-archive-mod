package io.github.nturner03.tlaarchive.reconstruction;

import io.github.nturner03.tlaarchive.TlaArchive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Every reconstruction operation, keyed by operation id, in the overworld's SavedData
 * ({@code data/tla_archive_reconstruction.dat}). It is ordinary SavedData, saved with the world; the hard-crash window
 * between it and the storages is accepted (owner ruling 4).
 *
 * <p>Committed operations stay as the record that a team's reconstruction is complete. Nothing is pruned yet.
 */
public final class ReconstructionJournal extends SavedData {
    static final String NAME = TlaArchive.MOD_ID + "_reconstruction";
    private static final SavedData.Factory<ReconstructionJournal> FACTORY =
            new SavedData.Factory<>(ReconstructionJournal::new, ReconstructionJournal::load);

    private final Map<UUID, ReconstructionOperation> operations = new LinkedHashMap<>();
    /** Operations whose keys can't be read back. Saved again unchanged so their stock is never dropped. */
    private final List<CompoundTag> unreadable = new ArrayList<>();

    public static ReconstructionJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Optional<ReconstructionOperation> find(UUID id) {
        return Optional.ofNullable(operations.get(id));
    }

    void add(ReconstructionOperation op) {
        operations.put(op.id(), op);
        setDirty();
    }

    /** Whether any operation still holds stock or work, so idle controllers can skip the lookup. */
    boolean hasPending() {
        for (var op : operations.values()) {
            if (!op.state().terminal()) {
                return true;
            }
        }
        return false;
    }

    /** The controller's operations that are not terminal. */
    public List<ReconstructionOperation> pendingAt(ResourceKey<Level> dimension, BlockPos pos) {
        var out = new ArrayList<ReconstructionOperation>();
        for (var op : operations.values()) {
            if (!op.state().terminal() && op.isFor(dimension, pos)) {
                out.add(op);
            }
        }
        return out;
    }

    /** The team's operations that are not terminal, including overflow still to go back. */
    public List<ReconstructionOperation> pendingFor(UUID team) {
        var out = new ArrayList<ReconstructionOperation>();
        for (var op : operations.values()) {
            if (!op.state().terminal() && op.team().equals(team)) {
                out.add(op);
            }
        }
        return out;
    }

    /** The team's committed operation, if its reconstruction is complete. */
    public Optional<ReconstructionOperation> completedFor(UUID team) {
        for (var op : operations.values()) {
            if (op.state() == ReconstructionOperation.State.COMMITTED && op.team().equals(team)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    /** Every committed operation, oldest first. */
    public List<ReconstructionOperation> committed() {
        return operations.values().stream()
                .filter(op -> op.state() == ReconstructionOperation.State.COMMITTED).toList();
    }

    /** Stops the team's running operations (owner ruling 7e). They roll back when their controller is next active. */
    int abortTeam(UUID team, String reason) {
        int aborted = 0;
        for (var op : operations.values()) {
            if (op.team().equals(team) && op.abort(reason)) {
                aborted++;
                TlaArchive.LOGGER.info("Reconstruction {} for team {} aborted: {}", op.id(), team, reason);
            }
        }
        if (aborted > 0) {
            setDirty();
        }
        return aborted;
    }

    /**
     * After a load, every operation still extracting or staged is rolled back, never committed (owner ruling 3). The
     * rollback itself happens when the operation's controller is next active; until then its stock stays here.
     */
    public int recoverAfterLoad() {
        int recovered = 0;
        for (var op : operations.values()) {
            if (recover(op)) {
                recovered++;
            }
        }
        if (recovered > 0) {
            setDirty();
        }
        return recovered;
    }

    private static boolean recover(ReconstructionOperation op) {
        var before = op.state();
        if (op.abort("interrupted by a server restart")) {
            TlaArchive.LOGGER.warn("Reconstruction {} was {} when the world loaded; rolling it back ({} items staged)",
                    op.id(), before, op.stagedTotal());
            return true;
        }
        return false;
    }

    /**
     * Stands in for a restart for one operation: round-trips it through NBT, drops the old object and recovers the
     * reloaded one. Other operations (parallel gametests) are left alone.
     */
    @VisibleForTesting
    public ReconstructionOperation reloadForTesting(UUID id, HolderLookup.Provider registries) {
        var tag = operations.get(id).save(registries).copy();
        operations.remove(id);
        var reloaded = ReconstructionOperation.load(tag, registries).orElseThrow();
        operations.put(id, reloaded);
        recover(reloaded);
        setDirty();
        return reloaded;
    }

    // --- Persistence ---

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        operations.values().forEach(op -> list.add(op.save(registries)));
        list.addAll(unreadable);
        tag.put("operations", list);
        return tag;
    }

    private static ReconstructionJournal load(CompoundTag tag, HolderLookup.Provider registries) {
        var journal = new ReconstructionJournal();
        var list = tag.getList("operations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var opTag = list.getCompound(i);
            var op = ReconstructionOperation.load(opTag, registries);
            if (op.isPresent()) {
                journal.operations.put(op.get().id(), op.get());
            } else {
                TlaArchive.LOGGER.error("Reconstruction journal entry can't be read (a key's mod may be missing); "
                        + "keeping it unchanged: {}", opTag);
                journal.unreadable.add(opTag);
            }
        }
        return journal;
    }
}
