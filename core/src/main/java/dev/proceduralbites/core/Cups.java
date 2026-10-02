package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.EXACT_GLASS_SHARE;
import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.PALETTE_TOLERANCE;
import static dev.proceduralbites.core.Params.TALL_VESSEL;
import static dev.proceduralbites.core.Params.TOP_VIEW_WIDTH;

import dev.proceduralbites.core.BiteFrames.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Camino de un ítem con recipiente: bebida, tazón, taza vista desde arriba, copa o envase propio. */
public final class Cups {
    private Cups() {
    }

    private static final int[] TRANSPARENT = {0, 0, 0, 0};

    public static final String OPAQUE = "recipiente opaco de otra forma: no se ve el contenido";

    /** Camino y contenido con los que se generan los fotogramas, o el motivo para omitir ({@code skipped}). */
    public record Choice(Kind kind, Mask contents, String skipped, RgbaImage ownBowl) {
        public Choice(Kind kind, Mask contents) {
            this(kind, contents, null, null);
        }

        public Choice(Kind kind, Mask contents, String skipped) {
            this(kind, contents, skipped, null);
        }
    }

    /**
     * {@code eaten}: el ítem se come, no se bebe. Una botella opaca de otra forma se omite
     * ({@link #OPAQUE}): no se ve el contenido.
     */
    public static Choice choose(RgbaImage full, RgbaImage empty, boolean eaten) {
        boolean glass = Drinks.isGlass(empty);
        if (!glass) {
            Bowls.Own own = Bowls.ownBowl(full, empty);
            if (own != null) {
                return new Choice(Kind.BOWL, own.contents(), null, own.bowl());
            }
        }
        Mask liquid = Drinks.findContents(full, empty);
        if (liquid.isEmpty()) {
            return new Choice(Kind.DRINK, liquid);
        }
        boolean aligned = Drinks.aligned(full, empty, liquid);
        if (!glass && !aligned) {
            if (tallVessel(empty)) {
                return new Choice(Kind.DRINK, liquid, OPAQUE);
            }
            return new Choice(Kind.BOWL, liquid);
        }
        if (glass && !aligned) {
            if (eaten && !innerLabel(full, liquid)) {
                return new Choice(Kind.CUP, cupContents(full, empty));
            }
            Mask surface = topViewSurface(full, liquid);
            if (surface != null) {
                return new Choice(Kind.BOWL, surface);
            }
        }
        return new Choice(Kind.DRINK, liquid);
    }

    /** Ítem que dibuja su propio envase y no lo devuelve, o {@code null} si no dibuja ninguno. */
    public static Choice chooseOwn(RgbaImage full) {
        if (OwnGlass.labeledGlass(full)) {
            return new Choice(Kind.CUP, new Mask(), OwnGlass.LABELED);
        }
        OwnGlass.Parts parts = OwnGlass.ownGlass(full);
        return parts == null ? null : new Choice(Kind.CUP, parts.contents());
    }

    public static List<RgbaImage> frames(RgbaImage full, RgbaImage empty, Choice choice) {
        if (choice.skipped() != null) {
            return List.of();
        }
        if (empty == null) {
            OwnGlass.Parts parts = OwnGlass.ownGlass(full);
            if (parts == null) {
                throw new ArithmeticException("sin envase propio");
            }
            return OwnGlass.frames(full, parts);
        }
        return switch (choice.kind()) {
            case BOWL -> Bowls.bowlFrames(full, choice.contents(), empty, choice.ownBowl());
            case CUP -> cupFrames(full, choice.contents(), empty);
            default -> Drinks.liquidFrames(full, choice.contents(), empty);
        };
    }

    static boolean tallVessel(RgbaImage empty) {
        Mask mask = Masks.opaque(empty);
        if (mask.isEmpty()) {
            throw new ArithmeticException("recipiente vacío");
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (Point p : mask) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
        }
        return maxY(mask) - mask.first().y() + 1 > TALL_VESSEL * (maxX - minX + 1);
    }

    /**
     * ¿La comida viene en vidrio aunque no devuelva recipiente? Con colores exactos: por
     * parecido entran comidas claras o azuladas que no tienen vidrio.
     */
    public static boolean drawsGlassFood(RgbaImage full, RgbaImage empty) {
        Mask mask = Masks.opaque(full);
        if (mask.isEmpty() || !Drinks.isGlass(empty)) {
            return false;
        }
        Set<Integer> palette = exact(glassPalette(empty));
        int glass = 0;
        for (Point p : mask) {
            if (palette.contains(rgb(full.get(p)))) {
                glass++;
            }
        }
        return glass >= EXACT_GLASS_SHARE * mask.size();
    }

