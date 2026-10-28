package io.github.nturner03.tlaarchive.manifest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * One approved manifest requirement (design: <i>Completion model</i>). {@code quantity} is how many matching items the
 * archive needs; a presence requirement is a quantity of 1. Quantities live in data so they can change without touching
 * the matcher.
 */
public record ManifestEntry(
        String key,
        String displayName,
        String category,
        MatchRule rule,
        long quantity,
        Optional<String> quantityNote,
        Optional<String> edgeCase,
        Optional<String> notes) {

    public static final Codec<ManifestEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("key").forGetter(ManifestEntry::key),
            Codec.STRING.fieldOf("display_name").forGetter(ManifestEntry::displayName),
            Codec.STRING.fieldOf("category").forGetter(ManifestEntry::category),
            MatchRule.MAP_CODEC.forGetter(ManifestEntry::rule),
            Codec.LONG.validate(q -> q >= 1 ? DataResult.success(q)
                    : DataResult.error(() -> "quantity must be at least 1, got " + q))
                    .fieldOf("quantity").forGetter(ManifestEntry::quantity),
            Codec.STRING.optionalFieldOf("quantity_note").forGetter(ManifestEntry::quantityNote),
            Codec.STRING.optionalFieldOf("edge_case").forGetter(ManifestEntry::edgeCase),
            Codec.STRING.optionalFieldOf("notes").forGetter(ManifestEntry::notes))
            .apply(i, ManifestEntry::new));
}
