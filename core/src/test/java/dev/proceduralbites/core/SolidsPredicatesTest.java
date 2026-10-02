package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Las versiones rápidas de {@link Solids} dan lo mismo que las literales, con radios de hasta 256x y
 * muchos empates.
 */
class SolidsPredicatesTest {
    private static final double IRR = Params.IRREGULARITY;

    /** Ondas y dientes como los arma {@code make_bite}, con un generador cualquiera. */
    private static double[][] waves(Random rnd) {
        double[][] w = new double[3][];
        int[] freqs = {2, 3, 5};
        for (int i = 0; i < 3; i++) {
            w[i] = new double[] {freqs[i], rnd.nextDouble() * 2 * Math.PI, 0.5 + rnd.nextDouble() * 0.5};
        }
        return w;
    }

    @Test
    void inBiteMatchesLiteral() {
        Random rnd = new Random(7);
        long checked = 0;
        for (int trial = 0; trial < 60; trial++) {
            int k = 1 << rnd.nextInt(5);                   // 16x .. 256x
            double r = Params.BITE_SIZE * 16 * k * (0.9 + rnd.nextDouble() * 0.2);
            double[][] waves = waves(rnd);
            double wsum = Solids.waveSum(waves);
            List<double[]> t = new ArrayList<>();
            double th = rnd.nextDouble() * 2 * Math.PI;
            double maxTooth = 0;
            for (int i = 0; i < Math.max(6, (int) (2 * Math.PI * r / (Params.TOOTH_SPACING * k))); i++) {
                double tr = Params.TOOTH_RADIUS * k * (1 + (rnd.nextDouble() * 0.5 - 0.25) * (0.5 + IRR));
                t.add(new double[] {r * StrictMath.cos(th), r * StrictMath.sin(th), tr});
                maxTooth = Math.max(maxTooth, tr);
                th += Params.TOOTH_SPACING * k / r;
            }
            double[][] teeth = t.toArray(new double[0][]);
            double bx = rnd.nextDouble() * 3;
            double by = rnd.nextDouble() * 3;
            int lim = (int) (r * 1.3 + maxTooth) + 2;
            for (int y = -lim; y <= lim; y++) {
                for (int x = -lim; x <= lim; x++) {
                    double ex = x - bx;
                    double ey = y - by;
                    double wob = Solids.wobble(waves, StrictMath.atan2(ey, ex)) / wsum;
                    boolean literal = PyMath.norm(ex, ey) <= r * (1 + 0.12 * IRR * wob);
                    for (int i = 0; i < teeth.length && !literal; i++) {
                        literal = PyMath.norm(ex - teeth[i][0], ey - teeth[i][1]) <= teeth[i][2];
                    }
                    assertEquals(literal, Solids.inBite(ex, ey, r, waves, wsum, teeth, maxTooth),
                        "prueba " + trial + " (" + x + ", " + y + ") r " + r);
                    checked++;
                }
            }
        }
        System.out.println("inBite: " + checked + " píxeles");
    }

    @Test
    void inLastBiteMatchesLiteral() {
        Random rnd = new Random(8);
        for (int trial = 0; trial < 60; trial++) {
            int k = 1 << rnd.nextInt(5);
            double r = 3 * k + rnd.nextDouble() * 16 * k;
            double[][] waves = waves(rnd);
            double wsum = Solids.waveSum(waves);
            double phase = rnd.nextDouble() * 2 * Math.PI;
            double tooth = Params.TOOTH_RADIUS * k;
            Point center = new Point(rnd.nextInt(40) - 20, rnd.nextInt(40) - 20);
            int n = Math.max(6, (int) (2 * Math.PI * r / (Params.TOOTH_SPACING * k)));
            double[][] teeth = new double[n][];
            for (int i = 0; i < n; i++) {
                double a = phase + 2 * Math.PI * i / n;
                teeth[i] = new double[] {center.x() + r * StrictMath.cos(a), center.y() + r * StrictMath.sin(a)};
            }
            int lim = (int) (r * 1.3) + 2;
            for (int y = -lim; y <= lim; y++) {
                for (int x = -lim; x <= lim; x++) {
                    Point p = new Point(center.x() + x, center.y() + y);
                    double ex = p.x() - center.x();
                    double ey = p.y() - center.y();
                    double wob = Solids.wobble(waves, StrictMath.atan2(ey, ex)) / wsum;
                    boolean literal = PyMath.norm(ex, ey) <= r * (1 + 0.12 * IRR * wob);
                    for (int i = 0; i < teeth.length && literal; i++) {
                        literal = PyMath.norm(p.x() - teeth[i][0], p.y() - teeth[i][1]) > tooth;
                    }
                    assertEquals(literal, Solids.inLastBite(p, center, r, waves, wsum, teeth, tooth),
                        "prueba " + trial + " " + p + " r " + r);
                }
            }
        }
    }

    /** Los 20 más cercanos con orden estable: en empate va primero el que va antes en pool. */
    @Test
    void nearestMatchesStableSort() {
        Random rnd = new Random(9);
        for (int trial = 0; trial < 3000; trial++) {
            // anillos de un disco: muchas distancias iguales por simetría
            int size = 1 + rnd.nextInt(60);
            List<Point> pool = new ArrayList<>();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (rnd.nextInt(3) > 0) {
                        pool.add(new Point(x, y));
                    }
                }
            }
            Point p = new Point(rnd.nextInt(size + 4) - 2, rnd.nextInt(size + 4) - 2);
            int n = 1 + rnd.nextInt(25);
            List<Point> want = new ArrayList<>(pool);
            want.sort(Comparator.comparingInt(q -> (q.x() - p.x()) * (q.x() - p.x()) + (q.y() - p.y()) * (q.y() - p.y())));
            want = want.subList(0, Math.min(n, want.size()));
            assertEquals(want, Solids.nearest(pool, p, n), "prueba " + trial);
        }
    }
}
