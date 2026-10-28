package io.github.nturner03.tlaarchive.catalog;

import io.github.nturner03.tlaarchive.manifest.ManifestEntry;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A manifest entry as the catalog screen sees it: what to show, not how to match. The rule travels as its name and
 * field ({@code item_id} + {@code minecraft:diamond}), enough for the icon and the rule's explanation.
 */
public record CatalogEntry(
        String key,
        String displayName,
        String category,
        String rule,
        String ruleArgument,
        long quantity,
        Optional<String> quantityNote,
        Optional<String> notes) {

    private static final StreamCodec<RegistryFriendlyByteBuf, Optional<String>> OPTIONAL_STRING =
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8).cast();

    public static final StreamCodec<RegistryFriendlyByteBuf, CatalogEntry> STREAM_CODEC = StreamCodec.of(
            (buf, entry) -> {
                buf.writeUtf(entry.key);
                buf.writeUtf(entry.displayName);
                buf.writeUtf(entry.category);
                buf.writeUtf(entry.rule);
                buf.writeUtf(entry.ruleArgument);
                buf.writeVarLong(entry.quantity);
                OPTIONAL_STRING.encode(buf, entry.quantityNote);
                OPTIONAL_STRING.encode(buf, entry.notes);
            },
            buf -> new CatalogEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readVarLong(), OPTIONAL_STRING.decode(buf), OPTIONAL_STRING.decode(buf)));

    public static CatalogEntry of(ManifestEntry entry) {
        return new CatalogEntry(entry.key(), entry.displayName(), entry.category(), entry.rule().type(),
                entry.rule().argument(), entry.quantity(), entry.quantityNote(), entry.notes());
    }
}
