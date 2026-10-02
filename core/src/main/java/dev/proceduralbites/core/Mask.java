package dev.proceduralbites.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Conjunto de píxeles que siempre se recorre en orden (y, x). Por dentro es un mapa de
 * bits sobre una ventana que crece según haga falta (con un TreeSet, 256x tardaba 10 s).
 * Una máscara muy dispersa lanza en vez de pedir gigas de memoria.
 */
public final class Mask implements Iterable<Point> {
    /** 2^30 bits = 128 MB. */
    public static final long MAX_BITS = 1L << 30;

    // ventana: x en [ox, ox + w), y en [oy, oy + h); bit (y - oy) * w + (x - ox)
    private int ox;
    private int oy;
    private int w;
    private int h;
    private long[] bits = new long[0];
    private int size;
    /** Ningún bit encendido antes de este índice (acelera {@link #first()}). */
    private int firstHint;
    private int modCount;

    public Mask() {
    }

    public Mask(Collection<Point> points) {
        for (Point p : points) {
            add(p);
        }
    }

    public Mask(Mask other) {
        copyFrom(other);
    }

    public static Mask of(Point... points) {
        return new Mask(List.of(points));
    }

    private void copyFrom(Mask other) {
        ox = other.ox;
        oy = other.oy;
        w = other.w;
        h = other.h;
        bits = other.bits.clone();
        size = other.size;
        firstHint = other.firstHint;
    }

    private boolean sameWindow(Mask o) {
        return ox == o.ox && oy == o.oy && w == o.w && h == o.h;
    }

    private int index(int x, int y) {
        long dx = (long) x - ox;
        long dy = (long) y - oy;
        if (dx < 0 || dx >= w || dy < 0 || dy >= h) {
            return -1;
        }
        return (int) (dy * w + dx);
    }

