package dev.proceduralbites.core;

/** Colores {r, g, b} o {r, g, b, a}; el alfa se ignora. */
public final class Colors {
    private Colors() {
    }

    /** Mezcla de los canales RGB, redondeada al par. */
    public static int[] lerp(double[] c, double[] target, double t) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = PyMath.round(c[i] + (target[i] - c[i]) * t);
        }
        return out;
    }

    public static int[] lerp(int[] c, int[] target, double t) {
        return lerp(toDouble(c), toDouble(target), t);
    }

    public static int[] lerp(double[] c, int[] target, double t) {
        return lerp(c, toDouble(target), t);
    }

    /** HSV en [0, 1]; el tono puede dar exactamente 1.0. */
    public static double[] hsv(int[] c) {
        double r = c[0] / 255.0;
        double g = c[1] / 255.0;
        double b = c[2] / 255.0;
        double maxc = Math.max(r, Math.max(g, b));
        double minc = Math.min(r, Math.min(g, b));
        double rangec = maxc - minc;
        double v = maxc;
        if (minc == maxc) {
            return new double[] {0.0, 0.0, v};
        }
        double s = rangec / maxc;
        double rc = (maxc - r) / rangec;
        double gc = (maxc - g) / rangec;
        double bc = (maxc - b) / rangec;
        double h;
        if (r == maxc) {
            h = bc - gc;
        } else if (g == maxc) {
            h = 2.0 + rc - bc;
        } else {
            h = 4.0 + gc - rc;
        }
        h = PyMath.mod(h / 6.0, 1.0);
        return new double[] {h, s, v};
    }

    /** Distancia RGB al cuadrado, en enteros: comparar contra {@code tol * tol}. */
    public static int colorDist2(int[] a, int[] b) {
        int dr = a[0] - b[0];
        int dg = a[1] - b[1];
        int db = a[2] - b[2];
        return dr * dr + dg * dg + db * db;
    }

    /** Luminancia Rec. 709. */
    public static double luma(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    public static double luma(int[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Distancia circular entre dos tonos en [0, 1]. */
    public static double hueGap(double a, double b) {
        double d = Math.abs(a - b);
        return Math.min(d, 1 - d);
    }

    private static double[] toDouble(int[] c) {
        return new double[] {c[0], c[1], c[2]};
    }
}
