package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bebidas con texturas raras o enormes: lanzan en vez de dar basura, y una HD no congela el juego. */
class DrinksHostileTest {

    /** Botella de vidrio con ruido de color (miles de colores distintos) y corcho, de lado {@code n}. */
    static RgbaImage bottle(int n, boolean filled, long seed, double widthFactor) {
        Random rnd = new Random(seed);
        RgbaImage im = new RgbaImage(n, n);
        int k = Math.max(1, n / 16);
        int x0 = (int) (n * (0.5 - 0.25 * widthFactor));
        int x1 = n - 1 - x0;
        for (int y = 2 * k; y < n - k; y++) {
            boolean neck = y < 5 * k;
            int a = neck ? n / 2 - 2 * k : x0;
            int b = neck ? n / 2 + 2 * k - 1 : x1;
            for (int x = a; x <= b; x++) {
                boolean wall = x < a + k || x > b - k || y >= n - 2 * k;
                if (y < 3 * k) {
                    im.set(x, y, 130 + rnd.nextInt(20), 85 + rnd.nextInt(15), 45 + rnd.nextInt(10), 255);   // corcho
                } else if (wall) {
                    im.set(x, y, 150 + rnd.nextInt(60), 190 + rnd.nextInt(40), 210 + rnd.nextInt(40), 255);  // vidrio
                } else if (filled) {
                    im.set(x, y, 200 + rnd.nextInt(40), 60 + rnd.nextInt(60), 20 + rnd.nextInt(30), 255);    // líquido
                }
            }
        }
        return im;
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 256, 512})
    void noisyHdBottleIsFast(int n) {
        // otra forma que el vacío: contenido por paleta, con miles de colores
        RgbaImage full = bottle(n, true, 1, 1.0);
        RgbaImage empty = bottle(n, false, 2, 0.8);
        List<RgbaImage> frames = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Mask liquid = Drinks.findContents(full, empty);
            assertTrue(liquid.size() > n * n / 8, "contenido: " + liquid.size());
            return Drinks.liquidFrames(full, liquid, empty);
        });
        assertEquals(Params.NUM_FRAMES, frames.size());
    }

    @Test
    void transparentEmptyContainerThrows() {
        // el recipiente vacío no tiene píxeles
        RgbaImage full = bottle(16, true, 3, 1.0);
        RgbaImage empty = new RgbaImage(20, 20);
        assertThrows(ArithmeticException.class, () -> Drinks.findContents(full, empty));
    }

    @Test
    void transparentItemHasNoContents() {
        // sin contenido: el mod deja el ítem sin animar
        assertTrue(Drinks.findContents(new RgbaImage(16, 16), bottle(16, false, 4, 1.0)).isEmpty());
    }

    @Test
    void emptyLiquidThrows() {
        RgbaImage full = bottle(16, true, 5, 1.0);
        assertThrows(ArithmeticException.class, () -> Drinks.liquidFrames(full, new Mask(), bottle(16, false, 6, 1.0)));
        assertThrows(ArithmeticException.class, () -> Drinks.liquidFrames(full, new Mask(), null));
    }
}
