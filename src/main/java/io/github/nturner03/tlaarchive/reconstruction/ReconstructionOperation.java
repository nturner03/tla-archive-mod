package io.github.nturner03.tlaarchive.reconstruction;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * One journalled reconstruction. AE2 has no atomic multi-key extraction ({@code docs/spikes/grid-stock-and-extraction.md}),
 * so the planned stock is moved key by key into a staging buffer this record owns, and is then either committed
 * (consumed) or put back as a whole. Reviewed port of the spike's {@code StagedExtraction}.
 *
 * <p>The staging buffer is the only place staged stock exists between extraction and commit or rollback, so the
 * journal is marked dirty after every change. Stock that won't go back stays in the buffer, still saved, as visible
 * overflow; it is never voided (owner ruling 3).
 */
public final class ReconstructionOperation {
    public enum State {
        /** Extracting planned keys into the buffer; spans several ticks only under the debug pacing. */
        EXTRACTING,
        /** Every planned amount is in the buffer; commits now, or after the debug hold. */
        STAGED,
        /** The buffer was consumed and reconstruction is complete. Terminal. */
        COMMITTED,
        /** Putting the buffer back; whatever doesn't fit stays in the buffer as overflow. */
        ROLLING_BACK,
        /** The buffer is empty again and nothing was consumed. Terminal. */
        ROLLED_BACK;

        public boolean terminal() {
            return this == COMMITTED || this == ROLLED_BACK;
        }
    }

    private final UUID id;
    private final UUID team;
    private final UUID startedBy;
    private final ResourceKey<Level> dimension;
    private final BlockPos controllerPos;
    private final String manifestLabel;
    private final Map<AEKey, Long> plan;
    private final int keysPerTick;
    private final long startedAt;
    private final KeyCounter staged = new KeyCounter();
    private State state = State.EXTRACTING;
    private int nextKey;
    private final long commitAt;
    private long finishedAt = -1;
    private String outcome = "";

    ReconstructionOperation(UUID id, UUID team, UUID startedBy, ResourceKey<Level> dimension, BlockPos controllerPos,
            String manifestLabel, Map<AEKey, Long> plan, int keysPerTick, long startedAt, long commitAt) {
        this.id = id;
        this.team = team;
        this.startedBy = startedBy;
        this.dimension = dimension;
        this.controllerPos = controllerPos.immutable();
        this.manifestLabel = manifestLabel;
        this.plan = new LinkedHashMap<>(plan);
        this.keysPerTick = keysPerTick;
        this.startedAt = startedAt;
        this.commitAt = commitAt;
    }

    public UUID id() {
        return id;
    }

    public UUID team() {
        return team;
    }

