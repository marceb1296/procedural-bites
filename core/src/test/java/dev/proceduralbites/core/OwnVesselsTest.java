package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import dev.proceduralbites.core.Params.BitePattern;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Recipientes con dibujo propio y tallos: que cada camino se alcance, lo que promete cada regla y los
 * casos hostiles.
 */
class OwnVesselsTest {

    private static FramesTest.Expected load(String name) throws IOException {
        try {
            return FramesTest.read(Paths.get(OwnVesselsTest.class.getResource("/frames/" + name + ".bin").toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, VesselsTest.Case> vessels() throws IOException {
        try {
            return VesselsTest.read(Paths.get(OwnVesselsTest.class.getResource("/vessels.bin").toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static RgbaImage bowl() throws IOException {
        return vessels().get("pb_bowl_fit~bowl~comida").empty();
    }

    private static RgbaImage bottle() throws IOException {
        return vessels().get("pb_glass_cup~bottle~bebida").empty();
    }

    private static void assertFrames(FramesTest.Expected want, Result got) {
        assertTrue(got.generated(), want.name() + ": " + got.skipped());
        assertEquals(FramesTest.kind(want.kind()), got.kind(), want.name());
        assertEquals(want.images().size() - 1, got.frames().size(), want.name());
        for (int i = 0; i < got.frames().size(); i++) {
            assertEquals("", FramesTest.diff(want.images().get(i + 1), got.frames().get(i).get(0)),
                    want.name() + " frame " + (i + 1));
        }
    }

    private static RgbaImage upscale(RgbaImage im, int f) {
        RgbaImage out = new RgbaImage(im.width * f, im.height * f);
        for (int y = 0; y < out.height; y++) {
            for (int x = 0; x < out.width; x++) {
                int[] c = im.get(x / f, y / f);
                out.set(x, y, c[0], c[1], c[2], c[3]);
            }
        }
        return out;
    }

    private static final List<String> GLASSES = List.of("pb_own_glass", "pb_own_cork", "pb_own_fruit", "pb_own_mug",
            "pb_own_lid", "pb_own_float", "pb_own_fruit32", "pb_own_leaf");

    /** Comida que trae su propio envase de vidrio: baja el nivel dentro de él. Una bebida no pasa por aquí. */
    @Test
    void ownGlassComesAfterTheStacks() throws IOException {
        for (String name : GLASSES) {
            FramesTest.Expected e = load(name);
            RgbaImage full = e.images().get(0);
            assertTrue(e.own(), name);
            assertFalse(BiteFrames.drawsGlassFood(full, bottle()), name + ": no usa los colores de la botella");
            assertFrames(e, BiteFrames.guessed(full, name, false, List.of(bowl()), List.of(bottle())));
            assertFrames(e, BiteFrames.guessed(full, name, false, List.of(), List.of()));
            assertFrames(e, BiteFrames.own(full));
            Result drunk = BiteFrames.guessed(full, name, true, List.of(bowl()), List.of(bottle()));
            assertEquals("bebida sin recipiente", drunk.skipped(), name);
        }
        // un sólido no dibuja ningún envase
        assertNull(BiteFrames.own(load("pb_fruit").images().get(0)));
        assertNull(BiteFrames.own(load("pb_own_bowl").images().get(0)));
    }

    /** Con texturas animadas se usa el primer fotograma. */
    @Test
    void animatedStripsUseFirstFrame() throws IOException {
        FramesTest.Expected e = load("pb_own_cork");
        RgbaImage full = e.images().get(0);
        RgbaImage strip = new RgbaImage(full.width, full.height * 3);
        for (Point p : Masks.opaque(full)) {
            strip.set(p, full.get(p));
            strip.set(new Point(p.x(), p.y() + full.height), new int[] {200, 30, 30, 255});
        }
        assertFrames(e, BiteFrames.own(strip));
        assertFrames(e, BiteFrames.guessed(strip, "pb_own_cork", false, List.of(bowl()), List.of(bottle())));
    }

    /** Frasco con una etiqueta que tapa las dos paredes: no se ve el nivel, queda sin animación. */
    @Test
    void labeledJarIsSkipped() throws IOException {
        FramesTest.Expected e = load("pb_own_label");
        RgbaImage full = e.images().get(0);
        assertEquals(FramesTest.LABEL, e.kind());
        assertTrue(OwnGlass.labeledGlass(full));
        assertNull(OwnGlass.ownGlass(full));
        for (Result r : List.of(BiteFrames.own(full),
                BiteFrames.guessed(full, "pb_own_label", false, List.of(bowl()), List.of(bottle())))) {
            assertFalse(r.generated());
            assertEquals(OwnGlass.LABELED, r.skipped());
            assertEquals(Kind.CUP, r.kind());
            assertTrue(r.frames().isEmpty());
        }
        // sin la etiqueta sobre las paredes es un frasco común
        assertFalse(OwnGlass.labeledGlass(load("pb_own_lid").images().get(0)));
    }

    /**
     * El corcho y la tapa se quitan desde el primer fotograma; el borde de un vaso y el vapor de una
     * taza se quedan.
     */
    @Test
    void stopperIsRemovedFromTheFirstFrame() throws IOException {
        Map<String, Integer> stoppers = Map.of("pb_own_glass", 0, "pb_own_cork", 6, "pb_own_fruit", 0, "pb_own_mug", 0,
                "pb_own_lid", 36, "pb_own_float", 0, "pb_own_fruit32", 0, "pb_own_leaf", 0);
        for (String name : GLASSES) {
            RgbaImage full = load(name).images().get(0);
            OwnGlass.Parts parts = OwnGlass.ownGlass(full);
            assertNotNull(parts, name);
            assertEquals(stoppers.get(name), parts.stopper().size(), name + ": tapón");
            assertFalse(parts.stopper().intersects(parts.vessel()), name);
            for (RgbaImage fr : OwnGlass.frames(full, parts)) {
                for (Point p : parts.stopper()) {
                    assertEquals(0, fr.alpha(p.x(), p.y()), name + ": tapón en " + p);
                }
                for (Point p : parts.vessel()) {
                    assertArrayEquals(full.get(p), fr.get(p), name + ": el envase cambia en " + p);
                }
            }
        }
        // el borde de atrás del vaso (fila 3) y el vapor de la taza (filas 0 a 2) son envase
        OwnGlass.Parts glass = OwnGlass.ownGlass(load("pb_own_glass").images().get(0));
        assertTrue(glass.vessel().contains(6, 3) && glass.vessel().contains(10, 3));
        OwnGlass.Parts mug = OwnGlass.ownGlass(load("pb_own_mug").images().get(0));
        assertTrue(mug.vessel().contains(7, 0) && mug.vessel().contains(6, 1) && mug.vessel().contains(7, 2));
        // la tapa entera, con la franja que queda entre los labios de vidrio (fila 5)
        OwnGlass.Parts lid = OwnGlass.ownGlass(load("pb_own_lid").images().get(0));
        assertTrue(lid.stopper().contains(5, 1) && lid.stopper().contains(5, 5) && lid.stopper().contains(10, 5));
        assertTrue(lid.vessel().contains(4, 5) && lid.vessel().contains(11, 5), "los labios del frasco quedan");
    }

    /** La fruta del borde se come por tercios; lo que tapaba del vaso se repone por simetría. */
    @Test
    void fruitIsEatenInThirdsAndTheGlassIsMirrored() throws IOException {
        RgbaImage full = load("pb_own_fruit").images().get(0);
        OwnGlass.Parts parts = OwnGlass.ownGlass(full);
        assertEquals(15, parts.garnish().size());
        assertEquals(16, parts.axis(), "paredes en las columnas 5 y 11");
        assertTrue(parts.garnish().contains(11, 2), "el brillo sin color que la fruta encierra es fruta");
        List<RgbaImage> frames = OwnGlass.frames(full, parts);
        int[] left = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            for (Point p : parts.garnish()) {
                if (java.util.Arrays.equals(frames.get(i).get(p), full.get(p))) {
                    left[i]++;
                }
            }
        }
        assertArrayEquals(new int[] {9, 5, 0}, left, "fruta que queda: filas 3 a 5, 4 a 5 y ninguna");
        RgbaImage last = frames.get(frames.size() - 1);
        assertArrayEquals(full.get(5, 4), last.get(11, 4), "la punta de la pared derecha");
        assertArrayEquals(full.get(5, 5), last.get(11, 5), "la pared derecha a la altura del borde");
        assertArrayEquals(full.get(6, 3), last.get(10, 3), "la punta del borde de atrás");
        assertEquals(0, last.alpha(12, 4), "la fruta de fuera del vaso no deja nada");
        // la pared sigue tapada mientras la fruta no bajó hasta ahí
        assertArrayEquals(full.get(11, 5), frames.get(0).get(11, 5));
    }

    /** Color de vidrio: azul grisáceo; no el metal, lo muy saturado ni lo oscuro. */
    @Test
    void glassColorBounds() {
        assertTrue(OwnGlass.glassLike(new int[] {150, 170, 205, 255}));
        assertTrue(OwnGlass.glassLike(new int[] {110, 124, 160, 255}));
        assertTrue(OwnGlass.glassLike(new int[] {178, 208, 226, 255}), "el vidrio de la botella");
        assertFalse(OwnGlass.glassLike(new int[] {150, 205, 196, 255}), "tono 170: verde agua");
        assertFalse(OwnGlass.glassLike(new int[] {178, 150, 205, 255}), "tono 270: lila");
        assertFalse(OwnGlass.glassLike(new int[] {200, 202, 210, 255}), "saturación 0,05: metal");
        assertFalse(OwnGlass.glassLike(new int[] {60, 90, 220, 255}), "saturación 0,73: azul intenso");
        assertFalse(OwnGlass.glassLike(new int[] {60, 70, 100, 255}), "brillo 0,39: azul oscuro");
    }

    /** Restos y sello: la hoja suelta se borra, un resto de vidrio se respeta y el vaso nunca queda abierto. */
    @Test
    void looseLeftoversAndBottomSeal() throws IOException {
        RgbaImage full = load("pb_own_leaf").images().get(0);
        OwnGlass.Parts parts = OwnGlass.ownGlass(full);
        assertEquals(21, parts.garnish().size());
        assertTrue(parts.garnish().contains(12, 3), "brillo azulado dentro de la fruta");
        assertFalse(parts.vessel().contains(3, 4), "color de vidrio que no toca las paredes");
        assertTrue(parts.body().contains(11, 12), "líquido en la esquina de la pared");
        List<RgbaImage> frames = OwnGlass.frames(full, parts);
        assertEquals(0, frames.get(0).alpha(15, 3), "hoja suelta de 2 px");
        assertEquals(0, frames.get(0).alpha(15, 4));
        assertArrayEquals(full.get(3, 4), frames.get(1).get(3, 4), "resto del color del vidrio");
        assertArrayEquals(full.get(3, 5), frames.get(1).get(3, 5));
        assertEquals(0, frames.get(1).alpha(4, 3), "la fruta de la que colgaba ya no está");
        assertEquals(0, frames.get(2).alpha(3, 4));
        assertArrayEquals(full.get(11, 12), frames.get(1).get(11, 12), "el corte no llegó a la esquina");
        assertTrue(OwnGlass.glassLike(frames.get(2).get(11, 12)), "la esquina queda de vidrio");
        assertEquals(255, frames.get(2).alpha(11, 12));
    }

    /** La espuma blanca y la pajilla apoyadas en el contenido bajan con él. */
    @Test
    void foamAndStrawGoDownWithTheBody() throws IOException {
        RgbaImage full = load("pb_own_float").images().get(0);
        OwnGlass.Parts parts = OwnGlass.ownGlass(full);
        // la pajilla está sobre la pared y toca el contenido solo en diagonal
        assertTrue(parts.body().contains(6, 4) && parts.body().contains(11, 2), "espuma y pajilla");
        assertTrue(parts.garnish().isEmpty());
        RgbaImage first = OwnGlass.frames(full, parts).get(0);
        assertEquals(0, first.alpha(11, 2));
        assertEquals(0, first.alpha(6, 4));
    }

    /** Tazón propio en el lugar del de referencia: el resultado no depende del pack activo. */
    @Test
    void ownBowlDoesNotDependOnThePack() throws IOException {
        Map<String, VesselsTest.Case> cases = vessels();
        RgbaImage bowl = bowl();
        RgbaImage pack = cases.get("pb_bowl_fit~bowl32~comida").empty();
        Set<Integer> reference = new HashSet<>();
        for (Point p : Masks.opaque(bowl)) {
            int[] c = bowl.get(p);
            reference.add((c[0] << 16) | (c[1] << 8) | c[2]);
        }
        for (String name : List.of("pb_own_bowl", "pb_own_heap")) {
            FramesTest.Expected e = load(name);
            RgbaImage full = e.images().get(0);
            assertTrue(BiteFrames.drawsVessel(full, bowl, false), name + ": calza en posición");
            Bowls.Fit fit = Bowls.bowlFit(full, bowl);
            assertFalse(fit.same(), name + ": no es el mismo dibujo");
            Bowls.Own own = Bowls.ownBowl(full, bowl);
            assertNotNull(own, name);
            for (Point p : Masks.opaque(own.bowl())) {
                int[] c = own.bowl().get(p);
                assertFalse(reference.contains((c[0] << 16) | (c[1] << 8) | c[2]), name + ": color del de referencia en " + p);
            }
            for (List<RgbaImage> stack : List.of(List.of(bowl), List.of(bowl, pack), List.of(pack, bowl))) {
                assertFrames(e, BiteFrames.guessed(full, name, false, stack, List.of(bottle())));
            }
            // con el tazón declarado da lo mismo
            assertFrames(e, BiteFrames.container(full, bowl));
        }
        // el mismo dibujo que el de referencia sigue por el camino de siempre, con el activo
        VesselsTest.Case same = cases.get("pb_bowl_fit~bowl~comida");
        assertTrue(Bowls.bowlFit(same.full(), bowl).same());
        assertNull(Bowls.ownBowl(same.full(), bowl));
    }

    /** Lo que el ítem dibuja fuera de su tazón y no es comida (los palillos) nunca cambia. */
    @Test
    void chopsticksNeverChange() throws IOException {
        FramesTest.Expected e = load("pb_own_bowl");
        RgbaImage full = e.images().get(0);
        Bowls.Own own = Bowls.ownBowl(full, bowl());
        Mask sticks = Masks.opaque(full).minus(own.contents());
        assertTrue(sticks.contains(4, 1) && sticks.contains(2, 3) && sticks.contains(5, 6), "palillos, también en la boca");
        for (RgbaImage fr : Bowls.bowlFrames(full, own.contents(), bowl(), own.bowl())) {
            for (Point p : sticks) {
                assertArrayEquals(full.get(p), fr.get(p), "cambia en " + p);
            }
        }
    }

    /** Las migas sueltas quedan hasta el penúltimo frame; la rama que asoma baja y no queda suelta. */
    @Test
    void crumbsStayUntilTheSecondToLastFrame() throws IOException {
        RgbaImage full = load("pb_own_heap").images().get(0);
        Bowls.Own own = Bowls.ownBowl(full, bowl());
        List<RgbaImage> frames = Bowls.bowlFrames(full, own.contents(), bowl(), own.bowl());
        for (Point crumb : List.of(new Point(15, 5), new Point(1, 3))) {
            assertArrayEquals(full.get(crumb), frames.get(0).get(crumb));
            assertArrayEquals(full.get(crumb), frames.get(1).get(crumb));
            assertEquals(0, frames.get(2).alpha(crumb.x(), crumb.y()), "miga en el último frame: " + crumb);
        }
        assertEquals(0, frames.get(0).alpha(10, 0), "la punta de la rama se come primero");
        // la punta que colgaba (13, 2) y (13, 3) queda bajo el corte pero separada del tazón: se quita
        assertEquals(255, frames.get(0).alpha(10, 2));
        assertEquals(0, frames.get(0).alpha(13, 2));
        assertEquals(0, frames.get(0).alpha(13, 3));
        for (int i = 0; i < frames.size(); i++) {
            int pieces = Masks.components(Masks.opaque(frames.get(i))).size();
            assertEquals(i < 2 ? 3 : 1, pieces, "frame " + (i + 1) + ": el tazón y las dos migas, y al final solo el tazón");
        }
        assertArrayEquals(own.bowl().rgba(), frames.get(2).rgba(), "el último frame es su propio tazón vacío");
    }

    /** Calza en posición pero no es ese tazón (sobresale a los dos lados o no se ve su pared interior): sólido. */
    @Test
    void wideOrCoveredIsNotAnOwnBowl() throws IOException {
        Map<String, VesselsTest.Case> cases = vessels();
        RgbaImage bowl = bowl();
        for (String name : List.of("pb_own_wide", "pb_own_covered")) {
            RgbaImage full = cases.get(name + "~bowl~comida").full();
            Bowls.Fit fit = Bowls.bowlFit(full, bowl);
            assertNotNull(fit, name + ": calza en posición");
            assertFalse(fit.same(), name);
            assertNull(Bowls.ownBowl(full, bowl), name);
            Result r = BiteFrames.guessed(full, name, false, List.of(bowl), List.of(bottle()));
            assertEquals(Kind.SOLID, r.kind(), name);
            Result want = BiteFrames.solid(full, name);
            for (int i = 0; i < want.frames().size(); i++) {
                assertEquals("", FramesTest.diff(want.frames().get(i).get(0), r.frames().get(i).get(0)), name);
            }
        }
    }

    /** La paleta del tazón propio: qué es comida y qué es tazón, y el desempate del interior. */
    @Test
    void ownBowlPalette() throws IOException {
        FramesTest.Expected e = load("pb_own_sauce");
        RgbaImage full = e.images().get(0);
        Bowls.Own own = Bowls.ownBowl(full, e.empty());
        assertNotNull(own);
        for (Point food : List.of(new Point(7, 9), new Point(7, 10), new Point(6, 9), new Point(6, 10), new Point(6, 8),
                new Point(0, 8), new Point(1, 9))) {
            assertTrue(own.contents().contains(food), "comida en " + food);
        }
        for (Point bowl : List.of(new Point(3, 10), new Point(8, 10), new Point(2, 7), new Point(12, 7))) {
            assertFalse(own.contents().contains(bowl), "tazón en " + bowl);
        }
        assertArrayEquals(full.get(4, 6), own.bowl().get(7, 7), "interior: el primero de los dos colores empatados");
        assertArrayEquals(full.get(3, 10), own.bowl().get(7, 10), "la franja sigue bajo la salsa");
        // la hoja de fuera del tazón baja con el montón: no desaparece toda en el primer frame
        List<RgbaImage> frames = Bowls.bowlFrames(full, own.contents(), e.empty(), own.bowl());
        assertArrayEquals(full.get(1, 9), frames.get(0).get(1, 9));
        assertEquals(0, frames.get(2).alpha(1, 9));
        // en una sola fila puede sobresalir a los dos lados
        assertNotNull(Bowls.ownBowl(vessels().get("pb_own_wide1~bowl~propio").full(), bowl()));
    }

    /** El tazón propio solo se busca con recipientes que no son de vidrio. */
    @Test
    void tintedPackBottleIsNotAnOwnBowl() throws IOException {
        FramesTest.Expected jar = load("pb_jar");
        RgbaImage full = jar.images().get(0);
        RgbaImage tinted = new RgbaImage(jar.empty().width, jar.empty().height);
        for (Point p : Masks.opaque(jar.empty())) {
            int[] c = jar.empty().get(p);
            tinted.set(p, new int[] {Math.min(255, c[0] + 8), c[1], c[2], c[3]});
        }
        assertTrue(Drinks.isGlass(tinted));
        assertNotNull(Bowls.ownBowl(full, tinted), "la sintética ya no calza como tazón propio con la botella teñida");
        Cups.Choice choice = Cups.choose(full, tinted, false);
        assertEquals(Kind.DRINK, choice.kind());
        assertNull(choice.ownBowl());
        assertEquals(Kind.DRINK, BiteFrames.container(full, tinted).kind());
    }

    /** A 32x el contorno del tazón mide 2 anillos: el interior no toma el color del borde. */
    @Test
    void hdOwnBowlKeepsItsInnerColor() throws IOException {
        FramesTest.Expected e = load("pb_own_bowl32");
        RgbaImage full = e.images().get(0);
        Bowls.Own own = Bowls.ownBowl(full, e.empty());
        assertNotNull(own);
        // interior de la boca tapado por la comida: el color de la pared interior, no el del borde
        assertArrayEquals(full.get(8, 12), own.bowl().get(16, 14));
        assertFalse(java.util.Arrays.equals(full.get(4, 12), own.bowl().get(16, 14)));
    }

    /** Tallo: fino, sobre el cuerpo, unido a él y de al menos 3 px (x k). */
    @Test
    void stemNeedsThreePixelsJoinedToTheBody() throws IOException {
        Map<String, Integer> stems = Map.of("pb_loose_stem", 0, "pb_tip2", 2, "pb_side_stem", 3, "pb_long_stem", 3,
                "pb_fruit", 6);
        for (Map.Entry<String, Integer> s : stems.entrySet()) {
            RgbaImage im = load(s.getKey()).images().get(0);
            Mask mask = Masks.opaque(im);
            assertEquals(s.getValue(), Solids.stemPixels(mask, 1).size(), s.getKey());
            assertEquals(s.getValue() >= 3, Solids.hasStem(mask, 1), s.getKey());
        }
        assertFalse(Solids.hasStem(new Mask(), 1));
        // a 32x hacen falta 6: los 3 px de la fruta, sin escalar, no alcanzan
        assertFalse(Solids.hasStem(Masks.opaque(load("pb_side_stem").images().get(0)), 2));
    }

    /** Fruta con el tallo de costado: el corazón flotaría, así que termina en un último bocado desde arriba. */
    @Test
    void shortHeartEndsInALastBite() throws IOException {
        RgbaImage side = load("pb_side_stem").images().get(0);
        List<Eaten> geometry = Solids.solidGeometry(side, "pb_side_stem", BitePattern.AUTO);
        assertArrayEquals(new double[] {0, -1}, geometry.get(geometry.size() - 1).biteDir());
        RgbaImage fruit = load("pb_fruit").images().get(0);
        List<Eaten> heart = Solids.solidGeometry(fruit, "pb_fruit", BitePattern.AUTO);
        assertNull(heart.get(heart.size() - 1).biteDir());
        Mask core = Masks.opaque(fruit).minus(heart.get(heart.size() - 1).pixels());
        Mask all = Masks.opaque(fruit);
        assertEquals(all.first().y(), core.first().y());
        assertEquals(all.stream().mapToInt(Point::y).max().getAsInt(), core.stream().mapToInt(Point::y).max().getAsInt());
        RgbaImage bar = load("pb_long_stem").images().get(0);
        List<Eaten> eaten = Solids.solidGeometry(bar, "pb_long_stem", BitePattern.AUTO);
        assertNotNull(eaten.get(eaten.size() - 1).biteDir());
    }

    /** Texturas raras: nunca lanza, y lo que no es un envase sigue por el camino de antes. */
    @Test
    void hostileInputs() throws IOException {
        RgbaImage clear = new RgbaImage(16, 16);
        assertNull(assertDoesNotThrow(() -> BiteFrames.own(clear)));
        assertNull(assertDoesNotThrow(() -> BiteFrames.own(new RgbaImage(0, 0))));
        assertNull(assertDoesNotThrow(() -> BiteFrames.own(SolidsHostileTest.disc(257))));
        // un envase de más de 256 px no se genera (17 x 16 = 272)
        RgbaImage huge = upscale(load("pb_own_glass").images().get(0), 17);
        assertNotNull(OwnGlass.ownGlass(huge));
        assertNull(BiteFrames.own(huge));
        assertNotNull(BiteFrames.own(upscale(load("pb_own_glass").images().get(0), 16)));
        assertNull(Cups.chooseOwn(clear));
        assertNull(OwnGlass.ownGlass(clear));
        assertFalse(OwnGlass.labeledGlass(clear));
        assertNull(Bowls.bowlFit(clear, bowl()));
        assertNull(Bowls.ownBowl(clear, bowl()));
        // tazón de referencia vacío: lanza y guessed lo absorbe
        RgbaImage soup = bowl();
        assertThrows(ArithmeticException.class, () -> Bowls.bowlFit(soup, clear));
        assertNull(assertDoesNotThrow(() -> BiteFrames.guessedBowl(soup, List.of(clear))));
        assertNull(BiteFrames.guessedBowl(soup, List.of()));
        assertNull(BiteFrames.guessedBowl(soup, List.of(SolidsHostileTest.disc(257))));
        assertNull(Bowls.bowlFit(upscale(bowl(), 2), bowl()));
        // todo de vidrio, sin contenido: no es un envase con algo que beber
        RgbaImage full = load("pb_own_glass").images().get(0);
        RgbaImage hollow = new RgbaImage(16, 16);
        OwnGlass.Parts parts = OwnGlass.ownGlass(full);
        for (Point p : parts.vessel()) {
            hollow.set(p, full.get(5, 4));
        }
        assertNull(OwnGlass.ownGlass(hollow));
        assertEquals(Kind.SOLID, BiteFrames.guessed(hollow, "hollow", false, List.of(bowl()), List.of(bottle())).kind());
        // sin cuerpo no hay fotogramas
        OwnGlass.Parts empty = new OwnGlass.Parts(parts.vessel(), new Mask(), new Mask(), parts.axis(), new Mask());
        assertThrows(ArithmeticException.class, () -> OwnGlass.frames(full, empty));
        assertThrows(ArithmeticException.class, () -> Cups.frames(clear, null, new Cups.Choice(Kind.CUP, new Mask())));
    }

    /** Un pack 256x no congela el juego: vidrio propio y tazón propio en menos de 3 s. */
    @Test
    void hdOwnVesselsAreFast() throws IOException {
        RgbaImage glass = upscale(load("pb_own_fruit").images().get(0), 16);
        assertEquals(256, glass.width);
        Result r = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BiteFrames.guessed(glass, "pb_own_fruit", false, List.of(upscale(bowl(), 16)), List.of(upscale(bottle(), 16))));
        assertTrue(r.generated(), r.skipped());
        assertEquals(Kind.CUP, r.kind());
        RgbaImage heap = upscale(load("pb_own_heap").images().get(0), 16);
        RgbaImage big = upscale(bowl(), 16);
        Result b = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BiteFrames.guessed(heap, "pb_own_heap", false, List.of(big), List.of()));
        assertTrue(b.generated(), b.skipped());
        assertEquals(Kind.BOWL, b.kind());
    }
}
