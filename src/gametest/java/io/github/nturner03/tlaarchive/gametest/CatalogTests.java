package io.github.nturner03.tlaarchive.gametest;

import appeng.api.stacks.AEItemKey;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.catalog.CatalogEntry;
import io.github.nturner03.tlaarchive.catalog.CatalogIcons;
import io.github.nturner03.tlaarchive.catalog.CatalogPayloads;
import io.github.nturner03.tlaarchive.catalog.CatalogSync;
import io.github.nturner03.tlaarchive.catalog.CatalogView;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestEntry;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import io.github.nturner03.tlaarchive.manifest.MatchRule;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The catalog's sync payloads, view model and icons, without a client (this host has no display; the screen itself is
 * checked by the owner through a release).
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CatalogTests {
    /** The pack's whole candidate list, the largest manifest expected (handoff → <i>Manifest contract</i>). */
    private static final int LARGE_MANIFEST = 1_213;
    /** Far below the 1 MiB clientbound limit, so long notes still fit. */
    private static final int PAYLOAD_BUDGET = 256 * 1024;

    private CatalogTests() {
    }

    private static <T> int encodedSize(GameTestHelper helper, StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T value) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        codec.encode(buf, value);
        return buf.readableBytes();
    }

    private static <T> T roundTrip(GameTestHelper helper, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        codec.encode(buf, value);
        var decoded = codec.decode(buf);
        helper.assertTrue(buf.readableBytes() == 0, "codec read everything it wrote");
        return decoded;
    }

    /** A generated manifest of {@code size} item entries in 18 categories, with realistic text lengths. */
    static ArchiveManifest largeManifest(int size) {
        var items = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).toList();
        var categories = new ArrayList<ArchiveManifest.Category>();
        for (int c = 0; c < 18; c++) {
            categories.add(new ArchiveManifest.Category("category_" + c, "Category number " + c));
        }
        var entries = new ArrayList<ManifestEntry>();
        for (int i = 0; i < size; i++) {
            entries.add(new ManifestEntry("generated_entry_number_" + i, "A Generated Display Name " + i,
                    "category_" + i % 18, new MatchRule.ItemId(items.get(i % items.size())), 1 + i % 500,
                    Optional.of("Placeholder quantity, not a balance decision"), Optional.of("EC-ordinary-id"),
                    Optional.of("A short player-facing note about where this item comes from and how it counts.")));
        }
        return new ArchiveManifest(1, "generated", 1, false, ArchiveManifest.QuantitiesStatus.PLACEHOLDER, "x",
                Optional.of(categories), entries);
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void largeManifestSyncsInBoundedPayloads(GameTestHelper helper) {
        var manifest = largeManifest(LARGE_MANIFEST);
        var payloads = CatalogSync.manifestPayloads(7, Optional.of(new ManifestMatcher(manifest)));
        int chunks = (LARGE_MANIFEST + CatalogSync.ENTRIES_PER_PAYLOAD - 1) / CatalogSync.ENTRIES_PER_PAYLOAD;
        helper.assertTrue(payloads.size() == 1 + chunks, "a header and " + chunks + " chunks: " + payloads.size());

        var header = roundTrip(helper, CatalogPayloads.ManifestHeader.STREAM_CODEC,
                (CatalogPayloads.ManifestHeader) payloads.get(0));
        helper.assertTrue(header.equals(payloads.get(0)), "header round trip");
        helper.assertTrue(header.present() && header.entryCount() == LARGE_MANIFEST && header.generation() == 7
                && header.label().equals("generated v1 [PLACEHOLDER QUANTITIES]")
                && header.categories().equals(manifest.categoryOrder()), "header content: " + header);

        var received = new CatalogEntry[LARGE_MANIFEST];
        for (var payload : payloads.subList(1, payloads.size())) {
            var chunk = (CatalogPayloads.ManifestEntries) payload;
            int size = encodedSize(helper, CatalogPayloads.ManifestEntries.STREAM_CODEC, chunk);
            helper.assertTrue(size < PAYLOAD_BUDGET, "chunk at " + chunk.offset() + " is " + size + " bytes");
            var decoded = roundTrip(helper, CatalogPayloads.ManifestEntries.STREAM_CODEC, chunk);
            helper.assertTrue(decoded.equals(chunk), "chunk round trip at " + chunk.offset());
            for (int i = 0; i < decoded.entries().size(); i++) {
                received[decoded.offset() + i] = decoded.entries().get(i);
            }
        }
        var expected = manifest.entries().stream().map(CatalogEntry::of).toList();
        helper.assertTrue(Arrays.asList(received).equals(expected), "every entry arrives once, in order");
        helper.assertTrue(expected.get(5).rule().equals("item_id")
                && expected.get(5).ruleArgument().equals(BuiltInRegistries.ITEM.getKey(
                        ((MatchRule.ItemId) manifest.entries().get(5).rule()).item()).toString()),
                "the rule travels as its name and field");

        var counts = new long[LARGE_MANIFEST];
        Arrays.fill(counts, Long.MAX_VALUE);
        counts[3] = -1;
        var payload = new CatalogPayloads.Counts(4, 7, ArchiveState.STALE, counts,
                List.of(Component.literal("Archive controller: STALE")), List.of(Component.literal("milestone")));
        int size = encodedSize(helper, CatalogPayloads.Counts.STREAM_CODEC, payload);
        helper.assertTrue(size < PAYLOAD_BUDGET, "counts payload is " + size + " bytes");
        var decoded = roundTrip(helper, CatalogPayloads.Counts.STREAM_CODEC, payload);
        helper.assertTrue(decoded.containerId() == 4 && decoded.generation() == 7
                && decoded.state() == ArchiveState.STALE && Arrays.equals(decoded.counts(), counts)
                && decoded.status().equals(payload.status()) && decoded.milestones().equals(payload.milestones()),
                "counts round trip");
        helper.succeed();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void noManifestSyncsAsAnEmptyHeader(GameTestHelper helper) {
        var payloads = CatalogSync.manifestPayloads(3, Optional.empty());
        helper.assertTrue(payloads.equals(List.of(new CatalogPayloads.ManifestHeader(3, false, "", List.of(), 0))),
                "one header that says no manifest: " + payloads);
        helper.succeed();
    }

    private static CatalogEntry entry(String key, String name, String category, long quantity) {
        return new CatalogEntry(key, name, category, "item_id", "minecraft:stone", quantity, Optional.empty(),
                Optional.empty());
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void viewFiltersAndTotals(GameTestHelper helper) {
        var entries = List.of(
                entry("white_wool", "White Wool", "colours", 4),
                entry("blue_wool", "Blue Wool", "colours", 4),
                entry("oak_planks", "Oak Planks", "wood", 64),
                entry("potion:swiftness", "Potion of Swiftness", "brewing", 1));
        var view = new CatalogView(entries, new long[] {4, 3, -1, 9});

        helper.assertTrue(view.ready(0) && !view.ready(1) && !view.ready(2) && view.ready(3), "readiness");
        helper.assertTrue(Arrays.equals(view.filter("", null, false), new int[] {0, 1, 2, 3}), "everything");
        helper.assertTrue(Arrays.equals(view.filter("WOOL", null, false), new int[] {0, 1}), "case-insensitive");
        helper.assertTrue(Arrays.equals(view.filter("blue wool", null, false), new int[] {1}), "every word");
        helper.assertTrue(Arrays.equals(view.filter("swiftness", null, false), new int[] {3}), "key and name");
        helper.assertTrue(Arrays.equals(view.filter("", "colours", false), new int[] {0, 1}), "category");
        helper.assertTrue(Arrays.equals(view.filter("", null, true), new int[] {1, 2}),
                "missing only; an unknown count is missing");
        helper.assertTrue(Arrays.equals(view.filter("wool", "colours", true), new int[] {1}), "combined");
        helper.assertTrue(view.filter("nothing", null, false).length == 0, "no match");

        helper.assertTrue(view.totals(null).equals(new CatalogView.Totals(2, 4)), "overall " + view.totals(null));
        helper.assertTrue(view.totals("colours").equals(new CatalogView.Totals(1, 2)), "colours");
        helper.assertTrue(view.totals("wood").missing() == 1, "wood missing");
        helper.assertTrue(view.totals("unused").equals(new CatalogView.Totals(0, 0)), "an unused category");

        var unknown = new CatalogView(entries, new long[] {4});
        helper.assertTrue(unknown.count(0) == -1 && unknown.totals(null).ready() == 0,
                "counts for another manifest are treated as unknown");
        helper.succeed();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void iconsFollowTheRule(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var diamond = CatalogIcons.icon(new CatalogEntry("diamond", "Diamond", "c", "item_id", "minecraft:diamond",
                1, Optional.empty(), Optional.empty()), registries);
        helper.assertTrue(diamond.is(Items.DIAMOND), "item_id: " + diamond);

        var banner = CatalogIcons.icon(new CatalogEntry("blue_banner", "Blue Banner", "c", "banner_base_color",
                "blue", 1, Optional.empty(), Optional.empty()), registries);
        helper.assertTrue(banner.is(Items.BLUE_BANNER), "banner: " + banner);

        var swiftness = CatalogIcons.icon(new CatalogEntry("potion:swiftness", "Swiftness", "c", "potion_effect",
                "minecraft:speed", 1, Optional.empty(), Optional.empty()), registries);
        var contents = swiftness.get(DataComponents.POTION_CONTENTS);
        helper.assertTrue(swiftness.is(Items.POTION) && contents != null
                && contents.potion().filter(p -> p.is(Potions.SWIFTNESS)).isPresent(),
                "potion_effect: a swiftness potion, " + contents);
        var matches = ManifestTests.matcher(helper).entriesSatisfiedBy(AEItemKey.of(swiftness));
        helper.assertTrue(matches.stream().anyMatch(e -> e.key().equals("potion:swiftness")),
                "the icon is itself an item the entry counts");

        var book = CatalogIcons.icon(new CatalogEntry("enchanted_book:efficiency", "Efficiency", "c",
                "enchantment_on_book", "minecraft:efficiency", 1, Optional.empty(), Optional.empty()), registries);
        var stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        helper.assertTrue(book.is(Items.ENCHANTED_BOOK) && stored != null
                && stored.keySet().stream().anyMatch(h -> h.is(Enchantments.EFFICIENCY)), "enchantment_on_book: " + stored);

        for (var bad : List.of(
                new CatalogEntry("a", "a", "c", "item_id", "minecraft:no_such_item", 1, Optional.empty(),
                        Optional.empty()),
                new CatalogEntry("b", "b", "c", "banner_base_color", "mauve", 1, Optional.empty(), Optional.empty()),
                new CatalogEntry("c", "c", "c", "potion_effect", "minecraft:no_such_effect", 1, Optional.empty(),
                        Optional.empty()),
                new CatalogEntry("d", "d", "c", "enchantment_on_book", "minecraft:no_such", 1, Optional.empty(),
                        Optional.empty()),
                new CatalogEntry("e", "e", "c", "future_rule", "x", 1, Optional.empty(), Optional.empty()))) {
            helper.assertTrue(CatalogIcons.icon(bad, registries).is(Items.BARRIER), "barrier for " + bad.rule());
        }
        // An effect no registered potion carries alone still gets a potion with that effect.
        var custom = CatalogIcons.icon(new CatalogEntry("x", "x", "c", "potion_effect", "minecraft:haste", 1,
                Optional.empty(), Optional.empty()), registries);
        var customContents = custom.get(DataComponents.POTION_CONTENTS);
        helper.assertTrue(customContents != null && customContents.customEffects().stream()
                .anyMatch(e -> e.getEffect().equals(MobEffects.DIG_SPEED)), "custom effect potion: " + customContents);
        helper.succeed();
    }
}
