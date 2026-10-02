package dev.proceduralbites.neoforge;

import dev.proceduralbites.core.Routes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.UseAnim;

/**
 * Los ítems que se comen o se beben y el recipiente que devuelven: {@code usingConvertsTo}
 * (guisos vanilla), {@code getCraftingRemainingItem} (miel, leche, mods) o la botella de
 * {@code PotionItem}, fija en su {@code finishUsingItem}.
 */
final class Consumables {
    private Consumables() {
    }

    record Entry(ResourceLocation item, Routes.Plan plan, ResourceLocation container) {
    }

    static List<Entry> discover() {
        List<Entry> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Entry e = entry(item);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    static Entry entry(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        ItemStack stack;
        Routes.Use use;
        try {
            stack = item.getDefaultInstance();
            UseAnim anim = item.getUseAnimation(stack);
            use = anim == UseAnim.EAT ? Routes.Use.EAT : anim == UseAnim.DRINK ? Routes.Use.DRINK : Routes.Use.OTHER;
        } catch (RuntimeException e) {
            ProceduralBites.LOGGER.debug("Se omite {}: getUseAnimation lanzó {}", id, e.toString());
            return null;
        }
        if (use == Routes.Use.OTHER) {
            return null;
        }
        ResourceLocation container = container(item, stack);
        Routes.Plan plan = Routes.choose(use, item instanceof PotionItem, item instanceof ThrowablePotionItem,
                container == null ? null : container.toString());
        return new Entry(id, plan, container);
    }

    private static ResourceLocation container(Item item, ItemStack stack) {
        Item out = null;
        try {
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null && food.usingConvertsTo().isPresent() && !food.usingConvertsTo().get().isEmpty()) {
                out = food.usingConvertsTo().get().getItem();
            } else {
                ItemStack rest = item.getCraftingRemainingItem(stack);
                if (!rest.isEmpty()) {
                    out = rest.getItem();
                } else if (item instanceof PotionItem) {
                    out = Items.GLASS_BOTTLE;
                }
            }
        } catch (RuntimeException e) {
            ProceduralBites.LOGGER.debug("Recipiente de {} ilegible: {}", item, e.toString());
        }
        return out == null || out == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(out);
    }
}
