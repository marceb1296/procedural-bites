package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link BiteFrames#container} y {@link BiteFrames#potion}: camino, fotogramas de referencia, tope de
 * 256 px y nunca lanzan.
 */
class BiteFramesContainersTest {
    private static final int POTION = 4;

    private static final int[][] COLORS = {
        {248, 36, 35}, {51, 235, 255}, {135, 163, 99}, {0, 0, 0}, {255, 255, 255}, {127, 128, 1}};

    @TestFactory
    Stream<DynamicTest> synthetic() throws IOException, URISyntaxException {
        return tests(FramesTest.binFiles(Paths.get(FramesTest.class.getResource("/frames").toURI())));
    }

    @TestFactory
    Stream<DynamicTest> local() throws IOException {
        String prop = System.getProperty("expectedDir", "");
        Assumptions.assumeTrue(!prop.isEmpty() && Files.isDirectory(Paths.get(prop, "drinks")),
                "sin datos locales (-PexpectedDir)");
        List<Path> files = new ArrayList<>(FramesTest.binFiles(Paths.get(prop, "drinks")));
        files.addAll(FramesTest.binFiles(Paths.get(prop, "potions")));
        return tests(files);
    }

    private static Stream<DynamicTest> tests(List<Path> files) throws IOException {
        List<DynamicTest> out = new ArrayList<>();
        for (Path f : files) {
            FramesTest.Expected e = FramesTest.read(f);
            if (e.kind() != POTION && e.empty() != null) {
                out.add(DynamicTest.dynamicTest("recipiente " + f.getFileName(), () -> checkContainer(e)));
            } else if (e.own()) {
                out.add(DynamicTest.dynamicTest("envase propio " + f.getFileName(), () -> checkOwn(e)));
            } else if (e.kind() == POTION) {
                out.add(DynamicTest.dynamicTest("poción " + f.getFileName(), () -> checkPotion(e)));
            }
        }
        assertFalse(out.isEmpty(), "sin recipientes ni pociones");
        return out.stream();
    }

    /** Mismo camino y mismos píxeles que los datos de referencia. */
    private static void checkContainer(FramesTest.Expected e) {
        Result r = BiteFrames.container(e.images().get(0), e.empty(), e.eaten());
        if (e.kind() == FramesTest.OPAQUE) {
            // botella opaca de otra forma: se omite con su motivo, también en tira
            assertFalse(r.generated(), e.name() + ": debía omitirse");
            assertEquals(Cups.OPAQUE, r.skipped(), e.name());
            assertTrue(r.frames().isEmpty(), e.name());
            assertEquals(Cups.OPAQUE,
                    BiteFrames.container(strip(e.images().get(0)), strip(e.empty()), e.eaten()).skipped(), e.name());
            return;
        }
        assertTrue(r.generated(), e.name() + ": " + r.skipped());
        assertEquals(FramesTest.kind(e.kind()), r.kind(), e.name());
        assertEquals(1, r.layers());
        List<RgbaImage> want = e.images().subList(1, e.images().size());
        assertEquals(want.size(), r.frames().size());
        for (int i = 0; i < want.size(); i++) {
            String diff = FramesTest.diff(want.get(i), r.frames().get(i).get(0));
            assertTrue(diff.isEmpty(), e.name() + " frame " + (i + 1) + ": " + diff);
        }
        // con texturas animadas se usa el primer fotograma de ambas
        Result strip = BiteFrames.container(strip(e.images().get(0)), strip(e.empty()), e.eaten());
        assertTrue(strip.generated(), e.name() + " en tira: " + strip.skipped());
        for (int i = 0; i < want.size(); i++) {
            String diff = FramesTest.diff(want.get(i), strip.frames().get(i).get(0));
            assertTrue(diff.isEmpty(), e.name() + " en tira, frame " + (i + 1) + ": " + diff);
        }
    }

    /** Envase con dibujo propio: mismo camino y mismos píxeles que los datos de referencia. */
    private static void checkOwn(FramesTest.Expected e) {
        Result r = BiteFrames.own(e.images().get(0));
        assertNotNull(r, e.name() + ": dibuja su propio envase");
        assertEquals(BiteFrames.Kind.CUP, r.kind(), e.name());
        if (e.kind() == FramesTest.LABEL) {
            assertFalse(r.generated(), e.name() + ": debía omitirse");
            assertEquals(OwnGlass.LABELED, r.skipped(), e.name());
            assertTrue(r.frames().isEmpty(), e.name());
            return;
        }
        assertTrue(r.generated(), e.name() + ": " + r.skipped());
        assertEquals(1, r.layers());
        List<RgbaImage> want = e.images().subList(1, e.images().size());
        assertEquals(want.size(), r.frames().size());
        for (int i = 0; i < want.size(); i++) {
            String diff = FramesTest.diff(want.get(i), r.frames().get(i).get(0));
            assertTrue(diff.isEmpty(), e.name() + " frame " + (i + 1) + ": " + diff);
        }
    }

