package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.FOAM_VALUE;
import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.OWN_GLASS_HUE_MAX;
import static dev.proceduralbites.core.Params.OWN_GLASS_HUE_MIN;
import static dev.proceduralbites.core.Params.OWN_GLASS_ROWS;
import static dev.proceduralbites.core.Params.OWN_GLASS_SATURATION_MAX;
import static dev.proceduralbites.core.Params.OWN_GLASS_SATURATION_MIN;
import static dev.proceduralbites.core.Params.OWN_GLASS_VALUE;
import static dev.proceduralbites.core.Params.OWN_NEUTRAL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Vaso, frasco o botella de vidrio que el mod dibuja con sus propios colores y que el ítem no devuelve. */
public final class OwnGlass {
    private OwnGlass() {
    }

    private static final int[] TRANSPARENT = {0, 0, 0, 0};

    public static final String LABELED = "frasco con etiqueta: no se ve el contenido";

    /** Envase (vidrio, borde, vapor), cuerpo (lo que se bebe), adorno, eje de simetría y tapón (corcho o tapa). */
    public record Parts(Mask vessel, Mask body, Mask garnish, int axis, Mask stopper) {
        public Mask contents() {
            return body.union(garnish);
        }
    }

    static boolean glassLike(int[] c) {
        double[] hsv = Colors.hsv(c);
        return OWN_GLASS_HUE_MIN / 360.0 <= hsv[0] && hsv[0] <= OWN_GLASS_HUE_MAX / 360.0
                && OWN_GLASS_SATURATION_MIN <= hsv[1] && hsv[1] <= OWN_GLASS_SATURATION_MAX
                && hsv[2] >= OWN_GLASS_VALUE;
    }

