package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lo que sale de la caché es lo que se generaría; la clave cubre todo; lo dañado nunca rompe; sin
 * disco, se genera igual.
 */
class FrameCacheTest {
    /**
     * Huella de los fotogramas de referencia de las texturas sintéticas. Si cambian, hay que
     * subir {@link FrameCache#ALGORITHM_VERSION} (si no, los jugadores verían fotogramas viejos)
     * y poner aquí la huella nueva, que sale en el mensaje.
     */
    private static final int DIGEST_VERSION = 6;
    private static final String EXPECTED_DIGEST = "e653df3902ef5b83554adc14c294870c559817960ae61408b5583e560e586bab";

    @TempDir
    Path dir;

    private static FramesTest.Expected load(String name) throws IOException {
        try {
            return FramesTest.read(Paths.get(FrameCacheTest.class.getResource("/frames/" + name + ".bin").toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    @Test
    void versionIsBumpedWhenTheAlgorithmChanges() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path frames = Paths.get(FrameCacheTest.class.getResource("/frames").toURI());
        List<Path> files = new ArrayList<>(FramesTest.binFiles(frames));
        // por nombre, no por Path: en Windows las rutas se ordenan sin distinguir mayúsculas
        files.sort(java.util.Comparator.comparing(f -> f.getFileName().toString()));
        files.add(frames.resolveSibling("vessels.bin"));
        for (Path f : files) {
            digest.update(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(f));
        }
        String now = hex(digest.digest());
        assertEquals(EXPECTED_DIGEST, now, "cambiaron los fotogramas esperados: subir FrameCache.ALGORITHM_VERSION "
                + "y actualizar EXPECTED_DIGEST y DIGEST_VERSION en esta prueba");
        assertEquals(DIGEST_VERSION, FrameCache.ALGORITHM_VERSION,
                "ALGORITHM_VERSION cambió: actualizar DIGEST_VERSION junto con la huella");
    }

    private static void assertSame(Result want, Result got, String what) {
        assertEquals(want.kind(), got.kind(), what);
        assertEquals(want.skipped(), got.skipped(), what);
        assertEquals(want.frames().size(), got.frames().size(), what);
        for (int i = 0; i < want.frames().size(); i++) {
            assertEquals(want.frames().get(i).size(), got.frames().get(i).size(), what);
            for (int j = 0; j < want.frames().get(i).size(); j++) {
                RgbaImage a = want.frames().get(i).get(j);
                RgbaImage b = got.frames().get(i).get(j);
                assertEquals(a.width + "x" + a.height, b.width + "x" + b.height, what);
                assertArrayEquals(a.rgba(), b.rgba(), what + " frame " + (i + 1) + " capa " + j);
            }
        }
    }

    /** Los seis puntos de entrada, con casos que generan y casos que se omiten. */
    private static Map<String, Callable<Result>[]> calls(FrameCache cache) throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        FramesTest.Expected juice = load("pb_juice");
        FramesTest.Expected bowl = load("pb_bowl_fit");
        FramesTest.Expected cup = load("pb_cup_dessert");
        FramesTest.Expected potion = load("pb_potion");
        RgbaImage big = new RgbaImage(257, 257);
        Map<String, Callable<Result>[]> out = new LinkedHashMap<>();
        put(out, "sólido", () -> BiteFrames.solid(fruit, "pb_fruit"), () -> cache.solid(fruit, "pb_fruit"));
        put(out, "sólido omitido", () -> BiteFrames.solid(big, "big"), () -> cache.solid(big, "big"));
        put(out, "bebida", () -> BiteFrames.container(juice.images().get(0), juice.empty(), false),
                () -> cache.container(juice.images().get(0), juice.empty(), false));
        put(out, "tazón", () -> BiteFrames.container(bowl.images().get(0), bowl.empty(), false),
                () -> cache.container(bowl.images().get(0), bowl.empty(), false));
        put(out, "copa", () -> BiteFrames.container(cup.images().get(0), cup.empty(), true),
                () -> cache.container(cup.images().get(0), cup.empty(), true));
        RgbaImage clear = new RgbaImage(16, 16);
        put(out, "sin contenido", () -> BiteFrames.container(clear, juice.empty(), false),
                () -> cache.container(clear, juice.empty(), false));
        put(out, "adivinado", () -> BiteFrames.guessed(cup.images().get(0), "cup", false, List.of(bowl.empty()),
                List.of(cup.empty())), () -> cache.guessed(cup.images().get(0), "cup", false, List.of(bowl.empty()),
                List.of(cup.empty())));
        RgbaImage glass = load("pb_own_fruit").images().get(0);
        put(out, "envase propio", () -> BiteFrames.guessed(glass, "glass", false, List.of(bowl.empty()),
                List.of(cup.empty())), () -> cache.guessed(glass, "glass", false, List.of(bowl.empty()),
                List.of(cup.empty())));
        RgbaImage jar = load("pb_own_label").images().get(0);
        put(out, "frasco con etiqueta", () -> BiteFrames.guessed(jar, "jar", false, List.of(bowl.empty()),
                List.of(cup.empty())), () -> cache.guessed(jar, "jar", false, List.of(bowl.empty()),
                List.of(cup.empty())));
        FramesTest.Expected ownBowl = load("pb_own_bowl");
        put(out, "tazón propio", () -> BiteFrames.guessed(ownBowl.images().get(0), "bowl", false,
                List.of(ownBowl.empty()), List.of()), () -> cache.guessed(ownBowl.images().get(0), "bowl", false,
                List.of(ownBowl.empty()), List.of()));
        put(out, "bebida sin recipiente", () -> BiteFrames.guessed(fruit, "x", true, List.of(), List.of()),
                () -> cache.guessed(fruit, "x", true, List.of(), List.of()));
        RgbaImage mug = load("pb_hint_mug").images().get(0);
        put(out, "taza con pista", () -> BiteFrames.topCup(mug), () -> cache.topCup(mug));
        RgbaImage rings = load("pb_hint_rim3").images().get(0);
        put(out, "taza sin superficie", () -> BiteFrames.topCup(rings), () -> cache.topCup(rings));
        put(out, "poción", () -> BiteFrames.potion(potion.bottle(), potion.overlay()),
                () -> cache.potion(potion.bottle(), potion.overlay()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Callable<Result>[]> out, String name, Callable<Result> direct,
            Callable<Result> cached) {
        out.put(name, new Callable[] {direct, cached});
    }

    @Test
    void cachedResultsAreTheGeneratedOnes() throws Exception {
        FrameCache cache = new FrameCache(dir, "1.0");
        Map<String, Callable<Result>[]> calls = calls(cache);
        Set<Kind> kinds = new HashSet<>();
        int skipped = 0;
        for (Map.Entry<String, Callable<Result>[]> e : calls.entrySet()) {
            Result want = e.getValue()[0].call();
            kinds.add(want.generated() ? want.kind() : null);
            skipped += want.generated() ? 0 : 1;
            assertSame(want, e.getValue()[1].call(), e.getKey() + " (generado)");
        }
        assertEquals(Set.of(Kind.values()).size() + 1, kinds.size(), "falta algún camino: " + kinds);
        assertEquals(5, skipped);
        assertEquals(0, cache.hits());
        assertEquals(calls.size(), cache.misses());
        // sin guardar, otra sesión no encuentra nada
        FrameCache unsaved = new FrameCache(dir, "1.0");
        unsaved.solid(load("pb_fruit").images().get(0), "pb_fruit");
        assertEquals(0, unsaved.hits());
        assertEquals(0, cache.save(DAY));
        // otra sesión (otra instancia) sobre la misma carpeta: todo sale del disco, idéntico
        FrameCache again = new FrameCache(dir, "1.0");
        for (Map.Entry<String, Callable<Result>[]> e : calls(again).entrySet()) {
            assertSame(e.getValue()[0].call(), e.getValue()[1].call(), e.getKey() + " (de la caché)");
        }
        assertEquals(calls.size(), again.hits());
        assertEquals(0, again.misses());
        assertEquals(0, again.errors(), again.lastError());
        assertEquals(0, again.generationMillis());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(FrameCache.FILE), files.map(f -> f.getFileName().toString()).toList());
        }
        // una carga sin cambios no reescribe el archivo
        Path pack = dir.resolve(FrameCache.FILE);
        Files.setLastModifiedTime(pack, FileTime.fromMillis(1_000_000_000_000L));
        assertEquals(0, again.save(DAY));
        assertEquals(1_000_000_000_000L, Files.getLastModifiedTime(pack).toMillis(), "se reescribió sin cambios");
    }

    private static final long DAY = 24L * 60 * 60 * 1000;

    /** Todo lo que decide el resultado está en la clave: cambiar una sola cosa da otra entrada. */
    @Test
    void keyCoversEverythingThatDecidesTheResult() throws IOException {
        RgbaImage a = load("pb_fruit").images().get(0);
        RgbaImage b = a.copy();
        int[] c = b.get(7, 9);
        b.set(7, 9, c[0] ^ 1, c[1], c[2], c[3]);                 // un bit de un píxel
        RgbaImage wide = new RgbaImage(32, 8, a.rgba().clone());   // los mismos bytes con otras medidas
        FrameCache x = new FrameCache(dir, "1.0");
        FrameCache salted = new FrameCache(dir, "1.1");
        List<String> keys = List.of(
                x.key("solid").text("apple").image(a).hex(),
                x.key("solid").text("apple").image(b).hex(),
                x.key("solid").text("apple").image(wide).hex(),
                x.key("solid").text("pear").image(a).hex(),
                x.key("solid").text(null).image(a).hex(),
                x.key("solid").text("").image(a).hex(),
                salted.key("solid").text("apple").image(a).hex(),
                x.key("container").flag(false).image(a).image(b).hex(),
                x.key("container").flag(true).image(a).image(b).hex(),
                x.key("container").flag(false).image(b).image(a).hex(),
                x.key("topCup").image(a).hex(),
                x.key("topCup").image(b).hex(),
                x.key("potion").image(a).image(b).hex(),
                x.key("potion").image(b).image(a).hex(),
                x.key("guessed").text("apple").flag(false).image(a).images(List.of(a)).images(List.of(b)).hex(),
                x.key("guessed").text("apple").flag(true).image(a).images(List.of(a)).images(List.of(b)).hex(),
                x.key("guessed").text("apple").flag(false).image(a).images(List.of(a, b)).images(List.of()).hex(),
                x.key("guessed").text("apple").flag(false).image(a).images(List.of()).images(List.of(a, b)).hex(),
                x.key("guessed").text("apple").flag(false).image(a).images(List.of(b)).images(List.of(a)).hex(),
                x.key("guessed").text("apple").flag(false).image(a).images(List.of(b, a)).images(List.of()).hex(),
                x.key("guessedTemplate").text("apple").flag(false).image(a).images(List.of()).images(List.of())
                        .images(List.of(b)).hex(),
                x.key("guessedTemplate").text("apple").flag(false).image(a).images(List.of()).images(List.of())
                        .images(List.of()).hex(),
                // los campos llevan su largo: "ab" + "c" no es "a" + "bc"
                x.key("solid").text("ab").text("c").hex(),
                x.key("solid").text("a").text("bc").hex());
        assertEquals(keys.size(), new HashSet<>(keys).size(), "claves repetidas: " + keys);
        assertEquals(x.key("solid").text("apple").image(a).hex(), x.key("solid").text("apple").image(a.copy()).hex());
        assertTrue(keys.get(0).matches("[0-9a-f]{64}"), keys.get(0));
        // la versión del algoritmo va primero en toda clave: al subirla, ninguna entrada vieja sirve
        assertEquals(new FrameCache.Key().number(FrameCache.ALGORITHM_VERSION).text("1.0").text("solid").hex(),
                x.key("solid").hex());
        assertFalse(new FrameCache.Key().number(FrameCache.ALGORITHM_VERSION + 1).text("1.0").text("solid").hex()
                .equals(x.key("solid").hex()));
    }

    /** Tazón por plantilla: la clave lleva solo las texturas de la plantilla. */
    @Test
    void templateKeyUsesOnlyTheGroup() throws IOException {
        FramesTest.Expected bowl = load("pb_tpl_green");
        RgbaImage full = bowl.images().get(0);
        RgbaImage other = load("pb_fruit").images().get(0);
        List<RgbaImage> group = bowl.group();
        List<RgbaImage> withOther = new java.util.ArrayList<>(group);
        withOther.add(other);
        FrameCache cache = new FrameCache(dir, "1.0");
        assertSame(BiteFrames.guessed(full, "g", false, List.of(), List.of(), group),
                cache.guessed(full, "g", false, List.of(), List.of(), group), "con la plantilla");
        assertEquals(Kind.BOWL, cache.guessed(full, "g", false, List.of(), List.of(), withOther).kind());
        assertEquals(1, cache.misses(), "otra comida que no es de la plantilla no cambia la clave");
        assertEquals(Kind.SOLID, cache.guessed(full, "g", false, List.of(), List.of(), List.of(other)).kind());
        assertEquals(Kind.SOLID, cache.guessed(full, "g", false, List.of(), List.of()).kind());
    }

    /** Los puntos de entrada usan todos sus argumentos en la clave. */
    @Test
    void entryPointsDoNotShareEntries() throws IOException {
        FramesTest.Expected cup = load("pb_cup_dessert");
        RgbaImage full = cup.images().get(0);
        RgbaImage fruit = load("pb_fruit").images().get(0);
        FrameCache cache = new FrameCache(dir, "1.0");
        // la misma copa comida y bebida: caminos distintos, no puede salir una por la otra
        assertEquals(Kind.CUP, cache.container(full, cup.empty(), true).kind());
        assertEquals(Kind.DRINK, cache.container(full, cup.empty(), false).kind());
        assertEquals(Kind.CUP, cache.container(full, cup.empty(), true).kind());
        // el nombre es la semilla de las mordidas
        assertSame(BiteFrames.solid(fruit, "a"), cache.solid(fruit, "a"), "a");
        assertSame(BiteFrames.solid(fruit, "b"), cache.solid(fruit, "b"), "b");
        assertSame(BiteFrames.solid(fruit, "a"), cache.solid(fruit, "a"), "a otra vez");
        // comida y bebida sin recipiente, y cada pila en su lugar
        assertSame(BiteFrames.guessed(full, "c", false, List.of(), List.of(cup.empty())),
                cache.guessed(full, "c", false, List.of(), List.of(cup.empty())), "comida en vidrio");
        assertSame(BiteFrames.guessed(full, "c", true, List.of(), List.of(cup.empty())),
                cache.guessed(full, "c", true, List.of(), List.of(cup.empty())), "bebida");
        assertSame(BiteFrames.guessed(full, "c", false, List.of(cup.empty()), List.of()),
                cache.guessed(full, "c", false, List.of(cup.empty()), List.of()), "vidrio en la pila del tazón");
        // sin la botella en la pila se muerde: no puede salir la copa guardada
        assertSame(BiteFrames.guessed(full, "c", false, List.of(), List.of()),
                cache.guessed(full, "c", false, List.of(), List.of()), "sin pilas");
        // la misma textura con la pista top_cup y mordida: no puede salir una por la otra
        RgbaImage mug = load("pb_hint_mug").images().get(0);
        assertSame(BiteFrames.topCup(mug), cache.topCup(mug), "taza con pista");
        assertSame(BiteFrames.solid(mug, "mug"), cache.solid(mug, "mug"), "la taza, mordida");
        assertSame(BiteFrames.topCup(mug), cache.topCup(mug), "taza con pista otra vez");
        // la misma botella con otro overlay es otra poción
        FramesTest.Expected potion = load("pb_potion");
        RgbaImage overlay = potion.overlay().copy();
        Point inside = Masks.opaque(overlay).first();
        overlay.set(inside.x(), inside.y(), 0, 0, 0, 0);
        assertSame(BiteFrames.potion(potion.bottle(), potion.overlay()), cache.potion(potion.bottle(), potion.overlay()), "poción");
        assertSame(BiteFrames.potion(potion.bottle(), overlay), cache.potion(potion.bottle(), overlay), "otro overlay");
        assertEquals(3, cache.hits());
        // otra versión del mod no lee las entradas de esta
        cache.save(DAY);
        assertEquals(1, new FrameCache(dir, "1.0").solid(fruit, "a").generated() ? 1 : 0);
        FrameCache other = new FrameCache(dir, "2.0");
        other.solid(fruit, "a");
        assertEquals(0, other.hits());
    }

    /** Una entrada dañada no sirve: cualquier byte cambiado o cualquier corte se detecta. */
    @Test
    void damagedEntriesAreRejected() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        byte[] good = FrameCache.encode(BiteFrames.solid(fruit, "pb_fruit"));
        assertSame(BiteFrames.solid(fruit, "pb_fruit"), FrameCache.decode(good), "ida y vuelta");
        for (int i = 0; i < good.length; i++) {
            byte[] bad = good.clone();
            bad[i] ^= 0x40;
            assertNull(FrameCache.decode(bad), "byte " + i + " cambiado");
        }
        for (int n = 0; n < good.length; n++) {
            assertNull(FrameCache.decode(Arrays.copyOf(good, n)), "cortado en " + n);
        }
        assertNull(FrameCache.decode(Arrays.copyOf(good, good.length + 1)), "un byte de más");
    }

    private static final List<String> NAMES = List.of("a", "b", "c", "d", "e");

    /** Caché guardada con 5 sólidos; devuelve los bytes del archivo. */
    private byte[] savedPack(RgbaImage fruit) throws IOException {
        FrameCache cache = new FrameCache(dir, "");
        for (String name : NAMES) {
            cache.solid(fruit, name);
        }
        cache.save(DAY);
        return Files.readAllBytes(dir.resolve(FrameCache.FILE));
    }

    /** Con el archivo dado, todo sigue dando lo que se generaría; devuelve cuántos salieron de la caché. */
    private int hitsWith(byte[] pack, RgbaImage fruit, List<Result> want, String what) throws IOException {
        Files.write(dir.resolve(FrameCache.FILE), pack);
        FrameCache cache = new FrameCache(dir, "");
        for (int i = 0; i < NAMES.size(); i++) {
            int n = i;
            assertSame(want.get(i), assertDoesNotThrow(() -> cache.solid(fruit, NAMES.get(n)), what), what);
        }
        assertDoesNotThrow(() -> cache.save(DAY), what);
        return cache.hits();
    }

    /** Archivo dañado en cualquier byte o cortado en cualquier punto: nunca lanza ni devuelve otros píxeles. */
    @Test
    void damagedPackNeverBreaks() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        List<Result> want = new ArrayList<>();
        for (String name : NAMES) {
            want.add(BiteFrames.solid(fruit, name));
        }
        byte[] good = savedPack(fruit);
        assertEquals(NAMES.size(), hitsWith(good, fruit, want, "intacto"));
        int total = 0;
        for (int i = 0; i < good.length; i += 7) {
            byte[] bad = good.clone();
            bad[i] ^= 0x10;
            total += hitsWith(bad, fruit, want, "byte " + i + " cambiado");
        }
        int tries = (good.length + 6) / 7;
        // un byte dañado dentro de una entrada solo pierde esa entrada
        assertTrue(total > tries * (NAMES.size() - 2), "se aprovechó poco: " + total + " de " + tries * NAMES.size());
        int last = -1;
        for (int n = 0; n < good.length; n += 11) {
            int hits = hitsWith(Arrays.copyOf(good, n), fruit, want, "cortado en " + n);
            assertTrue(hits >= last, "cortado en " + n + ": " + hits + " aciertos, antes " + last);
            last = hits;
        }
        assertEquals(NAMES.size() - 1, last, "cortado cerca del final, se pierde solo la última entrada");
        // después de regenerar, el archivo vuelve a servir entero
        hitsWith(Arrays.copyOf(good, good.length / 2), fruit, want, "a la mitad");
        assertEquals(NAMES.size(), hitsWith(Files.readAllBytes(dir.resolve(FrameCache.FILE)), fruit, want, "reparado"));
    }

