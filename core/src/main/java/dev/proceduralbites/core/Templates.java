package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.SHAPE_BACK;
import static dev.proceduralbites.core.Params.SHAPE_FRONT;
import static dev.proceduralbites.core.Params.SHAPE_MAIN;
import static dev.proceduralbites.core.Params.SHAPE_SIDED;
import static dev.proceduralbites.core.Params.SHAPE_WIDTH;
import static dev.proceduralbites.core.Params.TEMPLATE_MIN;
import static dev.proceduralbites.core.Params.TEMPLATE_SHARE;
import static dev.proceduralbites.core.Params.TEMPLATE_VOTE;
import static dev.proceduralbites.core.Params.TOP_CUP_SHADE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Comida en un tazón que el mod dibuja igual en varias comidas y que no calza con el de
 * referencia: el recipiente son los píxeles iguales (mismo lugar, mismo color) en las otras
 * comidas del mod, y el contenido, lo que cambia.
 */
public final class Templates {
    private Templates() {
    }

    public record Group(Map<Point, Integer> count, List<RgbaImage> members) {
        public int size() {
            return members.size();
        }
    }

    /** Las {@code others} del mismo tamaño con al menos TEMPLATE_SHARE de la silueta de {@code full} igual. */
    public static Group group(RgbaImage full, List<RgbaImage> others) {
        Mask mask = Masks.opaque(full);
        List<Point> points = mask.toList();
        int[] at = new int[points.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = points.get(i).y() * full.width + points.get(i).x();
        }
        double need = TEMPLATE_SHARE * at.length;
        int allowed = at.length - (int) Math.ceil(need);   // diferencias que todavía dejan llegar
        int[] f = packed(full);
        Map<Point, Integer> count = new HashMap<>();
        List<RgbaImage> members = new ArrayList<>();
        for (RgbaImage o : others) {
            if (o.width != full.width || o.height != full.height) {
                continue;
            }
            byte[] g = o.rgba();
            int misses = 0;
            for (int i = 0; i < at.length && misses <= allowed; i++) {
                if (f[at[i]] != (int) INTS.get(g, at[i] * 4)) {
                    misses++;   // la mayoría sale antes de recorrer todo
                }
            }
            if (misses > allowed || at.length - misses < need) {
                continue;
            }
            members.add(o);
            for (int i = 0; i < at.length; i++) {
                if (f[at[i]] == (int) INTS.get(g, at[i] * 4)) {
                    count.merge(points.get(i), 1, Integer::sum);
                }
            }
        }
        return new Group(count, members);
    }

