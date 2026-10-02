package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.FOAM_SATURATION;
import static dev.proceduralbites.core.Params.FOAM_VALUE;
import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.LIQUID_HUE_RANGE;
import static dev.proceduralbites.core.Params.MENISCUS;
import static dev.proceduralbites.core.Params.PALETTE_TOLERANCE;
import static dev.proceduralbites.core.Params.REMOVE_CORK;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bebidas: el nivel del líquido baja fila por fila. */
public final class Drinks {
    private Drinks() {
    }

    private static final int TOL2 = PALETTE_TOLERANCE * PALETTE_TOLERANCE;
    private static final int[] TRANSPARENT = {0, 0, 0, 0};

    /**
     * Paleta en cubos RGB de lado tol: un color a distancia {@code <= tol} está en uno de los
     * 27 cubos vecinos. Recorrer la paleta entera por píxel tardaba más de 3 s a 512x.
     */
    static final class Palette {
        private final int cell;
        private final double tol2;
        private final Map<Integer, List<int[]>> cells = new HashMap<>();
        private final boolean empty;

        Palette(List<int[]> colors, double tol) {
            cell = Math.max(1, (int) Math.ceil(tol));
            tol2 = tol * tol;
            empty = colors.isEmpty();
            for (int[] c : colors) {
                cells.computeIfAbsent(key(c[0] / cell, c[1] / cell, c[2] / cell), x -> new ArrayList<>()).add(c);
            }
        }

        private static int key(int r, int g, int b) {
            return ((r + 1) << 16) | ((g + 1) << 8) | (b + 1);
        }

