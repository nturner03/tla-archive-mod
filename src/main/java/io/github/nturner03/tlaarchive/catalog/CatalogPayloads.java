package io.github.nturner03.tlaarchive.catalog;

import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.client.ClientCatalog;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import io.github.nturner03.tlaarchive.team.ManifestChanges;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Server-to-client catalog data ({@code docs/architecture.md} → <i>Catalog</i>). The manifest goes once per manifest
 * generation per player, as a {@link ManifestHeader} and then the entries in {@link ManifestEntries} chunks; counts go
 * as {@link Counts}, by entry index, only while the player has the catalog open, and what changed since the team last
 * acknowledged a manifest as {@link Changes}. The only serverbound payload is {@link Acknowledge}; closing the screen
 * closes the menu.
 */
public final class CatalogPayloads {
    /** Bump when a payload's layout changes; a client with another version can't join. */
    public static final String VERSION = "2";

    private static final StreamCodec<RegistryFriendlyByteBuf, ArchiveManifest.Category> CATEGORY =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ArchiveManifest.Category::id,
                    ByteBufCodecs.STRING_UTF8, ArchiveManifest.Category::name,
                    ArchiveManifest.Category::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> COMPONENTS =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<RegistryFriendlyByteBuf, long[]> LONGS = StreamCodec.of(
            (buf, values) -> {
                buf.writeVarInt(values.length);
                for (long value : values) {
                    buf.writeLong(value);
                }
            },
            buf -> {
                var values = new long[buf.readVarInt()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = buf.readLong();
                }
                return values;
            });

    private CatalogPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TlaArchive.MOD_ID, path));
    }

    /**
     * The handlers run on the client's main thread. They only call into {@link ClientCatalog} from inside the
     * lambdas, so a dedicated server never loads it.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(VERSION);
        registrar.playToClient(ManifestHeader.TYPE, ManifestHeader.STREAM_CODEC,
                (payload, context) -> ClientCatalog.onHeader(payload));
        registrar.playToClient(ManifestEntries.TYPE, ManifestEntries.STREAM_CODEC,
                (payload, context) -> ClientCatalog.onEntries(payload));
        registrar.playToClient(Counts.TYPE, Counts.STREAM_CODEC,
                (payload, context) -> ClientCatalog.onCounts(payload, context.player()));
        registrar.playToClient(Changes.TYPE, Changes.STREAM_CODEC,
                (payload, context) -> ClientCatalog.onChanges(payload, context.player()));
        registrar.playToServer(Acknowledge.TYPE, Acknowledge.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof CatalogMenu menu
                    && menu.containerId == payload.containerId()) {
                menu.acknowledgeChanges(player);
            }
        });
    }

    /**
     * Starts a manifest snapshot. {@code present} is false when the server has no valid manifest; then the label is
     * empty and no entries follow.
     */
    public record ManifestHeader(int generation, boolean present, String label,
            List<ArchiveManifest.Category> categories, int entryCount) implements CustomPacketPayload {
        public static final Type<ManifestHeader> TYPE = payloadType("catalog_manifest_header");
        public static final StreamCodec<RegistryFriendlyByteBuf, ManifestHeader> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ManifestHeader::generation,
                        ByteBufCodecs.BOOL, ManifestHeader::present,
                        ByteBufCodecs.STRING_UTF8, ManifestHeader::label,
                        CATEGORY.apply(ByteBufCodecs.list()), ManifestHeader::categories,
                        ByteBufCodecs.VAR_INT, ManifestHeader::entryCount,
                        ManifestHeader::new);

        @Override
        public Type<ManifestHeader> type() {
            return TYPE;
        }
    }

    /** Entries {@code offset} to {@code offset + entries.size() - 1} of the snapshot {@code generation}. */
    public record ManifestEntries(int generation, int offset, List<CatalogEntry> entries)
            implements CustomPacketPayload {
        public static final Type<ManifestEntries> TYPE = payloadType("catalog_manifest_entries");
        public static final StreamCodec<RegistryFriendlyByteBuf, ManifestEntries> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ManifestEntries::generation,
                        ByteBufCodecs.VAR_INT, ManifestEntries::offset,
                        CatalogEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ManifestEntries::entries,
                        ManifestEntries::new);

        @Override
        public Type<ManifestEntries> type() {
            return TYPE;
        }
    }

    /**
     * What changed since the viewer's team last acknowledged a manifest, for menu {@code containerId}; empty when there
     * is nothing to compare (no manifest, or no owner team).
     */
    public record Changes(int containerId, Optional<ManifestChanges> changes) implements CustomPacketPayload {
        public static final Type<Changes> TYPE = payloadType("catalog_changes");
        public static final StreamCodec<RegistryFriendlyByteBuf, Changes> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Changes::containerId,
                ByteBufCodecs.optional(ManifestChanges.STREAM_CODEC), Changes::changes,
                Changes::new);

        @Override
        public Type<Changes> type() {
            return TYPE;
        }
    }

    /** Client to server: the viewer acknowledges the changes shown in menu {@code containerId} for their team. */
    public record Acknowledge(int containerId) implements CustomPacketPayload {
        public static final Type<Acknowledge> TYPE = payloadType("catalog_acknowledge");
        public static final StreamCodec<RegistryFriendlyByteBuf, Acknowledge> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.map(Acknowledge::new, Acknowledge::containerId).cast();

        @Override
        public Type<Acknowledge> type() {
            return TYPE;
        }
    }

    /**
     * One reading for the open catalog menu {@code containerId}. {@code counts} follows the entry order of manifest
     * {@code generation}; {@code -1} means the reading has no count for that entry. {@code status} is the state and
     * manifest lines, already worded with the reading's age; {@code milestones} is reconstruction and other history,
     * kept apart from current readiness.
     */
    public record Counts(int containerId, int generation, ArchiveState state, long[] counts,
            List<Component> status, List<Component> milestones) implements CustomPacketPayload {
        public static final Type<Counts> TYPE = payloadType("catalog_counts");
        public static final StreamCodec<RegistryFriendlyByteBuf, Counts> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Counts::containerId,
                ByteBufCodecs.VAR_INT, Counts::generation,
                ByteBufCodecs.VAR_INT.map(i -> ArchiveState.values()[i], ArchiveState::ordinal), Counts::state,
                LONGS, Counts::counts,
                COMPONENTS, Counts::status,
                COMPONENTS, Counts::milestones,
                Counts::new);

        @Override
        public Type<Counts> type() {
            return TYPE;
        }
    }
}
