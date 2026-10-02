package dev.proceduralbites.neoforge;

import dev.proceduralbites.core.BiteFrames;
import dev.proceduralbites.core.Hints;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuración de cliente. Se lee en cada recarga de recursos (F3+T). */
final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec.BooleanValue SOLIDS = BUILDER
            .comment("Comidas que se muerden")
            .define("solids", true);
    static final ModConfigSpec.BooleanValue DRINKS = BUILDER
            .comment("Bebidas, vasos, copas y tazas: baja el nivel")
            .define("drinks", true);
    static final ModConfigSpec.BooleanValue BOWLS = BUILDER
            .comment("Comida en tazón")
            .define("bowls", true);
    static final ModConfigSpec.BooleanValue POTIONS = BUILDER
            .comment("Pociones")
            .define("potions", true);
    static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED = BUILDER
            .comment("Ítems sin animación, por id; \"*\" vale por cualquier tramo (p. ej. \"minecraft:*_stew\")")
            .defineListAllowEmpty("excluded", List.of(), () -> "mod:item", o -> o instanceof String);
    static final ModConfigSpec.BooleanValue CACHE = BUILDER
            .comment("Guardar los fotogramas generados en .cache/proceduralbites para cargar más rápido")
            .define("cache", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    static boolean disabled(BiteFrames.Kind kind) {
        if (!SPEC.isLoaded()) {
            return false;
        }
        return switch (kind) {
            case SOLID -> !SOLIDS.get();
            case DRINK, CUP -> !DRINKS.get();
            case BOWL -> !BOWLS.get();
            case POTION -> !POTIONS.get();
        };
    }

    static String excluded(ResourceLocation item) {
        if (!SPEC.isLoaded()) {
            return null;
        }
        String id = item.toString();
        for (String pattern : EXCLUDED.get()) {
            if (Hints.matches(pattern, id)) {
                return pattern;
            }
        }
        return null;
    }

    static boolean cache() {
        return !SPEC.isLoaded() || CACHE.get();
    }
}
