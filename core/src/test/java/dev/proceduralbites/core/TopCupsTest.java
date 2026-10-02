package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tazas vistas desde arriba (pista top_cup): lo que no cubren los datos de referencia. */
class TopCupsTest {

    private static RgbaImage load(String name) throws IOException {
        try {
            return FramesTest.read(Paths.get(TopCupsTest.class.getResource("/frames/" + name + ".bin").toURI()))
                    .images().get(0);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static int[] wallOf(RgbaImage full, RgbaImage frame, Mask surface) {
        for (Point p : surface) {
            if (!java.util.Arrays.equals(full.get(p), frame.get(p))) {
                return frame.get(p);
            }
        }
        throw new AssertionError("el nivel no bajó");
    }

    /** La pared interior es el promedio de lo que no es superficie, oscurecido 0,45 y opaco. */
    @Test
    void wallIsTheShadedAverageOfTheVessel() throws IOException {
        RgbaImage full = load("pb_hint_yogurt");
        Mask surface = TopCups.surface(full);
        long[] sum = new long[3];
        int n = 0;
        for (Point p : Masks.opaque(full).minus(surface)) {
            for (int i = 0; i < 3; i++) {
                sum[i] += full.get(p)[i];
            }
            n++;
        }
        int[] want = new int[3];
        for (int i = 0; i < 3; i++) {
            double avg = PyMath.round((double) sum[i] / n);
            want[i] = PyMath.round(avg + (0 - avg) * Params.TOP_CUP_SHADE);
        }
        int[] wall = wallOf(full, TopCups.frames(full, surface).get(0), surface);
        assertEquals(List.of(want[0], want[1], want[2], 255), List.of(wall[0], wall[1], wall[2], wall[3]));
    }

    /** El borde de arriba baja con su forma: en cada columna se destapan las {@code d} filas de arriba. */
    @Test
    void topEdgeKeepsItsShape() throws IOException {
        RgbaImage full = load("pb_hint_yogurt");
        Mask surface = TopCups.surface(full);
        List<RgbaImage> frames = TopCups.frames(full, surface);
        int[] drops = {2, 3, 4};
        for (int f = 0; f < 3; f++) {
            for (Point p : surface) {
                int above = 0;
                while (surface.contains(p.x(), p.y() - above - 1)) {
                    above++;
                }
                boolean kept = java.util.Arrays.equals(full.get(p), frames.get(f).get(p));
                assertEquals(above >= drops[f], kept, "frame " + (f + 1) + " " + p);
            }
        }
    }

    @Test
    void entryPointSkipsWhatItCannotDo() throws IOException {
        // sin superficie: el loader sigue el camino de siempre
        BiteFrames.Result none = BiteFrames.topCup(load("pb_hint_rim3"));
        assertFalse(none.generated());
        assertEquals(BiteFrames.NO_SURFACE, none.skipped());
        assertEquals(BiteFrames.Kind.CUP, none.kind());
        // vacía y de un solo color: nunca lanza
        assertEquals(BiteFrames.NO_SURFACE, BiteFrames.topCup(new RgbaImage(16, 16)).skipped());
        RgbaImage flat = new RgbaImage(16, 16);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                flat.set(x, y, 90, 60, 30, 255);
            }
        }
        assertEquals(BiteFrames.NO_SURFACE, BiteFrames.topCup(flat).skipped());
        // más grande que el tope: se omite sin buscar
        RgbaImage big = new RgbaImage(257, 257);
        BiteFrames.Result r = assertTimeoutPreemptively(Duration.ofMillis(500), () -> BiteFrames.topCup(big));
        assertTrue(r.skipped().contains("257x257"), r.skipped());
        // tira animada: usa el primer fotograma
        RgbaImage one = load("pb_hint_mug");
        RgbaImage strip = new RgbaImage(16, 32);
        for (Point p : Masks.opaque(one)) {
            strip.set(p, one.get(p));
            strip.set(p.x(), p.y() + 16, 255, 0, 0, 255);
        }
        BiteFrames.Result animated = BiteFrames.topCup(strip);
        assertTrue(animated.generated(), animated.skipped());
        assertEquals(16, animated.frames().get(0).get(0).height);
        assertTrue(java.util.Arrays.equals(BiteFrames.topCup(one).frames().get(2).get(0).rgba(),
                animated.frames().get(2).get(0).rgba()));
    }

    /** Con cualquier textura: no lanza, y si genera cumple las reglas de las tazas. */
    @Test
    void neverThrowsAndKeepsTheRules() {
        Random rnd = new Random(5);
        int generated = 0;
        for (int i = 0; i < 400; i++) {
            int size = i % 7 == 0 ? 32 : 16;
            RgbaImage im = new RgbaImage(size, size);
            int colors = 2 + rnd.nextInt(5);
            int[][] palette = new int[colors][];
            for (int c = 0; c < colors; c++) {
                palette[c] = new int[] {rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)};
            }
            // anillos concéntricos con ruido: se parecen a un envase con algo adentro
            double cx = size / 2.0 + rnd.nextInt(3) - 1;
            double cy = size / 2.0 + rnd.nextInt(3) - 1;
            double r = size * (0.3 + rnd.nextDouble() * 0.18);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double d = Math.hypot(x - cx, (y - cy) * (0.7 + rnd.nextDouble() * 0.1));
                    if (d <= r) {
                        int ring = Math.min(colors - 1, (int) ((r - d) / (size / 16.0)));
                        int[] c = palette[y > cy + 1 ? Math.min(ring, 1) : rnd.nextInt(12) == 0 ? rnd.nextInt(colors) : ring];
                        im.set(x, y, c[0], c[1], c[2], 255);
                    }
                }
            }
            BiteFrames.Result result = BiteFrames.topCup(im);
            if (result.generated()) {
                generated++;
                assertEquals(3, result.frames().size());
                assertEquals(List.of(), QualityRulesTest.topCup(im), "textura al azar " + i);
            } else {
                assertEquals(BiteFrames.NO_SURFACE, result.skipped(), "textura al azar " + i);
                assertNull(TopCups.surface(im));
            }
        }
        assertTrue(generated >= 40 && generated <= 360, "generadas " + generated + " de 400");
    }
}
