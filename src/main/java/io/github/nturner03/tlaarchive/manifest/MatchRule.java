package io.github.nturner03.tlaarchive.manifest;

import appeng.api.stacks.AEItemKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;

/**
 * How a manifest entry recognises a stored item. The rule names come from the pack's candidate manifest
 * ({@code ~/tla-packwiz/docs/manifest/README.md}); the behaviour of each is ruled in the pack's
 * {@code docs/edge-cases.md}. Matching only reads item data, never changes it.
 */
public sealed interface MatchRule {
    List<String> TYPES = List.of(ItemId.TYPE, BannerBaseColor.TYPE, PotionEffect.TYPE, EnchantmentOnBook.TYPE);
    MapCodec<MatchRule> MAP_CODEC = Codec.STRING
            .validate(type -> TYPES.contains(type) ? DataResult.success(type)
                    : DataResult.error(() -> "unknown rule '" + type + "', expected one of " + TYPES))
            .dispatchMap("rule", MatchRule::type, MatchRule::codecFor);

    /** The {@code rule} value in manifest JSON. */
    String type();

    /** The rule's field as the manifest writes it: an item, colour, effect or enchantment id. */
    String argument();

    /** Every item whose keys can satisfy this rule. The matcher only looks at keys of these items. */
    List<Item> candidateItems();

    boolean matches(AEItemKey key);

    private static MapCodec<? extends MatchRule> codecFor(String type) {
        return switch (type) {
            case ItemId.TYPE -> ItemId.CODEC;
            case BannerBaseColor.TYPE -> BannerBaseColor.CODEC;
            case PotionEffect.TYPE -> PotionEffect.CODEC;
            case EnchantmentOnBook.TYPE -> EnchantmentOnBook.CODEC;
            default -> throw new IllegalArgumentException("unknown match rule '" + type + "'");
        };
    }

    /** EC-ordinary-id: the item id alone; custom name, durability and other data are ignored. */
    record ItemId(Item item) implements MatchRule {
        static final String TYPE = "item_id";
        static final MapCodec<ItemId> CODEC = BuiltInRegistries.ITEM.byNameCodec()
                .validate(item -> item == Items.AIR ? DataResult.error(() -> "item_id rule needs an item")
                        : DataResult.success(item))
                .fieldOf("item").xmap(ItemId::new, ItemId::item);

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String argument() {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }

        @Override
        public List<Item> candidateItems() {
            return List.of(item);
        }

        @Override
        public boolean matches(AEItemKey key) {
            return key.getItem() == item;
        }
    }

    /** EC-banners: the banner's base colour, which its item id encodes; patterns are ignored. */
    record BannerBaseColor(DyeColor color) implements MatchRule {
        static final String TYPE = "banner_base_color";
        static final MapCodec<BannerBaseColor> CODEC = DyeColor.CODEC.fieldOf("color")
                .xmap(BannerBaseColor::new, BannerBaseColor::color);

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String argument() {
            return color.getSerializedName();
        }

        @Override
        public List<Item> candidateItems() {
            return List.of(BannerBlock.byColor(color).asItem());
        }

        @Override
        public boolean matches(AEItemKey key) {
            return key.getItem() instanceof BannerItem banner && banner.getColor() == color;
        }
    }

    /**
     * EC-potion-forms: drinkable, splash and lingering potions whose only effect type is {@code effect}, at any
     * strength or duration. Effectless and multi-effect potions (EC-potion-bases) are not ruled yet, so they match no
     * entry.
     */
    record PotionEffect(ResourceLocation effect) implements MatchRule {
        static final String TYPE = "potion_effect";
        static final List<Item> FORMS = List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);
        static final MapCodec<PotionEffect> CODEC = ResourceLocation.CODEC
                .validate(id -> BuiltInRegistries.MOB_EFFECT.containsKey(id) ? DataResult.success(id)
                        : DataResult.error(() -> "unknown effect " + id))
                .fieldOf("effect").xmap(PotionEffect::new, PotionEffect::effect);

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String argument() {
            return effect.toString();
        }

        @Override
        public List<Item> candidateItems() {
            return FORMS;
        }

        @Override
        public boolean matches(AEItemKey key) {
            if (!FORMS.contains(key.getItem())) {
                return false;
            }
            var contents = key.get(DataComponents.POTION_CONTENTS);
            if (contents == null) {
                return false;
            }
            Set<Holder<MobEffect>> types = new HashSet<>();
            contents.getAllEffects().forEach(instance -> types.add(instance.getEffect()));
            return types.size() == 1 && types.iterator().next().is(effect);
        }
    }

    /**
     * EC-enchanted-books: an enchanted book that stores {@code enchantment} at any level. One book can satisfy several
     * entries. Enchantments on equipment (EC-equipment-enchantments) are not ruled and don't count.
     */
    record EnchantmentOnBook(ResourceLocation enchantment) implements MatchRule {
        static final String TYPE = "enchantment_on_book";
        // Enchantments are a datapack registry; ManifestLoader checks the id exists once registries are known.
        static final MapCodec<EnchantmentOnBook> CODEC = ResourceLocation.CODEC.fieldOf("enchantment")
                .xmap(EnchantmentOnBook::new, EnchantmentOnBook::enchantment);

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String argument() {
            return enchantment.toString();
        }

        @Override
        public List<Item> candidateItems() {
            return List.of(Items.ENCHANTED_BOOK);
        }

        @Override
        public boolean matches(AEItemKey key) {
            if (key.getItem() != Items.ENCHANTED_BOOK) {
                return false;
            }
            var stored = key.get(DataComponents.STORED_ENCHANTMENTS);
            return stored != null && stored.keySet().stream().anyMatch(holder -> holder.is(enchantment));
        }
    }
}
