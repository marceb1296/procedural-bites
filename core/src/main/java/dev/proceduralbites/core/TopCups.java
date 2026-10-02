package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.TOP_CUP_PEELS;
import static dev.proceduralbites.core.Params.TOP_CUP_SHADE;
import static dev.proceduralbites.core.Params.TOP_CUP_WIDTH;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Taza, vasito o balde visto desde arriba (pista top_cup): la textura sola no lo distingue de un pastel. */
public final class TopCups {
    private TopCups() {
    }

    /** Ancho de la fila más ancha, del primer al último píxel. */
    static int rowWidth(Mask pixels) {
        int best = 0;
        for (int[] run : rows(pixels).values()) {
            best = Math.max(best, run[1] - run[0] + 1);
        }
        return best;
    }

    private static Map<Integer, int[]> rows(Mask pixels) {
        Map<Integer, int[]> rows = new TreeMap<>();
        for (Point p : pixels) {
            int[] run = rows.get(p.y());
            if (run == null) {
                rows.put(p.y(), new int[] {p.x(), p.x()});
            } else {
                run[0] = Math.min(run[0], p.x());
                run[1] = Math.max(run[1], p.x());
            }
        }
        return rows;
    }

    private static int rgb(int[] c) {
        return c[0] << 16 | c[1] << 8 | c[2];
    }

    /**
     * Superficie del contenido, o {@code null}. Se quitan los colores del contorno (el borde
     * del envase) y la candidata es la pieza ancha más arriba, con al menos tantas filas de
     * envase debajo como mide; una gota que chorrea por delante no cuenta.
     */
    public static Mask surface(RgbaImage full) {
        Mask mask = Masks.opaque(full);
        if (mask.isEmpty()) {
            return null;
        }
        int width = rowWidth(mask);
        int bottom = Integer.MIN_VALUE;
        for (Point p : mask) {
            bottom = Math.max(bottom, p.y());
        }
        Set<Integer> removed = new HashSet<>();
        for (Point p : Masks.outerBorder(mask)) {
            removed.add(rgb(full.get(p)));
        }
        for (int peel = 0; peel <= TOP_CUP_PEELS; peel++) {
            Mask left = new Mask();
            for (Point p : mask) {
                if (!removed.contains(rgb(full.get(p)))) {
                    left.add(p);
                }
            }
            Mask piece = null;
            int pieceTop = 0;
            for (Mask c : Masks.components(left, Masks.N4)) {
                if (rowWidth(c) < TOP_CUP_WIDTH * width) {
                    continue;
                }
                // la más arriba; a igual altura, la más grande y después la del primer píxel
                int top = c.first().y();
                if (piece == null || top < pieceTop || top == pieceTop && (c.size() > piece.size()
                        || c.size() == piece.size() && c.first().compareTo(piece.first()) < 0)) {
                    piece = c;
                    pieceTop = top;
                }
            }
            if (piece == null) {
                return null;
            }
            Map<Integer, int[]> rows = rows(piece);
            int low = pieceTop;
            for (int y : rows.keySet()) {
                low = Math.max(low, y);
            }
            if (bottom - low >= low - pieceTop + 1) {
                int widest = rowWidth(piece);
                Mask out = new Mask();
                int kept = 0;
                for (Map.Entry<Integer, int[]> row : rows.entrySet()) {
                    int[] run = row.getValue();
                    if (2 * (run[1] - run[0] + 1) < widest) {
                        continue;
                    }
                    kept++;
                    for (int x = run[0]; x <= run[1]; x++) {
                        if (mask.contains(x, row.getKey())) {
                            out.add(x, row.getKey());
                        }
                    }
                }
                return kept < 2 * full.pxScale() ? null : out;
            }
            for (Point p : piece) {
                for (Point n : Masks.nbrs(p)) {
                    if (!piece.contains(n)) {
                        removed.add(rgb(full.get(p)));
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * El nivel baja y deja a la vista la pared interior de atrás, oscurecida. En cada columna
     * se destapa desde su primer píxel de superficie: el nivel nunca sube aunque haya un hueco.
     */
    public static List<RgbaImage> frames(RgbaImage full, Mask surface) {
        int k = full.pxScale();
        Mask shell = Masks.opaque(full).minus(surface);
        long[] sum = new long[3];
        for (Point p : shell) {
            int[] c = full.get(p);
            for (int i = 0; i < 3; i++) {
                sum[i] += c[i];
            }
        }
        if (shell.isEmpty()) {
            throw new ArithmeticException("la superficie ocupa toda la silueta");
        }
        int[] avg = new int[3];
        for (int i = 0; i < 3; i++) {
            avg[i] = PyMath.round((double) sum[i] / shell.size());
        }
        int[] wall = Colors.lerp(avg, new int[] {0, 0, 0}, TOP_CUP_SHADE);
        int top = Integer.MAX_VALUE;
        int low = Integer.MIN_VALUE;
        for (Point p : surface) {
            top = Math.min(top, p.y());
            low = Math.max(low, p.y());
        }
        int n = low - top + 1;
        Map<Integer, Integer> tops = new HashMap<>();
        for (Point p : surface) {
            tops.merge(p.x(), p.y(), Math::min);
        }
        List<RgbaImage> frames = new ArrayList<>();
        for (double keep : Solids.schedule(LAST_LIQUID)) {
            int d = Math.max(k, Math.min(n - k, PyMath.round((1 - keep) * n)));
            RgbaImage out = full.copy();
            for (Point p : surface) {
                if (p.y() < tops.get(p.x()) + d) {
                    out.set(p.x(), p.y(), wall[0], wall[1], wall[2], 255);
                }
            }
            frames.add(out);
        }
        return frames;
    }
}
