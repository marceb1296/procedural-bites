package dev.proceduralbites.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Morfología, componentes y profundidad de máscaras. */
public final class Masks {
    private Masks() {
    }

    /** El orden de los vecinos cambia el resultado: no reordenar. */
    public static final int[][] N4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    public static final int[][] N8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static Mask opaque(RgbaImage im) {
        Mask out = new Mask();
        for (int y = 0; y < im.height; y++) {
            for (int x = 0; x < im.width; x++) {
                if (im.alpha(x, y) > 0) {
                    out.add(new Point(x, y));
                }
            }
        }
        return out;
    }

    public static List<Point> nbrs(Point p) {
        return nbrs(p, N4);
    }

    public static List<Point> nbrs(Point p, int[][] offsets) {
        List<Point> out = new ArrayList<>(offsets.length);
        for (int[] d : offsets) {
            out.add(p.plus(d[0], d[1]));
        }
        return out;
    }

    public static Mask erode(Mask mask) {
        Mask out = new Mask();
        for (Point p : mask) {
            if (allIn(nbrs(p), mask)) {
                out.add(p);
            }
        }
        return out;
    }

    public static Mask dilate(Mask mask) {
        Mask out = new Mask(mask);
        for (Point p : mask) {
            for (Point n : nbrs(p)) {
                out.add(n);
            }
        }
        return out;
    }

    public static Mask opening(Mask mask, int k) {
        Mask body = mask;
        for (int i = 0; i < k; i++) {
            body = erode(body);
        }
        for (int i = 0; i < k; i++) {
            body = dilate(body);
        }
        return body.and(mask);
    }

    public static List<Mask> components(Mask mask) {
        return components(mask, N8);
    }

    /** Componentes conexas, en orden de su primer píxel (y, x); si el orden importa, pasar por {@link #bySize}. */
    public static List<Mask> components(Mask mask, int[][] offsets) {
        return flood(mask, p -> nbrs(p, offsets));
    }

    /**
     * Lo que tiene al menos {@code size} de grosor en cualquier dirección. En tiempo lineal
     * con sumas acumuladas: revisar size * size píxeles por píxel congelaba el juego en HD.
     */
    public static Mask thickPart(Mask mask, int size) {
        Mask out = new Mask();
        if (mask.isEmpty() || size <= 0) {
            return out;   // size <= 0: el cuadrado está vacío y no agrega nada
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Point p : mask) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        long w = (long) maxX - minX + 1;
        long h = (long) maxY - minY + 1;
        if (size > w || size > h) {
            return out;
        }
        // Máscara muy dispersa (píxeles lejanos entre sí): las tablas serían enormes y casi vacías.
        if (w * h > 16L * mask.size() + 4096) {
            return thickPartSparse(mask, size);
        }
        int cw = (int) w + 1;
        int ch = (int) h + 1;
        // sat[y * cw + x] = píxeles de la máscara en [0, x) x [0, y) de la caja envolvente
        int[] sat = new int[cw * ch];
        for (Point p : mask) {
            sat[(p.y() - minY + 1) * cw + (p.x() - minX + 1)] = 1;
        }
        for (int y = 1; y < ch; y++) {
            for (int x = 1; x < cw; x++) {
                sat[y * cw + x] += sat[(y - 1) * cw + x] + sat[y * cw + x - 1] - sat[(y - 1) * cw + x - 1];
            }
        }
        int full = size * size;
        int[] diff = new int[cw * ch];
        for (int y = 0; y + size < ch; y++) {
            for (int x = 0; x + size < cw; x++) {
                int count = sat[(y + size) * cw + x + size] - sat[y * cw + x + size]
                    - sat[(y + size) * cw + x] + sat[y * cw + x];
                if (count == full) {
                    diff[y * cw + x]++;
                    diff[y * cw + x + size]--;
                    diff[(y + size) * cw + x]--;
                    diff[(y + size) * cw + x + size]++;
                }
            }
        }
        for (int y = 0; y < ch - 1; y++) {
            for (int x = 0; x < cw - 1; x++) {
                int i = y * cw + x;
                if (x > 0) {
                    diff[i] += diff[i - 1];
                }
                if (y > 0) {
                    diff[i] += diff[i - cw];
                }
                if (x > 0 && y > 0) {
                    diff[i] -= diff[i - cw - 1];
                }
                if (diff[i] > 0) {
                    out.add(new Point(x + minX, y + minY));
                }
            }
        }
        return out;
    }