    /**
     * Superficie del líquido de una taza vista desde arriba, o {@code null}. La porcelana
     * blanca entra al contenido por paleta: la superficie es la pieza saturada más grande,
     * dentro del recipiente y ancha.
     */
    static Mask topViewSurface(RgbaImage full, Mask contents) {
        if (contents.isEmpty()) {
            throw new ArithmeticException("sin contenido");
        }
        Mask mask = Masks.opaque(full);
        Mask shell = mask.minus(contents);
        int bottom = maxY(contents);
        int count = 0;
        int saturatedCount = 0;
        Mask saturated = new Mask();
        for (Point p : contents) {
            boolean sat = Colors.hsv(full.get(p))[1] >= 0.25;
            if (sat) {
                saturated.add(p);
            }
            if (p.y() == bottom) {
                count++;
                saturatedCount += sat ? 1 : 0;
            }
        }
        if (shell.isEmpty() || saturatedCount * 2 >= count) {
            return null;
        }
        List<Mask> comps = Masks.bySize(Masks.components(saturated));
        if (comps.isEmpty()) {
            return null;
        }
        Mask top = comps.get(0);
        Map<Integer, int[]> rows = rowSpans(top);
        if (top.first().y() < shell.first().y() || maxY(top) >= bottom) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (Point p : mask) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
        }
        int width = 0;
        for (int[] r : rows.values()) {
            width = Math.max(width, r[1] - r[0] + 1);
        }
        return width >= TOP_VIEW_WIDTH * (maxX - minX + 1) ? top : null;
    }

    /** ¿La etiqueta cruza el recipiente de lado a lado? Distingue un frasco que se come de una copa de postre. */
    static boolean innerLabel(RgbaImage full, Mask contents) {
        Mask mask = Masks.opaque(full);
        Mask shell = mask.minus(contents);
        if (shell.isEmpty()) {
            return false;
        }
        int top = shell.first().y();
        Map<Integer, int[]> rows = rowSpans(mask);
        for (Mask piece : Masks.components(Drinks.splitLabel(full, contents).label())) {
            if (piece.first().y() < top) {
                continue;
            }
            for (Map.Entry<Integer, int[]> e : rowSpans(piece).entrySet()) {
                int[] row = rows.get(e.getKey());
                if (e.getValue()[0] == row[0] && e.getValue()[1] == row[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<int[]> glassPalette(RgbaImage empty) {
        return Drinks.rgbSet(empty, Masks.opaque(empty).minus(Drinks.cork(empty, new Mask(), null)));
    }

    private static Set<Integer> exact(List<int[]> palette) {
        Set<Integer> out = new HashSet<>();
        for (int[] c : palette) {
            out.add(rgb(c));
        }
        return out;
    }

    private static int rgb(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /**
     * Comida de una copa de vidrio que se come. La crema es casi del color del vidrio: el
     * contenido es el interior que no es exactamente vidrio, lo que no se parece al vidrio y
     * todo lo que está sobre la primera fila con vidrio exacto.
     */
    static Mask cupContents(RgbaImage full, RgbaImage empty) {
        Mask mask = Masks.opaque(full);
        int k = full.pxScale();
        List<int[]> palette = glassPalette(empty);
        if (palette.isEmpty() && !mask.isEmpty()) {
            throw new ArithmeticException("recipiente sin colores de vidrio");
        }
        Set<Integer> exact = exact(palette);
        Drinks.Palette near = new Drinks.Palette(palette, PALETTE_TOLERANCE);
        Mask inner = mask.minus(Masks.outerBorder(mask)).and(Masks.thickPart(mask, 3 * k));
        int top = 0;
        for (Point p : mask) {
            if (exact.contains(rgb(full.get(p)))) {
                top = p.y();   // el primero es el de más arriba
                break;
            }
        }
        Mask food = new Mask();
        for (Point p : mask) {
            int[] c = full.get(p);
            if ((inner.contains(p) && !exact.contains(rgb(c))) || !near.near(c) || p.y() < top) {
                food.add(p);
            }
        }
        return food;
    }

    static int[] cupBox(Mask shell) {
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        for (Point p : shell) {
            x0 = Math.min(x0, p.x());
            x1 = Math.max(x1, p.x());
        }
        return new int[] {x0, shell.first().y(), x1, maxY(shell)};
    }

    private static boolean inBox(Point p, int[] box) {
        return p.x() >= box[0] && p.x() <= box[2] && p.y() >= box[1] && p.y() <= box[3];
    }

    static Mask cupEdge(Mask shell, Mask food) {
        int[] box = cupBox(shell);
        Mask out = new Mask();
        for (Point p : food) {
            if (inBox(p, box) && (p.x() == box[0] || p.x() == box[2] || p.y() == box[3])) {
                out.add(p);
            }
        }
        return out;
    }

    /** Comida dibujada sobre el vidrio que, si se quita, deja el vaso abierto: solo la que hace falta. */
    static Mask cupSeal(RgbaImage full, Mask food) {
        Mask shell = Masks.opaque(full).minus(food);
        if (shell.isEmpty()) {
            return new Mask();
        }
        Mask seal = cupEdge(shell, food);
        if (seal.isEmpty()) {
            return seal;
        }
        int w = full.width;
        int h = full.height;
        int best = Masks.enclosed(shell.union(seal), w, h).size();
        if (best <= Masks.enclosed(shell, w, h).size()) {
            return new Mask();
        }
        for (Point p : new Mask(seal)) {
            Mask without = new Mask(seal);
            without.remove(p);
            int left = Masks.enclosed(shell.union(without), w, h).size();
            if (left >= best) {
                seal = without;
                best = left;
            }
        }
        return seal;
    }

    /**
     * La copa se vacía desde arriba, como con una cuchara; lo revelado queda transparente y el
     * vidrio nunca cambia. En el último fotograma no queda comida fuera del vidrio.
     */
    static List<RgbaImage> cupFrames(RgbaImage full, Mask contents, RgbaImage empty) {
        if (contents.isEmpty()) {
            throw new ArithmeticException("copa sin comida");
        }
        int k = full.pxScale();
        Set<Integer> palette = exact(glassPalette(empty));
        List<Integer> rows = Drinks.rowsOf(contents);
        Mask mask = Masks.opaque(full);
        Set<Mask> drawn = new HashSet<>(Masks.components(mask));
        Mask shell = mask.minus(contents);
        Mask seal = cupSeal(full, contents);
        List<Point> glass = new ArrayList<>();
        for (Point p : shell) {
            if (palette.contains(rgb(full.get(p)))) {
                glass.add(p);
            }
        }
        if (glass.isEmpty()) {
            shell.forEach(glass::add);
        }
        double[] steps = Solids.schedule(LAST_LIQUID);
        List<RgbaImage> frames = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            int gone = PyMath.round((1 - steps[i]) * rows.size());
            int cut = gone < rows.size() ? rows.get(gone) : rows.get(rows.size() - 1) + 1;
            boolean last = i == steps.length - 1;
            RgbaImage out = full.copy();
            for (Point p : contents) {
                if (seal.contains(p)) {
                    if (p.y() < cut || last) {
                        out.set(p, full.get(nearest(glass, p)));
                    }
                } else if (p.y() < cut) {
                    out.set(p, TRANSPARENT);
                }
            }
            if (last && !shell.isEmpty()) {
                int[] box = cupBox(shell);
                for (Point p : contents) {
                    if (!inBox(p, box)) {
                        out.set(p, TRANSPARENT);
                    }
                }
            }
            List<Mask> comps = Masks.bySize(Masks.components(Masks.opaque(out)));
            for (Mask comp : comps.subList(Math.min(1, comps.size()), comps.size())) {
                if (comp.size() <= 2 * k * k && !drawn.contains(comp) && !allGlass(full, comp, palette)) {
                    for (Point p : comp) {
                        out.set(p, TRANSPARENT);
                    }
                }
            }
            frames.add(out);
        }
        return frames;
    }

    static Point nearest(List<Point> points, Point p) {
        Point best = null;
        long bestD = Long.MAX_VALUE;
        for (Point q : points) {
            long dx = q.x() - p.x();
            long dy = q.y() - p.y();
            long d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = q;
            }
        }
        if (best == null) {
            throw new ArithmeticException("copa sin vidrio");
        }
        return best;
    }

    private static boolean allGlass(RgbaImage full, Mask comp, Set<Integer> palette) {
        for (Point p : comp) {
            if (!palette.contains(rgb(full.get(p)))) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, int[]> rowSpans(Mask m) {
        Map<Integer, int[]> rows = new TreeMap<>();
        for (Point p : m) {
            int[] r = rows.computeIfAbsent(p.y(), y -> new int[] {p.x(), p.x()});
            r[0] = Math.min(r[0], p.x());
            r[1] = Math.max(r[1], p.x());
        }
        return rows;
    }

    private static int maxY(Mask m) {
        int y = Integer.MIN_VALUE;
        for (Point p : m) {
            y = Math.max(y, p.y());
        }
        return y;
    }
}
