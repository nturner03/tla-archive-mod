package io.github.nturner03.tlaarchive.gametest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import org.jetbrains.annotations.Nullable;

/** Item stacks for the edge-case examples in the pack's {@code docs/edge-cases.md}. */
final class Samples {
    private Samples() {
    }

    static ItemStack potion(Item form, Holder<Potion> potion) {
        return PotionContents.createItemStack(form, potion);
    }

    /** EC-enchanted-books: one book with efficiency IV and fortune III. */
    static ItemStack efficiencyFortuneBook(GameTestHelper helper) {
        return book(helper, Enchantments.EFFICIENCY, 4, Enchantments.FORTUNE, 3);
    }

    /** A book with one enchantment. */
    static ItemStack book(GameTestHelper helper, ResourceKey<Enchantment> key, int level) {
        return book(helper, key, level, null, 0);
    }

    private static ItemStack book(GameTestHelper helper, ResourceKey<Enchantment> first, int firstLevel,
            @Nullable ResourceKey<Enchantment> second, int secondLevel) {
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment(helper, first), firstLevel);
        if (second != null) {
            enchantments.set(enchantment(helper, second), secondLevel);
        }
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }

    /**
     * Exactly the shipped test manifest's requirements ({@code representative-test v1}): 64 diamonds, the damaged
     * pickaxe, a patterned blue banner, a strength and a swiftness potion, and the efficiency + fortune book.
     */
    static List<ItemStack> manifestStock(GameTestHelper helper) {
        var out = new ArrayList<ItemStack>();
        out.add(new ItemStack(Items.DIAMOND, 64));
        out.addAll(manifestStockWithoutDiamonds(helper));
        return out;
    }

    static List<ItemStack> manifestStockWithoutDiamonds(GameTestHelper helper) {
        var out = new ArrayList<ItemStack>(manifestStockWithoutBook(helper));
        out.add(efficiencyFortuneBook(helper));
        return out;
    }

    static List<ItemStack> manifestStockWithoutBook(GameTestHelper helper) {
        return List.of(damagedRenamedPickaxe(), patternedBanner(helper, Items.BLUE_BANNER),
                potion(Items.POTION, Potions.STRENGTH), potion(Items.POTION, Potions.SWIFTNESS));
    }

    /** EC-ordinary-id: a renamed, half-damaged iron pickaxe. */
    static ItemStack damagedRenamedPickaxe() {
        var pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(pickaxe.getMaxDamage() / 2);
        pickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("Old Faithful"));
        return pickaxe;
    }

    /** EC-banners: a blue banner with patterns. */
    static ItemStack patternedBanner(GameTestHelper helper, Item banner) {
        var patterns = helper.getLevel().registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        var stack = new ItemStack(banner);
        stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
                .add(patterns.getHolderOrThrow(BannerPatterns.STRIPE_TOP), DyeColor.WHITE)
                .add(patterns.getHolderOrThrow(BannerPatterns.CROSS), DyeColor.YELLOW)
                .build());
        return stack;
    }

    private static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {
        return helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }
}