    /**
     * Las dos capas dan lo mismo que la poción compuesta: alfa exacto y color con diferencia
     * de 1 como mucho (el redondeo de la composición no es el de la mezcla).
     */
    private static void checkPotion(FramesTest.Expected e) {
        checkPotion(e, e.bottle(), e.overlay());
        // overlay opaco bajo toda la botella: pasa bajo el corcho y bajo el vidrio semitransparente
        RgbaImage cover = new RgbaImage(e.overlay().width, e.overlay().height);
        for (Point p : Masks.opaque(e.bottle())) {
            cover.set(p.x(), p.y(), 150 + p.y() * 5 % 100, 200, 120 + p.x() * 7 % 130, 255);
        }
        checkPotion(e, e.bottle(), cover);
    }

    private static void checkPotion(FramesTest.Expected e, RgbaImage bottle, RgbaImage overlay) {
        Result r = BiteFrames.potion(bottle, overlay);
        assertTrue(r.generated(), e.name() + ": " + r.skipped());
        assertEquals(Kind.POTION, r.kind());
        assertEquals(2, r.layers());
        // la capa 0 es el overlay tal cual (lo tiñe el juego) o transparente: nada de redondeos
        for (List<RgbaImage> f : r.frames()) {
            for (int y = 0; y < overlay.height; y++) {
                for (int x = 0; x < overlay.width; x++) {
                    int[] got = f.get(0).get(x, y);
                    if (got[3] != 0) {
                        assertEquals(java.util.Arrays.toString(overlay.get(x, y)), java.util.Arrays.toString(got),
                                e.name() + " capa 0 en (" + x + ", " + y + ")");
                    }
                }
            }
        }
        List<int[]> colors = new ArrayList<>(List.of(COLORS));
        colors.add(0, e.color());
        StringBuilder report = new StringBuilder();
        for (int[] color : colors) {
            List<RgbaImage> want = Potions.potionFrames(bottle, overlay, color).frames();
            assertEquals(want.size(), r.frames().size());
            for (int i = 0; i < want.size(); i++) {
                List<RgbaImage> layers = r.frames().get(i);
                RgbaImage got = Potions.alphaComposite(Potions.tint(layers.get(0), color, new Mask()), layers.get(1));
                String diff = closeEnough(want.get(i), got, layers.get(0));
                if (!diff.isEmpty()) {
                    report.append("\n  color ").append(color[0]).append(",").append(color[1]).append(",")
                            .append(color[2]).append(" frame ").append(i + 1).append(": ").append(diff);
                }
            }
        }
        if (report.length() > 0) {
            fail(e.name() + report);
        }
    }

    /**
     * Vacío si el alfa es igual y ningún canal difiere en más de 1. Excepción: en la fila del
     * brillo, sobre un overlay de alfa parcial, el blanco suma cobertura; esos píxeles se
     * cuentan aparte en {@link #partialShine}.
     */
    static String closeEnough(RgbaImage want, RgbaImage got, RgbaImage overlayLayer) {
        Mask liquid = Masks.opaque(overlayLayer);
        int top = liquid.isEmpty() ? -1 : liquid.first().y();
        int alpha = 0;
        int color = 0;
        int worst = 0;
        String first = "";
        for (int y = 0; y < want.height; y++) {
            for (int x = 0; x < want.width; x++) {
                int o = overlayLayer.get(x, y)[3];
                if (y == top && o > 0 && o < 255) {
                    partialShine++;
                    continue;
                }
                int[] a = want.get(x, y);
                int[] b = got.get(x, y);
                if (a[3] != b[3]) {
                    if (alpha++ == 0) {
                        first = " (" + x + ", " + y + ") alfa " + a[3] + " -> " + b[3];
                    }
                } else if (a[3] != 0) {
                    int d = Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
                    worst = Math.max(worst, d);
                    if (d > 1 && color++ == 0 && first.isEmpty()) {
                        first = " (" + x + ", " + y + ") " + a[0] + "," + a[1] + "," + a[2] + " -> " + b[0] + ","
                                + b[1] + "," + b[2];
                    }
                }
            }
        }
        if (alpha == 0 && color == 0) {
            return "";
        }
        return "alfa distinto " + alpha + ", color > 1: " + color + " (peor " + worst + ")" + first;
    }