    /** Por fila, los tramos de píxeles seguidos {x0, x1}, de izquierda a derecha. */
    static TreeMap<Integer, List<int[]>> rowRuns(Mask mask) {
        TreeMap<Integer, List<int[]>> out = new TreeMap<>();
        for (Point p : mask) {
            List<int[]> runs = out.computeIfAbsent(p.y(), y -> new ArrayList<>());
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == p.x() - 1) {
                runs.get(runs.size() - 1)[1] = p.x();
            } else {
                runs.add(new int[] {p.x(), p.x()});
            }
        }
        return out;
    }

    static TreeMap<Integer, boolean[]> glassEnds(RgbaImage full) {
        TreeMap<Integer, boolean[]> out = new TreeMap<>();
        for (Map.Entry<Integer, List<int[]>> e : rowRuns(Masks.opaque(full)).entrySet()) {
            List<int[]> runs = e.getValue();
            int y = e.getKey();
            out.put(y, new boolean[] {glassLike(full.get(runs.get(0)[0], y)),
                glassLike(full.get(runs.get(runs.size() - 1)[1], y))});
        }
        return out;
    }

    static List<Integer> glassWalls(RgbaImage full) {
        TreeMap<Integer, boolean[]> ends = glassEnds(full);
        List<Integer> walled = new ArrayList<>();
        for (Map.Entry<Integer, boolean[]> e : ends.entrySet()) {
            if (e.getValue()[0] && e.getValue()[1]) {
                walled.add(e.getKey());
            }
        }
        return !walled.isEmpty() && walled.size() >= OWN_GLASS_ROWS * ends.size() ? walled : List.of();
    }

    /** ¿Frasco con una etiqueta que tapa las dos paredes? No deja ver el contenido. */
    public static boolean labeledGlass(RgbaImage full) {
        List<Integer> walled = glassWalls(full);
        if (walled.isEmpty()) {
            return false;
        }
        int first = walled.get(0);
        int last = walled.get(walled.size() - 1);
        for (Map.Entry<Integer, boolean[]> e : glassEnds(full).entrySet()) {
            if (first < e.getKey() && e.getKey() < last && !e.getValue()[0] && !e.getValue()[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Las partes del envase, o {@code null} si no hay vidrio propio o una etiqueta tapa el
     * contenido. Es de vidrio si bastantes filas de la silueta empiezan y terminan en color
     * de vidrio; el cuerpo son las filas entre paredes, de abajo arriba.
     */
    public static Parts ownGlass(RgbaImage full) {
        Mask mask = Masks.opaque(full);
        TreeMap<Integer, List<int[]>> runs = rowRuns(mask);
        List<Integer> walled = glassWalls(full);
        if (walled.isEmpty() || labeledGlass(full)) {
            return null;
        }
        Mask like = new Mask();
        for (Point p : mask) {
            if (glassLike(full.get(p))) {
                like.add(p);
            }
        }
        Mask walls = new Mask();
        for (int y : walled) {
            List<int[]> row = runs.get(y);
            walls.add(row.get(0)[0], y);
            walls.add(row.get(row.size() - 1)[1], y);
        }
        Mask glass = new Mask();
        for (Mask c : Masks.components(like)) {
            if (c.intersects(walls)) {
                glass.addAll(c);
            }
        }
        Mask body = new Mask();
        List<Integer> spans = new ArrayList<>();
        boolean started = false;
        for (int y = runs.lastKey(); y >= runs.firstKey(); y--) {
            List<Point> best = List.of();
            int span = 0;
            for (int[] run : runs.getOrDefault(y, List.of())) {
                if (glass.contains(run[0], y) && glass.contains(run[1], y)) {
                    List<Point> food = new ArrayList<>();
                    for (int x = run[0]; x <= run[1]; x++) {
                        if (!glass.contains(x, y)) {
                            food.add(new Point(x, y));
                        }
                    }
                    if (food.size() > best.size()) {
                        best = food;
                        span = run[0] + run[1];
                    }
                }
            }
            if (!best.isEmpty()) {
                started = true;
                for (Point p : best) {
                    body.add(p);
                }
                spans.add(span);
            } else if (started) {
                break;
            }
        }
        if (body.isEmpty()) {
            return null;
        }
        int axis = mostRepeated(spans);
        Mask rest = mask.minus(glass).minus(body);
        int[] box = Cups.cupBox(glass);
        int top = body.first().y();
        Mask neutral = new Mask();
        for (Point p : rest) {
            if (Colors.hsv(full.get(p))[1] < OWN_NEUTRAL) {
                neutral.add(p);
            }
        }
        Mask parts = new Mask();
        Mask more = new Mask();
        for (Mask comp : Masks.components(rest.minus(neutral))) {
            if (!inside(comp, box)) {
                continue;
            }
            if (touches(comp, body)) {
                more.addAll(comp);                  // espuma, superficie, pajilla
            } else if (maxY(comp) < top) {
                parts.addAll(comp);                 // corcho, tapa de tela
            }
        }
        Mask garnish = rest.minus(neutral).minus(parts).minus(more);
        for (Mask comp : Masks.components(neutral)) {
            double[] values = new double[comp.size()];
            int n = 0;
            for (Point p : comp) {
                values[n++] = Colors.hsv(full.get(p))[2];
            }
            double value = PyMath.sum(values) / comp.size();
            if (inside(comp, box) && touches(comp, body) && value >= FOAM_VALUE) {
                more.addAll(comp);                  // espuma o crema blanca
                continue;
            }
            for (Point p : comp) {
                if (p.x() < box[0] || p.x() > box[2]) {
                    continue;
                }
                int around = 0;
                for (Point q : Masks.nbrs(p)) {
                    if (garnish.contains(q)) {
                        around++;
                    }
                }
                if (around < 2) {
                    parts.add(p);
                }
            }
        }
        body.addAll(more);
        int level = body.first().y();
        Mask stopper = new Mask();
        for (Mask comp : Masks.components(parts)) {
            int low = maxY(comp);
            if (low >= level) {
                continue;
            }
            for (Map.Entry<Integer, List<int[]>> e : runs.subMap(low, false, level, false).entrySet()) {
                int[] run = e.getValue().get(0);
                if (e.getValue().size() == 1 && allIn(glass, run, e.getKey())) {
                    stopper.addAll(comp);           // corcho, tapa
                    break;
                }
            }
        }
        Mask vessel = glass.union(parts.minus(stopper));
        return new Parts(vessel, body, mask.minus(glass).minus(parts).minus(body), axis, stopper);
    }

    /** El más repetido; en empate, el primero de la lista. */
    private static int mostRepeated(List<Integer> spans) {
        Map<Integer, Integer> count = new HashMap<>();
        for (int s : spans) {
            count.merge(s, 1, Integer::sum);
        }
        int best = spans.get(0);
        for (int s : spans) {
            if (count.get(s) > count.get(best)) {
                best = s;
            }
        }
        return best;
    }

    private static boolean allIn(Mask glass, int[] run, int y) {
        for (int x = run[0]; x <= run[1]; x++) {
            if (!glass.contains(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static boolean touches(Mask comp, Mask body) {
        for (Point p : comp) {
            for (Point q : Masks.nbrs(p, Masks.N8)) {
                if (body.contains(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inside(Mask comp, int[] box) {
        for (Point p : comp) {
            if (p.x() < box[0] || p.x() > box[2]) {
                return false;
            }
        }
        return true;
    }

    private static int maxY(Mask m) {
        int y = Integer.MIN_VALUE;
        for (Point p : m) {
            y = Math.max(y, p.y());
        }
        return y;
    }

    /**
     * El nivel baja dentro del vidrio propio. El tapón se quita desde el primer fotograma; el
     * adorno se come por tercios y lo que tapaba del envase se repone por simetría.
     */
    public static List<RgbaImage> frames(RgbaImage original, Parts parts) {
        RgbaImage full = original.copy();
        for (Point p : parts.stopper()) {
            full.set(p, TRANSPARENT);
        }
        Mask mask = Masks.opaque(full);
        int k = full.pxScale();
        Mask body = parts.body();
        Mask garnish = parts.garnish();
        Mask food = parts.contents();
        if (body.isEmpty()) {
            throw new ArithmeticException("envase sin contenido");
        }
        List<Point> glass = new ArrayList<>();
        for (Point p : parts.vessel()) {
            if (glassLike(full.get(p))) {
                glass.add(p);
            }
        }
        Map<Point, int[]> seal = new HashMap<>();
        for (Point p : garnish) {
            Point mirror = new Point(parts.axis() - p.x(), p.y());
            if (parts.vessel().contains(mirror)) {
                seal.put(p, full.get(mirror));
            }
        }
        for (Point p : Cups.cupSeal(full, food)) {
            if (!seal.containsKey(p)) {
                seal.put(p, full.get(Cups.nearest(glass, p)));
            }
        }
        Set<Mask> drawn = new HashSet<>(Masks.components(mask));
        List<Integer> bodyRows = Drinks.rowsOf(body);
        List<Integer> garnishRows = Drinks.rowsOf(garnish);
        double[] steps = Solids.schedule(LAST_LIQUID);
        List<RgbaImage> frames = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            boolean last = i == steps.length - 1;
            int cut = bodyRows.get(Math.min(PyMath.round((1 - steps[i]) * bodyRows.size()), bodyRows.size() - 1));
            int garnishCut = 0;
            if (!garnishRows.isEmpty()) {
                int gone = PyMath.round((i + 1) * garnishRows.size() / (double) steps.length);
                garnishCut = gone < garnishRows.size() ? garnishRows.get(gone)
                        : garnishRows.get(garnishRows.size() - 1) + 1;
            }
            RgbaImage out = full.copy();
            for (Point p : food) {
                boolean gone = p.y() < (body.contains(p) ? cut : garnishCut);
                int[] paint = seal.get(p);
                if (paint != null) {
                    if (gone || last) {
                        out.set(p, paint);
                    }
                } else if (gone) {
                    out.set(p, TRANSPARENT);
                }
            }
            List<Mask> comps = Masks.bySize(Masks.components(Masks.opaque(out)));
            for (Mask comp : comps.subList(Math.min(1, comps.size()), comps.size())) {
                if (comp.size() <= 2 * k * k && !drawn.contains(comp) && !allGlass(full, comp)) {
                    for (Point p : comp) {
                        out.set(p, TRANSPARENT);
                    }
                }
            }
            frames.add(out);
        }
        return frames;
    }

    private static boolean allGlass(RgbaImage full, Mask comp) {
        for (Point p : comp) {
            if (!glassLike(full.get(p))) {
                return false;
            }
        }
        return true;
    }
}
