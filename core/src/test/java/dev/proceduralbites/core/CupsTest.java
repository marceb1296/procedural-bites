package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Taza vista desde arriba, copas de postre y frascos con etiqueta que se comen: caminos y casos hostiles. */
class CupsTest {

    private static FramesTest.Expected load(String name) throws IOException {
        try {
            return FramesTest.read(Paths.get(CupsTest.class.getResource("/frames/" + name + ".bin").toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, VesselsTest.Case> vessels() throws IOException {
        try {
            return VesselsTest.read(Paths.get(CupsTest.class.getResource("/vessels.bin").toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
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

    /** La misma copa: si se come, se vacía con cuchara; si se bebe (devuelve la botella), baja el nivel. */
    @Test
    void eatenDecidesTheCup() throws IOException {
        FramesTest.Expected cup = load("pb_cup_dessert");
        FramesTest.Expected drunk = load("pb_cup_drunk");
        assertTrue(cup.eaten());
        assertFalse(drunk.eaten());
        assertEquals("", FramesTest.diff(cup.images().get(0), drunk.images().get(0)), "misma textura");
        assertFrames(cup, BiteFrames.container(cup.images().get(0), cup.empty(), true));
        assertFrames(drunk, BiteFrames.container(cup.images().get(0), cup.empty(), false));
        assertFrames(drunk, BiteFrames.container(cup.images().get(0), cup.empty()));
    }

    /** Frasco con etiqueta de lado a lado: aunque se coma, baja el nivel y la etiqueta queda. */
    @Test
    void labeledJarKeepsDrinking() throws IOException {
        FramesTest.Expected jar = load("pb_jar_food");
        assertTrue(jar.eaten());
        assertTrue(Cups.innerLabel(jar.images().get(0), Drinks.findContents(jar.images().get(0), jar.empty())));
        assertFrames(jar, BiteFrames.container(jar.images().get(0), jar.empty(), true));
        assertFrames(jar, BiteFrames.container(jar.images().get(0), jar.empty(), false));
    }

    /** Fruta que toca un lado en una fila y el otro en la siguiente: no cruza de lado a lado, no es etiqueta. */
    @Test
    void staircaseFruitIsNotALabel() throws IOException {
        FramesTest.Expected cup = load("pb_cup_dessert");
        RgbaImage full = cup.images().get(0);
        Mask liquid = Drinks.findContents(full, cup.empty());
        Mask label = Drinks.splitLabel(full, liquid).label();
        Mask shell = Masks.opaque(full).minus(liquid);
        assertTrue(Masks.components(label).stream().anyMatch(c -> c.first().y() >= shell.first().y()),
                "la sintética ya no tiene una pieza de etiqueta dentro del recipiente");
        assertFalse(Cups.innerLabel(full, liquid));
    }

    /** Comida sin recipiente: primero el tazón; si no lo dibuja, el vidrio; si no, sólido. */
    @Test
    void foodLooksForTheBowlThenTheGlass() throws IOException {
        Map<String, VesselsTest.Case> cases = vessels();
        RgbaImage bowl = cases.get("pb_bowl_fit~bowl~comida").empty();
        RgbaImage bottle = cases.get("pb_glass_cup~bottle~bebida").empty();
        FramesTest.Expected cup = load("pb_cup_dessert");
        FramesTest.Expected jar = load("pb_jar_food");
        assertFrames(cup, BiteFrames.guessed(cup.images().get(0), "pb_cup_dessert", false, List.of(bowl), List.of(bottle)));
        assertFrames(jar, BiteFrames.guessed(jar.images().get(0), "pb_jar_food", false, List.of(bowl), List.of(bottle)));
        // sin la pila del vidrio se toma por un frasco propio con etiqueta; en el juego la pila
        // siempre trae la botella vanilla
        RgbaImage full = cup.images().get(0);
        for (Result r : List.of(BiteFrames.guessed(full, "pb_cup_dessert", false, List.of(bowl), List.of()),
                BiteFrames.guessed(full, "pb_cup_dessert", false, List.of(bowl)))) {
            assertEquals(Kind.CUP, r.kind());
            assertEquals(OwnGlass.LABELED, r.skipped());
        }
        // el tazón tiene prioridad sobre el vidrio: un helado claro dibuja los dos
        FramesTest.Expected ice = load("pb_bowl_ice");
        RgbaImage scoop = ice.images().get(0);
        assertTrue(BiteFrames.drawsVessel(scoop, bowl, false) && BiteFrames.drawsGlassFood(scoop, bottle),
                "la sintética ya no dibuja el tazón y el vidrio a la vez");
        assertFrames(ice, BiteFrames.guessed(scoop, "pb_bowl_ice", false, List.of(bowl), List.of(bottle)));
        RgbaImage soup = cases.get("pb_bowl_fit~bowl~comida").full();
        // una bebida nunca es una copa que se come, y no busca el tazón
        FramesTest.Expected drunk = load("pb_cup_drunk");
        assertFrames(drunk, BiteFrames.guessed(full, "pb_cup_dessert", true, List.of(bowl), List.of(bottle)));
        assertFalse(BiteFrames.guessed(soup, "pb_bowl_fit", true, List.of(bowl), List.of()).generated());
    }

    /** Los sólidos no parecen vidrio: con las dos pilas se muerden igual. */
    @Test
    void solidsStaySolidWithBothStacks() throws IOException {
        Map<String, VesselsTest.Case> cases = vessels();
        RgbaImage bowl = cases.get("pb_bowl_fit~bowl~comida").empty();
        RgbaImage bottle = cases.get("pb_glass_cup~bottle~bebida").empty();
        for (String name : List.of("pb_fruit", "pb_carrot", "pb_bar", "pb_cookie", "pb_pair", "pb_tart", "pb_black_rim")) {
            RgbaImage full = cases.get(name + "~bowl~comida").full();
            Result r = BiteFrames.guessed(full, name, false, List.of(bowl), List.of(bottle));
            assertEquals(Kind.SOLID, r.kind(), name);
            Result want = BiteFrames.solid(full, name);
            for (int i = 0; i < want.frames().size(); i++) {
                assertEquals("", FramesTest.diff(want.frames().get(i).get(0), r.frames().get(i).get(0)), name);
            }
        }
    }

    /** Comida clara o azulada y frascos casi todo tapa: con colores exactos no pasan por vidrio. */
    @Test
    void lookalikeFoodIsNotInGlass() throws IOException {
        Map<String, VesselsTest.Case> cases = vessels();
        RgbaImage bowl = cases.get("pb_bowl_fit~bowl~comida").empty();
        RgbaImage bottle = cases.get("pb_glass_cup~bottle~bebida").empty();
        for (String name : List.of("pb_pale_food", "pb_jar_lid")) {
            RgbaImage full = cases.get(name + "~bottle~vidrio").full();
            assertTrue(BiteFrames.drawsVessel(full, bottle, true), name + ": la sintética ya no se parece al vidrio");
            assertFalse(BiteFrames.drawsGlassFood(full, bottle), name);
            Result r = BiteFrames.guessed(full, name, false, List.of(bowl), List.of(bottle));
            assertEquals(Kind.SOLID, r.kind(), name);
            Result want = BiteFrames.solid(full, name);
            assertTrue(want.generated(), name + ": " + want.skipped());
            assertEquals(want.frames().size(), r.frames().size(), name);
            for (int i = 0; i < want.frames().size(); i++) {
                assertEquals("", FramesTest.diff(want.frames().get(i).get(0), r.frames().get(i).get(0)), name);
            }
        }
        // como bebida sigue valiendo el parecido de color (porcelana)
        RgbaImage pale = cases.get("pb_pale_food~bottle~vidrio").full();
        assertEquals(Kind.DRINK, BiteFrames.guessed(pale, "pb_pale_food", true, List.of(bowl), List.of(bottle)).kind());
    }

    /** El umbral de vidrio exacto: 12 de 94 px (13 %) no alcanza; desde 19 de 94 (0,2 * 94 = 18,8), sí. */
    @Test
    void exactGlassThreshold() throws IOException {
        VesselsTest.Case jar = vessels().get("pb_jar_lid~bottle~vidrio");
        RgbaImage empty = jar.empty();
        int[] glass = empty.get(Masks.opaque(empty).minus(Drinks.cork(empty, new Mask(), null)).first());
        RgbaImage step = jar.full().copy();
        Mask mask = Masks.opaque(step);
        assertEquals(94, mask.size());
        int exact = 0;
        for (Point p : mask) {
            exact += isExact(step, p, empty) ? 1 : 0;
        }
        assertEquals(12, exact);
        // se repinta de vidrio píxel a píxel: cuenta justo al llegar a 19
        for (Point p : mask) {
            assertEquals(exact >= 19, Cups.drawsGlassFood(step, empty), exact + " px de vidrio exacto");
            if (!isExact(step, p, empty)) {
                step.set(p, glass);
                exact++;
            }
        }
        assertEquals(94, exact);
        assertTrue(Cups.drawsGlassFood(step, empty));
        // el borde justo (>=): 20 de 100 px cuenta; 19 no
        RgbaImage tie = new RgbaImage(16, 16);
        for (int i = 0; i < 100; i++) {
            tie.set(new Point(3 + i % 10, 3 + i / 10), i < 20 ? glass : new int[] {204, 44, 62, 255});
        }
        assertTrue(Cups.drawsGlassFood(tie, empty));
        tie.set(new Point(3, 3), new int[] {204, 44, 62, 255});
        assertFalse(Cups.drawsGlassFood(tie, empty));
    }

    private static boolean isExact(RgbaImage full, Point p, RgbaImage empty) {
        int[] c = full.get(p);
        for (int[] g : Cups.glassPalette(empty)) {
            if (g[0] == c[0] && g[1] == c[1] && g[2] == c[2]) {
                return true;
            }
        }
        return false;
    }

    /** Botella opaca de otra forma: no se ve el líquido y se omite. Un tazón o un balde opacos siguen por el tazón. */
    @Test
    void tallOpaqueVesselIsSkipped() throws IOException {
        FramesTest.Expected juice = load("pb_opaque_juice");
        RgbaImage full = juice.images().get(0);
        assertEquals(1, juice.images().size(), "el tipo 6 no trae fotogramas");
        assertFalse(Drinks.isGlass(juice.empty()));
        assertTrue(Cups.tallVessel(juice.empty()));
        Cups.Choice choice = Cups.choose(full, juice.empty(), false);
        assertEquals(Cups.OPAQUE, choice.skipped());
        assertEquals(juice.liquid(), choice.contents());
        assertTrue(Cups.frames(full, juice.empty(), choice).isEmpty());
        for (boolean eaten : new boolean[] {false, true}) {
            Result r = BiteFrames.container(full, juice.empty(), eaten);
            assertFalse(r.generated());
            assertEquals(Cups.OPAQUE, r.skipped());
            assertTrue(r.frames().isEmpty());
        }
        // alto contra ancho: la botella mide 4 x 13; el tazón sintético no es alto
        RgbaImage bowl = vessels().get("pb_bowl_fit~bowl~comida").empty();
        assertFalse(Cups.tallVessel(bowl));
        // el borde justo: 3 de alto por 2 de ancho es 1,5 veces (no es más alto); 4 por 2, sí
        assertFalse(Cups.tallVessel(block(2, 3)));
        assertTrue(Cups.tallVessel(block(2, 4)));
        assertFalse(Cups.tallVessel(block(4, 2)));
        assertThrows(ArithmeticException.class, () -> Cups.tallVessel(new RgbaImage(16, 16)));
    }

    private static RgbaImage block(int w, int h) {
        RgbaImage out = new RgbaImage(16, 16);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.set(5 + x, 2 + y, 40, 70, 50, 255);
            }
        }
        return out;
    }

    /** Fruta al lado del vaso: los restos sueltos de hasta 2 px se borran; el reflejo de vidrio suelto se queda. */
    @Test
    void looseCrumbsAreRemoved() throws IOException {
        FramesTest.Expected side = load("pb_cup_side");
        RgbaImage full = side.images().get(0);
        assertTrue(side.eaten());
        Result r = BiteFrames.guessed(full, "pb_cup_side", false, List.of(), List.of(side.empty()));
        assertFrames(side, r);
        assertEquals(Kind.CUP, r.kind());
        // sin la limpieza, en el último frame quedarían 2 px de fruta flotando
        List<Integer> rows = Drinks.rowsOf(side.liquid());
        Mask crumbs = new Mask();
        RgbaImage last = r.frames().get(2).get(0);
        for (Point p : side.liquid()) {
            if (p.y() == rows.get(rows.size() - 1)) {
                crumbs.add(p);
                assertEquals(0, last.alpha(p.x(), p.y()), "resto en " + p);
            }
        }
        assertEquals(2, crumbs.size(), "la sintética ya no deja 2 px sueltos");
        // el vidrio exacto que queda suelto (un reflejo) no es un resto: se conserva
        List<Mask> comps = Masks.bySize(Masks.components(Masks.opaque(last)));
        assertEquals(2, comps.size());
        assertEquals(1, comps.get(1).size());
        assertTrue(isExact(full, comps.get(1).first(), side.empty()));
    }

    /** Fruta sobre la esquina del vaso: al quitarla, la pared y la base toman el color del vidrio (sello). */
    @Test
    void foodOverTheGlassSealsTheCup() throws IOException {
        FramesTest.Expected corner = load("pb_cup_corner");
        RgbaImage full = corner.images().get(0);
        Mask mask = Masks.opaque(full);
        Mask contents = Cups.cupContents(full, corner.empty());
        assertEquals(corner.liquid(), contents);
        Mask seal = Mask.of(new Point(10, 10), new Point(10, 11), new Point(9, 12));
        assertEquals(seal, Cups.cupSeal(full, contents));
        Result r = BiteFrames.guessed(full, "pb_cup_corner", false, List.of(), List.of(corner.empty()));
        assertFrames(corner, r);
        RgbaImage last = r.frames().get(2).get(0);
        for (Point p : seal) {
            // mientras la fruta sigue ahí, no cambia; al quitarla, vidrio (el de la pared, justo arriba)
            assertTrue(Arrays.equals(full.get(p), r.frames().get(0).get(0).get(p)), "frame 1 en " + p);
            assertTrue(Arrays.equals(full.get(p), r.frames().get(1).get(0).get(p)), "frame 2 en " + p);
            assertTrue(Arrays.equals(full.get(10, 9), last.get(p)), "el sello no es vidrio en " + p);
        }
        assertEquals(0, last.alpha(10, 12), "el píxel de la esquina no hace falta");
        for (int x = 10; x <= 12; x++) {
            assertEquals(255, full.alpha(x, 13), "la sintética ya no tiene la base de la fruta");
            assertEquals(0, last.alpha(x, 13), "queda comida fuera del vaso en (" + x + ", 13)");
        }
        // el vaso vacío encierra su interior (6 x 8 px menos un reflejo); sin el sello, nada
        assertEquals(47, Masks.enclosed(Masks.opaque(last), 16, 16).size());
        assertEquals(0, Masks.enclosed(mask.minus(contents), 16, 16).size());
        // sin comida sobre el vidrio, o con la copa abierta por arriba, no hay sello
        for (String name : List.of("pb_cup_side", "pb_cup_dessert", "pb_cup_crumb")) {
            FramesTest.Expected e = load(name);
            assertTrue(Cups.cupSeal(e.images().get(0), e.liquid()).isEmpty(), name);
        }
        // la fruta de esta copa reemplaza un píxel de la pared izquierda: también es sello
        FramesTest.Expected cherry = load("pb_cup_cherry");
        assertEquals(Mask.of(new Point(3, 6)), Cups.cupSeal(cherry.images().get(0), cherry.liquid()));
        assertTrue(Cups.cupSeal(full, new Mask()).isEmpty());
        assertTrue(Cups.cupSeal(full, mask).isEmpty(), "todo comida: sin vidrio no hay sello");
        // un pack 256x no congela el juego
        RgbaImage big = upscale(full, 16);
        RgbaImage bigEmpty = upscale(corner.empty(), 16);
        Result hd = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> BiteFrames.container(big, bigEmpty, true));
        assertTrue(hd.generated(), hd.skipped());
        assertEquals(Kind.CUP, hd.kind());
    }

    /** Una miga dibujada aparte no es un resto nuevo: se queda hasta el último fotograma. */
    @Test
    void looseCrumbOfTheOriginalStays() throws IOException {
        FramesTest.Expected crumb = load("pb_cup_crumb");
        RgbaImage full = crumb.images().get(0);
        Point p = new Point(13, 11);
        assertTrue(crumb.liquid().contains(p), "la miga es comida");
        assertEquals(1, Masks.bySize(Masks.components(Masks.opaque(full))).get(1).size(), "la sintética ya no tiene la miga suelta");
        Result r = BiteFrames.container(full, crumb.empty(), true);
        assertFrames(crumb, r);
        assertEquals(255, r.frames().get(0).get(0).alpha(p.x(), p.y()), "frame 1");
        assertEquals(255, r.frames().get(1).get(0).alpha(p.x(), p.y()), "frame 2");
        assertEquals(0, r.frames().get(2).get(0).alpha(p.x(), p.y()), "frame 3");
    }

    /** Pila de recursos: se detecta con la botella vanilla y se genera con la del pack (32x). */
    @Test
    void packBottleIsUsedForTheFrames() throws IOException {
        FramesTest.Expected x2 = load("pb_cup_dessert_x2");
        RgbaImage vanilla = load("pb_cup_dessert").empty();
        assertEquals(32, x2.empty().width);
        assertFrames(x2, BiteFrames.guessed(x2.images().get(0), "pb_cup_dessert", false, List.of(),
                List.of(vanilla, x2.empty())));
        // un pack con otro tono de vidrio: los fotogramas son los de su botella, no los de la vanilla
        FramesTest.Expected pack = load("pb_cup_dessert_pack");
        Result withPack = BiteFrames.guessed(pack.images().get(0), "pb_cup_dessert", false, List.of(),
                List.of(vanilla, pack.empty()));
        assertFrames(pack, withPack);
        assertFalse(FramesTest.diff(load("pb_cup_dessert").images().get(1), withPack.frames().get(0).get(0)).isEmpty(),
                "la botella del pack ya no cambia los fotogramas");
        FramesTest.Expected tea = load("pb_top_cup_x2");
        assertFrames(tea, BiteFrames.guessed(tea.images().get(0), "pb_top_cup", true, List.of(),
                List.of(load("pb_top_cup").empty(), tea.empty())));
    }

    /** Taza vista desde arriba: solo si la pieza saturada es ancha y está dentro de la taza. */
    @Test
    void topViewNeedsAWideSurfaceInsideTheCup() throws IOException {
        for (String name : List.of("pb_top_cup", "pb_top_narrow", "pb_top_deep", "pb_top_garnish", "pb_garnish_cork")) {
            FramesTest.Expected e = load(name);
            RgbaImage full = e.images().get(0);
            Mask surface = Cups.topViewSurface(full, Drinks.findContents(full, e.empty()));
            if (name.equals("pb_top_cup")) {
                assertNotNull(surface, name);
                assertEquals(e.liquid(), surface, name);
            } else {
                assertNull(surface, name);
            }
            assertFrames(e, BiteFrames.guessed(full, name, true, List.of(), List.of(e.empty())));
        }
    }

    /** Lo que lanza adentro, {@link BiteFrames} lo convierte en omitir. */
    @Test
    void hostileInputs() throws IOException {
        FramesTest.Expected cup = load("pb_cup_dessert");
        RgbaImage full = cup.images().get(0);
        assertThrows(ArithmeticException.class, () -> Cups.cupFrames(full, new Mask(), cup.empty()));
        assertThrows(ArithmeticException.class, () -> Cups.cupContents(full, new RgbaImage(16, 16)));
        assertThrows(ArithmeticException.class, () -> Cups.topViewSurface(full, new Mask()));
        assertFalse(BiteFrames.container(full, new RgbaImage(16, 16), true).generated());
        assertFalse(BiteFrames.container(new RgbaImage(16, 16), cup.empty(), true).generated());
        // todo el ítem del color exacto del vidrio: no queda comida que quitar
        RgbaImage glassy = new RgbaImage(16, 16);
        for (Point p : Masks.opaque(full)) {
            glassy.set(p, cup.empty().get(6, 3));
        }
        assertTrue(Cups.cupContents(glassy, cup.empty()).isEmpty());
    }

    /** Un pack 256x no congela el juego: copa y taza en menos de 3 s. */
    @Test
    void hdCupsAreFast() throws IOException {
        FramesTest.Expected cup = load("pb_cup_dessert");
        RgbaImage full = upscale(cup.images().get(0), 16);
        RgbaImage empty = upscale(cup.empty(), 16);
        assertEquals(256, full.width);
        Result r = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> BiteFrames.container(full, empty, true));
        assertTrue(r.generated(), r.skipped());
        assertEquals(Kind.CUP, r.kind());
        FramesTest.Expected tea = load("pb_top_cup");
        RgbaImage bigTea = upscale(tea.images().get(0), 16);
        Result t = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BiteFrames.guessed(bigTea, "pb_top_cup", true, List.of(), List.of(tea.empty(), empty)));
        assertTrue(t.generated(), t.skipped());
        assertEquals(Kind.BOWL, t.kind());
    }
}
