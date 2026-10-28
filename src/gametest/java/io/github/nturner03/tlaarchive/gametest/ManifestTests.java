package io.github.nturner03.tlaarchive.gametest;

import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestEntry;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The test-only manifest (gametest resources), the manifest format, and the matcher's rules, without a network. */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManifestTests {
    private ManifestTests() {
    }

    static ManifestMatcher matcher(GameTestHelper helper) {
        return ActiveManifest.matcher().orElseThrow(() -> new GameTestAssertException("no active manifest loaded"));
    }

    static List<String> keysFor(GameTestHelper helper, ItemStack stack) {
        return matcher(helper).entriesSatisfiedBy(AEItemKey.of(stack)).stream().map(ManifestEntry::key).toList();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void representativeManifestLoadsAsTestOnly(GameTestHelper helper) {
        var manifest = matcher(helper).manifest();
        helper.assertTrue(manifest.testOnly(), "manifest is marked test_only");
        helper.assertTrue(manifest.label().contains("TEST-ONLY"), "label says TEST-ONLY: " + manifest.label());
        var expected = Map.of("diamond", 64L, "iron_pickaxe", 1L, "blue_banner", 1L, "potion:strength", 1L,
                "potion:swiftness", 1L, "enchanted_book:efficiency", 1L, "enchanted_book:fortune", 1L);
        helper.assertTrue(manifest.entries().size() == expected.size(), "7 entries, got " + manifest.entries());
        for (var entry : manifest.entries()) {
            helper.assertTrue(Long.valueOf(entry.quantity()).equals(expected.get(entry.key())),
                    "quantity of " + entry.key());
            helper.assertTrue(entry.quantityNote().filter(ArchiveManifest.TEST_ONLY_NOTE::equals).isPresent(),
                    entry.key() + " is labelled " + ArchiveManifest.TEST_ONLY_NOTE);
        }
        // A 0.3.0 file: the fields added later take their defaults.
        helper.assertTrue(manifest.format() == ArchiveManifest.FORMAT, "format defaults to 1");
        helper.assertTrue(manifest.quantitiesStatus() == ArchiveManifest.QuantitiesStatus.PLACEHOLDER,
                "quantities_status defaults to placeholder");
        helper.assertTrue(manifest.categories().isEmpty(), "no categories table");
        helper.assertTrue(manifest.categoryOrder().equals(List.of(new ArchiveManifest.Category("test", "test"))),
                "categories fall back to the entries' ids: " + manifest.categoryOrder());
        helper.succeed();
    }

    static DataResult<ArchiveManifest> parse(String top, String entries) {
        var json = JsonParser.parseString("{\"id\": \"draft\", \"version\": 3, \"test_only\": false, "
                + "\"description\": \"x\", " + top + (top.isEmpty() ? "" : ", ") + "\"entries\": [" + entries + "]}");
        return ArchiveManifest.CODEC.parse(JsonOps.INSTANCE, json);
    }

    static String entry(String key, String category) {
        return "{\"key\": \"" + key + "\", \"display_name\": \"" + key + "\", \"category\": \"" + category
                + "\", \"rule\": \"item_id\", \"item\": \"minecraft:" + key + "\", \"quantity\": 1}";
    }

    /** Manifest contract items 1–3 (owner ruling 2026-10-28): placeholder marker, categories table, format. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void manifestFormatAdditionsLoad(GameTestHelper helper) {
        var categories = "\"categories\": [{\"id\": \"stone\", \"name\": \"Stone\"}, "
                + "{\"id\": \"ores\", \"name\": \"Ores & Minerals\"}, {\"id\": \"empty\", \"name\": \"Empty\"}]";
        var entries = entry("diamond", "ores") + ", " + entry("cobblestone", "stone");

        var placeholder = parse("\"format\": 1, " + categories, entries).getOrThrow();
        helper.assertTrue(placeholder.label().equals("draft v3 [PLACEHOLDER QUANTITIES]"),
                "a real manifest without quantities_status is labelled placeholder: " + placeholder.label());
        helper.assertTrue(placeholder.categoryOrder().stream().map(ArchiveManifest.Category::id).toList()
                .equals(List.of("stone", "ores", "empty")), "the table's order, including an unused category");
        helper.assertTrue(placeholder.categoryOrder().get(1).name().equals("Ores & Minerals"), "category name");

        var reviewed = parse("\"quantities_status\": \"reviewed\"", entries).getOrThrow();
        helper.assertTrue(reviewed.label().equals("draft v3"), "reviewed quantities carry no marker");
        helper.assertTrue(reviewed.categoryOrder().stream().map(ArchiveManifest.Category::id).toList()
                .equals(List.of("ores", "stone")), "without a table, first use orders the categories");

        var testOnly = ArchiveManifest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id": "t", "version": 1, "test_only": true, "quantities_status": "placeholder", "description": "x",
                 "entries": [{"key": "diamond", "display_name": "Diamond", "category": "c", "rule": "item_id",
                   "item": "minecraft:diamond", "quantity": 1, "quantity_note": "TEST-ONLY placeholder"}]}""")
        ).getOrThrow();
        helper.assertTrue(testOnly.label().equals("t v1 [TEST-ONLY]"), "test-only wins: " + testOnly.label());
        helper.succeed();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void manifestFormatAdditionsAreValidated(GameTestHelper helper) {
        var entries = entry("diamond", "ores");
        var rejected = Map.of(
                "an entry whose category is not in the table",
                parse("\"categories\": [{\"id\": \"stone\", \"name\": \"Stone\"}]", entries),
                "a duplicate category", parse("\"categories\": [{\"id\": \"ores\", \"name\": \"A\"}, "
                        + "{\"id\": \"ores\", \"name\": \"B\"}]", entries),
                "a bad category id", parse("\"categories\": [{\"id\": \"Ores!\", \"name\": \"A\"}]", entries),
                "a newer format", parse("\"format\": 2", entries),
                "format 0", parse("\"format\": 0", entries),
                "an unknown quantities_status", parse("\"quantities_status\": \"final\"", entries));
        rejected.forEach((what, result) -> helper.assertTrue(result.isError(), "rejects " + what));
        helper.assertTrue(parse("\"format\": 2", entries).error().orElseThrow().message().contains("format 2"),
                "the error names the format");
        helper.succeed();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void testOnlyManifestRejectsAnUnlabelledQuantity(GameTestHelper helper) {
        var json = JsonParser.parseString("""
                {"id": "x", "version": 1, "test_only": true, "description": "x", "entries": [
                  {"key": "diamond", "display_name": "Diamond", "category": "test", "rule": "item_id",
                   "item": "minecraft:diamond", "quantity": 64}
                ]}""");
        var result = ArchiveManifest.CODEC.parse(JsonOps.INSTANCE, json);
        helper.assertTrue(result.isError(), "a test-only entry without the TEST-ONLY note is rejected");
        helper.succeed();
    }

    /** EC-potion-forms: forms are interchangeable, strength and duration don't matter, effects don't cross. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void potionsMatchByEffect(GameTestHelper helper) {
        var strength = List.of("potion:strength");
        helper.assertTrue(keysFor(helper, Samples.potion(Items.SPLASH_POTION, Potions.STRONG_STRENGTH))
                .equals(strength), "splash strength II -> strength");
        helper.assertTrue(keysFor(helper, Samples.potion(Items.LINGERING_POTION, Potions.LONG_STRENGTH))
                .equals(strength), "lingering long strength -> strength");
        helper.assertTrue(keysFor(helper, Samples.potion(Items.POTION, Potions.SWIFTNESS))
                .equals(List.of("potion:swiftness")), "swiftness -> swiftness only");
        // EC-potion-bases is deferred: effectless and multi-effect potions satisfy nothing yet.
        helper.assertTrue(keysFor(helper, Samples.potion(Items.POTION, Potions.WATER)).isEmpty(), "water");
        helper.assertTrue(keysFor(helper, Samples.potion(Items.POTION, Potions.TURTLE_MASTER)).isEmpty(),
                "turtle master");
        // EC-tipped-arrows is deferred.
        helper.assertTrue(keysFor(helper, Samples.potion(Items.TIPPED_ARROW, Potions.STRENGTH)).isEmpty(),
                "tipped arrow");
        helper.succeed();
    }

    /** EC-enchanted-books: one multi-enchantment book satisfies each of its enchantments. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void oneBookSatisfiesEachEnchantment(GameTestHelper helper) {
        var keys = keysFor(helper, Samples.efficiencyFortuneBook(helper));
        helper.assertTrue(keys.equals(List.of("enchanted_book:efficiency", "enchanted_book:fortune")),
                "efficiency + fortune book -> both entries, got " + keys);
        helper.assertTrue(keysFor(helper, new ItemStack(Items.ENCHANTED_BOOK)).isEmpty(), "empty book");
        helper.succeed();
    }

    /** EC-ordinary-id and EC-banners. */
    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void pickaxeAndBannerIgnoreTheirData(GameTestHelper helper) {
        helper.assertTrue(keysFor(helper, Samples.damagedRenamedPickaxe()).equals(List.of("iron_pickaxe")),
                "damaged, renamed pickaxe");
        helper.assertTrue(keysFor(helper, Samples.patternedBanner(helper, Items.BLUE_BANNER))
                .equals(List.of("blue_banner")), "patterned blue banner");
        helper.assertTrue(keysFor(helper, Samples.patternedBanner(helper, Items.RED_BANNER)).isEmpty(),
                "red banner is not blue");
        helper.succeed();
    }
}
