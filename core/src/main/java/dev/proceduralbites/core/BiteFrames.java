package dev.proceduralbites.core;

import dev.proceduralbites.core.Params.BitePattern;
import java.util.ArrayList;
import java.util.List;

/** Punto de entrada para los loaders: los fotogramas de un ítem o por qué se omite. Nunca lanza. */
public final class BiteFrames {
    private BiteFrames() {
    }

    /** Lado máximo de una textura que se genera: a 512x un sólido tarda ~9 s y congelaría al cliente. */
    public static final int MAX_SIZE = 256;

    public enum Kind {
        SOLID, DRINK, BOWL, POTION,
        /** Copa o envase de vidrio que no se devuelve: se vacía desde arriba y el vidrio queda. */
        CUP
    }

    /** Fotogramas (cada uno, sus capas de abajo arriba) o el motivo por el que se omite. */
    public record Result(Kind kind, List<List<RgbaImage>> frames, String skipped) {
        public static Result skip(Kind kind, String why) {
            return new Result(kind, List.of(), why);
        }

        public boolean generated() {
            return skipped == null;
        }

        public int layers() {
            return frames.isEmpty() ? 0 : frames.get(0).size();
        }
    }

    /** Comida que se muerde; {@code name} (la ruta del id del ítem) fija la semilla de las mordidas. */
    public static Result solid(RgbaImage texture, String name) {
        RgbaImage im = texture.firstFrame();
        String big = oversized(im);
        if (big != null) {
            return Result.skip(Kind.SOLID, big);
        }
        try {
            return single(Kind.SOLID, Solids.frames(im, name, BitePattern.AUTO));
        } catch (RuntimeException e) {
            // texturas raras (vacía, un píxel, todo comido)
            return Result.skip(Kind.SOLID, e.toString());
        }
    }

    /**
     * Comida en un recipiente que se devuelve: si es de vidrio o calza con el vacío baja el
     * nivel ({@link Kind#DRINK}); si no, se reconstruye el tazón ({@link Kind#BOWL}).
     */
    public static Result container(RgbaImage texture, RgbaImage emptyTexture) {
        return container(texture, emptyTexture, false);
    }

    /**
     * Con {@code eaten}, el ítem se come y no devuelve recipiente: en vidrio de otra forma es una copa
     * ({@link Kind#CUP}).
     */
    public static Result container(RgbaImage texture, RgbaImage emptyTexture, boolean eaten) {
        RgbaImage full = texture.firstFrame();
        RgbaImage empty = emptyTexture.firstFrame();
        // de distinto tamaño sí se puede (un mod a 16x con el tazón de un pack a 32x)
        String big = oversized(full) != null ? oversized(full) : oversized(empty);
        if (big != null) {
            return Result.skip(Kind.DRINK, big);
        }
        Kind kind = Kind.DRINK;
        try {
            Cups.Choice choice = Cups.choose(full, empty, eaten);
            if (choice.contents().isEmpty()) {
                return Result.skip(kind, "no detectó contenido");
            }
            kind = choice.kind();
            if (choice.skipped() != null) {
                return Result.skip(kind, choice.skipped());
            }
            return single(kind, Cups.frames(full, empty, choice));
        } catch (RuntimeException e) {
            return Result.skip(kind, e.toString());
        }
    }

