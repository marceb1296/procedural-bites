package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.EXACT_BOWL_SHARE;
import static dev.proceduralbites.core.Params.GLASS_SHARE;
import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.LIQUID_HUE_RANGE;
import static dev.proceduralbites.core.Params.NUM_FRAMES;
import static dev.proceduralbites.core.Params.PALETTE_TOLERANCE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Comida en tazón: el contenido baja y el tazón queda. */
public final class Bowls {
    private Bowls() {
    }

    private static final int TOL2 = PALETTE_TOLERANCE * PALETTE_TOLERANCE;

    /** El vacío alineado a lo visible del recipiente del ítem, o {@code null} si no calza. */
    public static RgbaImage fitContainer(RgbaImage full, Mask contents, RgbaImage empty) {
        int k = full.pxScale();
        if (full.width != empty.width || full.height != empty.height) {
            return null;
        }
        int w = full.width;
        int h = full.height;
        Mask shell = Masks.opaque(full).minus(contents);
        int emaskSize = Masks.opaque(empty).size();
        int[] sx = new int[shell.size()];
        int[] sy = new int[shell.size()];
        int[][] sc = new int[shell.size()][];
        int n = 0;
        for (Point p : shell) {
            sx[n] = p.x();
            sy[n] = p.y();
            sc[n++] = full.get(p);
        }
        byte[] eb = empty.rgba();
        int best = -1;
        int bdx = 0;
        int bdy = 0;
        // (6k + 1)^2 desplazamientos por píxel: a 512x tardaba 10 s. Se corta cuando lo que
        // falta ya no alcanza para superar al mejor, así que el elegido es el mismo
        for (int dy = -3 * k; dy <= 3 * k; dy++) {
            for (int dx = -3 * k; dx <= 3 * k; dx++) {
                int m = 0;
                for (int i = 0; i < n && m + (n - i) > best; i++) {
                    int ex = sx[i] - dx;
                    int ey = sy[i] - dy;
                    if (ex < 0 || ex >= w || ey < 0 || ey >= h) {
                        continue;
                    }
                    int j = (ey * w + ex) * 4;
                    if (eb[j + 3] == 0) {
                        continue;
                    }
                    int dr = sc[i][0] - (eb[j] & 0xff);
                    int dg = sc[i][1] - (eb[j + 1] & 0xff);
                    int db = sc[i][2] - (eb[j + 2] & 0xff);
                    if (dr * dr + dg * dg + db * db <= TOL2) {
                        m++;
                    }
                }
                if (m > best) {   // el primero en (dy, dx) gana el empate
                    best = m;
                    bdx = dx;
                    bdy = dy;
                }
            }
        }
        // 70%: dentro del tazón hay sombras de comida con tono de madera que no calzan
        if (shell.isEmpty() || best < 0.7 * shell.size() || best < 0.3 * emaskSize) {
            return null;
        }
        RgbaImage moved = new RgbaImage(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx = x + bdx;
                int ty = y + bdy;
                if (tx >= 0 && tx < w && ty >= 0 && ty < h) {
                    int[] c = empty.get(x, y);
                    moved.set(tx, ty, c[0], c[1], c[2], c[3]);
                }
            }
        }
        return moved;
    }

    public record Ellipse(double cx, double cy, double a, double b) {
        /** Con {@code a} o {@code b} en 0 lanza: si no, seguiría con infinitos. */
        public boolean contains(double x, double y, double ea, double eb, double ecy) {
            if (ea == 0 || eb == 0) {
                throw new ArithmeticException("elipse de ancho o alto 0");
            }
            double u = (x - cx) / ea;
            double v = (y - ecy) / eb;
            return u * u + v * v <= 1;
        }

        public boolean contains(Point p) {
            return contains(p.x(), p.y(), a, b, cy);
        }
    }

    /**
     * ¿La textura dibuja el recipiente aunque el ítem no lo devuelva? Comida: el tazón debe
     * calzar en posición (por paleta no alcanza: una corteza marrón comparte colores con la
     * madera). Bebida: lo que no es contenido tiene colores del vidrio.
     */
    public static boolean drawsVessel(RgbaImage full, RgbaImage empty, boolean drink) {
        if (!drink && (full.width != empty.width || full.height != empty.height)) {
            return false; // fitContainer no calza tamaños distintos
        }
        Mask contents = Drinks.findContents(full, empty);
        if (contents.isEmpty()) {
            return false;
        }
        if (drink) {
            int mask = Masks.opaque(full).size();
            return Drinks.isGlass(empty) && mask - contents.size() >= GLASS_SHARE * mask;
        }
        return fitContainer(full, contents, empty) != null;
    }

    public record Fit(RgbaImage moved, boolean same) {
    }

    /**
     * ¿La comida calza en posición con el tazón {@code empty}? Si casi nada tiene sus colores exactos,
     * el mod dibujó el suyo.
     */
    public static Fit bowlFit(RgbaImage full, RgbaImage empty) {
        if (full.width != empty.width || full.height != empty.height) {
            return null;
        }
        Mask first = Drinks.findContents(full, empty);
        if (first.isEmpty()) {
            return null;
        }
        RgbaImage moved = fitContainer(full, first, empty);
        if (moved == null) {
            return null;
        }
        Set<Integer> reference = new HashSet<>();
        for (Point p : Masks.opaque(moved)) {
            reference.add(rgb(moved.get(p)));
        }
        Mask rest = Masks.opaque(full).minus(first);
        int exact = 0;
        for (Point p : rest) {
            if (reference.contains(rgb(full.get(p)))) {
                exact++;
            }
        }
        return new Fit(moved, exact >= EXACT_BOWL_SHARE * rest.size());
    }

    private static int rgb(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    private static int rgba(int[] c) {
        return (c[3] << 24) | rgb(c);
    }

    /** La silueta del tazón sin el interior de la boca; el contorno mide k anillos. */
    static Mask zoneOf(Mask bowl, Mask opening, int k) {
        Mask ring = new Mask();
        Mask left = bowl;
        for (int i = 0; i < k; i++) {
            Mask border = Masks.outerBorder(left);
            ring.addAll(border);
            left = left.minus(border);
        }
        return bowl.minus(opening).union(ring);
    }

    public record Own(RgbaImage bowl, Mask contents) {
    }

    /**
     * Tazón que el mod dibuja con sus propios colores en el lugar del de referencia.
     * {@code null} si no calza o si es el mismo dibujo. Lo tapado por la comida se completa
     * con colores del propio tazón: ninguno sale de fuera de la textura.
     */
    public static Own ownBowl(RgbaImage full, RgbaImage empty) {
        return ownBowl(full, bowlFit(full, empty));
    }

    static Own ownBowl(RgbaImage full, Fit fit) {
        if (fit == null || fit.same()) {
            return null;
        }
        RgbaImage moved = fit.moved();
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        Mask bowl = Masks.opaque(moved);
        int wider = 0;
        for (Map.Entry<Integer, List<int[]>> row : OwnGlass.rowRuns(bowl).entrySet()) {
            List<int[]> runs = row.getValue();
            if (mask.contains(runs.get(0)[0] - 1, row.getKey())
                    && mask.contains(runs.get(runs.size() - 1)[1] + 1, row.getKey())) {
                wider++;
            }
        }
        if (wider >= 2 * k) {
            return null;                        // sobresale a los dos lados: no es ese tazón
        }
        Ellipse e = rimEllipse(bowl, k);
        Mask opening = new Mask();
        for (Point p : bowl) {
            if (e.contains(p)) {
                opening.add(p);
            }
        }
        Mask zoneOf = zoneOf(bowl, opening, k);
        Mask zone = mask.and(zoneOf);
        Mask inner = bowl.minus(zoneOf);
        Map<Integer, Integer> front = new LinkedHashMap<>();
        for (Point p : mask.and(bowl.minus(opening))) {
            if (p.y() > e.cy()) {
                front.merge(rgb(full.get(p)), 1, Integer::sum);
            }
        }
        Set<Integer> food = new HashSet<>();
        for (Point p : mask.and(inner)) {
            food.add(rgb(full.get(p)));
        }
        Map<Integer, Integer> near = new LinkedHashMap<>();
        for (Point p : zone) {
            if (Colors.colorDist2(full.get(p), moved.get(p)) <= TOL2) {
                near.merge(rgb(full.get(p)), 1, Integer::sum);
            }
        }
        Set<Integer> palette = new HashSet<>();
        for (Map.Entry<Integer, Integer> c : near.entrySet()) {
            if (c.getValue() >= 2 * k * k) {
                palette.add(c.getKey());
            }
        }
        Set<Integer> added = new HashSet<>();
        for (Map.Entry<Integer, Integer> c : front.entrySet()) {
            if (c.getValue() >= 2 * k * k && !food.contains(c.getKey()) && alike(c.getKey(), palette)) {
                added.add(c.getKey());
            }
        }
        palette.addAll(added);
        Mask drawn = new Mask();
        for (Point p : mask) {
            if (palette.contains(rgb(full.get(p)))) {
                drawn.add(p);
            }
        }
        Mask shell = new Mask();
        for (Mask c : Masks.components(drawn)) {
            if (c.intersects(zone)) {
                shell.addAll(c);
            }
        }
        Mask contents = mask.minus(shell);
        if (contents.isEmpty() || !shell.intersects(inner)) {
            return null;                        // sin pared interior a la vista no es un tazón
        }
        // tazón vacío: lo que el ítem muestra y, donde lo tapa la comida, el color de la
        // pared interior o el píxel propio más cercano de la zona
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        Map<Integer, int[]> seenColor = new LinkedHashMap<>();
        for (Point p : shell.and(inner)) {
            int[] c = full.get(p);
            seen.merge(rgba(c), 1, Integer::sum);
            seenColor.putIfAbsent(rgba(c), c);
        }
        int[] wall = null;
        int most = 0;
        for (Map.Entry<Integer, Integer> c : seen.entrySet()) {
            if (c.getValue() > most) {   // el primero gana en empate
                most = c.getValue();
                wall = seenColor.get(c.getKey());
            }
        }
        List<Point> visible = shell.and(bowl.minus(inner)).toList();
        RgbaImage out = new RgbaImage(full.width, full.height);
        for (Point p : shell.and(bowl)) {
            out.set(p, full.get(p));
        }
        for (Point p : bowl.minus(shell)) {
            if (inner.contains(p)) {
                out.set(p, wall);
            } else {
                out.set(p, full.get(nearestInRow(visible, p)));
            }
        }
        return new Own(out, contents);
    }

    /** ¿Del material del tazón? Mismo tono que algún color de la paleta, o los dos sin color. */
    private static boolean alike(int color, Set<Integer> palette) {
        double[] c = Colors.hsv(new int[] {color >> 16 & 0xff, color >> 8 & 0xff, color & 0xff});
        for (int other : palette) {
            double[] q = Colors.hsv(new int[] {other >> 16 & 0xff, other >> 8 & 0xff, other & 0xff});
            if ((c[1] < 0.25 && q[1] < 0.25)
                    || (c[1] >= 0.25 && q[1] >= 0.25 && Colors.hueGap(c[0], q[0]) <= LIQUID_HUE_RANGE / 360.0)) {
                return true;
            }
        }
        return false;
    }

    /** El más cercano, primero los de la misma fila; en empate, el primero. */
    static Point nearestInRow(List<Point> points, Point p) {
        Point best = null;
        boolean bestOther = true;
        long bestD = Long.MAX_VALUE;
        for (Point q : points) {
            boolean other = q.y() != p.y();
            long dx = q.x() - p.x();
            long dy = q.y() - p.y();
            long d = dx * dx + dy * dy;
            if (best == null || (!other && bestOther) || (other == bestOther && d < bestD)) {
                best = q;
                bestOther = other;
                bestD = d;
            }
        }
        if (best == null) {
            throw new ArithmeticException("tazón propio sin píxeles a la vista");
        }
        return best;
    }

    /** Boca del tazón: ancho de la fila más ancha, alto desde el borde de atrás hasta esa fila. */
    public static Ellipse rimEllipse(Mask mask, int k) {
        if (mask.isEmpty()) {
            throw new ArithmeticException("tazón vacío");
        }
        Map<Integer, int[]> rows = new TreeMap<>();   // y -> {min x, max x}
        for (Point p : mask) {
            int[] r = rows.computeIfAbsent(p.y(), y -> new int[] {p.x(), p.x()});
            r[0] = Math.min(r[0], p.x());
            r[1] = Math.max(r[1], p.x());
        }
        int w = 0;
        for (int[] r : rows.values()) {
            w = Math.max(w, r[1] - r[0] + 1);
        }
        int yW = Integer.MIN_VALUE;
        for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
            if (e.getValue()[1] - e.getValue()[0] + 1 >= w - k) {
                yW = Math.max(yW, e.getKey());
            }
        }
        int top = rows.keySet().iterator().next();
        int[] xs = rows.get(yW);
        double cx = (xs[0] + xs[1]) / 2.0;
        double a = w / 2.0 - k;
        double b = Math.max((yW - top) / 2.0, k);
        return new Ellipse(cx, top + b, a, b);
    }

    /** Cada fotograma: la comida es una elipse más chica y más abajo, recortada por la boca del tazón. */
    public static List<RgbaImage> bowlFrames(RgbaImage full, Mask contents, RgbaImage empty) {
        return bowlFrames(full, contents, empty, null);
    }

    /** Con {@code own} (el tazón propio del mod), lo que no es comida nunca cambia. */
    public static List<RgbaImage> bowlFrames(RgbaImage full, Mask contents, RgbaImage empty, RgbaImage own) {
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        Mask shell = mask.minus(contents);
        RgbaImage base = own != null ? own : fitContainer(full, contents, empty);
        boolean synthesized = base == null;
        Mask bowlMask;
        if (base != null) {
            bowlMask = Masks.opaque(base);
        } else {
            // sintetizar: la silueta del tazón = lo que no es comida + su elipse
            bowlMask = new Mask(shell);
            Ellipse e = rimEllipse(bowlMask, k);
            long[] s = new long[3];
            for (Point p : shell) {
                int[] c = full.get(p);
                for (int i = 0; i < 3; i++) {
                    s[i] += c[i];
                }
            }
            int[] avg = new int[3];
            for (int i = 0; i < 3; i++) {
                avg[i] = PyMath.round((double) s[i] / shell.size());
            }
            int[] dark = Colors.lerp(avg, new int[] {0, 0, 0}, 0.45);
            base = new RgbaImage(full.width, full.height);
            for (Point p : shell) {
                base.set(p, full.get(p));
            }
            for (int y = 0; y < full.height; y++) {
                for (int x = 0; x < full.width; x++) {
                    // solo dentro de la silueta original: la silueta nunca crece
                    if (mask.contains(x, y) && e.contains(x, y, e.a() + k, e.b() + k, e.cy())) {
                        int[] c = e.contains(x, y, e.a(), e.b(), e.cy()) ? dark : avg;
                        base.set(x, y, c[0], c[1], c[2], 255);
                        bowlMask.add(x, y);
                    }
                }
            }
        }
        Ellipse e = rimEllipse(bowlMask, k);
        Mask opening = new Mask();
        for (Point p : bowlMask) {
            if (e.contains(p)) {
                opening.add(p);
            }
        }
        if (synthesized) {
            // sin el vacío alineado, la comida solo baja por donde había comida
            opening = opening.and(contents);
            if (!opening.isEmpty()) {
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                for (Point p : opening) {
                    minX = Math.min(minX, p.x());
                    maxX = Math.max(maxX, p.x());
                    minY = Math.min(minY, p.y());
                    maxY = Math.max(maxY, p.y());
                }
                e = new Ellipse((minX + maxX) / 2.0, (minY + maxY) / 2.0, (maxX - minX + 1) / 2.0,
                        (maxY - minY + 1) / 2.0);
            }
        }
        Mask frontWalls = shell.minus(opening);
        FoodColor food = new FoodColor(full, contents);
        // comida amontonada sobre la boca: baja por filas, como el nivel de una bebida
        Mask heap = new Mask();
        for (Point p : contents.minus(opening)) {
            if (p.y() < e.cy()) {
                heap.add(p);
            }
        }
        Mask crumbs = new Mask();
        if (own != null) {
            // la comida de fuera del tazón baja como el montón; las migas sueltas quedan
            // hasta el penúltimo fotograma
            Mask outside = contents.minus(bowlMask);
            for (Mask c : Masks.components(mask)) {
                if (c.minus(outside).isEmpty()) {
                    crumbs.addAll(c);
                }
            }
            heap = heap.union(outside).minus(crumbs);
        }

        List<RgbaImage> frames = new ArrayList<>();
        double[] steps = Solids.schedule(LAST_LIQUID);
        for (int f = 0; f < steps.length; f++) {
            double keep = steps[f];
            double s = 0.45 + 0.55 * keep;             // la comida se encoge...
            double fcy = e.cy() + (1 - keep) * e.b();  // ...y baja dentro del tazón
            RgbaImage out = base.copy();
            // la pared y lo que el mod dibujó fuera del tazón se conservan
            for (Point p : frontWalls) {
                out.set(p, full.get(p));
            }
            for (Point p : opening) {
                if (e.contains(p.x(), p.y(), e.a() * s, e.b() * s, fcy) && (own == null || contents.contains(p))) {
                    out.set(p, food.at(e.cx() + (p.x() - e.cx()) / s, e.cy() + (p.y() - fcy) / s));
                }
            }
            // lo que el tazón no cubre y quedaría como agujero vuelve al original
            for (Point p : Masks.enclosed(Masks.opaque(out), full.width, full.height)) {
                int[] c = full.get(p);
                if (c[3] != 0) {
                    out.set(p.x(), p.y(), c[0], c[1], c[2], c[3]);
                }
            }
            if (f < NUM_FRAMES - 1) {
                for (Point p : crumbs) {
                    out.set(p, full.get(p));
                }
            }
            frames.add(out);
        }
        if (!heap.isEmpty()) {
            lowerHeap(full, heap, frames, k, own != null ? crumbs : null);
        }
        return frames;
    }

    /** El montón baja desde arriba, con cortes que quitan una parte pareja de la silueta en cada fotograma. */
    static void lowerHeap(RgbaImage full, Mask heap, List<RgbaImage> frames, int k, Mask crumbs) {
        Mask orig = Masks.opaque(full);
        List<Mask> sil = new ArrayList<>();
        for (RgbaImage fr : frames) {
            sil.add(Masks.opaque(fr));
        }
        int total = orig.minus(sil.get(sil.size() - 1)).size();
        java.util.TreeSet<Integer> ys = new java.util.TreeSet<>();
        int maxY = Integer.MIN_VALUE;
        for (Point p : heap) {
            ys.add(p.y());
            maxY = Math.max(maxY, p.y());
        }
        List<Integer> cuts = new ArrayList<>(ys);
        cuts.add(maxY + 1);
        int lo = 0;
        Mask prev = heap;
        for (int f = 0; f < frames.size(); f++) {
            double goal = total * (f + 1) / (double) NUM_FRAMES;
            double bestErr = Double.NaN;
            int bestI = -1;
            for (int i = lo; i < cuts.size(); i++) {
                // dentro de lo del fotograma anterior: si no, una fila corta de arriba volvería
                Mask kept = mound(heap, cuts.get(i), k).and(prev);
                double err = Math.abs(orig.minus(sil.get(f).union(kept)).size() - goal);
                if (bestI < 0 || err < bestErr) {
                    bestErr = err;
                    bestI = i;
                }
            }
            lo = f < NUM_FRAMES - 1 ? bestI : cuts.size() - 1;
            prev = mound(heap, cuts.get(lo), k).and(prev);
            if (crumbs != null) {
                Mask joined = Masks.bySize(Masks.components(sil.get(f).minus(crumbs).union(prev))).get(0);
                prev = prev.and(joined);
            }
            RgbaImage fr = frames.get(f);
            for (Point p : prev) {
                fr.set(p, full.get(p));
            }
        }
    }

    /** Lo que queda del montón bajo el corte, con forma de loma para que no parezca recortado. */
    static Mask mound(Mask heap, int cut, int k) {
        Mask kept = new Mask();
        for (Point p : heap) {
            if (p.y() >= cut) {
                kept.add(p);
            }
        }
        List<Integer> rows = new ArrayList<>(new java.util.TreeSet<>(kept.toList().stream().map(Point::y).toList()));
        for (int j = 0; j < Math.min(2, rows.size()); j++) {
            int y = rows.get(j);
            List<Integer> xs = new ArrayList<>();
            for (Point p : kept) {
                if (p.y() == y) {
                    xs.add(p.x());
                }
            }
            java.util.Collections.sort(xs);
            int trim = (2 - j) * k;
            if (xs.size() > 2 * trim) {
                for (int t = 0; t < trim; t++) {
                    kept.remove(new Point(xs.get(t), y));
                    kept.remove(new Point(xs.get(xs.size() - 1 - t), y));
                }
            }
        }
        return kept;
    }

    static final class FoodColor {
        private final RgbaImage full;
        private final Mask contents;
        private final List<Point> food;

        FoodColor(RgbaImage full, Mask contents) {
            this.full = full;
            this.contents = contents;
            this.food = contents.toList();
        }

        int[] at(double qx, double qy) {
            Point qi = new Point(PyMath.round(qx), PyMath.round(qy));
            if (contents.contains(qi)) {
                return full.get(qi);
            }
            if (food.isEmpty()) {
                throw new ArithmeticException("sin comida");
            }
            Point best = food.get(0);
            double bestD = Double.POSITIVE_INFINITY;
            for (Point c : food) {
                double d = (c.x() - qx) * (c.x() - qx) + (c.y() - qy) * (c.y() - qy);
                if (d < bestD) {   // el primero gana en empate
                    bestD = d;
                    best = c;
                }
            }
            return full.get(best);
        }
    }
}
