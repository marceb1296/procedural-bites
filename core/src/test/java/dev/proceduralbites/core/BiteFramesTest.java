package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.Params.BitePattern;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Una textura enorme no puede congelar al cliente: se omite sin generar. */
class BiteFramesTest {

    @ParameterizedTest
    @ValueSource(ints = {257, 512})
    void oversizedTextureIsSkippedWithoutGenerating(int size) {
        RgbaImage im = SolidsHostileTest.disc(size);
        BiteFrames.Result r = assertTimeoutPreemptively(Duration.ofMillis(500), () -> BiteFrames.solid(im, "disc"));
        assertFalse(r.generated());
        assertTrue(r.frames().isEmpty());
        assertTrue(r.skipped().contains(size + "x" + size), r.skipped());
    }

    @Test
    void wideTextureIsSkipped() {
        // más ancha que alta: no es una tira animada, firstFrame no la recorta
        RgbaImage im = new RgbaImage(512, 256);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 512; x++) {
                double u = (x - 256) / 220.0, v = (y - 128) / 110.0;
                if (u * u + v * v <= 1) {
                    im.set(x, y, 200, (x * 7) % 255, (y * 3) % 255, 255);
                }
            }
        }
        BiteFrames.Result r = assertTimeoutPreemptively(Duration.ofMillis(500), () -> BiteFrames.solid(im, "oval"));
        assertFalse(r.generated());
        assertTrue(r.skipped().contains("512x256"), r.skipped());
    }

    @Test
    void capIsInclusive() {
        // 256x es el tope: se genera, y da lo mismo que llamar al algoritmo directo
        RgbaImage im = SolidsHostileTest.disc(256);
        BiteFrames.Result r = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> BiteFrames.solid(im, "disc"));
        assertTrue(r.generated(), r.skipped());
        List<RgbaImage> direct = Solids.frames(im, "disc", BitePattern.AUTO);
        assertEquals(direct.size(), r.frames().size());
        for (int i = 0; i < direct.size(); i++) {
            assertEquals(1, r.frames().get(i).size(), "capas");
            assertTrue(Arrays.equals(direct.get(i).rgba(), r.frames().get(i).get(0).rgba()), "frame " + (i + 1));
        }
    }

    @Test
    void animatedStripUsesFirstFrame() {
        // una tira 16x640 (40 fotogramas) se recorta a 16x16 antes del tope
        RgbaImage strip = new RgbaImage(16, 16 * 40);
        RgbaImage disc = SolidsHostileTest.disc(16);
        System.arraycopy(disc.rgba(), 0, strip.rgba(), 0, disc.rgba().length);
        BiteFrames.Result r = BiteFrames.solid(strip, "disc");
        assertTrue(r.generated(), r.skipped());
        assertEquals(16, r.frames().get(0).get(0).height);
    }

    @Test
    void failureIsReportedNotThrown() {
        // textura vacía: Solids lanza ArithmeticException; el mod debe dejar el original
        BiteFrames.Result r = BiteFrames.solid(new RgbaImage(16, 16), "x");
        assertFalse(r.generated());
        assertTrue(r.skipped().contains("ArithmeticException"), r.skipped());
    }
}