    private static byte[] pack(int count, Object... fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {'P', 'B', 'P', '1'});
        out.writeInt(count);
        for (Object f : fields) {
            if (f instanceof byte[] b) {
                out.write(b);
            } else if (f instanceof Long l) {
                out.writeLong(l);
            } else {
                out.writeInt((Integer) f);
            }
        }
        return bytes.toByteArray();
    }

    /** Archivo armado a propósito: cantidades y largos falsos no reservan memoria ni cuelgan la carga. */
    @Test
    void forgedPackIsHarmless() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        Result want = BiteFrames.solid(fruit, "a");
        byte[] entry = FrameCache.encode(want);
        byte[] key = new byte[32];
        String hex = new FrameCache(dir, "").key("solid").text("a").image(fruit).hex();
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        long now = System.currentTimeMillis();
        Map<String, byte[]> packs = new LinkedHashMap<>();
        packs.put("cantidad enorme", pack(Integer.MAX_VALUE, key, now, entry.length, entry));
        packs.put("cantidad negativa", pack(-5, key, now, entry.length, entry));
        packs.put("largo enorme", pack(1, key, now, Integer.MAX_VALUE, entry));
        packs.put("largo negativo", pack(1, key, now, -1, entry));
        packs.put("largo mayor que el tope", pack(1, key, now, FrameCache.MAX_ENTRY_BYTES + 1, entry));
        packs.put("otra cabecera", new byte[] {'P', 'B', 'P', '2', 0, 0, 0, 0});
        packs.put("vacío", new byte[0]);
        byte[] other = FrameCache.encode(BiteFrames.solid(fruit, "zz"));
        packs.put("la entrada de otro", pack(1, key, now, other.length, other));
        packs.put("la entrada de otro con largo falso", pack(1, key, now, entry.length, other));
        for (Map.Entry<String, byte[]> e : packs.entrySet()) {
            Files.write(dir.resolve(FrameCache.FILE), e.getValue());
            FrameCache cache = new FrameCache(dir, "");
            Result r = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> cache.solid(fruit, "a"), e.getKey());
            if (e.getKey().equals("la entrada de otro")) {
                // con CRC válido y bajo la clave de "a" no se puede notar: quien escribe en la
                // carpeta del juego ya puede cambiar cualquier textura
                assertEquals(1, cache.hits(), e.getKey());
                continue;
            }
            assertSame(want, r, e.getKey());
            assertEquals(e.getKey().equals("cantidad enorme") ? 1 : 0, cache.hits(), e.getKey());
        }
        // una fecha de uso futura no hace eterna la entrada: al cargar se recorta a ahora
        long[] clock = {now};
        Files.write(dir.resolve(FrameCache.FILE), pack(1, key, Long.MAX_VALUE, entry.length, entry));
        FrameCache cache = new FrameCache(dir, "", () -> clock[0]);
        assertSame(want, cache.solid(fruit, "a"), "fecha futura");
        assertEquals(1, cache.hits());
        clock[0] = now + 40 * DAY;
        assertEquals(1, cache.save(30 * DAY), "la entrada con fecha futura no se quitó");
    }

    /** Un archivo más grande que el tope no se carga en memoria; se reemplaza al guardar. */
    @Test
    void oversizedPackIsNotLoaded() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        Path file = dir.resolve(FrameCache.FILE);
        byte[] pack = savedPack(fruit);
        // tope chico para no escribir 256 MB en cada corrida: justo el tamaño vale, un byte más no
        FrameCache fits = new FrameCache(dir, "", System::currentTimeMillis, pack.length);
        fits.solid(fruit, "a");
        assertEquals(1, fits.hits());
        Files.write(file, Arrays.copyOf(pack, pack.length + 1));
        FrameCache cache = new FrameCache(dir, "", System::currentTimeMillis, pack.length);
        assertSame(BiteFrames.solid(fruit, "a"), cache.solid(fruit, "a"), "con archivo más grande que el tope");
        assertEquals(0, cache.hits());
        cache.save(DAY);
        assertTrue(Files.size(file) < pack.length, "no se reemplazó: " + Files.size(file));
        assertTrue(FrameCache.MAX_PACK_BYTES >= 64L << 20, "el tope real es demasiado chico para un pack HD");
    }

    /** Entrada con CRC correcto y los campos dados: quien escribe en la carpeta puede armarla. */
    private static byte[] forged(int kind, int generated, int frames, int layers, int w, int h, byte[] rawPixels,
            Integer packedLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {'P', 'B', 'C', '1'});
        out.writeByte(kind);
        out.writeByte(generated);
        out.writeByte(frames);
        out.writeByte(layers);
        out.writeShort(w);
        out.writeShort(h);
        Deflater deflater = new Deflater();
        deflater.setInput(rawPixels);
        deflater.finish();
        byte[] buf = new byte[rawPixels.length + 1024];
        int n = deflater.deflate(buf);
        assertTrue(deflater.finished());
        deflater.end();
        out.writeInt(packedLength != null ? packedLength : n);
        out.write(buf, 0, n);
        return withCrc(bytes.toByteArray());
    }

    private static byte[] withCrc(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] out = Arrays.copyOf(body, body.length + 4);
        int v = (int) crc.getValue();
        out[body.length] = (byte) (v >>> 24);
        out[body.length + 1] = (byte) (v >>> 16);
        out[body.length + 2] = (byte) (v >>> 8);
        out[body.length + 3] = (byte) v;
        return out;
    }

    /** Entradas armadas a propósito, con CRC válido: medidas, cantidades y largos imposibles se rechazan. */
    @Test
    void forgedEntriesAreRejected() throws IOException {
        byte[] px = new byte[3 * 16 * 16 * 4];
        int solid = Kind.SOLID.ordinal();
        int potion = Kind.POTION.ordinal();
        Result ok = FrameCache.decode(forged(solid, 1, 3, 1, 16, 16, px, null));
        assertNotNull(ok, "la entrada bien armada sí vale");
        assertEquals(3, ok.frames().size());
        assertNotNull(FrameCache.decode(forged(potion, 1, 3, 2, 16, 16, new byte[2 * px.length], null)), "poción");
        assertNotNull(FrameCache.decode(forged(solid, 1, 3, 1, 256, 256, new byte[3 * 256 * 256 * 4], null)), "256x vale");
        assertNull(FrameCache.decode(forged(99, 1, 3, 1, 16, 16, px, null)), "tipo que no existe");
        assertNull(FrameCache.decode(forged(solid, 2, 3, 1, 16, 16, px, null)), "marca de generado inválida");
        assertNull(FrameCache.decode(forged(solid, 1, 2, 1, 16, 16, new byte[2 * 16 * 16 * 4], null)), "2 fotogramas");
        assertNull(FrameCache.decode(forged(solid, 1, 200, 1, 16, 16, new byte[200 * 16 * 16 * 4], null)), "200 fotogramas");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 2, 16, 16, new byte[2 * px.length], null)), "sólido con 2 capas");
        assertNull(FrameCache.decode(forged(potion, 1, 3, 1, 16, 16, px, null)), "poción con 1 capa");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 0, 16, new byte[0], null)), "ancho 0");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 16, 257, new byte[3 * 16 * 257 * 4], null)), "más alto que el tope");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 257, 16, new byte[3 * 257 * 16 * 4], null)), "más ancho que el tope");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 16, 16, px, 5)), "largo comprimido falso");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 16, 16, px, -1)), "largo comprimido negativo");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 16, 16, new byte[px.length - 1], null)), "faltan píxeles");
        assertNull(FrameCache.decode(forged(solid, 1, 3, 1, 16, 16, new byte[px.length + 1], null)), "sobran píxeles");
        // medidas enormes: no se reserva memoria para 65535 x 65535
        assertNull(assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> FrameCache.decode(forged(solid, 1, 3, 1, 65535, 65535, px, null))), "65535 x 65535");
        // bomba de compresión: 64 MB de ceros en unos pocos KB; no se descomprime de más
        byte[] bomb = forged(solid, 1, 3, 1, 16, 16, new byte[64 << 20], null);
        assertTrue(bomb.length < 1 << 20, "la bomba pesa " + bomb.length);
        assertNull(assertTimeoutPreemptively(Duration.ofMillis(500), () -> FrameCache.decode(bomb)), "bomba");
        // omitido sin motivo, o con bytes de más
        assertNull(FrameCache.decode(withCrc(new byte[] {'P', 'B', 'C', '1', 0, 0, 0, 0})), "omitido sin motivo");
        Result skipped = FrameCache.decode(withCrc(new byte[] {'P', 'B', 'C', '1', 0, 0, 0, 1, 'x'}));
        assertNotNull(skipped);
        assertEquals("x", skipped.skipped());
        assertNull(FrameCache.decode(withCrc(new byte[] {'P', 'B', 'C', '1', 0, 0, 0, 1, 'x', 'y'})), "motivo con cola");
        assertNull(FrameCache.decode(withCrc(new byte[] {'P', 'B', 'C', '2', 0, 0, 0, 1, 'x'})), "otra versión del formato");
        assertNull(FrameCache.decode(new byte[0]));
    }

    /** Si el disco falla, se genera igual y queda registrado; nunca lanza. */
    @Test
    void brokenDiskStillGenerates() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        Path notADir = dir.resolve("ocupado");
        Files.write(notADir, new byte[] {1});
        FrameCache cache = new FrameCache(notADir, "");
        Result r = assertDoesNotThrow(() -> cache.solid(fruit, "pb_fruit"));
        assertSame(BiteFrames.solid(fruit, "pb_fruit"), r, "disco roto");
        assertSame(BiteFrames.solid(fruit, "pb_fruit"), cache.solid(fruit, "pb_fruit"), "disco roto, otra vez");
        assertEquals(1, cache.misses());
        assertEquals(0, assertDoesNotThrow(() -> cache.save(DAY)));
        assertEquals(1, cache.errors(), cache.lastError());
        assertTrue(cache.lastError().contains(FrameCache.FILE), cache.lastError());
        assertEquals(1, Files.size(notADir), "se tocó el archivo que ocupaba el lugar");
        // el fallo no se olvida: el siguiente save() lo intenta de nuevo
        assertDoesNotThrow(() -> cache.save(DAY));
        assertEquals(2, cache.errors());
        // sin carpeta: no toca el disco ni guarda en memoria
        FrameCache none = new FrameCache(null, "");
        assertSame(BiteFrames.solid(fruit, "pb_fruit"), none.solid(fruit, "pb_fruit"), "sin carpeta");
        none.solid(fruit, "pb_fruit");
        assertEquals(0, none.hits());
        assertEquals(0, none.save(0));
        assertEquals(0, none.errors());
    }

    @Test
    void saveDropsEntriesNotUsedForLong() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        long[] clock = {1_700_000_000_000L};
        FrameCache first = new FrameCache(dir, "", () -> clock[0]);
        first.solid(fruit, "viejo");
        first.solid(fruit, "usado");
        assertEquals(0, first.save(30 * DAY));
        // 40 días después: una se usa, otra no, y se genera una nueva
        clock[0] += 40 * DAY;
        Path orphan = dir.resolve(FrameCache.FILE + ".123-1.tmp");
        Files.write(orphan, new byte[] {1});
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(System.currentTimeMillis() - 2 * DAY));
        Path freshTmp = dir.resolve(FrameCache.FILE + ".456-1.tmp");
        Files.write(freshTmp, new byte[] {1});
        Path foreign = dir.resolve("notas.txt");
        Files.write(foreign, new byte[] {1});
        Files.setLastModifiedTime(foreign, FileTime.fromMillis(System.currentTimeMillis() - 400 * DAY));
        FrameCache later = new FrameCache(dir, "", () -> clock[0]);
        later.solid(fruit, "usado");
        later.solid(fruit, "nuevo");
        assertEquals(1, later.hits());
        assertEquals(1, later.save(30 * DAY), "debía quitar solo la que no se usó");
        assertFalse(Files.exists(orphan), "temporal huérfano");
        assertTrue(Files.exists(freshTmp), "temporal de otra instancia en uso");
        assertTrue(Files.exists(foreign), "archivo ajeno");
        FrameCache check = new FrameCache(dir, "", () -> clock[0]);
        check.solid(fruit, "usado");
        check.solid(fruit, "nuevo");
        assertEquals(2, check.hits());
        check.solid(fruit, "viejo");
        assertEquals(1, check.misses());
        // usar una entrada dentro del mismo día no obliga a reescribir
        assertEquals(0, later.errors(), later.lastError());
    }

    /** Los proveedores de sprites corren en paralelo: mismas y distintas claves a la vez. */
    @Test
    void parallelUseIsSafe() throws Exception {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        Result[] want = new Result[4];
        for (int i = 0; i < want.length; i++) {
            want[i] = BiteFrames.solid(fruit, "p" + i);
        }
        FrameCache cache = new FrameCache(dir, "");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 3; round++) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Result>> futures = new ArrayList<>();
                for (int t = 0; t < 64; t++) {
                    int id = t % want.length;
                    futures.add(pool.submit(() -> {
                        go.await();
                        Result r = cache.solid(fruit, "p" + id);
                        cache.save(DAY);   // guardar mientras otros hilos leen y escriben
                        return r;
                    }));
                }
                go.countDown();
                for (int t = 0; t < futures.size(); t++) {
                    assertSame(want[t % want.length], futures.get(t).get(), "hilo " + t + " ronda " + round);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, cache.errors(), cache.lastError());
        assertEquals(64 * 3, cache.hits() + cache.misses());
        assertTrue(cache.hits() >= 64 * 2, "aciertos: " + cache.hits());
        cache.save(DAY);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(FrameCache.FILE), files.map(f -> f.getFileName().toString()).toList());
        }
        FrameCache again = new FrameCache(dir, "");
        for (int i = 0; i < want.length; i++) {
            assertSame(want[i], again.solid(fruit, "p" + i), "final " + i);
        }
        assertEquals(want.length, again.hits());
    }

    /** Dos juegos abiertos con la misma carpeta: el que guarda último no borra lo del otro. */
    @Test
    void twoInstancesKeepEachOthersEntries() throws IOException {
        RgbaImage fruit = load("pb_fruit").images().get(0);
        FrameCache one = new FrameCache(dir, "");
        FrameCache two = new FrameCache(dir, "");
        one.solid(fruit, "uno");
        two.solid(fruit, "dos");
        one.save(DAY);
        two.save(DAY);
        FrameCache check = new FrameCache(dir, "");
        check.solid(fruit, "uno");
        check.solid(fruit, "dos");
        assertEquals(2, check.hits());
    }

    /** Más de 1000 comidas: leerlas de la caché tarda mucho menos que generarlas. */
    @Test
    void thousandEntriesReadFasterThanGenerated() throws IOException {
        List<RgbaImage> textures = new ArrayList<>();
        for (String name : List.of("pb_fruit", "pb_carrot", "pb_cookie", "pb_pair", "pb_tart", "pb_pie32")) {
            textures.add(load(name).images().get(0));
        }
        int count = 1008;
        FrameCache cold = new FrameCache(dir, "");
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            assertTrue(cold.solid(textures.get(i % textures.size()), "item" + i).generated());
        }
        cold.save(DAY);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        FrameCache warm = new FrameCache(dir, "");
        for (int i = 0; i < count; i++) {
            assertTrue(warm.solid(textures.get(i % textures.size()), "item" + i).generated());
        }
        warm.save(DAY);
        long warmMs = (System.nanoTime() - t1) / 1_000_000;
        long bytes = Files.size(dir.resolve(FrameCache.FILE));
        System.out.println("FrameCache: " + count + " sólidos generados y guardados en " + coldMs + " ms; leídos en "
                + warmMs + " ms; " + bytes / 1024 + " KB en disco");
        assertEquals(count, cold.misses());
        assertEquals(count, warm.hits());
        assertEquals(0, warm.errors(), warm.lastError());
        assertTrue(warmMs * 5 < coldMs, "leer (" + warmMs + " ms) no es 5 veces más rápido que generar (" + coldMs + " ms)");
    }
}
