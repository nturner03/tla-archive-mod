package io.github.nturner03.tlaarchive.manifest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.nturner03.tlaarchive.TlaArchive;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/tla_archive/archive_manifest/active.json} on every datapack (re)load. The mod ships a test-only
 * manifest at that path; a pack replaces it with a datapack file at the same path. An invalid file leaves the server
 * with no manifest (logged), so controllers say so instead of counting against stale requirements.
 */
public final class ManifestLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "archive_manifest";
    public static final ResourceLocation ACTIVE = ResourceLocation.fromNamespaceAndPath(TlaArchive.MOD_ID, "active");

    private final RegistryAccess registries;

    public ManifestLoader(RegistryAccess registries) {
        super(new Gson(), DIRECTORY);
        this.registries = registries;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
            ProfilerFiller profiler) {
        var json = files.get(ACTIVE);
        if (json == null) {
            TlaArchive.LOGGER.warn("No archive manifest at data/{}/{}/{}.json; controllers will show no counts",
                    ACTIVE.getNamespace(), DIRECTORY, ACTIVE.getPath());
            ActiveManifest.set(null);
            return;
        }
        var parsed = ArchiveManifest.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json)
                .flatMap(this::checkEnchantments);
        parsed.ifSuccess(manifest -> {
            ActiveManifest.set(new ManifestMatcher(manifest));
            TlaArchive.LOGGER.info("Loaded archive manifest {} with {} entries", manifest.label(),
                    manifest.entries().size());
        }).ifError(error -> {
            ActiveManifest.set(null);
            TlaArchive.LOGGER.error("Archive manifest {} is invalid, controllers will show no counts: {}", ACTIVE,
                    error.message());
        });
    }

    /** Enchantments are a datapack registry, so their ids can only be checked against the loaded registries. */
    private DataResult<ArchiveManifest> checkEnchantments(ArchiveManifest manifest) {
        var enchantments = registries.registryOrThrow(Registries.ENCHANTMENT);
        var unknown = new ArrayList<String>();
        for (var entry : manifest.entries()) {
            if (entry.rule() instanceof MatchRule.EnchantmentOnBook book
                    && !enchantments.containsKey(book.enchantment())) {
                unknown.add(entry.key() + " -> " + book.enchantment());
            }
        }
        return unknown.isEmpty() ? DataResult.success(manifest)
                : DataResult.error(() -> "unknown enchantments: " + unknown);
    }
}
