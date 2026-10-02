package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Donde Python lanza, Java no sigue en silencio con un valor inventado (0, Integer.MAX_VALUE o NaN). */
class PyMathTest {

    @Test
    void roundOfNonFiniteOrHugeFailsLikePython() {
        // Python: ValueError / OverflowError. (int) Math.rint da 0 y 2147483647.
        assertThrows(ArithmeticException.class, () -> PyMath.round(Double.NaN));
        assertThrows(ArithmeticException.class, () -> PyMath.round(Double.POSITIVE_INFINITY));
        assertThrows(ArithmeticException.class, () -> PyMath.round(Double.NEGATIVE_INFINITY));
        assertThrows(ArithmeticException.class, () -> PyMath.round(3e9));
        assertEquals(Integer.MIN_VALUE, PyMath.round(-2147483648.4));
        assertEquals(Integer.MAX_VALUE, PyMath.round(2147483647.4));
    }

    @Test
    void moduloByZeroFailsLikePython() {
        // Python: ZeroDivisionError. Java: NaN.
        assertThrows(ArithmeticException.class, () -> PyMath.mod(1.0, 0.0));
        assertThrows(ArithmeticException.class, () -> PyMath.mod(1.0, -0.0));
    }

    @Test
    void unitOfZeroVectorFailsLikePython() {
        // Python: ZeroDivisionError. Java: {NaN, NaN}.
        assertThrows(ArithmeticException.class, () -> PyMath.unit(0.0, 0.0));
    }

    @Test
    void roundingEdges() {
        assertEquals(2, PyMath.round(2.5));
        assertEquals(0, PyMath.round(-0.5));
        assertEquals(-2, PyMath.round(-1.5));
        assertEquals(1.0, PyMath.mod(-1e-18, 1.0));
        assertEquals(-0.0, PyMath.mod(0.0, -1.0));
    }
}
