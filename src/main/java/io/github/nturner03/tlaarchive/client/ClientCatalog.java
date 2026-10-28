package io.github.nturner03.tlaarchive.client;

import io.github.nturner03.tlaarchive.catalog.CatalogEntry;
import io.github.nturner03.tlaarchive.catalog.CatalogMenu;
import io.github.nturner03.tlaarchive.catalog.CatalogPayloads;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of the catalog data, filled by {@link CatalogPayloads} on the client's main thread. It keeps the
 * manifest until the player disconnects and the counts of the open catalog menu only. The screen polls
 * {@link #version()} to notice changes.
 */
public final class ClientCatalog {
    @Nullable
    private static CatalogPayloads.ManifestHeader header;
    private static CatalogEntry @Nullable [] entries;
    private static int received;
    @Nullable
    private static CatalogPayloads.Counts counts;
    private static int version;

    private ClientCatalog() {
    }

    public static void onHeader(CatalogPayloads.ManifestHeader payload) {
        header = payload;
        entries = new CatalogEntry[payload.entryCount()];
        received = 0;
        version++;
    }

    public static void onEntries(CatalogPayloads.ManifestEntries payload) {
        if (header == null || entries == null || payload.generation() != header.generation()
                || payload.offset() < 0 || payload.offset() + payload.entries().size() > entries.length) {
            return;
        }
        for (int i = 0; i < payload.entries().size(); i++) {
            if (entries[payload.offset() + i] == null) {
                received++;
            }
            entries[payload.offset() + i] = payload.entries().get(i);
        }
        version++;
    }

    public static void onCounts(CatalogPayloads.Counts payload, Player player) {
        if (player.containerMenu instanceof CatalogMenu menu && menu.containerId == payload.containerId()) {
            counts = payload;
            version++;
        }
    }

    /** The catalog closed: its counts are no longer updated, so don't keep them. */
    public static void clearCounts() {
        counts = null;
        version++;
    }

    public static void clear() {
        header = null;
        entries = null;
        received = 0;
        counts = null;
        version++;
    }

    /** Changes whenever anything here changes. */
    public static int version() {
        return version;
    }

    public static Optional<CatalogPayloads.ManifestHeader> header() {
        return Optional.ofNullable(header);
    }

    /** Every entry of the current manifest, once all of them have arrived. */
    public static Optional<List<CatalogEntry>> entries() {
        if (entries == null || received < entries.length) {
            return Optional.empty();
        }
        return Optional.of(Arrays.asList(entries));
    }

    /** The latest counts for menu {@code containerId}, if they belong to the manifest the client holds. */
    public static Optional<CatalogPayloads.Counts> counts(int containerId) {
        if (counts == null || counts.containerId() != containerId || header == null
                || counts.generation() != header.generation()) {
            return Optional.empty();
        }
        return Optional.of(counts);
    }

    /** The latest status lines for menu {@code containerId}, even while the manifest is still arriving. */
    public static Optional<CatalogPayloads.Counts> latest(int containerId) {
        return counts != null && counts.containerId() == containerId ? Optional.of(counts) : Optional.empty();
    }
}
