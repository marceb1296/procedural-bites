package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.proceduralbites.core.Params.BitePattern;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Texturas vacías, de un píxel o enormes: lanzan en vez de dar fotogramas basura, y una HD no congela el juego. */
class SolidsHostileTest {

    @Test
    void emptyTextureThrows() {
        // área 0
        assertThrows(ArithmeticException.class, () -> Solids.frames(new RgbaImage(16, 16), "x", BitePattern.AUTO));
    }

    @Test
    void singlePixelThrows() {
        // no queda nada
        RgbaImage im = new RgbaImage(16, 16);
        im.set(5, 5, 200, 0, 0, 255);
        assertThrows(ArithmeticException.class, () -> Solids.frames(im, "x", BitePattern.AUTO));
        RgbaImage one = new RgbaImage(1, 1);
        one.set(0, 0, 200, 0, 0, 255);
        assertThrows(ArithmeticException.class, () -> Solids.frames(one, "x", BitePattern.AUTO));
    }

    /** Disco con ruido de color: el peor caso habitual de un pack HD. */
    static RgbaImage disc(int n) {
        RgbaImage im = new RgbaImage(n, n);
        double c = n / 2.0;
        double r = n * 0.43;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if ((x - c) * (x - c) + (y - c) * (y - c) <= r * r) {
                    im.set(x, y, 200, (x * 7) % 255, (y * 3) % 255, 255);
                }
            }
        }
        return im;
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 256})
    void hdTextureIsFast(int size) {
        RgbaImage im = disc(size);
        List<RgbaImage> frames = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> Solids.frames(im, "disc" + size, BitePattern.AUTO));
        assertEquals(Params.NUM_FRAMES, frames.size());
    }
}
