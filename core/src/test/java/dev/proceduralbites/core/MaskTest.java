package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link Mask} es un mapa de bits con una ventana que crece: se compara contra un
 * {@code TreeSet<Point>} (la implementación anterior, trivialmente correcta) con
 * operaciones al azar entre máscaras de ventanas distintas y coordenadas negativas.
 */
class MaskTest {

    @Test
    void behavesLikeSortedSet() {
        Random rnd = new Random(99);
        for (int trial = 0; trial < 300; trial++) {
            List<Mask> masks = new ArrayList<>();
            List<TreeSet<Point>> refs = new ArrayList<>();
            for (int m = 0; m < 3; m++) {
                masks.add(new Mask());
                refs.add(new TreeSet<>());
            }
            for (int op = 0; op < 200; op++) {
                int a = rnd.nextInt(3);
                int b = rnd.nextInt(3);
                // coordenadas en una zona que se va corriendo: obliga a crecer por todos los lados
                int span = 3 + rnd.nextInt(40);
                Point p = new Point(rnd.nextInt(span) - span / 2 + trial % 7, rnd.nextInt(span) - span / 2 - trial % 5);
                switch (rnd.nextInt(9)) {
                    case 0, 1, 2 -> assertEquals(refs.get(a).add(p), masks.get(a).add(p), "add");
                    case 3 -> assertEquals(refs.get(a).remove(p), masks.get(a).remove(p), "remove");
                    case 4 -> {
                        masks.set(a, masks.get(a).union(masks.get(b)));
                        TreeSet<Point> r = new TreeSet<>(refs.get(a));
                        r.addAll(refs.get(b));
                        refs.set(a, r);
                    }
                    case 5 -> {
                        masks.set(a, masks.get(a).minus(masks.get(b)));
                        TreeSet<Point> r = new TreeSet<>(refs.get(a));
                        r.removeAll(refs.get(b));
                        refs.set(a, r);
                    }
                    case 6 -> {
                        masks.set(a, masks.get(a).and(masks.get(b)));
                        TreeSet<Point> r = new TreeSet<>(refs.get(a));
                        r.retainAll(refs.get(b));
                        refs.set(a, r);
                    }
                    case 7 -> {
                        masks.get(a).addAll(masks.get(b));
                        refs.get(a).addAll(new ArrayList<>(refs.get(b)));
                    }
                    default -> {
                        masks.get(a).removeAll(masks.get(b));
                        refs.get(a).removeAll(new ArrayList<>(refs.get(b)));
                    }
                }
                for (int m = 0; m < 3; m++) {
                    Mask got = masks.get(m);
                    TreeSet<Point> want = refs.get(m);
                    String where = "prueba " + trial + ", op " + op + ", máscara " + m;
                    assertEquals(want.size(), got.size(), where);
                    assertEquals(new ArrayList<>(want), got.toList(), where);   // orden (y, x)
                    if (!want.isEmpty()) {
                        assertEquals(want.first(), got.first(), where);
                    }
                    assertEquals(want.contains(p), got.contains(p), where);
                }
                boolean eq = refs.get(a).equals(refs.get(b));
                assertEquals(eq, masks.get(a).equals(masks.get(b)), "equals");
                if (eq) {
                    assertEquals(masks.get(a).hashCode(), masks.get(b).hashCode(), "hashCode");
                }
                boolean inter = refs.get(a).stream().anyMatch(refs.get(b)::contains);
                assertEquals(inter, masks.get(a).intersects(masks.get(b)), "intersects");
            }
        }
    }

    /** Dos píxeles muy lejanos no pueden pedir gigas de memoria: se rechaza rápido. */
    @Test
    void sparseMaskIsRejected() {
        Mask m = new Mask();
        m.add(new Point(0, 0));
        assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> assertThrows(IllegalArgumentException.class, () -> m.add(new Point(1 << 20, 1 << 20))));
        assertEquals(1, m.size());
        assertEquals(List.of(new Point(0, 0)), m.toList());
        // los extremos de int no desbordan el cálculo de la ventana
        Mask edge = new Mask();
        edge.add(new Point(Integer.MAX_VALUE, Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> edge.add(new Point(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertEquals(List.of(new Point(Integer.MAX_VALUE, Integer.MIN_VALUE)), edge.toList());
    }

    @Test
    void modifyingWhileIteratingFails() {
        Mask m = Mask.of(new Point(0, 0), new Point(1, 0));
        assertThrows(ConcurrentModificationException.class, () -> {
            for (Point p : m) {
                m.add(new Point(p.x() + 5, p.y()));
            }
        });
    }
}