    /** Los 4 bytes de cada píxel juntos: solo se comparan, el orden no importa. */
    private static final java.lang.invoke.VarHandle INTS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class, java.nio.ByteOrder.BIG_ENDIAN);

    private static int[] packed(RgbaImage im) {
        byte[] b = im.rgba();
        int[] out = new int[b.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) INTS.get(b, i * 4);
        }
        return out;
    }

    public record Features(double front, double back, double width, double main, double sided) {
        public boolean bowl() {
            return front >= SHAPE_FRONT && back <= SHAPE_BACK && width >= SHAPE_WIDTH && main >= SHAPE_MAIN
                    && sided >= SHAPE_SIDED;
        }
    }

    /**
     * Medidas de forma de tazón visto de tres cuartos. front: filas (x k) de recipiente bajo
     * el contenido en el tercio central; back: filas sobre el contenido; width: fila más ancha
     * del contenido sobre el ancho del recipiente; main: pieza más grande sobre el contenido;
     * sided: parte del contenido con recipiente a los dos lados.
     */
    public static Features features(RgbaImage full, Mask vessel, Mask contents) {
        if (vessel.isEmpty() || contents.isEmpty()) {
            return null;
        }
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        Map<Integer, List<Integer>> cols = new TreeMap<>();
        for (Point p : mask) {
            cols.computeIfAbsent(p.x(), x -> new ArrayList<>()).add(p.y());
        }
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        for (Point p : vessel) {
            x0 = Math.min(x0, p.x());
            x1 = Math.max(x1, p.x());
        }
        double third = (x1 - x0 + 1) / 3.0;
        Integer front = null;
        int back = 0;
        boolean any = false;
        for (int x = x0; x <= x1; x++) {
            if (!(x0 + third <= x && x <= x1 - third) || !cols.containsKey(x)) {
                continue;
            }
            List<Integer> col = cols.get(x);
            int cTop = Integer.MAX_VALUE;
            int cLow = Integer.MIN_VALUE;
            for (int y : col) {
                if (contents.contains(x, y)) {
                    cTop = Math.min(cTop, y);
                    cLow = Math.max(cLow, y);
                }
            }
            int f;
            int b = 0;
            if (cLow == Integer.MIN_VALUE) {
                f = col.size();
            } else {
                int below = 0;
                boolean allVessel = true;
                for (int y : col) {
                    if (y > cLow) {
                        below++;
                        allVessel &= vessel.contains(x, y);
                    } else if (y < cTop && vessel.contains(x, y)) {
                        b++;
                    }
                }
                f = allVessel ? below : -1;
            }
            front = front == null ? f : Math.min(front, f);
            back = any ? Math.max(back, b) : b;
            any = true;
        }
        if (!any) {
            return null;
        }
        Map<Integer, int[]> crows = rowSpans(contents);
        Map<Integer, int[]> vrows = rowSpans(vessel);
        int width = 0;
        for (int[] r : crows.values()) {
            width = Math.max(width, r[1] - r[0] + 1);
        }
        int sided = 0;
        for (Point p : contents) {
            int[] r = vrows.get(p.y());
            if (r != null && r[0] < p.x() && p.x() < r[1]) {
                sided++;
            }
        }
        int main = Masks.bySize(Masks.components(contents)).get(0).size();
        return new Features((double) front / k, (double) back / k, (double) width / (x1 - x0 + 1),
                (double) main / contents.size(), (double) sided / contents.size());
    }

    private static Map<Integer, int[]> rowSpans(Mask m) {
        Map<Integer, int[]> rows = new HashMap<>();
        for (Point p : m) {
            int[] r = rows.computeIfAbsent(p.y(), y -> new int[] {p.x(), p.x()});
            r[0] = Math.min(r[0], p.x());
            r[1] = Math.max(r[1], p.x());
        }
        return rows;
    }

    /** Material del tazón: tono, saturación y brillo de las filas de abajo. */
    record Material(double mh, double hw, double smin, double smax, double vmin, double vmax) {
        boolean test(int[] c) {
            double[] hsv = Colors.hsv(c);
            if (!(vmin - 0.35 <= hsv[2] && hsv[2] <= vmax + 0.35)) {
                return false;
            }
            if (smax < 0.2) {                   // base sin color (cerámica)
                return hsv[1] < 0.25;
            }
            return Colors.hueGap(hsv[0], mh) <= hw + 8 / 360.0 && smin - 0.25 <= hsv[1] && hsv[1] <= smax + 0.25;
        }
    }

    record Vessel(Mask vessel, Material material) {
    }

    /**
     * Lo que se dibuja como tazón: el material de la base y los utensilios que salen del cuerpo; la
     * comida repetida no.
     */
    static Vessel vessel(RgbaImage full, Group group) {
        Mask mask = Masks.opaque(full);
        int k = full.pxScale();
        int n = group.size();
        Mask shared = new Mask();
        for (Map.Entry<Point, Integer> e : group.count().entrySet()) {
            if (e.getValue() >= Math.min(TEMPLATE_MIN, n)) {
                shared.add(e.getKey());
            }
        }
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Point p : mask) {
            top = Math.min(top, p.y());
            bottom = Math.max(bottom, p.y());
        }
        int cut = bottom - Math.max(2 * k, PyMath.round(0.3 * (bottom - top + 1)));
        Mask seed = new Mask();
        for (Point p : shared) {
            if (p.y() > cut) {
                seed.add(p);
            }
        }
        if (seed.isEmpty()) {
            return new Vessel(new Mask(), null);
        }
        List<double[]> hsvs = new ArrayList<>();
        for (Point p : seed) {
            hsvs.add(Colors.hsv(full.get(p)));
        }
        double[] hs = new double[hsvs.size()];
        double[] ss = new double[hsvs.size()];
        for (int i = 0; i < hs.length; i++) {
            hs[i] = hsvs.get(i)[0];
            ss[i] = hsvs.get(i)[1];
        }
        java.util.Arrays.sort(hs);
        java.util.Arrays.sort(ss);
        double mh = hs[hs.length / 2];
        double ms = ss[ss.length / 2];
        List<double[]> core = new ArrayList<>();
        for (double[] c : hsvs) {
            if (Colors.hueGap(c[0], mh) <= 16 / 360.0 && Math.abs(c[1] - ms) <= 0.5) {
                core.add(c);
            }
        }
        if (core.isEmpty()) {
            core = hsvs;
        }
        double hw = Double.NEGATIVE_INFINITY;
        double smin = Double.POSITIVE_INFINITY;
        double smax = Double.NEGATIVE_INFINITY;
        double vmin = Double.POSITIVE_INFINITY;
        double vmax = Double.NEGATIVE_INFINITY;
        for (double[] c : core) {
            hw = Math.max(hw, Colors.hueGap(c[0], mh));
            smin = Math.min(smin, c[1]);
            smax = Math.max(smax, c[1]);
            vmin = Math.min(vmin, c[2]);
            vmax = Math.max(vmax, c[2]);
        }
        Material material = new Material(mh, hw, smin, smax, vmin, vmax);
        Mask matPixels = new Mask();
        for (Point p : shared) {
            if (material.test(full.get(p))) {
                matPixels.add(p);
            }
        }
        Mask mat = new Mask();
        for (Mask c : Masks.components(matPixels)) {
            if (c.intersects(seed)) {
                mat.addAll(c);
            }
        }
        if (mat.isEmpty()) {
            return new Vessel(new Mask(), null);
        }
        Map<Integer, int[]> rows = rowSpans(mat);
        Mask body = new Mask();
        for (Point p : mask) {
            int[] r = rows.get(p.y());
            if (r != null && r[0] <= p.x() && p.x() <= r[1]) {
                body.add(p);
            }
        }
        Mask vessel = new Mask(mat);
        for (Mask c : Masks.components(shared.minus(mat))) {
            Mask out = c.minus(body);
            if (out.isEmpty()) {
                continue;
            }
            Set<Integer> colors = new HashSet<>();
            for (Point p : out) {
                colors.add(rgb(full.get(p)));
            }
            Mask same = new Mask();
            for (Point p : c) {
                if (colors.contains(rgb(full.get(p)))) {
                    same.add(p);
                }
            }
            for (Mask g : Masks.components(same)) {
                if (g.intersects(out)) {
                    vessel.addAll(g);
                }
            }
        }
        return new Vessel(vessel, material);
    }

    /**
     * El tazón vacío con el dibujo del ítem. Lo que la comida tapa en la boca queda liso, con
     * el color más repetido de la pared interior; en el resto del cuerpo, el píxel de
     * recipiente más cercano.
     */
    static RgbaImage bowlImage(RgbaImage full, Mask vessel, Mask contents, Map<Point, int[]> hidden,
            Material material) {
        int k = full.pxScale();
        Mask hiddenMask = new Mask(hidden.keySet());
        Map<Integer, int[]> rows = rowSpans(vessel.union(hiddenMask));
        Mask body = vessel.union(hiddenMask);
        for (Point p : contents) {
            int[] r = rows.get(p.y());
            if (r != null && r[0] < p.x() && p.x() < r[1]) {
                body.add(p);
            }
        }
        Bowls.Ellipse e = Bowls.rimEllipse(body, k);
        Mask opening = new Mask();
        for (Point p : body) {
            if (e.contains(p)) {
                opening.add(p);
            }
        }
        Mask inner = body.minus(Bowls.zoneOf(body, opening, k));
        Set<Integer> outside = new HashSet<>();
        for (Point p : vessel.minus(inner)) {
            outside.add(rgba(full.get(p)));
        }
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        Map<Integer, int[]> seenColor = new HashMap<>();
        for (Point p : vessel.and(inner)) {
            int[] c = full.get(p);
            if (outside.contains(rgba(c)) && (material == null || material.test(c))) {
                seen.merge(rgba(c), 1, Integer::sum);
                seenColor.putIfAbsent(rgba(c), c);
            }
        }
        int[] wall = null;
        int most = 0;
        for (Map.Entry<Integer, Integer> c : seen.entrySet()) {
            if (c.getValue() > most) {   // el primero gana en empate
                most = c.getValue();
                wall = seenColor.get(c.getKey());
            }
        }
        if (most < 2 * k * k) {
            long[] sum = new long[3];
            for (Point p : vessel) {
                int[] c = full.get(p);
                for (int i = 0; i < 3; i++) {
                    sum[i] += c[i];
                }
            }
            if (vessel.isEmpty()) {
                throw new ArithmeticException("tazón sin recipiente");
            }
            int[] avg = new int[3];
            for (int i = 0; i < 3; i++) {
                avg[i] = PyMath.round((double) sum[i] / vessel.size());
            }
            int[] dark = Colors.lerp(avg, new int[] {0, 0, 0}, TOP_CUP_SHADE);
            wall = new int[] {dark[0], dark[1], dark[2], 255};
        }
        RgbaImage out = new RgbaImage(full.width, full.height);
        for (Point p : vessel) {
            out.set(p, full.get(p));
        }
        for (Map.Entry<Point, int[]> h : hidden.entrySet()) {
            out.set(h.getKey(), h.getValue());
        }
        List<Point> visible = vessel.toList();
        for (Point p : body.minus(vessel).minus(hiddenMask)) {
            if (inner.contains(p)) {
                out.set(p, wall);
            } else {
                out.set(p, full.get(Bowls.nearestInRow(visible, p)));
            }
        }
        return out;
    }

    /** El tazón vacío y la comida, si {@code full} es comida en un tazón por plantilla; {@code null} si no. */
    public static Bowls.Own shapeBowl(RgbaImage full, List<RgbaImage> others) {
        Group group = group(full, others);
        int n = group.size();
        if (n == 0) {
            return null;
        }
        Mask mask = Masks.opaque(full);
        Mask vessel = new Mask();
        for (Map.Entry<Point, Integer> e : group.count().entrySet()) {
            if (e.getValue() >= Math.max(1, TEMPLATE_VOTE * n)) {
                vessel.add(e.getKey());
            }
        }
        Features f = features(full, vessel, mask.minus(vessel));
        if (f == null || !f.bowl()) {
            return null;
        }
        Vessel drawn = vessel(full, group);
        if (!drawn.vessel().isEmpty() && !mask.minus(drawn.vessel()).isEmpty()) {
            vessel = drawn.vessel();
        }
        Mask contents = mask.minus(vessel);
        Map<Point, int[]> hidden = new LinkedHashMap<>();
        if (drawn.material() != null) {
            int k = full.pxScale();
            Mask ring = new Mask();
            Mask left = mask;
            for (int i = 0; i < 2 * k; i++) {
                Mask border = Masks.outerBorder(left);
                ring.addAll(border);
                left = left.minus(border);
            }
            for (Point p : contents.and(ring)) {
                TreeMap<Integer, Integer> seen = new TreeMap<>(Integer::compareUnsigned);   // orden sin signo de (r, g, b, a)
                Map<Integer, int[]> colors = new HashMap<>();
                for (RgbaImage o : group.members()) {
                    int[] c = o.get(p);
                    if (c[3] != 0) {
                        seen.merge(rgbaOrder(c), 1, Integer::sum);
                        colors.putIfAbsent(rgbaOrder(c), c);
                    }
                }
                int best = 0;
                int[] color = null;
                for (Map.Entry<Integer, Integer> c : seen.entrySet()) {
                    if (c.getValue() > best) {
                        best = c.getValue();
                        color = colors.get(c.getKey());
                    }
                }
                if (color != null && best >= TEMPLATE_MIN && drawn.material().test(color)) {
                    hidden.put(p, color);
                }
            }
        }
        return new Bowls.Own(bowlImage(full, vessel, contents, hidden, drawn.material()), contents);
    }

    public static List<RgbaImage> frames(RgbaImage full, Bowls.Own own) {
        return Bowls.bowlFrames(full, own.contents(), null, own.bowl());
    }

    private static int rgb(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    private static int rgba(int[] c) {
        return (c[3] << 24) | rgb(c);
    }

    /** Color empaquetado que ordena como (r, g, b, a) sin signo. */
    private static int rgbaOrder(int[] c) {
        return (c[0] << 24) | (c[1] << 16) | (c[2] << 8) | c[3];
    }
}
