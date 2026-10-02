package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Ítems que no devuelven recipiente pero lo dibujan: detección contra los datos de referencia y camino elegido. */
class VesselsTest {

    static final int FOOD = 0;
    static final int GLASS_DRINK = 1;
    static final int GLASS_FOOD = 2;
    static final int SAME_BOWL = 3;
    static final int OWN_BOWL = 4;
    /** Envase propio, sin vacío: 0 = ninguno, 1 = vidrio propio, 2 = frasco con etiqueta. */
    static final int OWN_VESSEL = 5;
    private static final String[] SUFFIX = {"~comida", "~bebida", "~vidrio", "~mismo", "~propio", "~envase"};

    /** Un caso de {@code vessels.bin}: {@code ítem~vacío~comida|bebida|vidrio|mismo|propio} o {@code ítem~envase}. */
    record Case(String name, RgbaImage full, RgbaImage empty, int mode, int result) {
        boolean want() {
            return result != 0;
        }

        int draws() {
            return switch (mode) {
                case GLASS_FOOD -> BiteFrames.drawsGlassFood(full, empty) ? 1 : 0;
                case SAME_BOWL -> {
                    Bowls.Fit fit = Bowls.bowlFit(full, empty);
                    yield fit != null && fit.same() ? 1 : 0;
                }
                case OWN_BOWL -> Bowls.ownBowl(full, empty) != null ? 1 : 0;
                case OWN_VESSEL -> {
                    Cups.Choice own = Cups.chooseOwn(full);
                    yield own == null ? 0 : own.skipped() == null ? 1 : 2;
                }
                default -> BiteFrames.drawsVessel(full, empty, mode == GLASS_DRINK) ? 1 : 0;
            };
        }
    }

