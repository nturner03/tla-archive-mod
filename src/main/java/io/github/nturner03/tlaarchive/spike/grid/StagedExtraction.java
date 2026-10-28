package io.github.nturner03.tlaarchive.spike.grid;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * SPIKE (throwaway). A journalled multi-key extraction: AE2 has no atomic multi-key extract, so required stock is
 * moved key by key into a staging buffer owned by this record, and either committed (consumed) or compensated (put
 * back) as a whole. The record is serialisable so that an interrupted operation can be recovered after a restart.
 *
 * <p>The staging buffer is the only place staged stock exists between extraction and commit/rollback, so it must be
 * persisted (marked dirty) after every step. Production code would keep it in world SavedData.
 */
final class StagedExtraction {
    enum State {
        /** Extracting keys into the buffer; may span several steps (ticks). */
        EXTRACTING,
        /** Every planned amount is in the buffer; waiting for the commit decision. */
        STAGED,
        /** The buffer was consumed. Terminal. */
        COMMITTED,
        /** Putting the buffer back; anything that did not fit stays in the buffer. */
        ROLLING_BACK,
        /** The buffer is empty again. Terminal. */
        ROLLED_BACK
    }

    final UUID id;
    private final Map<AEKey, Long> plan;
    private final KeyCounter staged = new KeyCounter();
    private State state = State.EXTRACTING;
    private int nextKey;

    StagedExtraction(UUID id, Map<AEKey, Long> plan) {
        this.id = id;
        this.plan = new LinkedHashMap<>(plan);
    }

    State state() {
        return state;
    }

    KeyCounter staged() {
        return staged;
    }

    /** Simulates every planned key. Returns the first key that is short, or null if everything is available now. */
    @Nullable
    AEKey validate(MEStorage storage, IActionSource src) {
        for (var e : plan.entrySet()) {
            if (storage.extract(e.getKey(), e.getValue(), Actionable.SIMULATE, src) < e.getValue()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Extracts up to {@code maxKeys} more planned keys for real. A short extraction switches to ROLLING_BACK (the
     * caller then calls {@link #rollback}); a completed plan switches to STAGED.
     */
    void step(MEStorage storage, IActionSource src, int maxKeys) {
        if (state != State.EXTRACTING) {
            return;
        }
        var keys = plan.keySet().toArray(new AEKey[0]);
        for (int n = 0; n < maxKeys && nextKey < keys.length; n++, nextKey++) {
            var key = keys[nextKey];
            long wanted = plan.get(key);
            long got = storage.extract(key, wanted, Actionable.MODULATE, src);
            staged.add(key, got);
            if (got < wanted) {
                state = State.ROLLING_BACK;
                return;
            }
        }
        if (nextKey == keys.length) {
            state = State.STAGED;
        }
    }

    /** Puts staged stock back. Whatever does not fit stays staged (and persisted) for a later retry. */
    void rollback(MEStorage storage, IActionSource src) {
        if (state == State.COMMITTED || state == State.ROLLED_BACK) {
            return;
        }
        state = State.ROLLING_BACK;
        var remaining = new KeyCounter();
        for (var e : staged) {
            long inserted = storage.insert(e.getKey(), e.getLongValue(), Actionable.MODULATE, src);
            remaining.add(e.getKey(), e.getLongValue() - inserted);
        }
        remaining.removeZeros();
        staged.clear();
        staged.addAll(remaining);
        if (staged.isEmpty()) {
            state = State.ROLLED_BACK;
        }
    }

    /** Consumes the staged stock. Only valid once everything is staged. */
    void commit() {
        if (state != State.STAGED) {
            throw new IllegalStateException("cannot commit in state " + state);
        }
        staged.clear();
        state = State.COMMITTED;
    }

    /**
     * Recovery after a load. An operation interrupted while extracting or rolling back is rolled back. A fully staged
     * operation is rolled forward only if {@code commitStaged} (an owner ruling: "interrupted final consumption").
     */
    void recover(MEStorage storage, IActionSource src, boolean commitStaged) {
        switch (state) {
            case EXTRACTING, ROLLING_BACK -> rollback(storage, src);
            case STAGED -> {
                if (commitStaged) {
                    commit();
                } else {
                    rollback(storage, src);
                }
            }
            case COMMITTED, ROLLED_BACK -> {
            }
        }
    }

    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("state", state.name());
        tag.putInt("nextKey", nextKey);
        tag.put("plan", saveCounts(plan, registries));
        var stagedMap = new LinkedHashMap<AEKey, Long>();
        for (var e : staged) {
            stagedMap.put(e.getKey(), e.getLongValue());
        }
        tag.put("staged", saveCounts(stagedMap, registries));
        return tag;
    }

    static StagedExtraction load(CompoundTag tag, HolderLookup.Provider registries) {
        var op = new StagedExtraction(tag.getUUID("id"), loadCounts(tag.getList("plan", Tag.TAG_COMPOUND), registries));
        op.state = State.valueOf(tag.getString("state"));
        op.nextKey = tag.getInt("nextKey");
        loadCounts(tag.getList("staged", Tag.TAG_COMPOUND), registries).forEach(op.staged::add);
        return op;
    }

    private static ListTag saveCounts(Map<AEKey, Long> counts, HolderLookup.Provider registries) {
        var list = new ListTag();
        counts.forEach((key, amount) -> {
            var entry = new CompoundTag();
            entry.put("key", key.toTagGeneric(registries));
            entry.putLong("amount", amount);
            list.add(entry);
        });
        return list;
    }

    private static Map<AEKey, Long> loadCounts(ListTag list, HolderLookup.Provider registries) {
        var counts = new LinkedHashMap<AEKey, Long>();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            counts.put(AEKey.fromTagGeneric(registries, entry.getCompound("key")), entry.getLong("amount"));
        }
        return counts;
    }
}
