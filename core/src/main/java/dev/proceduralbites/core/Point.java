package dev.proceduralbites.core;

/** Píxel (x, y). Se ordena por fila y luego por columna. */
public record Point(int x, int y) implements Comparable<Point> {
    @Override
    public int compareTo(Point o) {
        return y != o.y ? Integer.compare(y, o.y) : Integer.compare(x, o.x);
    }

    public Point plus(int dx, int dy) {
        return new Point(x + dx, y + dy);
    }
}
