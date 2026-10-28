package io.github.nturner03.tlaarchive.manifest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A versioned collection manifest, loaded from data (design: <i>Completion model</i>). A {@code test_only} manifest
 * holds placeholder quantities, not balance decisions, and every one of its entries must say so in
 * {@code quantity_note}.
 */
public record ArchiveManifest(String id, int version, boolean testOnly, String description,
        List<ManifestEntry> entries) {

    public static final String TEST_ONLY_NOTE = "TEST-ONLY placeholder";

    public static final Codec<ArchiveManifest> CODEC = RecordCodecBuilder.<ArchiveManifest>create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ArchiveManifest::id),
            Codec.INT.fieldOf("version").forGetter(ArchiveManifest::version),
            Codec.BOOL.fieldOf("test_only").forGetter(ArchiveManifest::testOnly),
            Codec.STRING.fieldOf("description").forGetter(ArchiveManifest::description),
            ManifestEntry.CODEC.listOf().fieldOf("entries").forGetter(ArchiveManifest::entries))
            .apply(i, ArchiveManifest::new))
            .validate(ArchiveManifest::validate);

    public ArchiveManifest {
        entries = List.copyOf(entries);
    }

    /** Shown wherever counts are shown, so a placeholder manifest is never mistaken for the real one. */
    public String label() {
        return id + " v" + version + (testOnly ? " [TEST-ONLY]" : "");
    }

    private static DataResult<ArchiveManifest> validate(ArchiveManifest manifest) {
        List<String> problems = new ArrayList<>();
        var keys = new HashSet<String>();
        for (var entry : manifest.entries) {
            if (!entry.key().matches("[a-z0-9_.:/-]+")) {
                problems.add("bad key '" + entry.key() + "'");
            }
            if (!keys.add(entry.key())) {
                problems.add("duplicate key '" + entry.key() + "'");
            }
            if (manifest.testOnly && !entry.quantityNote().filter(TEST_ONLY_NOTE::equals).isPresent()) {
                problems.add("'" + entry.key() + "' in a test-only manifest needs quantity_note \""
                        + TEST_ONLY_NOTE + "\"");
            }
        }
        return problems.isEmpty() ? DataResult.success(manifest)
                : DataResult.error(() -> String.join("; ", problems));
    }
}
