package dev.proceduralbites.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Mersenne Twister igual a {@code random.Random} de CPython, con semilla de 64 bits sin
 * signo: las mordidas de cada ítem salen de esta secuencia.
 */
public final class PyRandom {
    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] mt = new int[N];
    private int mti;

    public PyRandom(long seed) {
        int lo = (int) seed;
        int hi = (int) (seed >>> 32);
        // palabras de 32 bits, la menos significativa primero, sin ceros a la izquierda (0 da [0])
        int[] key = hi != 0 ? new int[] {lo, hi} : new int[] {lo};
        initByArray(key);
    }

    /** Semilla a partir del nombre del ítem. */
    public static PyRandom seededRng(String name) {
        return new PyRandom(seedOf(name));
    }

    /** Los primeros 8 bytes del SHA-256 del nombre, big-endian. */
    public static long seedOf(String name) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long seed = 0;
        for (int i = 0; i < 8; i++) {
            seed = (seed << 8) | (digest[i] & 0xff);
        }
        return seed;
    }

    /** Double de 53 bits en [0, 1). */
    public double random() {
        int a = nextInt() >>> 5;
        int b = nextInt() >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    private int nextInt() {
        if (mti >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            for (; kk < N - 1; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            int y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            mti = 0;
        }
        int y = mt[mti++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }
}
