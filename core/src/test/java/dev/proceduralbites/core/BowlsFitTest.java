package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** La poda de {@link Bowls#fitContainer} elige el mismo desplazamiento que la búsqueda completa, también en empates. */
class BowlsFitTest {

    @Test
    void matchesLiteral() {
        Random rnd = new Random(11);
        int[][] palette = {{150, 100, 60}, {60, 40, 25}, {200, 190, 180}};
        int fits = 0;
        for (int trial = 0; trial < 400; trial++) {
            int n = 16 * (1 + rnd.nextInt(2));
            RgbaImage empty = new RgbaImage(n, n);
            RgbaImage full = new RgbaImage(n, n);
            int shift = rnd.nextInt(3);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int band = Math.abs(x - n / 2) + Math.abs(y - n / 2);
                    if (band < n / 3 && rnd.nextInt(10) > 0) {
                        int[] c = palette[(band + (trial % 2 == 0 ? 0 : x)) % palette.length];
                        empty.set(x, y, c[0], c[1], c[2], 255);
                        int fx = x + shift;
                        if (fx < n && rnd.nextInt(8) > 0) {
                            full.set(fx, y, c[0], c[1], c[2], 255);
                        }
                    }
                }
            }
            Mask contents = new Mask();
            for (Point p : Masks.opaque(full)) {
                if (rnd.nextInt(6) == 0) {
                    contents.add(p);
                }
            }
            RgbaImage want = literal(full, contents, empty);
            RgbaImage got = Bowls.fitContainer(full, contents, empty);
            String where = "prueba " + trial;
            assertEquals(want == null, got == null, where);
            if (want != null) {
                fits++;
                assertArrayEquals(want.rgba(), got.rgba(), where);
            }
        }
        assertEquals(true, fits > 100, "pocos casos calzaron: " + fits);
    }

    /**
     * Empates seguros: un rectángulo de un color dentro de otro más grande calza igual
     * en muchos desplazamientos; gana el primero en (dy, dx). Y escalones: cada fila
     * de más que calza suma 1, así que el mejor supera al anterior por 1 exacto.
     */
    @Test
    void tiesAndStepsMatchLiteral() {
        int fits = 0;
        for (int trial = 0; trial < 60; trial++) {
            int n = 16;
            RgbaImage empty = new RgbaImage(n, n);
            RgbaImage full = new RgbaImage(n, n);
            int ew = 6 + trial % 5;
            int fw = 2 + trial % 3;
            for (int y = 3; y < 13; y++) {
                for (int x = 8 - ew / 2; x < 8 + ew / 2; x++) {
                    empty.set(x, y, 150, 100, 60, 255);
                }
                int len = trial % 2 == 0 ? fw : 1 + (y - 3) % (fw + 2);   // escalones de a 1
                for (int x = 8 - len / 2 + trial % 3 - 1; x < 8 - len / 2 + trial % 3 - 1 + len; x++) {
                    full.set(x, y, 150, 100, 60, 255);
                }
            }
            RgbaImage want = literal(full, new Mask(), empty);
            RgbaImage got = Bowls.fitContainer(full, new Mask(), empty);
            String where = "prueba " + trial;
            assertEquals(want == null, got == null, where);
            if (want != null) {
                fits++;
                assertArrayEquals(want.rgba(), got.rgba(), where);
            }
        }
        assertEquals(true, fits >= 40, "casos que calzan: " + fits);
    }

    /**
     * El mejor desplazamiento supera al anterior por 1 exacto, con todos los píxeles
     * coincidiendo: la poda no puede cortarlo. El vacío tiene una copia de la columna
     * a la que le falta un píxel (dx = -1, antes en el orden) y una completa (dx = +2).
     */
    @Test
    void betterByOneIsNotPruned() {
        RgbaImage full = new RgbaImage(16, 16);
        RgbaImage empty = new RgbaImage(16, 16);
        for (int y = 4; y < 9; y++) {
            int g = 40 * (y - 4);   // colores distintos por fila: solo calza con dy = 0
            full.set(8, y, 150, g, 60, 255);
            empty.set(6, y, 150, g, 60, 255);        // full en x - 2: dx = +2, calzan 5
            if (y != 6) {
                empty.set(9, y, 150, g, 60, 255);    // dx = -1: calzan 4
            }
        }
        RgbaImage want = literal(full, new Mask(), empty);
        RgbaImage got = Bowls.fitContainer(full, new Mask(), empty);
        assertEquals(true, want != null, "el caso debe calzar");
        assertEquals(255, want.get(8, 4)[3], "la versión literal elige dx = +2");
        assertArrayEquals(want.rgba(), got.rgba());
    }

    /** La búsqueda completa, sin poda. */
    private static RgbaImage literal(RgbaImage full, Mask contents, RgbaImage empty) {
        int k = full.pxScale();
        Mask shell = Masks.opaque(full).minus(contents);
        Mask emask = Masks.opaque(empty);
        int best = -1;
        int bdx = 0;
        int bdy = 0;
        for (int dy = -3 * k; dy <= 3 * k; dy++) {
            for (int dx = -3 * k; dx <= 3 * k; dx++) {
                int m = 0;
                for (Point p : shell) {
                    if (emask.contains(p.x() - dx, p.y() - dy)
                            && Colors.colorDist2(full.get(p), empty.get(p.x() - dx, p.y() - dy)) <= 576) {
                        m++;
                    }
                }
                if (m > best) {
                    best = m;
                    bdx = dx;
                    bdy = dy;
                }
            }
        }
        if (shell.isEmpty() || best < 0.7 * shell.size() || best < 0.3 * emask.size()) {
            return null;
        }
        RgbaImage moved = new RgbaImage(full.width, full.height);
        for (Point p : emask) {
            if (moved.inBounds(p.x() + bdx, p.y() + bdy)) {
                moved.set(new Point(p.x() + bdx, p.y() + bdy), empty.get(p));
            }
        }
        return moved;
    }
}
