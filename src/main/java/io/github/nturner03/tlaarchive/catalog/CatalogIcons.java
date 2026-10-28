package io.github.nturner03.tlaarchive.catalog;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.BannerBlock;

/**
 * The item a catalog row shows for an entry, built from its rule: the item for {@code item_id}, the banner of that
 * colour, a potion carrying the effect, or an enchanted book with the enchantment. Anything it can't resolve shows
 * as a barrier.
 */
public final class CatalogIcons {
    private CatalogIcons() {
    }

    public static ItemStack icon(CatalogEntry entry, HolderLookup.Provider registries) {
        var id = ResourceLocation.tryParse(entry.ruleArgument());
        return switch (entry.rule()) {
            case "item_id" -> id == null ? missing() : BuiltInRegistries.ITEM.getOptional(id)
                    .map(ItemStack::new).filter(stack -> !stack.isEmpty()).orElseGet(CatalogIcons::missing);
            case "banner_base_color" -> Optional.ofNullable(DyeColor.byName(entry.ruleArgument(), null))
                    .map(color -> new ItemStack(BannerBlock.byColor(color).asItem()))
                    .orElseGet(CatalogIcons::missing);
            case "potion_effect" -> id == null ? missing() : potion(id);
            case "enchantment_on_book" -> id == null ? missing() : registries.lookup(Registries.ENCHANTMENT)
                    .flatMap(lookup -> lookup.get(ResourceKey.create(Registries.ENCHANTMENT, id)))
                    .map(holder -> EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, 1)))
                    .orElseGet(CatalogIcons::missing);
            default -> missing();
        };
    }

    /** The first registered potion whose only effect is {@code effect}, else a potion with just that effect. */
    private static ItemStack potion(ResourceLocation effect) {
        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(effect).orElse(null);
        if (holder == null) {
            return missing();
        }
        for (var potion : BuiltInRegistries.POTION.holders().toList()) {
            var effects = potion.value().getEffects();
            if (effects.size() == 1 && effects.get(0).getEffect().equals(holder)) {
                return PotionContents.createItemStack(Items.POTION, potion);
            }
        }
        var stack = new ItemStack(Items.POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(),
                List.of(new MobEffectInstance(holder))));
        return stack;
    }

    private static ItemStack missing() {
        return new ItemStack(Items.BARRIER);
    }
}