        boolean near(int[] c) {
            if (empty) {
                throw new ArithmeticException("paleta vacía");
            }
            int r = c[0] / cell;
            int g = c[1] / cell;
            int b = c[2] / cell;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dg = -1; dg <= 1; dg++) {
                    for (int db = -1; db <= 1; db++) {
                        List<int[]> list = cells.get(key(r + dr, g + dg, b + db));
                        if (list != null) {
                            for (int[] q : list) {
                                if (Colors.colorDist2(c, q) <= tol2) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    static List<int[]> rgbSet(RgbaImage im, Mask m) {
        TreeSet<Integer> seen = new TreeSet<>();
        List<int[]> out = new ArrayList<>();
        for (Point p : m) {
            int[] c = im.get(p);
            if (seen.add((c[0] << 16) | (c[1] << 8) | c[2])) {
                out.add(new int[] {c[0], c[1], c[2]});
            }
        }
        return out;
    }

    private static int maxY(Mask m) {
        if (m.isEmpty()) {
            throw new ArithmeticException("máscara vacía");
        }
        int y = Integer.MIN_VALUE;
        for (Point p : m) {
            y = Math.max(y, p.y());
        }
        return y;
    }


    /** Contenido: los colores que no están en el recipiente vacío. Sirve aunque la botella tenga otra forma. */
    public static Mask contentsByPalette(RgbaImage full, RgbaImage empty) {
        // los colores del corcho no cuentan como recipiente: una crema o espuma
        // parecida al corcho es contenido
        Mask stopper = cork(empty, new Mask(), null);
        Palette palette = new Palette(rgbSet(empty, Masks.opaque(empty).minus(stopper)), PALETTE_TOLERANCE);
        Mask diff = new Mask();
        for (Point p : Masks.opaque(full)) {
            if (!palette.near(full.get(p))) {
                diff.add(p);
            }
        }
        List<Mask> comps = Masks.bySize(Masks.components(diff));
        Mask out = new Mask();
        if (comps.isEmpty()) {
            return out;
        }
        // la mayor y las que tengan al menos 25% de ella: un brillo del vidrio puede partir el líquido
        for (Mask c : comps) {
            if (c.size() >= 0.25 * comps.get(0).size()) {
                out.addAll(c);
            }
        }
        return out;
    }

    public static Mask contentsByPosition(RgbaImage full, RgbaImage empty) {
        Mask diff = new Mask();
        for (Point p : Masks.opaque(full)) {
            int[] e = empty.get(p);
            if (e[3] == 0 || Colors.colorDist2(full.get(p), e) > TOL2) {
                diff.add(p);
            }
        }
        List<Mask> comps = Masks.components(diff);
        return comps.isEmpty() ? new Mask() : Masks.bySize(comps).get(0);
    }

    /** Misma forma que el vacío: diferencia por posición (leche blanca contra brillos blancos); si no, por paleta. */
    public static Mask findContents(RgbaImage full, RgbaImage empty) {
        if (full.width == empty.width && full.height == empty.height) {
            Mask byPos = contentsByPosition(full, empty);
            if (!byPos.isEmpty() && aligned(full, empty, byPos)) {
                return byPos;
            }
        }
        return contentsByPalette(full, empty);
    }

    public static boolean aligned(RgbaImage full, RgbaImage empty, Mask contents) {
        if (full.width != empty.width || full.height != empty.height) {
            return false;
        }
        Mask shell = Masks.opaque(full).minus(contents);
        if (shell.isEmpty()) {
            return false;
        }
        int same = 0;
        for (Point p : shell) {
            int[] e = empty.get(p);
            if (e[3] > 0 && Colors.colorDist2(full.get(p), e) <= TOL2) {
                same++;
            }
        }
        // coincide casi todo lo que no es contenido, y eso cubre buena parte del vacío
        // (si no, una botella de otra forma "coincide" en 6 píxeles sueltos)
        return (double) same / shell.size() >= 0.85 && same >= 0.5 * Masks.opaque(empty).size();
    }

    /** Píxeles cálidos y angostos arriba del recipiente; con vacío, solo si tiene corcho y de colores parecidos. */
    public static Mask cork(RgbaImage full, Mask liquid, RgbaImage empty) {
        Palette refColors = null;
        if (empty != null) {
            Mask ref = cork(empty, new Mask(), null);
            if (ref.isEmpty()) {
                return new Mask();
            }
            refColors = new Palette(rgbSet(empty, ref), PALETTE_TOLERANCE * 1.5);
        }
        Mask mask = Masks.opaque(full);
        if (mask.isEmpty()) {
            throw new ArithmeticException("textura sin píxeles opacos");
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Point p : mask) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        double topLimit = minY + (maxY - minY) * 0.35;
        // por paleta, el corcho entra al contenido como pieza aparte: cuenta la pieza,
        // salvo el cuerpo del líquido, que es toda de corcho
        Mask candidates = mask.minus(liquid);
        List<Mask> pieces = Masks.bySize(Masks.components(liquid));
        for (Mask piece : pieces.subList(Math.min(1, pieces.size()), pieces.size())) {
            boolean all = true;
            for (Point p : piece) {
                if (!corky(full, p, topLimit, refColors)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                candidates.addAll(piece);
            }
        }
        Mask warm = new Mask();
        for (Point p : candidates) {
            if (corky(full, p, topLimit, refColors)) {
                warm.add(p);
            }
        }
        List<Mask> comps = Masks.components(warm);
        if (comps.isEmpty()) {
            return new Mask();
        }
        Mask c = Masks.bySize(comps).get(0);
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
        for (Point p : c) {
            x0 = Math.min(x0, p.x());
            x1 = Math.max(x1, p.x());
        }
        int itemWidth = maxX - minX + 1;
        if (x1 - x0 + 1 > 0.7 * itemWidth) {   // un borde de tazón, no un corcho
            return new Mask();
        }
        // un corcho tapa un cuello angosto: la fila de abajo mide como mucho 60% del ancho
        int below = maxY(c) + 1;
        int n0 = Integer.MAX_VALUE, n1 = Integer.MIN_VALUE;
        for (Point p : mask) {
            if (p.y() == below) {
                n0 = Math.min(n0, p.x());
                n1 = Math.max(n1, p.x());
            }
        }
        if (n0 <= n1 && n1 - n0 + 1 > 0.6 * itemWidth) {
            return new Mask();
        }
        return c;
    }

    private static boolean corky(RgbaImage full, Point p, double topLimit, Palette refColors) {
        int[] c = full.get(p);
        double[] hsv = Colors.hsv(c);
        return p.y() <= topLimit && (hsv[0] <= 45 / 360.0 || hsv[0] >= 340 / 360.0) && hsv[1] >= 0.12 && hsv[2] >= 0.2
                && (refColors == null || refColors.near(c));
    }

    /**
     * Con interior transparente encerrado (botella, jarra) se ve de lado; sólido por dentro (tazón,
     * balde), desde arriba.
     */
    public static boolean isGlass(RgbaImage empty) {
        int w = empty.width;
        int h = empty.height;
        boolean[] outside = new boolean[w * h];
        int[] stack = new int[w * h];
        int top = 0;
        int count = 0;
        int solid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean opaque = empty.alpha(x, y) > 0;
                if (opaque) {
                    solid++;
                } else if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    outside[y * w + x] = true;
                    stack[top++] = y * w + x;
                    count++;
                }
            }
        }
        while (top > 0) {
            int i = stack[--top];
            int x = i % w;
            int y = i / w;
            for (int[] d : Masks.N4) {
                int nx = x + d[0];
                int ny = y + d[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    int j = ny * w + nx;
                    if (!outside[j] && empty.alpha(nx, ny) == 0) {
                        outside[j] = true;
                        stack[top++] = j;
                        count++;
                    }
                }
            }
        }
        return (long) solid + count < (long) w * h;
    }


    public record Split(Mask consumable, Mask label, Mask core) {
    }

    /** Espuma o crema: poco saturada, casi blanca y al menos tan clara como el líquido. */
    public static boolean isFoam(RgbaImage full, Mask comp, double bright) {
        double[] s = new double[comp.size()];
        double[] v = new double[comp.size()];
        int i = 0;
        for (Point p : comp) {
            double[] hsv = Colors.hsv(full.get(p));
            s[i] = hsv[1];
            v[i++] = hsv[2];
        }
        double value = PyMath.sum(v) / v.length;
        return PyMath.sum(s) / s.length < FOAM_SATURATION && value >= FOAM_VALUE && value >= bright;
    }

    /**
     * Separa el contenido en consumible, etiqueta y líquido. Líquido: la familia de color de
     * la fila más baja. Etiqueta: lo que cruza la silueta de lado a lado.
     */
    public static Split splitLabel(RgbaImage full, Mask contents) {
        Mask mask = Masks.opaque(full);
        int bottom = maxY(contents);
        int refCount = 0;
        List<Double> sat = new ArrayList<>();
        for (Point p : contents) {
            if (p.y() == bottom) {
                double[] hsv = Colors.hsv(full.get(p));
                refCount++;
                if (hsv[1] >= 0.25) {
                    sat.add(hsv[0]);
                }
            }
        }
        boolean neutral = sat.size() * 2 < refCount;
        double hue = 0;
        if (!neutral) {
            double[] cos = new double[sat.size()];
            double[] sin = new double[sat.size()];
            for (int i = 0; i < sat.size(); i++) {
                cos[i] = StrictMath.cos(2 * Math.PI * sat.get(i));
                sin[i] = StrictMath.sin(2 * Math.PI * sat.get(i));
            }
            hue = PyMath.mod(StrictMath.atan2(PyMath.sum(sin), PyMath.sum(cos)) / (2 * Math.PI), 1.0);
        }
        double hueC = hue;
        // la familia del líquido: cerca de la media o de algún tono de la fila de abajo
        java.util.function.Predicate<int[]> isLiquid = c -> {
            double[] hsv = Colors.hsv(c);
            if (neutral) {
                return hsv[1] < 0.25;
            }
            if (hsv[1] < 0.25) {
                return false;
            }
            if (Colors.hueGap(hsv[0], hueC) <= LIQUID_HUE_RANGE / 360.0) {
                return true;
            }
            for (double r : sat) {
                if (Colors.hueGap(hsv[0], r) <= LIQUID_HUE_RANGE / 360.0) {
                    return true;
                }
            }
            return false;
        };
        Map<Integer, int[]> rows = new TreeMap<>();   // y -> {min x, max x} de lo que no es líquido
        Mask other = new Mask();
        for (Point p : contents) {
            if (!isLiquid.test(full.get(p))) {
                other.add(p);
                int[] r = rows.computeIfAbsent(p.y(), y -> new int[] {p.x(), p.x()});
                r[0] = Math.min(r[0], p.x());
                r[1] = Math.max(r[1], p.x());
            }
        }
        Mask band = new Mask();
        for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
            for (int x = e.getValue()[0]; x <= e.getValue()[1]; x++) {
                if (contents.contains(x, e.getKey())) {
                    band.add(x, e.getKey());
                }
            }
        }
        Map<Integer, int[]> silhouette = new HashMap<>();   // y -> {min x, max x} del ítem
        for (Point p : mask) {
            int[] r = silhouette.computeIfAbsent(p.y(), y -> new int[] {p.x(), p.x()});
            r[0] = Math.min(r[0], p.x());
            r[1] = Math.max(r[1], p.x());
        }
        Mask label = new Mask();
        for (Mask comp : Masks.components(band, Masks.N4)) {
            boolean left = false;
            boolean right = false;
            for (Point p : comp) {
                int[] r = silhouette.get(p.y());
                left |= p.x() == r[0];
                right |= p.x() == r[1];
            }
            if (left && right) {
                label.addAll(comp);
            }
        }
        // lo que asoma por encima del recipiente (rodaja, palito, pajilla) no baja con el
        // nivel: queda entero como la etiqueta. La espuma y la crema sí se consumen
        Mask shell = mask.minus(contents);
        if (!shell.isEmpty()) {
            int top = shell.first().y();
            List<Double> pure = new ArrayList<>();
            for (Point p : contents) {
                if (isLiquid.test(full.get(p))) {
                    pure.add(Colors.hsv(full.get(p))[2]);
                }
            }
            double bright = pure.isEmpty() ? 0.0
                    : PyMath.sum(pure.stream().mapToDouble(Double::doubleValue).toArray()) / pure.size();
            for (Mask comp : Masks.components(other)) {
                if (comp.first().y() < top) {
                    if (isFoam(full, comp, bright)) {
                        label.removeAll(comp);
                    } else {
                        label.addAll(comp);
                    }
                }
            }
            // tapa: pieza aparte por encima del recipiente, aunque sea del color del líquido
            for (Mask comp : Masks.components(contents)) {
                if (comp.size() != contents.size() && maxY(comp) < top && !isFoam(full, comp, bright)) {
                    label.addAll(comp);
                }
            }
        }
        Mask consumable = contents.minus(label);
        Mask core = new Mask();
        for (Point p : consumable) {
            if (isLiquid.test(full.get(p))) {
                core.add(p);
            }
        }
        return new Split(consumable, label, core);
    }


    public record Profile(Map<Integer, int[]> byRow, int[] main, List<Integer> sources) {
    }

    /** Degradado vertical del líquido; color principal: el más frecuente del cuerpo, sin el fondo ni los extremos. */
    public static Profile liquidProfile(RgbaImage full, Mask liquid, List<Integer> rows) {
        Map<Integer, List<Integer>> lists = new HashMap<>();
        for (int y : rows) {
            lists.put(y, new ArrayList<>());
        }
        for (Point p : liquid) {   // orden (y, x): las x de cada fila salen ordenadas
            List<Integer> xs = lists.get(p.y());
            if (xs != null) {
                xs.add(p.x());
            }
        }
        Map<Integer, int[]> byRow = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : lists.entrySet()) {
            byRow.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        if (rows.isEmpty()) {
            throw new ArithmeticException("líquido sin filas");
        }
        int[] widths = rows.stream().mapToInt(y -> byRow.get(y).length).sorted().toArray();
        int median = widths[widths.length / 2];
        // filas fuente: no la punta de un palito ni la boca estrecha del frasco
        double minWidth = Math.max(3, median / 2.0);
        List<Integer> sources = new ArrayList<>();
        for (int y : rows) {
            if (byRow.get(y).length >= minWidth) {
                sources.add(y);
            }
        }
        if (sources.isEmpty()) {
            sources = new ArrayList<>(rows);
        }
        List<int[]> body = new ArrayList<>();
        for (int y : sources.subList(0, sources.size() - 1)) {
            int[] xs = byRow.get(y);
            for (int i = 1; i < xs.length - 1; i++) {
                body.add(full.get(xs[i], y));
            }
        }
        if (body.isEmpty()) {
            for (Point p : liquid) {
                body.add(full.get(p));
            }
        }
        // a igual frecuencia, el que aparece primero (filas de arriba abajo)
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Map<Integer, int[]> colors = new HashMap<>();
        for (int[] c : body) {
            int key = (c[0] << 24) | (c[1] << 16) | (c[2] << 8) | c[3];
            counts.merge(key, 1, Integer::sum);
            colors.putIfAbsent(key, c);
        }
        int bestKey = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        return new Profile(byRow, colors.get(bestKey), sources);
    }

    /**
     * Pinta las filas que quedan con el degradado comprimido (brillo arriba, cuerpo, sombra
     * abajo); cada fila toma su fila fuente reescalada, para conservar el sombreado lateral.
     */
    static void compressLiquid(RgbaImage px, RgbaImage fp, Map<Integer, int[]> byRow, List<Integer> rows,
                               List<Integer> kept, int[] main) {
        int n = kept.size();
        if (n == 0) {
            return;
        }
        int shadow = rows.get(rows.size() - 1);
        List<Integer> upper = rows.size() > 1 ? rows.subList(0, rows.size() - 1) : rows;
        int highlight = upper.get(0);
        double bestLuma = Double.NEGATIVE_INFINITY;
        for (int y : upper) {
            int[] xs = byRow.get(y);
            long s = 0;
            for (int x : xs) {
                int[] c = fp.get(x, y);
                s += c[0] + c[1] + c[2];
            }
            double l = (double) s / xs.length;
            if (l > bestLuma) {   // el primero gana en empate
                bestLuma = l;
                highlight = y;
            }
        }
        List<Integer> body = new ArrayList<>();
        for (int y : upper) {
            if (y != highlight) {
                body.add(y);
            }
        }
        if (body.isEmpty()) {
            body.add(highlight);
        }
        int[] shadeRow = byRow.get(shadow);
        int[] shade = fp.get(shadeRow[shadeRow.length / 2], shadow);

        if (n == 1) {
            mainRow(px, byRow.get(kept.get(0)), kept.get(0), shade, main);
            return;
        }
        if (n == 2) {
            mainRow(px, byRow.get(kept.get(0)), kept.get(0), shade, main);
            copyRow(px, fp, byRow, kept.get(1), shadow);
            return;
        }
        List<Integer> src = new ArrayList<>();
        src.add(highlight);
        for (int j = 0; j < n - 2; j++) {
            src.add(body.get(PyMath.round((double) (j * (body.size() - 1)) / Math.max(n - 3, 1))));
        }
        src.add(shadow);
        for (int i = 0; i < n; i++) {
            copyRow(px, fp, byRow, kept.get(i), src.get(i));
        }
    }

    private static void mainRow(RgbaImage px, int[] xs, int y, int[] shade, int[] main) {
        for (int i = 0; i < xs.length; i++) {
            boolean edge = xs.length >= 4 && (i == 0 || i == xs.length - 1);
            px.set(new Point(xs[i], y), edge ? shade : main);
        }
    }

    private static void copyRow(RgbaImage px, RgbaImage fp, Map<Integer, int[]> byRow, int yDst, int ySrc) {
        int[] dst = byRow.get(yDst);
        int[] src = byRow.get(ySrc);
        for (int i = 0; i < dst.length; i++) {
            double t = dst.length > 1 ? (double) i / (dst.length - 1) : 0.5;
            px.set(new Point(dst[i], yDst), fp.get(src[PyMath.round(t * (src.length - 1))], ySrc));
        }
    }


    /**
     * Baja el nivel quitando filas de líquido de arriba abajo. Lo revelado toma el recipiente
     * vacío si tiene la misma forma; si no, queda transparente por dentro y con vidrio en la
     * silueta, para que el frasco nunca quede abierto.
     */
    public static List<RgbaImage> liquidFrames(RgbaImage full, Mask liquid, RgbaImage empty) {
        Mask mask = Masks.opaque(full);
        int k = full.pxScale();
        Mask border = Masks.outerBorder(mask);
        Mask stopper = REMOVE_CORK && empty != null ? cork(full, liquid, empty) : new Mask();
        liquid = liquid.minus(stopper);   // el corcho no marca el nivel
        // piezas sueltas que el artista dibujó a propósito (reflejos del vidrio)
        List<Mask> drawn = new ArrayList<>(Masks.components(mask));
        if (empty != null && empty.width == full.width && empty.height == full.height) {
            drawn.addAll(Masks.components(Masks.opaque(empty)));
        }
        boolean sameShape = empty != null && aligned(full, empty, liquid);
        boolean sideView = empty != null ? isGlass(empty) : maxY(liquid) >= maxY(mask) - 2 * k;
        Mask label = new Mask();
        Mask core = liquid;
        if (sideView) {
            Split split = splitLabel(full, liquid);   // la etiqueta queda intacta
            liquid = split.consumable();
            label = split.label();
            core = split.core().isEmpty() ? liquid : split.core();
        }
        RgbaImage fill = sameShape ? empty : null;
        // el nivel avanza por las filas donde el líquido se ve (detrás de una etiqueta no se nota)
        List<Integer> liquidRows = rowsOf(liquid);
        List<Integer> coreRows = rowsOf(core);
        Profile profile = liquidProfile(full, core, coreRows);
        // vidrio para tapar la silueta: el color del recipiente más cercano en el borde
        List<Point> glassEdge = sideView ? border.minus(liquid).minus(label).toList() : List.of();
        Mask walls = mask.minus(liquid);   // vidrio + etiqueta
        Map<Integer, int[]> wallRows = new HashMap<>();   // y -> {min x, max x}
        Map<Integer, int[]> wallCols = new HashMap<>();   // x -> {min y, max y}
        for (Point q : walls) {
            int[] r = wallRows.computeIfAbsent(q.y(), y -> new int[] {q.x(), q.x()});
            r[0] = Math.min(r[0], q.x());
            r[1] = Math.max(r[1], q.x());
            int[] c = wallCols.computeIfAbsent(q.x(), x -> new int[] {q.y(), q.y()});
            c[0] = Math.min(c[0], q.y());
            c[1] = Math.max(c[1], q.y());
        }
        // borde superior del recipiente por columna (solo piezas grandes): lo de arriba es aire
        List<Mask> shellParts = Masks.bySize(Masks.components(mask.minus(liquid)));
        Map<Integer, Integer> rimTop = new HashMap<>();
        for (Mask part : shellParts) {
            if (part.size() >= 0.25 * shellParts.get(0).size()) {
                for (Point p : part) {
                    rimTop.merge(p.x(), p.y(), Math::min);
                }
            }
        }
        int[] wall = null;
        if (fill == null && !sideView) {
            Mask others = mask.minus(liquid);
            if (others.isEmpty()) {
                throw new ArithmeticException("no hay recipiente");
            }
            long[] s = new long[3];
            for (Point p : others) {
                int[] c = full.get(p);
                for (int i = 0; i < 3; i++) {
                    s[i] += c[i];
                }
            }
            double[] avg = {(double) s[0] / others.size(), (double) s[1] / others.size(), (double) s[2] / others.size()};
            wall = Colors.lerp(avg, new int[] {0, 0, 0}, 0.45);
        }

        List<RgbaImage> frames = new ArrayList<>();
        for (double keep : Solids.schedule(LAST_LIQUID)) {
            int nGone = PyMath.round((1 - keep) * liquidRows.size());
            int cut = nGone < liquidRows.size() ? liquidRows.get(nGone) : liquidRows.get(liquidRows.size() - 1) + 1;
            RgbaImage out = full.copy();
            Mask left = new Mask();
            for (Point p : liquid) {
                if (p.y() >= cut) {
                    left.add(p);
                    continue;
                }
                if (fill != null) {
                    out.set(p, fill.get(p));
                } else if (sideView && border.contains(p) && !glassEdge.isEmpty()
                        && betweenWalls(p, wallRows, wallCols)) {
                    out.set(p, glassAt(full, glassEdge, p));
                } else if (sideView || p.y() < rimTop.getOrDefault(p.x(), -1)) {
                    out.set(p, TRANSPARENT);
                } else {
                    out.set(p.x(), p.y(), wall[0], wall[1], wall[2], 255);
                }
            }
            int keptRows = 0;
            for (int y : liquidRows) {
                if (y >= cut) {
                    keptRows++;
                }
            }
            if (sideView) {
                // el nivel baja comprimiendo el degradado, no recortando filas
                List<Integer> keptCore = new ArrayList<>();
                for (int y : coreRows) {
                    if (y >= cut) {
                        keptCore.add(y);
                    }
                }
                compressLiquid(out, full, profile.byRow(), profile.sources(), keptCore, profile.main());
            }
            // brillo de superficie solo vistos desde arriba y con 3 filas o más
            if (!left.isEmpty() && MENISCUS != 0 && !sideView && keptRows >= 3) {
                int top = left.first().y();
                for (Point p : left) {
                    if (p.y() == top) {
                        int[] c = out.get(p);
                        int[] rgb = Colors.lerp(c, new int[] {255, 255, 255}, MENISCUS);
                        out.set(p.x(), p.y(), rgb[0], rgb[1], rgb[2], c[3]);
                    }
                }
            }
            for (Point p : stopper) {
                out.set(p, TRANSPARENT);
            }
            // sin píxeles flotando: piezas diminutas nuevas (un reflejo que quedó en el aire)
            List<Mask> comps = Masks.bySize(Masks.components(Masks.opaque(out)));
            for (int i = 1; i < comps.size(); i++) {
                Mask comp = comps.get(i);
                boolean tiny = comp.size() <= 2 * k * k;
                boolean stray = !sideView && comp.size() < 0.25 * comps.get(0).size();
                if ((tiny || stray) && !drawn.contains(comp)) {
                    for (Point p : comp) {
                        out.set(p, TRANSPARENT);
                    }
                }
            }
            frames.add(out);
        }
        return frames;
    }

    static List<Integer> rowsOf(Mask m) {
        TreeSet<Integer> ys = new TreeSet<>();
        for (Point p : m) {
            ys.add(p.y());
        }
        return new ArrayList<>(ys);
    }

    private static boolean betweenWalls(Point p, Map<Integer, int[]> rows, Map<Integer, int[]> cols) {
        int[] r = rows.get(p.y());
        int[] c = cols.get(p.x());
        return (r != null && r[0] < p.x() && r[1] > p.x()) || (c != null && c[0] < p.y() && c[1] > p.y());
    }

    private static int[] glassAt(RgbaImage full, List<Point> edge, Point p) {
        Point best = edge.get(0);
        int bestD = Integer.MAX_VALUE;
        for (Point q : edge) {
            int d = (q.x() - p.x()) * (q.x() - p.x()) + (q.y() - p.y()) * (q.y() - p.y());
            if (d < bestD) {
                bestD = d;
                best = q;
            }
        }
        return full.get(best);
    }
}
