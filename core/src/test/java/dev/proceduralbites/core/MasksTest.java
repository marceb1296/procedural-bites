package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MasksTest {

    /** A 256x el grosor mínimo es 48: la versión literal revisa 48 * 48 píxeles por píxel y congela el juego. */
    @Test
    void thickPartScalesToHdTextures() {
        Mask full = new Mask();
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                full.add(new Point(x, y));
            }
        }
        Mask thick = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> Masks.thickPart(full, 48));
        assertEquals(full, thick);
    }

    /** La versión rápida da lo mismo que la literal, también con coordenadas negativas. */
    @Test
    void thickPartMatchesLiteralVersion() {
        Random rnd = new Random(1234);
        for (int trial = 0; trial < 400; trial++) {
            Mask m = new Mask();
            int w = 1 + rnd.nextInt(20);
            int h = 1 + rnd.nextInt(20);
            int ox = rnd.nextInt(10) - 5;
            int oy = rnd.nextInt(10) - 5;
            double density = rnd.nextDouble();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (rnd.nextDouble() < density) {
                        m.add(new Point(x + ox, y + oy));
                    }
                }
            }
            if (trial % 50 == 0) {
                m.add(new Point(ox + 500, oy - 300));   // punto lejano: caja envolvente dispersa
            }
            int size = rnd.nextInt(7) - 1;   // incluye 0 y -1: da vacío
            assertEquals(literalThickPart(m, size), Masks.thickPart(m, size), "prueba " + trial + ", size " + size);
        }
    }

    /** La versión literal. */
    private static Mask literalThickPart(Mask mask, int size) {
        Mask out = new Mask();
        for (Point p : mask) {
            boolean all = true;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    all &= mask.contains(p.x() + i, p.y() + j);
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

    @Test
    void enclosedIsFourConnectedLikePython() {
        // (1, 1) solo toca el exterior en diagonal, por (0, 0): está encerrado
        Mask m = new Mask();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                if (!(x == 1 && y == 1) && !(x == 0 && y == 0)) {
                    m.add(new Point(x, y));
                }
            }
        }
        Mask holes = Masks.enclosed(m, 3, 3);
        assertEquals(1, holes.size());
        assertTrue(holes.contains(1, 1));
        // un anillo de 5x5 encierra 9 píxeles
        Mask ring = new Mask();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                if (x == 0 || x == 4 || y == 0 || y == 4) {
                    ring.add(new Point(x, y));
                }
            }
        }
        assertEquals(9, Masks.enclosed(ring, 5, 5).size());
    }
}
