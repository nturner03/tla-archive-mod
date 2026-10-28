package io.github.nturner03.tlaarchive.manifest;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;

/**
 * Maps AE2 item keys to the manifest entries they satisfy. Lookups go through each rule's candidate items, so the cost
 * of counting an entry grows with the stored variants of those items, not with the size of the network.
 */
public final class ManifestMatcher {
    private final ArchiveManifest manifest;
    private final Map<Item, List<ManifestEntry>> entriesByItem = new HashMap<>();

    public ManifestMatcher(ArchiveManifest manifest) {
        this.manifest = manifest;
        for (var entry : manifest.entries()) {
            for (var item : entry.rule().candidateItems()) {
                entriesByItem.computeIfAbsent(item, i -> new ArrayList<>()).add(entry);
            }
        }
    }

    public ArchiveManifest manifest() {
        return manifest;
    }

    /** Every entry {@code key} satisfies, in manifest order. A multi-enchantment book satisfies several. */
    public List<ManifestEntry> entriesSatisfiedBy(AEItemKey key) {
        var candidates = entriesByItem.get(key.getItem());
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream().filter(entry -> entry.rule().matches(key)).toList();
    }

    /** The total amount in {@code stock} of keys that satisfy {@code entry}. Saturates at {@link Long#MAX_VALUE}. */
    public static long count(ManifestEntry entry, KeyCounter stock) {
        long total = 0;
        for (var item : entry.rule().candidateItems()) {
            // IGNORE_ALL returns every stored variant of the item, whatever its data components.
            for (var stored : stock.findFuzzy(AEItemKey.of(item), FuzzyMode.IGNORE_ALL)) {
                if (stored.getKey() instanceof AEItemKey key && entry.rule().matches(key)) {
                    long amount = stored.getLongValue();
                    total = amount > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + amount;
                }
            }
        }
        return total;
    }
}