    /** Píxeles de brillo sobre overlay de alfa parcial que {@link #closeEnough} no comparó. */
    static int partialShine;

    @Test
    void opaqueOverlayHasNoExceptions() {
        // la excepción de closeEnough no puede tapar nada en una poción con overlay opaco
        partialShine = 0;
        RgbaImage overlay = overlayOf(16);
        Result r = BiteFrames.potion(bottle(16), overlay);
        for (List<RgbaImage> f : r.frames()) {
            RgbaImage got = Potions.alphaComposite(Potions.tint(f.get(0), COLORS[0], new Mask()), f.get(1));
            closeEnough(got, got, f.get(0));
        }
        assertEquals(0, partialShine);
    }


    private static RgbaImage bottle(int n) {
        // botella de vidrio: pared opaca, interior transparente encerrado
        RgbaImage im = new RgbaImage(n, n);
        for (int y = n / 4; y < n - 1; y++) {
            for (int x = n / 4; x < n - n / 4; x++) {
                boolean wall = y == n / 4 || y == n - 2 || x == n / 4 || x == n - n / 4 - 1;
                if (wall) {
                    im.set(x, y, 200, 220, 240, 255);
                }
            }
        }
        return im;
    }

    private static RgbaImage filled(RgbaImage empty) {
        RgbaImage im = new RgbaImage(empty.width, empty.height, empty.rgba().clone());
        int n = empty.width;
        for (int y = n / 4 + 1; y < n - 2; y++) {
            for (int x = n / 4 + 1; x < n - n / 4 - 1; x++) {
                im.set(x, y, 230, 120 + (y % 3) * 10, 20, 255);
            }
        }
        return im;
    }

    private static RgbaImage overlayOf(int n) {
        RgbaImage im = new RgbaImage(n, n);
        for (int y = n / 4 + 1; y < n - 2; y++) {
            for (int x = n / 4 + 1; x < n - n / 4 - 1; x++) {
                im.set(x, y, 200 + (y % 3) * 20, 200, 200, 255);
            }
        }
        return im;
    }

    @Test
    void containerWorks() {
        // prueba de humo con dibujos propios: si esto se omite, las comparaciones no dicen nada
        Result r = BiteFrames.container(filled(bottle(16)), bottle(16));
        assertTrue(r.generated(), r.skipped());
        assertEquals(Kind.DRINK, r.kind());
        Result p = BiteFrames.potion(bottle(16), overlayOf(16));
        assertTrue(p.generated(), p.skipped());
    }

