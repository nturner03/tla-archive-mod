package io.github.nturner03.tlaarchive.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * What the catalog screen lists: the entries with their counts, filtered by search text, category and "missing only",
 * and the totals per category. Plain code so gametests can check it without a client.
 */
public final class CatalogView {
    private final List<CatalogEntry> entries;
    private final long[] counts;
    private final String[] searchText;

    /** {@code counts} by entry index, {@code -1} for unknown; null, or a different length, means all unknown. */
    public CatalogView(List<CatalogEntry> entries, long @Nullable [] counts) {
        this.entries = List.copyOf(entries);
        if (counts != null && counts.length == entries.size()) {
            this.counts = counts.clone();
        } else {
            this.counts = new long[entries.size()];
            Arrays.fill(this.counts, -1);
        }
        this.searchText = new String[entries.size()];
        for (int i = 0; i < searchText.length; i++) {
            var entry = this.entries.get(i);
            searchText[i] = (entry.displayName() + " " + entry.key()).toLowerCase(Locale.ROOT);
        }
    }

    public List<CatalogEntry> entries() {
        return entries;
    }

    public CatalogEntry entry(int index) {
        return entries.get(index);
    }

    /** The current count, or {@code -1} if unknown. */
    public long count(int index) {
        return counts[index];
    }

    public boolean ready(int index) {
        return counts[index] >= entries.get(index).quantity();
    }

    /**
     * Entry indices in manifest order that match every word of {@code query} (in the name or key, ignoring case), are
     * in {@code category} unless it is null, and are not ready if {@code missingOnly}. An unknown count is missing.
     */
    public int[] filter(String query, @Nullable String category, boolean missingOnly) {
        var words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        var out = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            if (category != null && !category.equals(entries.get(i).category())) {
                continue;
            }
            if (missingOnly && ready(i)) {
                continue;
            }
            if (matchesAll(searchText[i], words)) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean matchesAll(String text, String[] words) {
        for (var word : words) {
            if (!word.isEmpty() && !text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Ready and total entries in {@code category}, or in the whole manifest if it is null. */
    public Totals totals(@Nullable String category) {
        int ready = 0;
        int total = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (category == null || category.equals(entries.get(i).category())) {
                total++;
                if (ready(i)) {
                    ready++;
                }
            }
        }
        return new Totals(ready, total);
    }

    public record Totals(int ready, int total) {
        public int missing() {
            return total - ready;
        }
    }
}
