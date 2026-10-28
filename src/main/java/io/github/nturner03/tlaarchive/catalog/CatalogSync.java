package io.github.nturner03.tlaarchive.catalog;

import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.controller.ControllerReadout;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Sends one open catalog its data. The manifest goes to a player once per manifest generation (the client keeps it
 * until it disconnects); counts go when the menu opens, when the controller's state or the manifest changes, and
 * every {@link #COUNTS_INTERVAL_TICKS} ticks, which also refreshes the reading's age.
 */
public final class CatalogSync {
    /** Entries per {@link CatalogPayloads.ManifestEntries}; keeps each payload far below the 1 MiB limit. */
    public static final int ENTRIES_PER_PAYLOAD = 256;
    public static final int COUNTS_INTERVAL_TICKS = 20;

    /** The manifest generation each online player was last sent. */
    private static final Map<UUID, Integer> SENT_GENERATION = new ConcurrentHashMap<>();

    private final CatalogMenu menu;
    private final ServerPlayer viewer;
    private final ArchiveControllerBlockEntity controller;
    @Nullable
    private ArchiveState sentState;
    private int sentGeneration = -1;
    private long nextCountsTime;

    CatalogSync(CatalogMenu menu, ServerPlayer viewer, ArchiveControllerBlockEntity controller) {
        this.menu = menu;
        this.viewer = viewer;
        this.controller = controller;
    }

    /** The player left: the next catalog they open gets the manifest again. */
    public static void forget(UUID player) {
        SENT_GENERATION.remove(player);
    }

    void tick() {
        // The server checks stillValid after broadcastChanges, so check it here first: nothing reaches a player who
        // just left the owner team.
        if (!menu.stillValid(viewer)) {
            return;
        }
        int generation = ActiveManifest.generation();
        Integer sent = SENT_GENERATION.get(viewer.getUUID());
        if (sent == null || sent != generation) {
            manifestPayloads(generation, ActiveManifest.matcher())
                    .forEach(payload -> PacketDistributor.sendToPlayer(viewer, payload));
            SENT_GENERATION.put(viewer.getUUID(), generation);
        }
        long gameTime = viewer.serverLevel().getGameTime();
        var state = controller.state();
        if (state != sentState || generation != sentGeneration || gameTime >= nextCountsTime) {
            PacketDistributor.sendToPlayer(viewer, counts(menu.containerId, controller, gameTime));
            sentState = state;
            sentGeneration = generation;
            nextCountsTime = gameTime + COUNTS_INTERVAL_TICKS;
        }
    }

    /** The header, then the entries in chunks of {@link #ENTRIES_PER_PAYLOAD}. */
    public static List<CustomPacketPayload> manifestPayloads(int generation, Optional<ManifestMatcher> matcher) {
        var out = new ArrayList<CustomPacketPayload>();
        if (matcher.isEmpty()) {
            out.add(new CatalogPayloads.ManifestHeader(generation, false, "", List.of(), 0));
            return out;
        }
        var manifest = matcher.get().manifest();
        var entries = manifest.entries().stream().map(CatalogEntry::of).toList();
        out.add(new CatalogPayloads.ManifestHeader(generation, true, manifest.label(), manifest.categoryOrder(),
                entries.size()));
        for (int offset = 0; offset < entries.size(); offset += ENTRIES_PER_PAYLOAD) {
            out.add(new CatalogPayloads.ManifestEntries(generation, offset,
                    entries.subList(offset, Math.min(entries.size(), offset + ENTRIES_PER_PAYLOAD))));
        }
        return out;
    }

    /** The controller's last reading in the active manifest's entry order, with its status and milestones. */
    public static CatalogPayloads.Counts counts(int containerId, ArchiveControllerBlockEntity controller,
            long gameTime) {
        var entries = ActiveManifest.matcher().map(m -> m.manifest().entries()).orElse(List.of());
        var counts = new long[entries.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = controller.count(entries.get(i).key()).orElse(-1);
        }
        return new CatalogPayloads.Counts(containerId, ActiveManifest.generation(), controller.state(), counts,
                ControllerReadout.status(controller, gameTime), ControllerReadout.reconstructionLines(controller));
    }
}
