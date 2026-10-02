package dev.proceduralbites.core;

/** Imagen RGBA de 8 bits por canal; los píxeles se leen y escriben como {r, g, b, a}. */
public final class RgbaImage {
    public final int width;
    public final int height;
    private final byte[] rgba;

    public RgbaImage(int width, int height) {
        this(width, height, new byte[byteCount(width, height)]);
    }

    /** {@code rgba}: 4 bytes por píxel, por filas; se usa el mismo arreglo, sin copiar. */
    public RgbaImage(int width, int height, byte[] rgba) {
        int expected = byteCount(width, height);
        if (rgba.length != expected) {
            throw new IllegalArgumentException("se esperaban " + expected + " bytes, hay " + rgba.length);
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    public byte[] rgba() {
        return rgba;
    }

    public RgbaImage copy() {
        return new RgbaImage(width, height, rgba.clone());
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public int[] get(int x, int y) {
        int i = index(x, y);
        return new int[] {rgba[i] & 0xff, rgba[i + 1] & 0xff, rgba[i + 2] & 0xff, rgba[i + 3] & 0xff};
    }

    public int[] get(Point p) {
        return get(p.x(), p.y());
    }

    public int alpha(int x, int y) {
        return rgba[index(x, y) + 3] & 0xff;
    }

    /** Recorta cada canal a 0..255. */
    public void set(int x, int y, int r, int g, int b, int a) {
        int i = index(x, y);
        rgba[i] = clip8(r);
        rgba[i + 1] = clip8(g);
        rgba[i + 2] = clip8(b);
        rgba[i + 3] = clip8(a);
    }

    public void set(Point p, int[] c) {
        set(p.x(), p.y(), c[0], c[1], c[2], c[3]);
    }

    /** Píxel como lo guarda {@code NativeImage} de Minecraft: {@code 0xAABBGGRR}. */
    public int abgr(int x, int y) {
        int[] c = get(x, y);
        return c[3] << 24 | c[2] << 16 | c[1] << 8 | c[0];
    }

    public void setAbgr(int x, int y, int abgr) {
        set(x, y, abgr & 0xff, abgr >>> 8 & 0xff, abgr >>> 16 & 0xff, abgr >>> 24);
    }

    /** Si es una textura animada (tira vertical), el primer fotograma. */
    public RgbaImage firstFrame() {
        if (height <= width) {
            return this;
        }
        byte[] out = new byte[width * width * 4];
        System.arraycopy(rgba, 0, out, 0, out.length);
        return new RgbaImage(width, width, out);
    }

    /** Factor de resolución respecto a 16x16 (32x32 da 2). */
    public int pxScale() {
        return Math.max(1, width / 16);
    }

    private static int byteCount(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("medidas negativas: " + width + "x" + height);
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(width, height), 4);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("imagen demasiado grande: " + width + "x" + height, e);
        }
    }

    private static byte clip8(int v) {
        return (byte) (v < 0 ? 0 : Math.min(v, 255));
    }

    private int index(int x, int y) {
        if (!inBounds(x, y)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") fuera de " + width + "x" + height);
        }
        return (y * width + x) * 4;
    }
}
