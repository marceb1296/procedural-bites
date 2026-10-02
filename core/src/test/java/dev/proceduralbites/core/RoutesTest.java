package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.proceduralbites.core.Routes.Plan;
import dev.proceduralbites.core.Routes.Route;
import dev.proceduralbites.core.Routes.Use;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Ítems reales con los datos verificados en sus fuentes (uso, clase y lo que devuelven), más casos inventados. */
class RoutesTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "-", value = {
        // ítem,                        uso,   poción, arrojadiza, devuelve,               camino
        "minecraft:apple,               EAT,   false, false, -,                      GUESS_BOWL",
        "minecraft:golden_apple,        EAT,   false, false, -,                      GUESS_BOWL",
        "minecraft:honey_bottle,        DRINK, false, false, minecraft:glass_bottle, CONTAINER",
        "minecraft:milk_bucket,         DRINK, false, false, minecraft:bucket,       CONTAINER",
        "minecraft:mushroom_stew,       EAT,   false, false, minecraft:bowl,         CONTAINER",
        "minecraft:suspicious_stew,     EAT,   false, false, minecraft:bowl,         CONTAINER",
        "minecraft:potion,              DRINK, true,  false, minecraft:glass_bottle, POTION",
        "minecraft:splash_potion,       DRINK, true,  true,  minecraft:glass_bottle, SKIP",
        "minecraft:lingering_potion,    DRINK, true,  true,  minecraft:glass_bottle, SKIP",
        "minecraft:ominous_bottle,      DRINK, false, false, -,                      GUESS_GLASS",
        "minecraft:bread,               EAT,   false, false, -,                      GUESS_BOWL",
        "minecraft:water_bucket,        OTHER, false, false, minecraft:bucket,       SKIP",
        "minecraft:bow,                 OTHER, false, false, -,                      SKIP",
        "farmersdelight:hot_cocoa,      DRINK, false, false, minecraft:glass_bottle, CONTAINER",
        "farmersdelight:beef_stew,      EAT,   false, false, minecraft:bowl,         CONTAINER",
        "farmersdelight:barbecue_stick, EAT,   false, false, -,                      GUESS_BOWL",
        // sopas que no declaran recipiente y otras que sí (usingConvertsTo), verificado con javap
        "croptopia:steamed_broccoli,    EAT,   false, false, -,                      GUESS_BOWL",
        "croptopia:pumpkin_soup,        EAT,   false, false, -,                      GUESS_BOWL",
        "croptopia:leek_soup,           EAT,   false, false, minecraft:bowl,         CONTAINER",
        "croptopia:tea,                 DRINK, false, false, -,                      GUESS_GLASS",
        "croptopia:coffee,              DRINK, false, false, minecraft:glass_bottle, CONTAINER",
        "mod:skewer,                    EAT,   false, false, minecraft:stick,        SOLID",
        "mod:jam_jar,                   EAT,   false, false, minecraft:glass_bottle, CONTAINER",
        "mod:cup_of_tea,                DRINK, false, false, mod:cup,                CONTAINER",
    })
    void route(String item, Use use, boolean potion, boolean thrown, String container, Route want) {
        Plan plan = Routes.choose(use, potion, thrown, container);
        assertEquals(want, plan.route(), item + ": " + plan.reason());
        assertEquals(want == Route.GUESS_BOWL ? "minecraft:bowl"
                : want == Route.GUESS_GLASS ? "minecraft:glass_bottle" : null, plan.route().vessel(), item);
        if (want == Route.SKIP) {
            assertNotNull(plan.reason());
            assertFalse(plan.reason().isEmpty());
        }
    }
}