    static Map<String, Case> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); DataInputStream d = new DataInputStream(in)) {
            assertEquals("PBV1", new String(d.readNBytes(4), StandardCharsets.US_ASCII), "cabecera de " + file);
            List<RgbaImage> images = new ArrayList<>();
            for (int i = d.readInt(); i > 0; i--) {
                int w = d.readInt();
                int h = d.readInt();
                byte[] raw = d.readNBytes(w * h * 4);
                assertEquals(w * h * 4, raw.length, "archivo truncado: " + file);
                images.add(new RgbaImage(w, h, raw));
            }
            Map<String, Case> out = new LinkedHashMap<>();
            for (int i = d.readInt(); i > 0; i--) {
                String name = new String(d.readNBytes(d.readUnsignedShort()), StandardCharsets.UTF_8);
                RgbaImage full = images.get(d.readInt());
                RgbaImage empty = images.get(d.readInt());
                int mode = d.readUnsignedByte();
                assertTrue(mode <= OWN_VESSEL, "modo " + mode + " en " + name);
                assertTrue(name.endsWith(SUFFIX[mode]), name + " con modo " + mode);
                int result = d.readUnsignedByte();
                assertTrue(result <= (mode == OWN_VESSEL ? 2 : 1), name + " con resultado " + result);
                out.put(name, new Case(name, full, empty, mode, result));
            }
            assertEquals(-1, d.read(), "sobran bytes en " + file);
            return out;
        }
    }

    private static Map<String, Case> synthetic() throws IOException {
        try {
            return read(Paths.get(VesselsTest.class.getResource("/vessels.bin").toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static Stream<DynamicTest> parity(Map<String, Case> cases) {
        assertFalse(cases.isEmpty());
        return cases.values().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> assertEquals(c.result(), c.draws(), c.name())));
    }

    @TestFactory
    Stream<DynamicTest> syntheticParity() throws IOException {
        Map<String, Case> cases = synthetic();
        // todas las ramas, con casos que sí y que no
        for (int mode = FOOD; mode <= OWN_VESSEL; mode++) {
            int m = mode;
            assertTrue(cases.values().stream().anyMatch(c -> c.mode() == m && c.want()), "sin positivos, modo " + mode);
            assertTrue(cases.values().stream().anyMatch(c -> c.mode() == m && !c.want()), "sin negativos, modo " + mode);
        }
        assertTrue(cases.values().stream().anyMatch(c -> c.mode() == OWN_VESSEL && c.result() == 2), "sin etiqueta");
        return parity(cases);
    }

    @TestFactory
    Stream<DynamicTest> localParity() throws IOException {
        String prop = System.getProperty("expectedDir", "");
        Path file = Paths.get(prop, "vessels.bin");
        Assumptions.assumeTrue(!prop.isEmpty() && Files.isRegularFile(file), "sin datos locales (-PexpectedDir)");
        return parity(read(file));
    }

    private static Case get(Map<String, Case> cases, String name) {
        Case c = cases.get(name);
        assertNotNull(c, "falta el caso " + name);
        return c;
    }

    /** La sopa en un tazón que calza, sin recipiente declarado: se reconstruye el tazón, no se muerde. */
    @Test
    void soupWithoutContainerKeepsTheBowl() throws IOException {
        Case c = get(synthetic(), "pb_bowl_fit~bowl~comida");
        Result r = BiteFrames.guessed(c.full(), "pb_bowl_fit", false, List.of(c.empty()));
        assertTrue(r.generated(), r.skipped());
        assertEquals(Kind.BOWL, r.kind());
        assertSameFrames(BiteFrames.container(c.full(), c.empty()), r);
    }

    /** Mismos fotogramas, píxel a píxel. */
    private static void assertSameFrames(Result want, Result got) {
        assertTrue(want.generated(), want.skipped());
        assertEquals(want.frames().size(), got.frames().size());
        for (int i = 0; i < want.frames().size(); i++) {
            assertEquals(want.frames().get(i).size(), got.frames().get(i).size());
            for (int j = 0; j < want.frames().get(i).size(); j++) {
                RgbaImage a = want.frames().get(i).get(j);
                RgbaImage b = got.frames().get(i).get(j);
                assertEquals(a.width + "x" + a.height, b.width + "x" + b.height, "frame " + (i + 1));
                assertTrue(Arrays.equals(a.rgba(), b.rgba()), "frame " + (i + 1) + ": " + FramesTest.diff(a, b));
            }
        }
    }

    /** Pila de recursos: basta que calce uno, y los fotogramas se hacen con el activo (el último). */
    @Test
    void stackDetectsWithAnyAndGeneratesWithTheActive() throws IOException {
        Map<String, Case> cases = synthetic();
        Case c = get(cases, "pb_bowl_fit~bowl~comida");
        RgbaImage pack = get(cases, "pb_bowl_fit~bowl32~comida").empty();
        assertFalse(BiteFrames.drawsVessel(c.full(), pack, false), "el tazón 32x no calza con la comida 16x");
        Result r = BiteFrames.guessed(c.full(), "pb_bowl_fit", false, List.of(c.empty(), pack));
        assertEquals(Kind.BOWL, r.kind());
        assertSameFrames(BiteFrames.container(c.full(), pack), r);
    }

    /** Solo calza uno que no es el primero: se busca en toda la pila. */
    @Test
    void stackMatchesALaterVersion() throws IOException {
        Map<String, Case> cases = synthetic();
        Case c = get(cases, "pb_bowl_fit~bowl~comida");
        RgbaImage other = get(cases, "pb_bowl_fit~bowl32~comida").empty();
        Result r = BiteFrames.guessed(c.full(), "pb_bowl_fit", false, List.of(other, c.empty()));
        assertEquals(Kind.BOWL, r.kind());
        assertSameFrames(BiteFrames.container(c.full(), c.empty()), r);
    }

    /** Ningún sólido sintético parece un tazón: se muerde igual que antes. */
    @Test
    void solidsStaySolid() throws IOException {
        Map<String, Case> cases = synthetic();
        for (String name : List.of("pb_fruit", "pb_carrot", "pb_bar", "pb_cookie", "pb_pair", "pb_tart")) {
            Case c = get(cases, name + "~bowl~comida");
            assertFalse(c.want(), name);
            Result r = BiteFrames.guessed(c.full(), name, false, List.of(c.empty()));
            assertEquals(Kind.SOLID, r.kind(), name);
            assertSameFrames(BiteFrames.solid(c.full(), name), r);
        }
    }

    /** Taza de vidrio de otra forma: baja el nivel con la botella. */
    @Test
    void glassCupDrinks() throws IOException {
        Case c = get(synthetic(), "pb_glass_cup~bottle~bebida");
        Result r = BiteFrames.guessed(c.full(), "pb_glass_cup", true, List.of(c.empty()));
        assertTrue(r.generated(), r.skipped());
        assertEquals(Kind.DRINK, r.kind());
        assertSameFrames(BiteFrames.container(c.full(), c.empty()), r);
    }

    /** Botella toda contenido: sin vidrio, se omite. */
    @Test
    void darkBottleIsSkipped() throws IOException {
        Case c = get(synthetic(), "pb_dark_bottle~bottle~bebida");
        Result r = BiteFrames.guessed(c.full(), "pb_dark_bottle", true, List.of(c.empty()));
        assertFalse(r.generated());
        assertEquals("bebida sin recipiente", r.skipped());
    }

    /** Texturas raras: nunca lanza. */
    @Test
    void hostileTexturesNeverThrow() throws IOException {
        RgbaImage bowl = get(synthetic(), "pb_bowl_fit~bowl~comida").empty();
        RgbaImage clear = new RgbaImage(16, 16);
        for (boolean drink : new boolean[] {false, true}) {
            assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsVessel(clear, bowl, drink)));
            assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsVessel(bowl, clear, drink)));
            assertFalse(assertDoesNotThrow(() -> BiteFrames.guessed(clear, "clear", drink, List.of(bowl))).generated());
            assertDoesNotThrow(() -> BiteFrames.guessed(bowl, "bowl", drink, List.of()));
        }
        RgbaImage bottle = get(synthetic(), "pb_glass_cup~bottle~bebida").empty();
        assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsGlassFood(clear, bottle)));
        assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsGlassFood(bottle, clear)));
        assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsGlassFood(SolidsHostileTest.disc(257), bottle)));
        assertFalse(assertDoesNotThrow(() -> BiteFrames.drawsGlassFood(bottle, SolidsHostileTest.disc(257))));
    }

    /** Un sólido 256x no tarda más por buscar el tazón; 257x se omite sin buscarlo. */
    @Test
    void bigTexturesStayFast() throws IOException {
        RgbaImage bowl = get(synthetic(), "pb_bowl_fit~bowl~comida").empty();
        RgbaImage disc = SolidsHostileTest.disc(256);
        Result r = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BiteFrames.guessed(disc, "disc", false, List.of(bowl, disc)));
        assertEquals(Kind.SOLID, r.kind());
        RgbaImage big = SolidsHostileTest.disc(257);
        Result skip = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> BiteFrames.guessed(big, "disc", true, List.of(big)));
        assertFalse(skip.generated());
    }
}
