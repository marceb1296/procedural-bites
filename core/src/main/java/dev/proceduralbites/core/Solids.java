package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.BITE_SIDE;
import static dev.proceduralbites.core.Params.BITE_SIDE_COS;
import static dev.proceduralbites.core.Params.BITE_SIZE;
import static dev.proceduralbites.core.Params.CONTOUR_TOLERANCE;
import static dev.proceduralbites.core.Params.EDGE_STRENGTH;
import static dev.proceduralbites.core.Params.ELONGATED;
import static dev.proceduralbites.core.Params.IRREGULARITY;
import static dev.proceduralbites.core.Params.KEEP_WHOLE_PARTS;
import static dev.proceduralbites.core.Params.LAST_ANCHOR;
import static dev.proceduralbites.core.Params.LAST_REMAINING;
import static dev.proceduralbites.core.Params.MIN_ISLAND;
import static dev.proceduralbites.core.Params.MIN_THICKNESS;
import static dev.proceduralbites.core.Params.NUM_FRAMES;
import static dev.proceduralbites.core.Params.TOOTH_RADIUS;
import static dev.proceduralbites.core.Params.TOOTH_SPACING;

import dev.proceduralbites.core.Params.BitePattern;
import dev.proceduralbites.core.Params.EdgeMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Comida sólida: mordidas acumulativas con borde irregular. Trigonometría con StrictMath: da lo mismo
 * en cualquier JVM.
 */
public final class Solids {
    private Solids() {
    }

    private static final double TWO_PI = 2 * Math.PI;
    /** Cota de {@code 0.12 * IRREGULARITY * |wob|}, con holgura para el redondeo. */
    private static final double WOB_BOUND = 0.12 * IRREGULARITY * 1.001;
    private static final double[][] WAVE_FREQS = {{2}, {3}, {5}};

    public static List<RgbaImage> frames(RgbaImage im, String name, BitePattern pattern) {
        List<RgbaImage> out = new ArrayList<>();
        for (Eaten e : solidGeometry(im, name, pattern)) {
            out.add(renderSolid(im, e, Params.EDGE_MODE));
        }
        return out;
    }

    public static double[] schedule(double last) {
        double[] out = new double[NUM_FRAMES];
        for (int k = 1; k <= NUM_FRAMES; k++) {
            out[k - 1] = 1 - (1 - last) * ((double) k / NUM_FRAMES);
        }
        return out;
    }

    /** El tallo: una protuberancia fina sobre el cuerpo y unida a él. */
    public static Mask stemPixels(Mask mask, int k) {
        Mask body = Masks.opening(mask, k);
        Mask stem = new Mask();
        if (body.isEmpty()) {
            return stem;
        }
        int top = body.first().y();
        Mask main = Masks.bySize(Masks.components(mask)).get(0);
        for (Point p : mask.minus(body).and(main)) {
            if (p.y() < top) {
                stem.add(p);
            }
        }
        return stem;
    }

    private static int maxRow(Mask m) {
        int y = Integer.MIN_VALUE;
        for (Point p : m) {
            y = Math.max(y, p.y());
        }
        return y;
    }

    /** Al menos 3 px a 16x: la punta de una porción de pizza tiene 2. */
    public static boolean hasStem(Mask mask, int k) {
        return stemPixels(mask, k).size() >= 3 * k;
    }

    public record Step(List<double[]> dirs, boolean last) {
    }

