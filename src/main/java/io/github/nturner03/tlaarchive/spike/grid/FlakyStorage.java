package io.github.nturner03.tlaarchive.spike.grid;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * SPIKE (throwaway). A storage whose simulated extraction is optimistic: a real extraction removes only half of what
 * the simulation promised. Stands in for third-party inventories whose simulate path disagrees with their real one.
 */
final class FlakyStorage implements MEStorage {
    private final KeyCounter stock = new KeyCounter();
    private final Set<AEKey> seeded = new HashSet<>();

    void seed(AEKey what, long amount) {
        seeded.add(what);
        stock.add(what, amount);
    }

    long get(AEKey what) {
        return stock.get(what);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        // Only takes back keys it was seeded with, so it does not swallow unrelated network inserts.
        if (!seeded.contains(what)) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            stock.add(what, amount);
        }
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        long available = Math.min(amount, stock.get(what));
        if (mode == Actionable.SIMULATE) {
            return available;
        }
        long removed = available / 2;
        stock.remove(what, removed);
        return removed;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        out.addAll(stock);
    }

    @Override
    public Component getDescription() {
        return Component.literal("spike flaky storage");
    }
}
