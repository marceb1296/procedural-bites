package dev.proceduralbites.core;

import java.util.Set;

/** Qué camino le toca a un ítem según cómo se usa, si es poción y qué devuelve al terminar. */
public final class Routes {
    private Routes() {
    }

    public enum Use {
        EAT, DRINK, OTHER
    }

    /** GUESS_*: sin recipiente declarado, la textura decide (tazón o vidrio; si no, sólido u omitido). */
    public enum Route {
        SOLID, CONTAINER, POTION, SKIP, GUESS_BOWL, GUESS_GLASS;

        public String vessel() {
            return this == GUESS_BOWL ? "minecraft:bowl" : this == GUESS_GLASS ? GLASS_BOTTLE : null;
        }
    }

    public static final String GLASS_BOTTLE = "minecraft:glass_bottle";

    public record Plan(Route route, String reason) {
    }

    /** Recipientes que cuentan aunque el ítem se coma; el palito de una brocheta no. */
    public static final Set<String> VESSELS = Set.of("minecraft:bowl", "minecraft:glass_bottle", "minecraft:bucket");

    public static Plan choose(Use use, boolean potion, boolean thrown, String container) {
        if (use == Use.OTHER) {
            return new Plan(Route.SKIP, "no se come ni se bebe");
        }
        if (thrown) {
            return new Plan(Route.SKIP, "poción arrojadiza: se lanza, no se bebe");
        }
        if (potion) {
            return new Plan(Route.POTION, null);
        }
        if (container != null && (use == Use.DRINK || VESSELS.contains(container))) {
            return new Plan(Route.CONTAINER, null);
        }
        if (container != null) {
            return new Plan(Route.SOLID, null);
        }
        return new Plan(use == Use.EAT ? Route.GUESS_BOWL : Route.GUESS_GLASS, null);
    }
}
