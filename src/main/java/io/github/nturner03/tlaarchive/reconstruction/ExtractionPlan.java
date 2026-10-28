package io.github.nturner03.tlaarchive.reconstruction;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import io.github.nturner03.tlaarchive.manifest.ManifestEntry;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual keys and amounts a reconstruction takes to cover every manifest entry (design: <i>Reconstruction and
 * consumption</i>). A key that satisfies several entries, such as a multi-enchantment book, is taken once and credited
 * to each of them. Only the required quantities are taken.
 *
 * <p>Which variant to take when several stored keys satisfy an entry is not ruled yet. Until it is, the plan prefers
 * keys that satisfy the most entries still short, then the most plentiful key, then a stable order by the key's text.
 * The plan is greedy, in manifest order, so it can miss a cover that a cleverer choice would find; it never takes more
 * than an entry needs.
 *
 * @param amounts what to extract, in extraction order
 * @param credit  per entry key, how many planned items satisfy it (can exceed the quantity for shared keys)
 */
public record ExtractionPlan(String manifestLabel, Map<AEKey, Long> amounts, Map<String, Long> credit,
        List<Shortfall> shortfalls) {

    /** An entry the stock can't cover: {@code available} of {@code entry.quantity()}. */
    public record Shortfall(ManifestEntry entry, long available) {
    }

    public ExtractionPlan {
        amounts = Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
        credit = Collections.unmodifiableMap(new LinkedHashMap<>(credit));
        shortfalls = List.copyOf(shortfalls);
    }

    /** Whether the plan covers every entry. Only a complete plan may be extracted. */
    public boolean complete() {
        return shortfalls.isEmpty();
    }

    public static ExtractionPlan build(ManifestMatcher matcher, KeyCounter stock) {
        var entries = matcher.manifest().entries();
        var credit = new LinkedHashMap<String, Long>();
        entries.forEach(entry -> credit.put(entry.key(), 0L));
        var planned = new LinkedHashMap<AEKey, Long>();
        var shortfalls = new ArrayList<Shortfall>();

        for (var entry : entries) {
            if (credit.get(entry.key()) >= entry.quantity()) {
                continue;
            }
            for (var candidate : candidates(matcher, entry, stock, planned, credit)) {
                long need = entry.quantity() - credit.get(entry.key());
                if (need <= 0) {
                    break;
                }
                long take = Math.min(need, candidate.available);
                planned.merge(candidate.key, take, Long::sum);
                for (var satisfied : matcher.entriesSatisfiedBy(candidate.key)) {
                    credit.merge(satisfied.key(), take, Long::sum);
                }
            }
            long have = credit.get(entry.key());
            if (have < entry.quantity()) {
                shortfalls.add(new Shortfall(entry, have));
            }
        }
        return new ExtractionPlan(matcher.manifest().label(), planned, credit, shortfalls);
    }

    private record Candidate(AEItemKey key, long available, int unmetEntries) {
    }

    /** Stored variants that satisfy {@code entry} and are not already fully planned, best first. */
    private static List<Candidate> candidates(ManifestMatcher matcher, ManifestEntry entry, KeyCounter stock,
            Map<AEKey, Long> planned, Map<String, Long> credit) {
        var out = new ArrayList<Candidate>();
        for (var item : entry.rule().candidateItems()) {
            for (var stored : stock.findFuzzy(AEItemKey.of(item), FuzzyMode.IGNORE_ALL)) {
                if (!(stored.getKey() instanceof AEItemKey key) || !entry.rule().matches(key)) {
                    continue;
                }
                long available = stored.getLongValue() - planned.getOrDefault(key, 0L);
                if (available <= 0) {
                    continue;
                }
                int unmet = 0;
                for (var satisfied : matcher.entriesSatisfiedBy(key)) {
                    if (credit.get(satisfied.key()) < satisfied.quantity()) {
                        unmet++;
                    }
                }
                out.add(new Candidate(key, available, unmet));
            }
        }
        out.sort(Comparator.comparingInt(Candidate::unmetEntries).reversed()
                .thenComparing(Comparator.comparingLong(Candidate::available).reversed())
                .thenComparing(candidate -> candidate.key.toString()));
        return out;
    }
}