    /** Direcciones de mordida por fotograma; salvo en la fruta con corazón, el último es un bocado compacto. */
    public static List<Step> bitePlan(Mask mask, BitePattern pattern, PyRandom rng, int k) {
        List<List<double[]>> plan = bitePlanRaw(mask, pattern, rng, k);
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            List<double[]> dirs = plan.get(i);
            out.add(new Step(dirs, i == plan.size() - 1 && dirs.size() == 1));
        }
        return out;
    }

    private static List<List<double[]>> bitePlanRaw(Mask mask, BitePattern pattern, PyRandom rng, int k) {
        int n = NUM_FRAMES;
        if (pattern == BitePattern.AUTO) {
            // PCA de la silueta: ¿el ítem es alargado?
            long sumX = 0, sumY = 0;
            for (Point p : mask) {
                sumX += p.x();
                sumY += p.y();
            }
            double cx = (double) sumX / mask.size();
            double cy = (double) sumY / mask.size();
            double[] xx = new double[mask.size()];
            double[] yy = new double[mask.size()];
            double[] xy = new double[mask.size()];
            int i = 0;
            for (Point p : mask) {
                xx[i] = (p.x() - cx) * (p.x() - cx);
                yy[i] = (p.y() - cy) * (p.y() - cy);
                xy[i] = (p.x() - cx) * (p.y() - cy);
                i++;
            }
            double sxx = PyMath.sum(xx);
            double syy = PyMath.sum(yy);
            double sxy = PyMath.sum(xy);
            double tr = sxx + syy;
            double det = sxx * syy - sxy * sxy;
            double l1 = tr / 2 + Math.sqrt(pyMax0(tr * tr / 4 - det));
            double l2 = tr - l1;
            if (l2 <= 0 || Math.sqrt(l1 / l2) >= ELONGATED) {
                double[] axis;
                if (Math.abs(sxy) > 1e-9) {
                    axis = PyMath.unit(sxy, l1 - sxx);
                } else {
                    axis = sxx >= syy ? new double[] {1, 0} : new double[] {0, 1};
                }
                if (axis[1] > 0 || (axis[1] == 0 && axis[0] < 0)) {   // la punta de arriba (o derecha)
                    axis = new double[] {-axis[0], -axis[1]};
                }
                return repeat(axis, n);
            }
            // con tallo (fruta con corazón): lados alternos; si no, siempre del mismo lado
            pattern = hasStem(mask, k) ? BitePattern.ALTERNATE : BitePattern.FRONT;
        }
        if (pattern == BitePattern.ALTERNATE || pattern == BitePattern.ALTERNATE_TOP) {
            double[] a;
            double[] b;
            if (rng.random() < 0.5) {
                a = new double[] {-1, 0};
                b = new double[] {1, 0};
            } else {
                a = new double[] {1, 0};
                b = new double[] {-1, 0};
            }
            List<List<double[]>> plan = new ArrayList<>();
            for (int i = 0; i < n - 1; i++) {
                plan.add(List.of(i % 2 == 0 ? a : b));
            }
            // con tallo: al final se muerden ambos lados y queda el corazón;
            // sin tallo: la última mordida entra por el medio (desde arriba)
            plan.add(pattern == BitePattern.ALTERNATE ? List.of(a, b) : List.of(new double[] {0, -1}));
            return plan;
        }
        return repeat(PyMath.unit(BITE_SIDE[0], BITE_SIDE[1]), n);
    }

    /** x si no es menor que 0, incluido -0.0. */
    private static double pyMax0(double x) {
        return 0 > x ? 0 : x;
    }

    private static List<List<double[]>> repeat(double[] dir, int n) {
        List<List<double[]>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(List.of(dir));
        }
        return out;
    }

    private record Settled(Mask eaten, int loose) {
    }

    /**
     * Píxeles comidos acumulados por fotograma: cada mordida es un círculo con dientes, tan profundo
     * como pida el objetivo.
     */
    public static List<Eaten> solidGeometry(RgbaImage im, String name, BitePattern pattern) {
        return new Geometry(im, name).run(pattern);
    }

    private static final class Geometry {
        final PyRandom rng;
        final RgbaImage im;
        final int k;
        final Mask mask;
        final int area;
        final List<Mask> parts;
        final int minT;
        final Mask thick0;
        final double cx;
        final double cy;
        final int extent;
        final int minY;
        final int maxY;
        final boolean[] mainFams = new boolean[13];
        Mask eaten = new Mask();

        Geometry(RgbaImage im, String name) {
            this.im = im;
            rng = PyRandom.seededRng(name);
            k = im.pxScale();
            mask = Masks.opaque(im);
            area = mask.size();
            if (area == 0) {
                // sin píxeles no hay centro: seguir daría NaN en silencio
                throw new ArithmeticException("textura sin píxeles opacos");
            }
            parts = Masks.visualComponents(mask, mask);   // partes separadas desde el original
            minT = MIN_THICKNESS * k;
            thick0 = Masks.thickPart(mask, minT);          // lo que era grueso en el original
            long sumX = 0, sumY = 0;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (Point p : mask) {
                sumX += p.x();
                sumY += p.y();
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y());
                maxY = Math.max(maxY, p.y());
            }
            cx = (double) sumX / area;
            cy = (double) sumY / area;
            extent = Math.max(maxX - minX, maxY - minY) + 1;
            this.minY = minY;
            this.maxY = maxY;

            // material principal: familia de color más abundante en las partes gruesas
            // (la baya, no la hoja; la carne, no el palito). 12 tonos + 1 neutro.
            int[] counts = new int[13];
            for (Point p : thick0.isEmpty() ? mask : thick0) {
                counts[family(p)]++;
            }
            int mainFam = 0;
            for (int f = 1; f < 13; f++) {
                if (counts[f] > counts[mainFam]) {   // el primero gana en empate
                    mainFam = f;
                }
            }
            mainFams[mainFam] = true;
            if (mainFam != 12) {
                mainFams[(mainFam + 1) % 12] = true;
                mainFams[Math.floorMod(mainFam - 1, 12)] = true;
            }
        }

        Mask mainColored(Mask pixels) {
            Mask out = new Mask();
            for (Point p : pixels) {
                if (mainFams[family(p)]) {
                    out.add(p);
                }
            }
            return out;
        }

        int family(Point p) {
            double[] hsv = Colors.hsv(im.get(p));
            return hsv[1] < 0.2 || hsv[2] < 0.15 ? 12 : Math.floorMod((int) (hsv[0] * 12), 12);
        }

        List<Eaten> run(BitePattern pattern) {
            List<Eaten> frames = new ArrayList<>();
            double[] targets = schedule(LAST_REMAINING);
            List<Step> plan = bitePlan(mask, pattern, rng, k);   // antes del bucle: consume números aleatorios en este orden
            for (int f = 0; f < Math.min(targets.length, plan.size()); f++) {
                double target = targets[f];
                Step step = plan.get(f);
                boolean last = step.last();
                double[] lastDir = last ? step.dirs().get(0) : null;
                if (f == plan.size() - 1 && pattern != BitePattern.ALTERNATE_TOP && step.dirs().size() == 2
                        && hasStem(mask, k)) {
                    Mask heart = heartBite(target * area);
                    Mask core = mask.minus(heart);
                    if (!core.isEmpty() && core.first().y() == minY && maxRow(core) == maxY) {
                        eaten = heart;
                        frames.add(new Eaten(new Mask(eaten), null));
                        continue;
                    }
                    // el corazón no llega de arriba abajo (fruta ladeada, con el tallo sobre un
                    // costado): flotaría; termina en un último bocado compacto, mordido desde arriba
                    last = true;
                    lastDir = new double[] {0, -1};
                }
                if (last) {
                    double[] d = lastDir;
                    eaten = lastBite(target * area, d);
                    frames.add(new Eaten(new Mask(eaten), d));
                    continue;
                }
                int startRem = area - eaten.size();
                List<double[]> dirs = step.dirs();
                for (int i = 0; i < dirs.size(); i++) {
                    double goal = startRem - (startRem - target * area) * (i + 1) / dirs.size();
                    Bite bite = makeBite(dirs.get(i));
                    Mask rem = mask.minus(eaten);
                    Mask before = eaten;

                    java.util.function.DoubleFunction<Settled> result = t -> settle(before.union(bite.region(t, rem)));

                    double far = extent + bite.r + 2;   // no toca
                    double near = -extent;              // come todo
                    for (int it = 0; it < 22; it++) {
                        double mid = (far + near) / 2;
                        if (area - result.apply(mid).eaten().size() > goal) {
                            far = mid;
                        } else {
                            near = mid;
                        }
                    }
                    double farV = far;
                    double nearV = near;
                    double best = Math.abs(area - result.apply(farV).eaten().size() - goal)
                        <= Math.abs(area - result.apply(nearV).eaten().size() - goal) ? farV : nearV;
                    Settled chosen = result.apply(best);
                    if (chosen.loose() != 0) {
                        // la mordida desprendió un pedazo: probar mordidas un poco menos
                        // profundas que no lo desprendan, aceptando quedarse algo corto
                        double t = best;
                        while (t < far + 4 * k) {
                            t += 0.25 * k;
                            Settled cand = result.apply(t);
                            if (cand.loose() == 0 && area - cand.eaten().size() <= goal + 0.1 * area) {
                                chosen = cand;
                                break;
                            }
                        }
                    }
                    eaten = chosen.eaten();
                }
                frames.add(new Eaten(new Mask(eaten), null));
            }
            return frames;
        }

        /** Corazón de la fruta con tallo: franja vertical alineada con el tallo, con dientes en los dos bordes. */
        Mask heartBite(double goal) {
            Mask rem = mask.minus(eaten);
            Mask stem = stemPixels(mask, k);
            long sumX = 0;
            for (Point p : stem) {
                sumX += p.x();
            }
            double xc = stem.isEmpty() ? cx : (double) sumX / stem.size();
            int y0 = minY;
            int height = maxY - y0 + 1;
            double[][] waves = randomWaves();
            double wsum = waveSum(waves);
            double shift = rng.uniform(0, 1);
            double tooth = TOOTH_RADIUS * k;
            double spacing = TOOTH_SPACING * k;
            // el tallo se queda siempre, aunque sea del color de la fruta
            Mask thin = new Mask();
            for (Point p : rem.minus(thick0)) {
                if (!mainFams[family(p)]) {
                    thin.add(p);
                }
            }
            thin.addAll(stem.and(rem));
            Mask before = eaten;

            java.util.function.DoubleFunction<Settled> result = w -> {
                Mask keep = new Mask();
                for (Point p : rem) {
                    double t = (double) (p.y() - y0) / height;
                    double[] terms = new double[3];
                    for (int i = 0; i < 3; i++) {
                        terms[i] = waves[i][2] * StrictMath.sin(waves[i][0] * 2 * Math.PI * t + waves[i][1]);
                    }
                    double h = w * (1 + 0.12 * IRREGULARITY * PyMath.sum(terms) / wsum);
                    if (Math.abs(p.x() - xc) > h) {
                        continue;
                    }
                    // dientes: muescas redondas a lo largo de los dos bordes
                    int i = PyMath.round((p.y() - y0) / spacing - shift);
                    double ty = y0 + (i + shift) * spacing;
                    double edge = p.x() >= xc ? xc + h : xc - h;
                    if (PyMath.norm(p.x() - edge, p.y() - ty) <= tooth) {
                        continue;
                    }
                    keep.add(p);
                }
                if (!keep.isEmpty()) {
                    Mask joined = new Mask();
                    for (Mask comp : Masks.visualComponents(keep.union(thin), mask)) {
                        if (comp.intersects(keep)) {
                            joined.addAll(comp);
                        }
                    }
                    keep = joined;
                }
                return settle(before.union(rem.minus(keep)));
            };

            Mask base = thick0.isEmpty() ? mask : thick0.union(mainColored(mask.minus(thick0)));
            double goalFood = goal / area * base.size();
            java.util.function.ToIntFunction<Settled> left = r -> base.minus(r.eaten()).size();
            double lo = minT / 2.0;
            double hi = extent;
            for (int it = 0; it < 20; it++) {
                double mid = (lo + hi) / 2;
                if (left.applyAsInt(result.apply(mid)) < goalFood) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            Settled rLo = result.apply(lo);
            Settled rHi = result.apply(hi);
            return Math.abs(left.applyAsInt(rLo) - goalFood) <= Math.abs(left.applyAsInt(rHi) - goalFood)
                ? rLo.eaten() : rHi.eaten();
        }

        final class Bite {
            final double[] d;
            final double r;
            final double jitter;
            final double[][] waves;   // {f, fase, peso}
            final double wsum;
            final double[][] teeth;   // {tx, ty, tr}
            final double maxTooth;

            Bite(double[] d) {
                this.d = d;
                r = BITE_SIZE * extent * (1 + rng.uniform(-0.1, 0.1));
                jitter = rng.uniform(-1.0, 1.0) * k;
                waves = randomWaves();
                wsum = waveSum(waves);
                List<double[]> t = new ArrayList<>();
                double th = rng.uniform(0, TWO_PI);
                int count = Math.max(6, (int) (TWO_PI * r / (TOOTH_SPACING * k)));
                for (int i = 0; i < count; i++) {
                    double tr = TOOTH_RADIUS * k * (1 + rng.uniform(-0.25, 0.25) * (0.5 + IRREGULARITY));
                    t.add(new double[] {r * StrictMath.cos(th), r * StrictMath.sin(th), tr});
                    th += TOOTH_SPACING * k / r;
                }
                teeth = t.toArray(new double[0][]);
                double m = 0;
                for (double[] tooth : teeth) {
                    m = Math.max(m, tooth[2]);
                }
                maxTooth = m;
            }

            Mask region(double t, Mask pixels) {
                double bx = cx + d[0] * t - d[1] * jitter;
                double by = cy + d[1] * t + d[0] * jitter;
                Mask out = new Mask();
                for (Point p : pixels) {
                    if (inBite(p.x() - bx, p.y() - by, r, waves, wsum, teeth, maxTooth)) {
                        out.add(p);
                    }
                }
                return out;
            }
        }

        Bite makeBite(double[] d) {
            return new Bite(d);
        }

        /** Ondas del borde: {f, fase, peso} para f = 2, 3 y 5. */
        double[][] randomWaves() {
            double[][] waves = new double[3][];
            for (int i = 0; i < 3; i++) {
                double phase = rng.uniform(0, TWO_PI);
                double weight = rng.uniform(0.5, 1.0);
                waves[i] = new double[] {WAVE_FREQS[i][0], phase, weight};
            }
            return waves;
        }

        /**
         * Quita espigas de 1 px junto al corte y los pedazos sueltos; quedan la pieza más grande y las
         * partes separadas desde el original.
         */
        Settled settle(Mask eatenIn) {
            Mask e = new Mask(eatenIn);
            for (int pass = 0; pass < 2; pass++) {
                Mask rem = mask.minus(e);
                Mask spikes = new Mask();
                for (Point p : rem) {
                    int inRem = 0;
                    for (int[] d : Masks.N4) {
                        if (rem.contains(p.x() + d[0], p.y() + d[1])) {
                            inRem++;
                        }
                    }
                    if (inRem <= 1 && touches(p, e, Masks.N8)) {
                        spikes.add(p);
                    }
                }
                e.addAll(spikes);
            }
            // grosor mínimo: lo que era grueso y una mordida dejó más delgado que
            // MIN_THICKNESS también se come. Lo que ya era delgado se respeta.
            Mask rem = mask.minus(e);
            e.addAll(rem.and(thick0).minus(Masks.thickPart(rem, minT)));
            List<Mask> comps = Masks.bySize(Masks.visualComponents(mask.minus(e), mask));
            int loose = 0;
            for (int i = 0; i < comps.size(); i++) {
                Mask comp = comps.get(i);
                boolean whole = KEEP_WHOLE_PARTS && parts.contains(comp);
                if ((i == 0 || whole) && comp.size() >= MIN_ISLAND * k * k) {
                    continue;
                }
                e.addAll(comp);
                loose += comp.size();
            }
            return new Settled(e, loose);
        }

        /** Último bocado: trozo compacto lo más lejos posible de las mordidas, dentro de lo que sigue grueso. */
        Mask lastBite(double goal, double[] d) {
            Mask rem = mask.minus(eaten);
            Map<Point, Integer> dist = Masks.depthMap(rem);   // BFS: distancia al borde
            List<Point> food = new ArrayList<>();
            for (Point p : rem) {
                if (mainFams[family(p)]) {
                    food.add(p);
                }
            }
            if (food.isEmpty()) {
                food = rem.toList();
            }
            if (food.isEmpty()) {
                // las mordidas se comieron todo
                throw new ArithmeticException("no queda nada para el último bocado");
            }
            // centro: entre lo que tiene al menos la mitad del grosor máximo, lo más lejos
            // de las mordidas (la punta de la zanahoria, no el medio: el trozo sigue donde
            // estaba y no "salta"); a igualdad, lo más grueso y luego arriba a la izquierda
            int maxDist = 0;
            for (Point p : food) {
                maxDist = Math.max(maxDist, dist.get(p));
            }
            int thr = (int) Math.ceil(maxDist * LAST_ANCHOR);
            Point c = food.get(0);
            for (Point p : food) {
                if (lastBiteKeyGreater(p, c, dist, d, thr)) {
                    c = p;
                }
            }
            double[][] waves = randomWaves();
            double wsum = waveSum(waves);
            double phase = rng.uniform(0, TWO_PI);
            double tooth = TOOTH_RADIUS * k;
            Point center = c;
            Mask before = eaten;
            Mask thin = new Mask();
            for (Point p : rem.minus(thick0)) {
                if (!mainFams[family(p)]) {
                    thin.add(p);
                }
            }

            java.util.function.DoubleFunction<Settled> result = r -> {
                int nTeeth = Math.max(6, (int) (TWO_PI * r / (TOOTH_SPACING * k)));
                double[][] teeth = new double[nTeeth][];
                for (int i = 0; i < nTeeth; i++) {
                    double a = phase + TWO_PI * i / nTeeth;
                    teeth[i] = new double[] {center.x() + r * StrictMath.cos(a), center.y() + r * StrictMath.sin(a)};
                }
                Mask keep = new Mask();
                for (Point p : rem) {
                    if (inLastBite(p, center, r, waves, wsum, teeth, tooth)) {
                        keep.add(p);
                    }
                }
                // palitos, huesos y tallos (delgados desde el original y de otro color) se
                // quedan solo si están unidos al bocado; lo delgado del color de la comida
                // (bayas de 5 px a 32x) es comida
                if (!keep.isEmpty()) {
                    Mask joined = new Mask();
                    for (Mask comp : Masks.visualComponents(keep.union(thin), mask)) {
                        if (comp.intersects(keep)) {
                            joined.addAll(comp);
                        }
                    }
                    keep = joined;
                }
                return settle(before.union(rem.minus(keep)));
            };

            // el objetivo se mide sobre la comida: gruesa o del color principal. Tallos,
            // hojas finas y palitos no cuentan
            Mask base = thick0.isEmpty() ? mask : thick0.union(mainColored(mask.minus(thick0)));
            double goalThick = goal / area * base.size();
            java.util.function.ToIntFunction<Settled> left = s -> base.minus(s.eaten()).size();

            double lo = minT;
            double hi = extent;
            for (int it = 0; it < 20; it++) {
                double mid = (lo + hi) / 2;
                if (left.applyAsInt(result.apply(mid)) < goalThick) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            Settled rLo = result.apply(lo);
            Settled rHi = result.apply(hi);
            return Math.abs(left.applyAsInt(rLo) - goalThick) <= Math.abs(left.applyAsInt(rHi) - goalThick)
                ? rLo.eaten() : rHi.eaten();
        }
    }

    /**
     * Clave del centro del último bocado. Compara con {@code <} y {@code >} a propósito:
     * {@code Double.compare} ordena -0.0 antes que 0.0 y cambiaría el empate.
     */
    static boolean lastBiteKeyGreater(Point p, Point c, Map<Point, Integer> dist, double[] d, int thr) {
        int dp = dist.get(p);
        int dc = dist.get(c);
        if ((dp >= thr) != (dc >= thr)) {
            return dp >= thr;
        }
        double sp = -(p.x() * d[0] + p.y() * d[1]);
        double sc = -(c.x() * d[0] + c.y() * d[1]);
        if (sp != sc) {
            return sp > sc;
        }
        if (dp != dc) {
            return dp > dc;
        }
        if (p.y() != c.y()) {
            return -p.y() > -c.y();
        }
        return -p.x() > -c.x();
    }

    static double waveSum(double[][] waves) {
        return PyMath.sum(waves[0][2], waves[1][2], waves[2][2]);
    }

    static double wobble(double[][] waves, double ang) {
        double[] terms = new double[3];
        for (int i = 0; i < 3; i++) {
            terms[i] = waves[i][2] * StrictMath.sin(waves[i][0] * ang + waves[i][1]);
        }
        return PyMath.sum(terms);
    }

    /** ¿Se come el píxel (ex, ey), relativo al centro? Solo el anillo cercano al borde necesita atan2 y los dientes. */
    static boolean inBite(double ex, double ey, double r, double[][] waves, double wsum, double[][] teeth,
                          double maxTooth) {
        double n = PyMath.norm(ex, ey);
        if (n < r * (1 - WOB_BOUND) - 0.5) {
            return true;
        }
        if (n > r * (1 + WOB_BOUND) + maxTooth + 0.5) {
            return false;
        }
        double wob = wobble(waves, StrictMath.atan2(ey, ex)) / wsum;
        if (n <= r * (1 + 0.12 * IRREGULARITY * wob)) {
            return true;
        }
        for (double[] t : teeth) {
            if (PyMath.norm(ex - t[0], ey - t[1]) <= t[2]) {
                return true;
            }
        }
        return false;
    }

    /** ¿El píxel queda en el bocado de radio r? Mismo recorte por anillo que {@link #inBite}. */
    static boolean inLastBite(Point p, Point center, double r, double[][] waves, double wsum, double[][] teeth,
                              double tooth) {
        double ex = p.x() - center.x();
        double ey = p.y() - center.y();
        double n = PyMath.norm(ex, ey);
        if (n < Math.min(r * (1 - WOB_BOUND), r - tooth) - 0.5) {
            return true;
        }
        if (n > r * (1 + WOB_BOUND) + 0.5) {
            return false;
        }
        double wob = wobble(waves, StrictMath.atan2(ey, ex)) / wsum;
        if (n > r * (1 + 0.12 * IRREGULARITY * wob)) {
            return false;
        }
        for (double[] t : teeth) {
            if (PyMath.norm(p.x() - t[0], p.y() - t[1]) <= tooth) {
                return false;
            }
        }
        return true;
    }

    private static boolean touches(Point p, Mask m, int[][] offsets) {
        for (int[] d : offsets) {
            if (m.contains(p.x() + d[0], p.y() + d[1])) {
                return true;
            }
        }
        return false;
    }

    /** Color de "pulpa": promedio del 40% más claro del interior, aclarado. */
    public static int[] interiorColor(RgbaImage im) {
        Mask mask = Masks.opaque(im);
        Mask inner = Masks.erode(mask);
        if (inner.isEmpty()) {
            inner = mask;
        }
        List<int[]> cols = new ArrayList<>();
        for (Point p : inner) {
            cols.add(im.get(p));
        }
        cols.sort(Comparator.comparingInt(c -> c[0] + c[1] + c[2]));   // estable: el orden de los empates importa
        List<int[]> top = cols.subList((int) (cols.size() * 0.6), cols.size());
        if (top.isEmpty()) {
            top = cols;
        }
        double[] avg = new double[3];
        for (int i = 0; i < 3; i++) {
            long s = 0;
            for (int[] c : top) {
                s += c[i];
            }
            avg[i] = (double) s / top.size();
        }
        return Colors.lerp(avg, new int[] {255, 255, 255}, 0.25);
    }

    /** Luminancia media del interior vecino menos la del contorno. */
    public static double outlineContrast(RgbaImage px, Mask border, Mask region) {
        List<Double> diffs = new ArrayList<>();
        for (Point b : border) {
            for (Point n : Masks.nbrs(b)) {
                if (region.contains(n) && !border.contains(n)) {
                    diffs.add(Colors.luma(px.get(n)) - Colors.luma(px.get(b)));
                }
            }
        }
        if (diffs.isEmpty()) {
            return 0.0;
        }
        return PyMath.sum(diffs.stream().mapToDouble(Double::doubleValue).toArray()) / diffs.size();
    }

    /**
     * Los n más cercanos a p; en empate, el que va antes en pool. Sin ordenar todo el anillo: a 256x
     * tardaba más de un segundo.
     */
    static List<Point> nearest(List<Point> pool, Point p, int n) {
        int[] dist = new int[n];
        Point[] best = new Point[n];
        int count = 0;
        for (Point q : pool) {
            int dq = (q.x() - p.x()) * (q.x() - p.x()) + (q.y() - p.y()) * (q.y() - p.y());
            if (count == n && dq >= dist[n - 1]) {
                continue;   // a igual distancia gana el que ya estaba (va antes en pool)
            }
            int i = count < n ? count++ : n - 1;
            while (i > 0 && dist[i - 1] > dq) {
                dist[i] = dist[i - 1];
                best[i] = best[i - 1];
                i--;
            }
            dist[i] = dq;
            best[i] = q;
        }
        return java.util.Arrays.asList(best).subList(0, count);
    }

    public static boolean biteSide(Point p, double[] center, double[] d) {
        double vx = p.x() - center[0];
        double vy = p.y() - center[1];
        double n = PyMath.norm(vx, vy);
        if (n == 0) {
            n = 1;   // vector nulo
        }
        return (vx * d[0] + vy * d[1]) / n > BITE_SIDE_COS;
    }

    /**
     * Color del contorno repintado: si el más cercano del borde se aleja del objetivo más que
     * CONTOUR_TOLERANCE, se corrige.
     */
    public static int[] contourColor(int[] c, double goal) {
        double lum = Colors.luma(c);
        // sin pasar del negro ni del blanco: si ya lo es, queda igual (si no, dividía por cero)
        double target = Math.max(goal + CONTOUR_TOLERANCE, 0);
        if (lum > target) {
            return Colors.lerp(c, new int[] {0, 0, 0}, (lum - target) / lum);
        }
        target = Math.min(goal - CONTOUR_TOLERANCE, 255);
        if (lum < target) {
            return Colors.lerp(c, new int[] {255, 255, 255}, (target - lum) / (255 - lum));
        }
        return new int[] {c[0], c[1], c[2]};
    }

    public static RgbaImage renderSolid(RgbaImage im, Eaten eaten, EdgeMode edgeMode) {
        RgbaImage out = im.copy();
        Mask e = eaten.pixels();
        for (Point p : e) {
            out.set(p.x(), p.y(), 0, 0, 0, 0);
        }
        double[] d = eaten.biteDir();
        double[] center = null;
        if (d != null) {
            // último bocado: el borde que no mira a la mordida es "orilla" de la
            // comida y lleva contorno; sin él el trozo se ve deslavado
            Mask mask = Masks.opaque(im);
            Mask rem = mask.minus(e);
            long sumX = 0, sumY = 0;
            for (Point p : rem) {
                sumX += p.x();
                sumY += p.y();
            }
            center = new double[] {(double) sumX / rem.size(), (double) sumY / rem.size()};
            // perfil del borde del original: anillos 1..k y el contraste entre anillos vecinos
            int rings = im.pxScale();
            Map<Point, Integer> d0 = Masks.depthMap(mask);
            Map<Point, Integer> d1 = Masks.depthMap(rem);
            TreeMap<Integer, List<Point>> byDepth = new TreeMap<>();
            for (Point q : new Mask(d0.keySet())) {
                byDepth.computeIfAbsent(d0.get(q), x -> new ArrayList<>()).add(q);
            }
            double[] step = new double[rings + 1];
            for (int r = 1; r <= rings; r++) {
                Mask region = new Mask();
                for (Point q : mask) {
                    if (d0.get(q) >= r) {
                        region.add(q);
                    }
                }
                step[r] = outlineContrast(im, new Mask(byDepth.getOrDefault(r, List.of())), region);
            }
            // de adentro hacia afuera: cada anillo nuevo toma, entre los 20 píxeles
            // más cercanos del mismo anillo del original, el color que reproduce el
            // contraste original contra el anillo de adentro ya pintado.
            // Cada anillo solo lee anillos más profundos: el orden dentro del anillo no importa.
            Mask d1Order = new Mask(d1.keySet());
            for (int r = rings; r >= 1; r--) {
                for (Point p : d1Order) {
                    if (d1.get(p) != r) {
                        continue;
                    }
                    if (d0.get(p) <= r || biteSide(p, center, d)) {
                        continue;   // ya era borde del original, o es el lado mordido
                    }
                    List<Double> inner = new ArrayList<>();
                    for (Point n : Masks.nbrs(p)) {
                        if (d1.getOrDefault(n, 0) > r) {
                            inner.add(Colors.luma(out.get(n)));
                        }
                    }
                    if (inner.isEmpty()) {
                        inner.add(Colors.luma(out.get(p)));
                    }
                    double goal = PyMath.sum(inner.stream().mapToDouble(Double::doubleValue).toArray()) / inner.size()
                        - step[r];
                    List<Point> pool = byDepth.get(r);
                    if (pool == null || pool.isEmpty()) {
                        pool = byDepth.get(1);
                    }
                    List<Point> near = nearest(pool, p, 20);
                    Point q = near.get(0);
                    double bestGap = Math.abs(Colors.luma(im.get(q)) - goal);
                    for (Point cand : near) {
                        double gap = Math.abs(Colors.luma(im.get(cand)) - goal);
                        if (gap < bestGap) {
                            bestGap = gap;
                            q = cand;
                        }
                    }
                    int[] oc = contourColor(im.get(q), goal);
                    out.set(p.x(), p.y(), oc[0], oc[1], oc[2], out.alpha(p.x(), p.y()));
                }
            }
        }
        if (edgeMode != EdgeMode.NONE) {
            int[] pulp = interiorColor(im);
            Mask rem = Masks.opaque(im).minus(e);
            for (Point p : rem) {
                if (d != null && !biteSide(p, center, d)) {
                    continue;
                }
                if (touches(p, e, Masks.N4)) {
                    int[] c = out.get(p);
                    int[] rgb = edgeMode == EdgeMode.DARK
                        ? Colors.lerp(c, new int[] {0, 0, 0}, EDGE_STRENGTH * 0.5)
                        : Colors.lerp(c, pulp, EDGE_STRENGTH);
                    out.set(p.x(), p.y(), rgb[0], rgb[1], rgb[2], c[3]);
                }
            }
        }
        return out;
    }
}
