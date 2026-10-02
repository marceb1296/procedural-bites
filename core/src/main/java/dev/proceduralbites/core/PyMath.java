package dev.proceduralbites.core;

/** Operaciones numéricas con la semántica de Python: los fotogramas dependen de la última cifra. */
public final class PyMath {
    private PyMath() {
    }

    /** Redondeo al par ({@code round(2.5) = 2}); no usar {@code Math.round}. Lanza si no cabe en int. */
    public static int round(double x) {
        double r = Math.rint(x);
        if (!(r >= Integer.MIN_VALUE && r <= Integer.MAX_VALUE)) {
            throw new ArithmeticException("round(" + x + ") no cabe en int");
        }
        return (int) r;
    }

    /** Módulo con el signo del divisor: {@code -1e-18 % 1.0} da 1.0. */
    public static double mod(double x, double y) {
        if (y == 0.0) {
            throw new ArithmeticException("float modulo");
        }
        double m = x % y;
        if (m != 0.0) {
            if ((y < 0) != (m < 0)) {
                m += y;
            }
        } else {
            m = Math.copySign(0.0, y);
        }
        return m;
    }

    /** Suma compensada de Neumaier en el orden dado: una suma simple puede diferir en la última cifra. */
    public static double sum(double... xs) {
        double f = 0.0;
        double c = 0.0;
        for (double x : xs) {
            double t = f + x;
            if (Math.abs(f) >= Math.abs(x)) {
                c += (f - t) + x;
            } else {
                c += (x - t) + f;
            }
            f = t;
        }
        return c != 0.0 && Double.isFinite(c) ? f + c : f;
    }

    /** {@code sqrt(x*x + y*y)}, sin {@code Math.hypot}: da otra última cifra. */
    public static double norm(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    public static double[] unit(double x, double y) {
        double n = norm(x, y);
        if (n == 0.0) {
            throw new ArithmeticException("float division by zero");
        }
        return new double[] {x / n, y / n};
    }
}
