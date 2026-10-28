package io.github.nturner03.tlaarchive.spike.observe;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;

/**
 * SPIKE (throwaway). An honest in-memory storage holding many distinct keys, so a test network can carry thousands of
 * key types (a 1k cell holds at most 63). Only takes back keys it already holds, so it does not swallow unrelated
 * inserts.
 */
final class BulkStorage implements MEStorage {
    final KeyCounter stock = new KeyCounter();

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (stock.get(what) == 0) {
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
        if (mode == Actionable.MODULATE && available > 0) {
            stock.remove(what, available);
        }
        return available;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        out.addAll(stock);
    }

    @Override
    public Component getDescription() {
        return Component.literal("spike bulk storage");
    }
}
