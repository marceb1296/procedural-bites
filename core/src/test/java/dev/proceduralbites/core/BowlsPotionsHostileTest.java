package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tazones y pociones con texturas raras o enormes: lanzan en vez de dar infinitos o NaN, y una HD no
 * congela el juego.
 */
class BowlsPotionsHostileTest {

    /** Tazón de lado {@code n} con ruido, desplazado {@code shift} px, y comida amontonada si {@code food}. */
    static RgbaImage bowl(int n, int shift, boolean food, long seed) {
        Random rnd = new Random(seed);
        RgbaImage im = new RgbaImage(n, n);
        double cx = n / 2.0 + shift;
        double cy = n * 0.55 + shift;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double u = (x - cx) / (n * 0.44);
                double v = (y - cy) / (n * 0.3);
                double fu = (x - cx) / (n * 0.35);
                double fv = (y - (cy - n * 0.12)) / (n * 0.18);
                if (food && fu * fu + fv * fv <= 1) {
                    im.set(x, y, 90 + rnd.nextInt(40), 150 + rnd.nextInt(40), 50 + rnd.nextInt(30), 255);
                } else if (u * u + v * v <= 1 && y >= cy - n * 0.1) {
                    im.set(x, y, 150 + rnd.nextInt(8), 100 + rnd.nextInt(8), 60 + rnd.nextInt(8), 255);
                }
            }
        }
        return im;
    }

    static Mask foodMask(RgbaImage im) {
        Mask m = new Mask();
        for (Point p : Masks.opaque(im)) {
            if (im.get(p)[1] >= 150) {
                m.add(p);
            }
        }
        return m;
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 256})   // 512x tarda ~5 s: queda fuera por el tope de tamaño
    void hdBowlIsFast(int n) {
        int k = n / 16;
        RgbaImage full = bowl(n, k, true, 1);
        RgbaImage empty = bowl(n, 0, false, 2);
        Mask food = foodMask(full);
        List<RgbaImage> frames = assertTimeoutPreemptively(Duration.ofSeconds(3),
            () -> Bowls.bowlFrames(full, food, empty));
        assertEquals(Params.NUM_FRAMES, frames.size());
        // vacío de otro tamaño: el tazón se sintetiza
        RgbaImage other = bowl(n / 2, 0, false, 3);
        assertEquals(Params.NUM_FRAMES,
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> Bowls.bowlFrames(full, food, other)).size());
    }

    @Test
    void narrowBowlThrows() {
        // ancho 2 a 16x: la elipse tiene a = 0
        RgbaImage full = new RgbaImage(16, 16);
        Mask contents = new Mask();
        for (int y = 2; y < 14; y++) {
            for (int x = 7; x <= 8; x++) {
                full.set(x, y, y < 5 ? 90 : 150, y < 5 ? 160 : 100, 60, 255);
                if (y < 5) {
                    contents.add(new Point(x, y));
                }
            }
        }
        assertThrows(ArithmeticException.class, () -> Bowls.bowlFrames(full, contents, new RgbaImage(20, 20)));
    }

    @Test
    void bowlWithoutFoodStaysWhole() {
        // tazón sintetizado sin comida: la boca queda vacía y los fotogramas son el original
        RgbaImage full = bowl(16, 0, false, 4);
        List<RgbaImage> frames = Bowls.bowlFrames(full, new Mask(), new RgbaImage(20, 20));
        assertEquals(3, frames.size());
        for (RgbaImage f : frames) {
            assertArrayEquals(full.rgba(), f.rgba());
        }
    }

    @Test
    void potionEdgeCases() {
        RgbaImage bottle = new RgbaImage(16, 16);
        for (int y = 3; y < 14; y++) {
            bottle.set(4, y, 178, 208, 226, 150);
            bottle.set(11, y, 178, 208, 226, 150);
        }
        RgbaImage overlay = new RgbaImage(16, 16);
        for (int y = 6; y < 13; y++) {
            for (int x = 5; x <= 10; x++) {
                overlay.set(x, y, 230, 230, 230, 255);
            }
        }
        int[] color = {200, 40, 40};
        assertEquals(Params.NUM_FRAMES, Potions.potionFrames(bottle, overlay, color).frames().size());
        // overlay vacío
        assertThrows(ArithmeticException.class, () -> Potions.potionFrames(bottle, new RgbaImage(16, 16), color));
        // overlay de otro tamaño que la botella
        RgbaImage big = new RgbaImage(32, 32);
        big.set(10, 10, 230, 230, 230, 255);
        assertThrows(IllegalArgumentException.class, () -> Potions.potionFrames(bottle, big, color));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void hdPotionIsFast(int n) {
        RgbaImage bottle = new RgbaImage(n, n);
        RgbaImage overlay = new RgbaImage(n, n);
        for (int y = n / 8; y < n - n / 8; y++) {
            for (int x = n / 4; x < n - n / 4; x++) {
                boolean wall = x < n / 4 + n / 16 || x >= n - n / 4 - n / 16;
                if (wall) {
                    bottle.set(x, y, 178, 208, 226, 150);
                } else if (y > n / 3) {
                    overlay.set(x, y, 200 + (x * y) % 50, 200, 200, 255);
                }
            }
        }
        assertEquals(Params.NUM_FRAMES, assertTimeoutPreemptively(Duration.ofSeconds(3),
            () -> Potions.potionFrames(bottle, overlay, new int[] {50, 100, 200})).frames().size());
    }
}