    public UUID startedBy() {
        return startedBy;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos controllerPos() {
        return controllerPos;
    }

    public String manifestLabel() {
        return manifestLabel;
    }

    public Map<AEKey, Long> plan() {
        return Collections.unmodifiableMap(plan);
    }

    public State state() {
        return state;
    }

    /** What the buffer holds now. In {@link State#ROLLING_BACK} this is the overflow still to go back. */
    public KeyCounter staged() {
        return staged;
    }

    public long stagedTotal() {
        long total = 0;
        for (var e : staged) {
            total += e.getLongValue();
        }
        return total;
    }

    public long startedAt() {
        return startedAt;
    }

    public long commitAt() {
        return commitAt;
    }

    /** Game time of the commit or the completed rollback, or -1. */
    public long finishedAt() {
        return finishedAt;
    }

    /** Why the operation rolled back, or how it ended. */
    public String outcome() {
        return outcome;
    }

    public boolean isFor(ResourceKey<Level> dimension, BlockPos pos) {
        return this.dimension.equals(dimension) && controllerPos.equals(pos);
    }

    /**
     * Extracts the next planned keys for real, trusting only the amount {@code MODULATE} returns. A short key switches
     * the operation to {@link State#ROLLING_BACK} (the caller then calls {@link #rollback}); a completed plan switches it
     * to {@link State#STAGED}.
     */
    void step(MEStorage storage, IActionSource source) {
        if (state != State.EXTRACTING) {
            return;
        }
        var keys = plan.keySet().toArray(new AEKey[0]);
        int budget = keysPerTick <= 0 ? keys.length : keysPerTick;
        for (int n = 0; n < budget && nextKey < keys.length; n++, nextKey++) {
            var key = keys[nextKey];
            long wanted = plan.get(key);
            long got = storage.extract(key, wanted, Actionable.MODULATE, source);
            staged.add(key, got);
            if (got < wanted) {
                state = State.ROLLING_BACK;
                outcome = "only " + got + " of " + wanted + " " + key.getDisplayName().getString()
                        + " could be extracted";
                nextKey++;
                return;
            }
        }
        if (nextKey >= keys.length) {
            state = State.STAGED;
        }
    }

    /** Puts staged stock back. Whatever doesn't fit stays staged (and saved) for a later attempt. */
    void rollback(MEStorage storage, IActionSource source, long gameTime) {
        if (state.terminal()) {
            return;
        }
        state = State.ROLLING_BACK;
        var remaining = new KeyCounter();
        for (var e : staged) {
            long inserted = storage.insert(e.getKey(), e.getLongValue(), Actionable.MODULATE, source);
            remaining.add(e.getKey(), e.getLongValue() - inserted);
        }
        remaining.removeZeros();
        staged.clear();
        staged.addAll(remaining);
        finishIfEmpty(gameTime);
    }

    /**
     * Moves overflow into a player's inventory, as far as it has room. Only for {@link State#ROLLING_BACK}. Returns the
     * number of items moved.
     */
    long moveOverflowTo(Inventory inventory, long gameTime) {
        if (state != State.ROLLING_BACK) {
            return 0;
        }
        long moved = 0;
        for (var key : List.copyOf(staged.keySet())) {
            if (!(key instanceof AEItemKey item)) {
                continue;
            }
            long amount = staged.get(key);
            while (amount > 0) {
                var stack = item.toStack((int) Math.min(amount, item.getMaxStackSize()));
                int before = stack.getCount();
                inventory.add(stack);
                int added = before - stack.getCount();
                if (added <= 0) {
                    break;
                }
                staged.remove(key, added);
                amount -= added;
                moved += added;
            }
        }
        staged.removeZeros();
        finishIfEmpty(gameTime);
        return moved;
    }

    /** Stops an operation that is still extracting or staged; it then rolls back. Returns whether it was stopped. */
    boolean abort(String reason) {
        if (state != State.EXTRACTING && state != State.STAGED) {
            return false;
        }
        state = State.ROLLING_BACK;
        outcome = reason;
        return true;
    }

    /** Consumes the staged stock. Only valid once everything is staged. */
    void commit(long gameTime) {
        if (state != State.STAGED) {
            throw new IllegalStateException("cannot commit reconstruction " + id + " in state " + state);
        }
        staged.clear();
        state = State.COMMITTED;
        finishedAt = gameTime;
        outcome = "committed";
    }

    private void finishIfEmpty(long gameTime) {
        if (staged.isEmpty()) {
            state = State.ROLLED_BACK;
            finishedAt = gameTime;
        }
    }

    // --- Persistence ---

    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("team", team);
        tag.putUUID("started_by", startedBy);
        tag.putString("dimension", dimension.location().toString());
        tag.put("controller_pos", NbtUtils.writeBlockPos(controllerPos));
        tag.putString("manifest", manifestLabel);
        tag.putInt("keys_per_tick", keysPerTick);
        tag.putLong("started_at", startedAt);
        tag.putLong("commit_at", commitAt);
        tag.putLong("finished_at", finishedAt);
        tag.putString("state", state.name());
        tag.putInt("next_key", nextKey);
        tag.putString("outcome", outcome);
        tag.put("plan", saveCounts(plan, registries));
        var stagedMap = new LinkedHashMap<AEKey, Long>();
        for (var e : staged) {
            stagedMap.put(e.getKey(), e.getLongValue());
        }
        tag.put("staged", saveCounts(stagedMap, registries));
        return tag;
    }

    /** Empty if any key can't be read back (for example its mod was removed); the journal then keeps the raw tag. */
    static Optional<ReconstructionOperation> load(CompoundTag tag, HolderLookup.Provider registries) {
        var plan = loadCounts(tag.getList("plan", Tag.TAG_COMPOUND), registries);
        var stagedCounts = loadCounts(tag.getList("staged", Tag.TAG_COMPOUND), registries);
        var pos = NbtUtils.readBlockPos(tag, "controller_pos");
        var dimensionId = ResourceLocation.tryParse(tag.getString("dimension"));
        if (plan == null || stagedCounts == null || pos.isEmpty() || dimensionId == null) {
            return Optional.empty();
        }
        var op = new ReconstructionOperation(tag.getUUID("id"), tag.getUUID("team"), tag.getUUID("started_by"),
                ResourceKey.create(Registries.DIMENSION, dimensionId), pos.get(), tag.getString("manifest"), plan,
                tag.getInt("keys_per_tick"), tag.getLong("started_at"), tag.getLong("commit_at"));
        op.finishedAt = tag.getLong("finished_at");
        op.state = State.valueOf(tag.getString("state"));
        op.nextKey = tag.getInt("next_key");
        op.outcome = tag.getString("outcome");
        stagedCounts.forEach(op.staged::add);
        return Optional.of(op);
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

    @Nullable
    private static Map<AEKey, Long> loadCounts(ListTag list, HolderLookup.Provider registries) {
        var counts = new LinkedHashMap<AEKey, Long>();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            AEKey key;
            try {
                key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
            } catch (RuntimeException e) {
                key = null;
            }
            if (key == null) {
                return null;
            }
            counts.merge(key, entry.getLong("amount"), Long::sum);
        }
        return counts;
    }
}
