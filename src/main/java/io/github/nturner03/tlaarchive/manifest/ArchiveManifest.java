package io.github.nturner03.tlaarchive.manifest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

/**
 * A versioned collection manifest, loaded from data (design: <i>Completion model</i>). {@code version} is the content
 * version; {@code format} is the file format, so a newer format is rejected cleanly instead of half-read. A
 * {@code test_only} manifest is a test fixture, and every one of its entries must say so in {@code quantity_note}.
 * {@code quantities_status} marks a real manifest whose quantities are still placeholders. Fields added after
 * {@code 0.3.0} are optional, so {@code 0.3.0} files still load.
 */
public record ArchiveManifest(
        int format,
        String id,
        int version,
        boolean testOnly,
        QuantitiesStatus quantitiesStatus,
        String description,
        Optional<List<Category>> categories,
        List<ManifestEntry> entries) {

    public static final String TEST_ONLY_NOTE = "TEST-ONLY placeholder";
    /** The newest file format this mod reads. */
    public static final int FORMAT = 1;
    public static final String KEY_PATTERN = "[a-z0-9_.:/-]+";

    public static final Codec<ArchiveManifest> CODEC = RecordCodecBuilder.<ArchiveManifest>create(i -> i.group(
            Codec.INT.optionalFieldOf("format", FORMAT).forGetter(ArchiveManifest::format),
            Codec.STRING.fieldOf("id").forGetter(ArchiveManifest::id),
            Codec.INT.fieldOf("version").forGetter(ArchiveManifest::version),
            Codec.BOOL.fieldOf("test_only").forGetter(ArchiveManifest::testOnly),
            QuantitiesStatus.CODEC.optionalFieldOf("quantities_status", QuantitiesStatus.PLACEHOLDER)
                    .forGetter(ArchiveManifest::quantitiesStatus),
            Codec.STRING.fieldOf("description").forGetter(ArchiveManifest::description),
            Category.CODEC.listOf().optionalFieldOf("categories").forGetter(ArchiveManifest::categories),
            ManifestEntry.CODEC.listOf().fieldOf("entries").forGetter(ArchiveManifest::entries))
            .apply(i, ArchiveManifest::new))
            .validate(ArchiveManifest::validate);

    public ArchiveManifest {
        categories = categories.map(List::copyOf);
        entries = List.copyOf(entries);
    }

    /** Shown wherever counts are shown, so a placeholder manifest is never mistaken for the real one. */
    public String label() {
        var marker = testOnly ? " [TEST-ONLY]"
                : quantitiesStatus == QuantitiesStatus.PLACEHOLDER ? " [PLACEHOLDER QUANTITIES]" : "";
        return id + " v" + version + marker;
    }

    /**
     * The categories in display order: the manifest's {@code categories} table, or, for a file without one, each
     * category id in the order entries first use it, named by its id.
     */
    public List<Category> categoryOrder() {
        if (categories.isPresent()) {
            return categories.get();
        }
        var seen = new LinkedHashMap<String, Category>();
        for (var entry : entries) {
            seen.computeIfAbsent(entry.category(), id -> new Category(id, id));
        }
        return List.copyOf(seen.values());
    }

    private static DataResult<ArchiveManifest> validate(ArchiveManifest manifest) {
        if (manifest.format > FORMAT || manifest.format < 1) {
            return DataResult.error(() -> "manifest format " + manifest.format + " is not supported; this mod reads "
                    + "format " + FORMAT + " or older");
        }
        List<String> problems = new ArrayList<>();
        var categoryIds = new HashSet<String>();
        manifest.categories.ifPresent(list -> list.forEach(category -> {
            if (!categoryIds.add(category.id())) {
                problems.add("duplicate category '" + category.id() + "'");
            }
        }));
        var keys = new HashSet<String>();
        for (var entry : manifest.entries) {
            if (!entry.key().matches(KEY_PATTERN)) {
                problems.add("bad key '" + entry.key() + "'");
            }
            if (!keys.add(entry.key())) {
                problems.add("duplicate key '" + entry.key() + "'");
            }
            if (manifest.categories.isPresent() && !categoryIds.contains(entry.category())) {
                problems.add("'" + entry.key() + "' has category '" + entry.category()
                        + "', which is not in the categories table");
            }
            if (manifest.testOnly && !entry.quantityNote().filter(TEST_ONLY_NOTE::equals).isPresent()) {
                problems.add("'" + entry.key() + "' in a test-only manifest needs quantity_note \""
                        + TEST_ONLY_NOTE + "\"");
            }
        }
        return problems.isEmpty() ? DataResult.success(manifest)
                : DataResult.error(() -> String.join("; ", problems));
    }

    /** Whether the quantities are balance decisions yet. A manifest without the field is a placeholder. */
    public enum QuantitiesStatus implements StringRepresentable {
        PLACEHOLDER,
        REVIEWED;

        public static final Codec<QuantitiesStatus> CODEC = StringRepresentable.fromEnum(QuantitiesStatus::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A catalog category: its id, as entries use it, and its player-facing name. */
    public record Category(String id, String name) {
        public static final Codec<Category> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.validate(id -> id.matches(KEY_PATTERN) ? DataResult.success(id)
                        : DataResult.error(() -> "bad category id '" + id + "'"))
                        .fieldOf("id").forGetter(Category::id),
                Codec.STRING.fieldOf("name").forGetter(Category::name))
                .apply(i, Category::new));
    }
}
