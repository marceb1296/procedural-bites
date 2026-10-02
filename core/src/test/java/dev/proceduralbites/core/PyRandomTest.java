package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Vectores calculados con CPython 3.12.3 ({@code random.Random}). */
class PyRandomTest {

    @Test
    void randomMatchesCPython() {
        assertEquals(0.6394267984578837, new PyRandom(42).random());
    }

    @Test
    void seedOfMatchesPrototype() {
        assertEquals(0x3a7bd3e2360a3d29L, PyRandom.seedOf("apple"));
        // Semillas con el bit 63 encendido: en Java son long negativos.
        assertEquals(0xe4b8ed3ab4bc0c5eL, PyRandom.seedOf("golden_carrot"));
        assertEquals(0x87e8667c0e7c9d3cL, PyRandom.seedOf("farmersdelight:beef_stew"));
        // Python codifica el nombre en UTF-8.
        assertEquals(0x850f7dc43910ff89L, PyRandom.seedOf("café"));
    }

    @Test
    void seededRngSequences() {
        assertSequence("apple", 0.5382403416162858, 0.05355141509926953, 0.3272416959342629);
        assertSequence("golden_carrot", 0.44298189334890403, 0.5379226641228219, 0.6782860228373636);
        assertSequence("farmersdelight:beef_stew", 0.29039512142967305, 0.1879344789695062, 0.5173652600883651);
        assertSequence("café", 0.867887143470014, 0.28487498759753294, 0.24966851566051118);
    }

    @Test
    void uniformMatchesCPython() {
        PyRandom rng = PyRandom.seededRng("apple");
        assertEquals(0.007648068323257151, rng.uniform(-0.1, 0.1));
        assertEquals(0.33647346453040533, rng.uniform(0, 2 * Math.PI));
        assertEquals(-0.3455166081314742, rng.uniform(-1.0, 1.0));
    }

    /**
     * Semillas de una y dos palabras de 32 bits (cambian la longitud de la clave) y
     * valores 0, 311, 312 y 999, que cruzan varias regeneraciones del estado (312
     * llamadas a {@code random()} por cada una).
     */
    @Test
    void keyLengthsAndTwists() {
        assertAt(0L, 0.8444218515250481, 0.39380795178170946, 0.5190037287013293, 0.4804125346981437);
        assertAt(1L, 0.13436424411240122, 0.3272414146871332, 0.3167351468856021, 0.7062615472551386);
        assertAt(0xffffffffL, 0.6353574441341173, 0.8815812211541993, 0.49918500993323056, 0.3214643568909129);
        assertAt(0x100000000L, 0.11299430095636409, 0.744851853306793, 0.5141503636199082, 0.04156870367167198);
        assertAt(-1L, 0.021825695401270107, 0.28054018059273567, 0.8375637927891323, 0.9009945166016444);
    }

    private static void assertSequence(String name, double... expected) {
        PyRandom rng = PyRandom.seededRng(name);
        for (double e : expected) {
            assertEquals(e, rng.random(), name);
        }
    }

    private static void assertAt(long seed, double at0, double at311, double at312, double at999) {
        PyRandom rng = new PyRandom(seed);
        double[] xs = new double[1000];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = rng.random();
        }
        String msg = Long.toUnsignedString(seed);
        assertEquals(at0, xs[0], msg);
        assertEquals(at311, xs[311], msg);
        assertEquals(at312, xs[312], msg);
        assertEquals(at999, xs[999], msg);
    }
}