    private boolean bit(int i) {
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    public boolean contains(Point p) {
        return contains(p.x(), p.y());
    }

    public boolean contains(int x, int y) {
        int i = index(x, y);
        return i >= 0 && bit(i);
    }

    public boolean add(Point p) {
        return add(p.x(), p.y());
    }

    public boolean add(int x, int y) {
        int i = index(x, y);
        if (i < 0) {
            grow(x, y);
            i = index(x, y);
        }
        if (bit(i)) {
            return false;
        }
        bits[i >>> 6] |= 1L << i;
        size++;
        modCount++;
        if (i < firstHint) {
            firstHint = i;
        }
        return true;
    }

    public void addAll(Mask other) {
        if (sameWindow(other)) {
            int n = 0;
            for (int j = 0; j < bits.length; j++) {
                bits[j] |= other.bits[j];
                n += Long.bitCount(bits[j]);
            }
            size = n;
            firstHint = Math.min(firstHint, other.firstHint);
            modCount++;
            return;
        }
        for (Point p : other) {
            add(p);
        }
    }

    /** Vacía con la misma ventana que {@code other}: las operaciones entre ambas van palabra a palabra. */
    static Mask emptyLike(Mask other) {
        Mask out = new Mask();
        out.ox = other.ox;
        out.oy = other.oy;
        out.w = other.w;
        out.h = other.h;
        out.bits = new long[other.bits.length];
        out.firstHint = Integer.MAX_VALUE;
        return out;
    }

    public boolean remove(Point p) {
        return remove(p.x(), p.y());
    }

    public boolean remove(int x, int y) {
        int i = index(x, y);
        if (i < 0 || !bit(i)) {
            return false;
        }
        bits[i >>> 6] &= ~(1L << i);
        size--;
        modCount++;
        return true;
    }

    public void removeAll(Mask other) {
        if (sameWindow(other)) {
            int n = 0;
            for (int j = 0; j < bits.length; j++) {
                bits[j] &= ~other.bits[j];
                n += Long.bitCount(bits[j]);
            }
            size = n;
            modCount++;
            return;
        }
        for (Point p : other) {
            remove(p);
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public Point first() {
        int i = nextSetBit(firstHint);
        if (i < 0) {
            throw new NoSuchElementException();
        }
        firstHint = i;
        return pointAt(i);
    }

    private Point pointAt(int i) {
        return new Point(ox + i % w, oy + i / w);
    }

    private int nextSetBit(int from) {
        int j = from >>> 6;
        if (j >= bits.length) {
            return -1;
        }
        long word = bits[j] & (-1L << from);
        while (true) {
            if (word != 0) {
                int i = j * 64 + Long.numberOfTrailingZeros(word);
                return i < w * h ? i : -1;
            }
            if (++j >= bits.length) {
                return -1;
            }
            word = bits[j];
        }
    }

    /** Cada lado se revisa antes de multiplicar: 2^32 x 2^32 desborda un long y pasaría como negativo. */
    private static boolean tooBig(long nw, long nh) {
        return nw > MAX_BITS || nh > MAX_BITS || nw * nh > MAX_BITS;
    }

    private void grow(int x, int y) {
        long x0;
        long x1;
        long y0;
        long y1;
        if (w == 0) {
            x0 = x;
            x1 = (long) x + 1;
            y0 = y;
            y1 = (long) y + 1;
        } else {
            x0 = ox;
            x1 = (long) ox + w;
            y0 = oy;
            y1 = (long) oy + h;
            long padX = Math.max(8, w / 2);
            long padY = Math.max(8, h / 2);
            if (x < x0) {
                x0 = x - padX;
            }
            if (x >= x1) {
                x1 = (long) x + 1 + padX;
            }
            if (y < y0) {
                y0 = y - padY;
            }
            if (y >= y1) {
                y1 = (long) y + 1 + padY;
            }
            // el margen nunca hace fallar: si con él no entra, se prueba sin él
            if (tooBig(x1 - x0, y1 - y0)) {
                x0 = Math.min(ox, x);
                x1 = Math.max((long) ox + w, (long) x + 1);
                y0 = Math.min(oy, y);
                y1 = Math.max((long) oy + h, (long) y + 1);
            }
        }
        x0 = Math.max(x0, Integer.MIN_VALUE);
        y0 = Math.max(y0, Integer.MIN_VALUE);
        x1 = Math.min(x1, Integer.MAX_VALUE + 1L);
        y1 = Math.min(y1, Integer.MAX_VALUE + 1L);
        long nw = x1 - x0;
        long nh = y1 - y0;
        if (tooBig(nw, nh)) {
            throw new IllegalArgumentException("máscara demasiado dispersa: ventana de " + nw + " x " + nh);
        }
        Mask old = new Mask(this);
        ox = (int) x0;
        oy = (int) y0;
        w = (int) nw;
        h = (int) nh;
        bits = new long[(int) ((nw * nh + 63) >>> 6)];
        firstHint = 0;
        for (int i = old.nextSetBit(0); i >= 0; i = old.nextSetBit(i + 1)) {
            int j = index(old.ox + i % old.w, old.oy + i / old.w);
            bits[j >>> 6] |= 1L << j;
        }
    }

    public Mask union(Mask other) {
        Mask out = new Mask(this);
        out.addAll(other);
        return out;
    }

    public Mask minus(Mask other) {
        Mask out = new Mask(this);
        out.removeAll(other);
        return out;
    }

    public Mask and(Mask other) {
        Mask out = new Mask(this);
        if (sameWindow(other)) {
            int n = 0;
            for (int j = 0; j < out.bits.length; j++) {
                out.bits[j] &= other.bits[j];
                n += Long.bitCount(out.bits[j]);
            }
            out.size = n;
            return out;
        }
        for (Point p : this) {
            if (!other.contains(p)) {
                out.remove(p);
            }
        }
        return out;
    }

    public boolean intersects(Mask other) {
        if (sameWindow(other)) {
            for (int j = 0; j < bits.length; j++) {
                if ((bits[j] & other.bits[j]) != 0) {
                    return true;
                }
            }
            return false;
        }
        Mask small = size <= other.size ? this : other;
        Mask big = small == this ? other : this;
        for (Point p : small) {
            if (big.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public List<Point> toList() {
        List<Point> out = new ArrayList<>(size);
        for (Point p : this) {
            out.add(p);
        }
        return out;
    }

    public Stream<Point> stream() {
        return toList().stream();
    }

    /** Modificar la máscara mientras se recorre lanza {@link ConcurrentModificationException}. */
    @Override
    public Iterator<Point> iterator() {
        return new Iterator<>() {
            private final int expected = modCount;
            private int next = nextSetBit(firstHint);

            @Override
            public boolean hasNext() {
                return next >= 0;
            }

            @Override
            public Point next() {
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                if (next < 0) {
                    throw new NoSuchElementException();
                }
                Point p = pointAt(next);
                next = nextSetBit(next + 1);
                return p;
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Mask m) || m.size != size) {
            return false;
        }
        if (sameWindow(m)) {
            return Arrays.equals(bits, m.bits);
        }
        for (Point p : this) {
            if (!m.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /** Como {@code Set.hashCode}: no depende de la ventana. */
    @Override
    public int hashCode() {
        int hash = 0;
        for (Point p : this) {
            hash += p.hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return toList().toString();
    }
}
