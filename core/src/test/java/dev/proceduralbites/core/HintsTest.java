package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.proceduralbites.core.Hints.Type;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pistas por ítem; {@code hints_cases.txt} trae los casos de referencia. */
class HintsTest {

    private static Hints of(Map<String, List<String>> layer) {
        return new Hints(List.of(layer));
    }

    @Test
    void samePrecedenceAsThePrototype() throws IOException, URISyntaxException {
        List<Map<String, List<String>>> layers = new ArrayList<>();
        List<String[]> cases = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(HintsTest.class.getResource("/hints_cases.txt").toURI()),
                StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f[0].equals("R")) {
                int level = Integer.parseInt(f[1]);
                while (layers.size() <= level) {
                    layers.add(new LinkedHashMap<>());
                }
                layers.get(level).computeIfAbsent(f[2], k -> new ArrayList<>()).add(f[3]);
            } else {
                cases.add(f);
            }
        }
        assertTrue(cases.size() >= 30 && layers.size() >= 3, cases.size() + " casos, " + layers.size() + " capas");
        Hints hints = new Hints(layers);
        int withHint = 0;
        for (String[] c : cases) {
            Type got = hints.lookup(c[1]);
            assertEquals(c[2], got == null ? "-" : got.key(), "pista de \"" + c[1] + "\"");
            withHint += got == null ? 0 : 1;
        }
        assertTrue(withHint >= 10 && withHint < cases.size(), "casos con y sin pista: " + withHint);
    }

    @Test
    void starMatchesAnyStretch() {
        assertTrue(Hints.matches("mod:*yogurtitem", "mod:yogurtitem"));
        assertTrue(Hints.matches("mod:*yogurtitem", "mod:appleyogurtitem"));
        assertFalse(Hints.matches("mod:*yogurtitem", "mod:yogurtitems"));
        assertFalse(Hints.matches("mod:*yogurtitem", "other:yogurtitem"));
        assertTrue(Hints.matches("*", ""));
        assertTrue(Hints.matches("", ""));
        assertFalse(Hints.matches("", "a"));
        assertTrue(Hints.matches("a*b*c", "abc"));
        assertTrue(Hints.matches("a*b*c", "a1b2b3c"));
        assertFalse(Hints.matches("a*b*c", "a1b2b3"));
        assertTrue(Hints.matches("**a**", "a"));
        assertFalse(Hints.matches("a", "A"));
        // no es una expresión regular
        assertFalse(Hints.matches("a.c", "abc"));
        assertTrue(Hints.matches("a.c", "a.c"));
    }

    @Test
    void exactBeatsPatternAndUpperLayerBeatsLower() {
        Map<String, List<String>> low = Map.of("top_cup", List.of("m:*"), "none", List.of("m:jar"));
        Map<String, List<String>> high = Map.of("solid", List.of("m:*"), "auto", List.of("m:free"));
        Hints hints = new Hints(List.of(low, high));
        assertEquals(Type.NONE, hints.lookup("m:jar"));       // exacto, aunque esté más abajo
        assertEquals(Type.SOLID, hints.lookup("m:cup"));      // el mismo patrón: gana la capa de arriba
        assertNull(hints.lookup("m:free"));                   // auto anula la pista
        assertNull(hints.lookup("other:cup"));
        assertEquals(Type.TOP_CUP, new Hints(List.of(high, low)).lookup("m:cup"));
        assertEquals(4, hints.size());
    }

    @Test
    void longerPatternWinsInTheSameLayer() {
        Hints hints = of(Map.of("top_cup", List.of("m:*"), "none", List.of("m:*jar"), "solid", List.of("m:big*jar")));
        assertEquals(Type.TOP_CUP, hints.lookup("m:cup"));
        assertEquals(Type.NONE, hints.lookup("m:jar"));
        assertEquals(Type.SOLID, hints.lookup("m:bigjar"));
    }

    @Test
    void junkIsIgnored() {
        Map<String, List<String>> layer = new LinkedHashMap<>();
        layer.put("TOP_CUP", List.of("m:a"));                 // el nombre del tipo va en minúsculas
        layer.put("cup", List.of("m:b"));
        layer.put("none", Arrays.asList(null, "m:c"));
        layer.put("solid", null);
        Hints hints = new Hints(Arrays.asList(null, layer));
        assertNull(hints.lookup("m:a"));
        assertNull(hints.lookup("m:b"));
        assertEquals(Type.NONE, hints.lookup("m:c"));
        assertEquals(1, hints.size());
        assertNull(Hints.EMPTY.lookup("m:c"));
    }

    @Test
    void hostilePatternDoesNotHang() {
        // con retroceso ingenuo, 30 estrellas contra un texto que no calza no termina
        String pattern = "a*".repeat(30) + "b";
        String text = "a".repeat(2000);
        assertFalse(assertTimeoutPreemptively(Duration.ofMillis(500), () -> Hints.matches(pattern, text)));
        Hints hints = of(Map.of("none", List.of(pattern)));
        assertNull(assertTimeoutPreemptively(Duration.ofMillis(500), () -> hints.lookup(text)));
    }
}