    @Test
    void oversizedContainerIsSkippedWithoutGenerating() {
        RgbaImage empty = bottle(512);
        RgbaImage full = filled(empty);
        Result r = assertTimeoutPreemptively(Duration.ofMillis(500), () -> BiteFrames.container(full, empty));
        assertFalse(r.generated());
        assertTrue(r.skipped().contains("512x512"), r.skipped());
        // el recipiente también cuenta: comida chica con un vacío enorme
        Result r2 = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> BiteFrames.container(filled(bottle(16)), bottle(512)));
        assertFalse(r2.generated());
        assertTrue(r2.skipped().contains("512x512"), r2.skipped());
    }

    @Test
    void oversizedPotionIsSkippedWithoutGenerating() {
        Result r = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> BiteFrames.potion(bottle(512), overlayOf(512)));
        assertFalse(r.generated());
        assertTrue(r.skipped().contains("512x512"), r.skipped());
        Result r2 = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> BiteFrames.potion(bottle(16), overlayOf(257)));
        assertFalse(r2.generated());
        assertTrue(r2.skipped().contains("257x257"), r2.skipped());
    }

    @Test
    void capIsInclusiveForContainers() {
        RgbaImage empty = bottle(256);
        Result r = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> BiteFrames.container(filled(empty), empty));
        assertTrue(r.generated(), r.skipped());
        Result p = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> BiteFrames.potion(bottle(256), overlayOf(256)));
        assertTrue(p.generated(), p.skipped());
    }

    @Test
    void potionSizeMismatchIsSkipped() {
        // los recipientes de otro tamaño sí se generan; overlay y botella de distinto tamaño no
        Result p = BiteFrames.potion(bottle(32), overlayOf(16));
        assertFalse(p.generated());
        assertTrue(p.skipped().contains("16x16") && p.skipped().contains("32x32"), p.skipped());
    }

    @Test
    void noContentsIsSkipped() {
        // el ítem es igual al vacío: no hay contenido
        Result r = BiteFrames.container(bottle(16), bottle(16));
        assertFalse(r.generated());
        assertTrue(r.skipped().contains("contenido"), r.skipped());
    }

    @Test
    void transparentInputsAreSkipped() {
        assertFalse(BiteFrames.container(new RgbaImage(16, 16), bottle(16)).generated());
        assertFalse(BiteFrames.container(filled(bottle(16)), new RgbaImage(16, 16)).generated());
        assertFalse(BiteFrames.potion(bottle(16), new RgbaImage(16, 16)).generated());
    }

    @Test
    void animatedStripsUseFirstFrame() {
        RgbaImage empty = bottle(16);
        Result r = BiteFrames.container(strip(filled(empty)), strip(empty));
        assertTrue(r.generated(), r.skipped());
        Result plain = BiteFrames.container(filled(empty), empty);
        for (int i = 0; i < plain.frames().size(); i++) {
            assertEquals("", FramesTest.diff(plain.frames().get(i).get(0), r.frames().get(i).get(0)), "frame " + (i + 1));
        }
        Result p = BiteFrames.potion(strip(bottle(16)), strip(overlayOf(16)));
        assertTrue(p.generated(), p.skipped());
        assertEquals(16, p.frames().get(0).get(1).height);
    }

    private static RgbaImage strip(RgbaImage frame) {
        RgbaImage out = new RgbaImage(frame.width, frame.height * 8);
        System.arraycopy(frame.rgba(), 0, out.rgba(), 0, frame.rgba().length);
        return out;
    }

    /**
     * Texturas al azar (ruido, manchas, alfa parcial, tamaños distintos): ningún punto
     * de entrada lanza, y si genera, da 3 fotogramas del tamaño de la entrada.
     */
    @Test
    void neverThrows() {
        Random rng = new Random(20260930L);
        for (int i = 0; i < 300; i++) {
            RgbaImage a = random(rng);
            RgbaImage b = rng.nextInt(3) == 0 ? random(rng) : sameSize(a, rng);
            String name = "fuzz" + i;
            check(assertDoesNotThrow(() -> BiteFrames.solid(a, name), name), a);
            check(assertDoesNotThrow(() -> BiteFrames.container(a, b), name), a);
            check(assertDoesNotThrow(() -> BiteFrames.container(a, b, true), name), a);
            check(assertDoesNotThrow(() -> BiteFrames.guessed(a, name, false, List.of(b), List.of(b)), name), a);
            check(assertDoesNotThrow(() -> BiteFrames.potion(b, a), name), a);
            Result own = assertDoesNotThrow(() -> BiteFrames.own(a), name);
            if (own != null) {
                check(own, a);
            }
        }
    }

    private static void check(Result r, RgbaImage input) {
        assertNotNull(r.kind());
        if (!r.generated()) {
            assertTrue(r.frames().isEmpty());
            assertFalse(r.skipped().isEmpty());
            return;
        }
        RgbaImage in = input.firstFrame();
        assertEquals(Params.NUM_FRAMES, r.frames().size());
        for (List<RgbaImage> frame : r.frames()) {
            for (RgbaImage layer : frame) {
                assertEquals(in.width + "x" + in.height, layer.width + "x" + layer.height);
            }
        }
    }

    private static RgbaImage random(Random rng) {
        int w = rng.nextInt(25);
        int h = rng.nextInt(4) == 0 ? rng.nextInt(60) : w;
        return paint(new RgbaImage(w, h), rng);
    }

    private static RgbaImage sameSize(RgbaImage a, Random rng) {
        return paint(new RgbaImage(a.width, a.height), rng);
    }

    private static RgbaImage paint(RgbaImage im, Random rng) {
        int mode = rng.nextInt(4);
        for (int y = 0; y < im.height; y++) {
            for (int x = 0; x < im.width; x++) {
                boolean on = switch (mode) {
                    case 0 -> rng.nextBoolean();                                           // ruido
                    case 1 -> Math.hypot(x - im.width / 2.0, y - im.height / 2.0) < im.width / 3.0;  // mancha
                    case 2 -> (x + y) % 3 == 0;                                            // rayas finas
                    default -> rng.nextInt(10) != 0;                                       // casi lleno
                };
                if (on) {
                    im.set(x, y, rng.nextInt(256), rng.nextInt(256), rng.nextInt(256),
                            rng.nextInt(5) == 0 ? rng.nextInt(256) : 255);
                }
            }
        }
        return im;
    }
}
