package io.github.nturner03.tlaarchive.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The manifest a team last acknowledged: its id, version and label, and each entry's quantity by key (migration
 * policy, owner ruling 2026-10-28). Keys are never reused with a new meaning, so key and quantity are enough to say
 * what changed.
 */
public record SeenManifest(String id, int version, String label, Map<String, Long> quantities) {
    public static final Codec<SeenManifest> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(SeenManifest::id),
            Codec.INT.fieldOf("version").forGetter(SeenManifest::version),
            Codec.STRING.fieldOf("label").forGetter(SeenManifest::label),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("quantities").forGetter(SeenManifest::quantities))
            .apply(i, SeenManifest::new));

    public SeenManifest {
        quantities = Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
    }

    public static SeenManifest of(ArchiveManifest manifest) {
        var quantities = new LinkedHashMap<String, Long>();
        manifest.entries().forEach(entry -> quantities.put(entry.key(), entry.quantity()));
        return new SeenManifest(manifest.id(), manifest.version(), manifest.label(), quantities);
    }
}