    /** ¿La textura dibuja el recipiente {@code empty} aunque el ítem no lo devuelva? */
    public static boolean drawsVessel(RgbaImage texture, RgbaImage emptyTexture, boolean drink) {
        RgbaImage full = texture.firstFrame();
        RgbaImage empty = emptyTexture.firstFrame();
        if (oversized(full) != null || oversized(empty) != null) {
            return false;
        }
        try {
            return Bowls.drawsVessel(full, empty, drink);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** ¿La comida viene en el vidrio {@code empty} (colores exactos) aunque no devuelva recipiente? */
    public static boolean drawsGlassFood(RgbaImage texture, RgbaImage emptyTexture) {
        RgbaImage full = texture.firstFrame();
        RgbaImage empty = emptyTexture.firstFrame();
        if (oversized(full) != null || oversized(empty) != null) {
            return false;
        }
        try {
            return Cups.drawsGlassFood(full, empty);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Ítem que no devuelve recipiente. {@code stack}: las versiones del recipiente en la pila
     * de recursos, vanilla primero; si la textura dibuja alguna, se genera con la activa.
     */
    public static Result guessed(RgbaImage texture, String name, boolean drink, List<RgbaImage> stack) {
        return drink ? guessed(texture, name, true, List.of(), stack) : guessed(texture, name, false, stack, List.of());
    }

    /** La bebida busca la botella; la comida, el tazón, la botella (frascos y copas) y su propio envase. */
    public static Result guessed(RgbaImage texture, String name, boolean drink, List<RgbaImage> bowls,
            List<RgbaImage> bottles) {
        return guessed(texture, name, drink, bowls, bottles, List.of());
    }

    /** Además, el tazón que el mod repite en varias comidas; {@code others}: las demás comidas del mod. */
    public static Result guessed(RgbaImage texture, String name, boolean drink, List<RgbaImage> bowls,
            List<RgbaImage> bottles, List<RgbaImage> others) {
        if (oversized(texture.firstFrame()) == null) {
            if (!drink) {
                RgbaImage bowl = guessedBowl(texture, bowls);
                if (bowl != null) {
                    return container(texture, bowl);
                }
            }
            if (drink ? drawsAny(texture, bottles, true) : drawsAnyGlassFood(texture, bottles)) {
                return container(texture, bottles.get(bottles.size() - 1), !drink);
            }
            if (!drink) {
                Result own = own(texture);
                if (own != null) {
                    return own;
                }
                Result shaped = template(texture, others);
                if (shaped != null) {
                    return shaped;
                }
            }
        }
        return drink ? Result.skip(Kind.DRINK, "bebida sin recipiente") : solid(texture, name);
    }

    /**
     * Las de {@code others} que comparten dibujo con {@code texture}: con ellas solas, {@link
     * #template} da lo mismo.
     */
    public static List<RgbaImage> templateGroup(RgbaImage texture, List<RgbaImage> others) {
        RgbaImage full = texture.firstFrame();
        if (oversized(full) != null || others.isEmpty()) {
            return List.of();
        }
        try {
            List<RgbaImage> firsts = new ArrayList<>();
            for (RgbaImage o : others) {
                firsts.add(o.firstFrame());
            }
            return List.copyOf(Templates.group(full, firsts).members());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Comida en un tazón que el mod repite en varias comidas y que no calza con el de la pila; {@code
     * null} si no lo es.
     */
    public static Result template(RgbaImage texture, List<RgbaImage> others) {
        RgbaImage full = texture.firstFrame();
        if (oversized(full) != null || others.isEmpty()) {
            return null;
        }
        try {
            List<RgbaImage> firsts = new ArrayList<>();
            for (RgbaImage o : others) {
                firsts.add(o.firstFrame());
            }
            Bowls.Own own = Templates.shapeBowl(full, firsts);
            return own == null ? null : single(Kind.BOWL, Templates.frames(full, own));
        } catch (RuntimeException e) {
            return null;                        // textura rara: sigue como sólido
        }
    }

    /**
     * Tazón con el que se genera una comida que no lo devuelve, o {@code null}. Si dibuja un
     * tazón propio en el lugar de uno de la pila, se usa ese de la pila: así el resultado no
     * depende del pack.
     */
    static RgbaImage guessedBowl(RgbaImage texture, List<RgbaImage> bowls) {
        RgbaImage full = texture.firstFrame();
        for (RgbaImage ref : bowls) {
            RgbaImage empty = ref.firstFrame();
            if (oversized(empty) != null) {
                continue;
            }
            try {
                Bowls.Fit fit = Bowls.bowlFit(full, empty);
                if (fit != null && fit.same()) {
                    return bowls.get(bowls.size() - 1);
                }
                if (fit != null && Bowls.ownBowl(full, fit) != null) {
                    return ref;
                }
            } catch (RuntimeException e) {
                // textura rara: como si no calzara
            }
        }
        return null;
    }

    /** Vaso, frasco o botella de vidrio dibujado por el mod ({@link Kind#CUP}); {@code null} si no hay envase. */
    public static Result own(RgbaImage texture) {
        RgbaImage full = texture.firstFrame();
        if (oversized(full) != null) {
            return null;
        }
        Kind kind = Kind.CUP;
        try {
            Cups.Choice choice = Cups.chooseOwn(full);
            if (choice == null) {
                return null;
            }
            kind = choice.kind();
            if (choice.skipped() != null) {
                return Result.skip(kind, choice.skipped());
            }
            return single(kind, Cups.frames(full, null, choice));
        } catch (RuntimeException e) {
            return Result.skip(kind, e.toString());
        }
    }

    public static final String NO_SURFACE = "pista top_cup: no se encontró la superficie";

    /** Pista top_cup: baja la superficie de la taza; sin superficie visible, {@link #NO_SURFACE}. */
    public static Result topCup(RgbaImage texture) {
        RgbaImage full = texture.firstFrame();
        String big = oversized(full);
        if (big != null) {
            return Result.skip(Kind.CUP, big);
        }
        try {
            Mask surface = TopCups.surface(full);
            if (surface == null) {
                return Result.skip(Kind.CUP, NO_SURFACE);
            }
            return single(Kind.CUP, TopCups.frames(full, surface));
        } catch (RuntimeException e) {
            return Result.skip(Kind.CUP, e.toString());
        }
    }

    private static boolean drawsAny(RgbaImage texture, List<RgbaImage> stack, boolean drink) {
        for (RgbaImage ref : stack) {
            if (drawsVessel(texture, ref, drink)) {
                return true;
            }
        }
        return false;
    }

    private static boolean drawsAnyGlassFood(RgbaImage texture, List<RgbaImage> stack) {
        for (RgbaImage ref : stack) {
            if (drawsGlassFood(texture, ref)) {
                return true;
            }
        }
        return false;
    }

    /** Poción: {@code overlay} es el líquido sin teñir (lo tiñe el juego) y {@code bottle}, la botella. */
    public static Result potion(RgbaImage bottleTexture, RgbaImage overlayTexture) {
        RgbaImage bottle = bottleTexture.firstFrame();
        RgbaImage overlay = overlayTexture.firstFrame();
        String bad = rejectPair(overlay, bottle);
        if (bad != null) {
            return Result.skip(Kind.POTION, bad);
        }
        try {
            return new Result(Kind.POTION, List.copyOf(Potions.potionLayers(bottle, overlay)), null);
        } catch (RuntimeException e) {
            return Result.skip(Kind.POTION, e.toString());
        }
    }

    private static String oversized(RgbaImage im) {
        if (im.width > MAX_SIZE || im.height > MAX_SIZE) {
            return "textura de " + im.width + "x" + im.height + ", más grande que el tope de " + MAX_SIZE + "x"
                    + MAX_SIZE;
        }
        return null;
    }

    private static String rejectPair(RgbaImage im, RgbaImage other) {
        String big = oversized(im);
        if (big == null) {
            big = oversized(other);
        }
        if (big != null) {
            return big;
        }
        if (im.width != other.width || im.height != other.height) {
            return "texturas de distinto tamaño: " + im.width + "x" + im.height + " y " + other.width + "x"
                    + other.height;
        }
        return null;
    }

    private static Result single(Kind kind, List<RgbaImage> frames) {
        List<List<RgbaImage>> out = new ArrayList<>();
        for (RgbaImage f : frames) {
            out.add(List.of(f));
        }
        return new Result(kind, List.copyOf(out), null);
    }
}