    /** {@code thick_part} literal, solo para máscaras dispersas (pocos píxeles en una caja enorme). */
    private static Mask thickPartSparse(Mask mask, int size) {
        Mask out = new Mask();
        for (Point p : mask) {
            boolean all = true;
            for (int i = 0; i < size && all; i++) {
                for (int j = 0; j < size && all; j++) {
                    all = mask.contains(p.x() + i, p.y() + j);
                }
            }
            if (all) {
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        out.add(p.plus(i, j));
                    }
                }
            }
        }
        return out;
    }

    /** Piezas como las ve el ojo: une en diagonal solo si esa diagonal ya existía en {@code orig}. */
    public static List<Mask> visualComponents(Mask pixels, Mask orig) {
        // con coordenadas sueltas: se llama decenas de veces por textura
        Mask left = new Mask(pixels);
        List<Mask> comps = new ArrayList<>();
        int[] stack = new int[64];
        while (!left.isEmpty()) {
            Point start = left.first();
            left.remove(start);
            Mask comp = Mask.emptyLike(pixels);
            comp.add(start);
            int top = 0;
            stack[top++] = start.x();
            stack[top++] = start.y();
            while (top > 0) {
                int y = stack[--top];
                int x = stack[--top];
                for (int[] d : N4) {
                    if (left.remove(x + d[0], y + d[1])) {
                        comp.add(x + d[0], y + d[1]);
                        if (top + 2 > stack.length) {
                            stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        }
                        stack[top++] = x + d[0];
                        stack[top++] = y + d[1];
                    }
                }
                for (int[] d : DIAGONALS) {
                    if (!orig.contains(x + d[0], y) && !orig.contains(x, y + d[1]) && left.remove(x + d[0], y + d[1])) {
                        comp.add(x + d[0], y + d[1]);
                        if (top + 2 > stack.length) {
                            stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        }
                        stack[top++] = x + d[0];
                        stack[top++] = y + d[1];
                    }
                }
            }
            comps.add(comp);
        }
        return comps;
    }

    /** De mayor a menor; en empate, la del píxel más arriba y a la izquierda. */
    public static List<Mask> bySize(List<Mask> comps) {
        List<Mask> out = new ArrayList<>(comps);
        out.sort(Comparator.comparingInt((Mask c) -> -c.size()).thenComparing(Mask::first));
        return out;
    }

    /** Profundidad de cada píxel: 1 toca el exterior, 2 es el anillo de adentro... */
    public static Map<Point, Integer> depthMap(Mask region) {
        Map<Point, Integer> depth = new HashMap<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        for (Point p : outerBorder(region)) {
            depth.put(p, 1);
            queue.add(p);
        }
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (Point n : nbrs(p)) {
                if (region.contains(n) && !depth.containsKey(n)) {
                    depth.put(n, depth.get(p) + 1);
                    queue.add(n);
                }
            }
        }
        return depth;
    }

    public static Mask outerBorder(Mask mask) {
        Mask out = new Mask();
        for (Point p : mask) {
            if (!allIn(nbrs(p), mask)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean allIn(List<Point> ps, Mask mask) {
        for (Point n : ps) {
            if (!mask.contains(n)) {
                return false;
            }
        }
        return true;
    }

    private static List<Mask> flood(Mask mask, java.util.function.Function<Point, List<Point>> links) {
        Mask left = new Mask(mask);
        List<Mask> comps = new ArrayList<>();
        while (!left.isEmpty()) {
            Point start = left.first();
            left.remove(start);
            Mask comp = Mask.of(start);
            ArrayDeque<Point> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                for (Point n : links.apply(stack.pop())) {
                    if (left.remove(n)) {
                        comp.add(n);
                        stack.push(n);
                    }
                }
            }
            comps.add(comp);
        }
        return comps;
    }

    /** Píxeles transparentes sin camino 4-conexo al borde de la imagen. */
    public static Mask enclosed(Mask mask, int w, int h) {
        boolean[] outside = new boolean[w * h];
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !mask.contains(x, y)) {
                    outside[y * w + x] = true;
                    stack.push(new int[] {x, y});
                }
            }
        }
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            for (int[] d : N4) {
                int x = p[0] + d[0];
                int y = p[1] + d[1];
                if (x >= 0 && y >= 0 && x < w && y < h && !outside[y * w + x] && !mask.contains(x, y)) {
                    outside[y * w + x] = true;
                    stack.push(new int[] {x, y});
                }
            }
        }
        Mask out = new Mask();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!outside[y * w + x] && !mask.contains(x, y)) {
                    out.add(new Point(x, y));
                }
            }
        }
        return out;
    }
}
