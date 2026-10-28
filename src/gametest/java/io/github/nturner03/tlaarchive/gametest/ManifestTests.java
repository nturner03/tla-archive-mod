package io.github.nturner03.tlaarchive.gametest;

import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonParser;
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

/** The shipped test-only manifest and the matcher's rules, without a network. */
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
