package dev.proceduralbites.core;

import static dev.proceduralbites.core.Params.LAST_LIQUID;
import static dev.proceduralbites.core.Params.MENISCUS;
import static dev.proceduralbites.core.Params.REMOVE_CORK;

import java.util.ArrayList;
import java.util.List;

/** Pociones: el overlay del líquido, teñido por el juego, baja fila por fila. Composición alfa entera. */
public final class Potions {
    private Potions() {
    }

    public static RgbaImage tint(RgbaImage overlay, int[] color, Mask hidden) {
        RgbaImage out = new RgbaImage(overlay.width, overlay.height);
        for (Point p : Masks.opaque(overlay).minus(hidden)) {
            int[] c = overlay.get(p);
            out.set(p.x(), p.y(), c[0] * color[0] / 255, c[1] * color[1] / 255, c[2] * color[2] / 255, c[3]);
        }
        return out;
    }

    /** {@code src} encima de {@code dst}, con aritmética entera. */
    public static RgbaImage alphaComposite(RgbaImage dst, RgbaImage src) {
        if (dst.width != src.width || dst.height != src.height) {
            throw new IllegalArgumentException("las imágenes no tienen el mismo tamaño");
        }
        RgbaImage out = new RgbaImage(dst.width, dst.height);
        for (int y = 0; y < dst.height; y++) {
            for (int x = 0; x < dst.width; x++) {
                int[] d = dst.get(x, y);
                int[] s = src.get(x, y);
                if (s[3] == 0) {
                    out.set(x, y, d[0], d[1], d[2], d[3]);
                    continue;
                }
                long blend = (long) d[3] * (255 - s[3]);
                long outa = s[3] * 255L + blend;
                long c1 = s[3] * 255L * 255L * 128L / outa;
                long c2 = 255L * 128L - c1;
                int[] ch = new int[3];
                for (int i = 0; i < 3; i++) {
                    ch[i] = (int) (sh(s[i] * c1 + d[i] * c2 + (0x80 << 7)) >> 7);
                }
                out.set(x, y, ch[0], ch[1], ch[2], (int) sh(outa + 0x80));
            }
        }
        return out;
    }

    private static long sh(long v) {
        return ((v >> 8) + v) >> 8;
    }

    public record Potion(RgbaImage full, List<RgbaImage> frames) {
    }

    public static Potion potionFrames(RgbaImage bottle, RgbaImage overlay, int[] color) {
        Mask liquid = Masks.opaque(overlay);
        if (liquid.isEmpty()) {
            throw new ArithmeticException("overlay sin píxeles");
        }
        RgbaImage full = alphaComposite(tint(overlay, color, new Mask()), bottle);
        Mask stopper = REMOVE_CORK ? Drinks.cork(bottle, new Mask(), null) : new Mask();
        int y0 = liquid.first().y();
        int y1 = Integer.MIN_VALUE;
        for (Point p : liquid) {
            y1 = Math.max(y1, p.y());
        }
        List<RgbaImage> frames = new ArrayList<>();
        for (double keep : Solids.schedule(LAST_LIQUID)) {
            int cut = y0 + PyMath.round((1 - keep) * (y1 - y0 + 1));
            Mask gone = new Mask();
            for (Point p : liquid) {
                if (p.y() < cut) {
                    gone.add(p);
                }
            }
            RgbaImage layer = tint(overlay, color, gone);
            Mask left = liquid.minus(gone);
            if (!left.isEmpty() && MENISCUS != 0) {
                int top = left.first().y();
                for (Point p : left) {
                    if (p.y() == top) {
                        int[] c = layer.get(p);
                        int[] rgb = Colors.lerp(c, new int[] {255, 255, 255}, MENISCUS);
                        layer.set(p.x(), p.y(), rgb[0], rgb[1], rgb[2], c[3]);
                    }
                }
            }
            RgbaImage frame = alphaComposite(layer, bottle);
            for (Point p : stopper) {
                frame.set(p.x(), p.y(), 0, 0, 0, 0);
            }
            frames.add(frame);
        }
        return new Potion(full, frames);
    }

    /**
     * Dos capas por fotograma para que el juego tiña el líquido en tiempo real: el overlay
     * sin teñir y la botella con el brillo de la superficie (la composición es asociativa).
     */
    public static List<List<RgbaImage>> potionLayers(RgbaImage bottle, RgbaImage overlay) {
        Mask liquid = Masks.opaque(overlay);
        if (liquid.isEmpty()) {
            throw new ArithmeticException("overlay sin píxeles");
        }
        if (bottle.width != overlay.width || bottle.height != overlay.height) {
            throw new IllegalArgumentException("botella y overlay de distinto tamaño");
        }
        int shine = PyMath.round(MENISCUS * 255);
        Mask stopper = REMOVE_CORK ? Drinks.cork(bottle, new Mask(), null) : new Mask();
        int y0 = liquid.first().y();
        int y1 = Integer.MIN_VALUE;
        for (Point p : liquid) {
            y1 = Math.max(y1, p.y());
        }
        List<List<RgbaImage>> frames = new ArrayList<>();
        for (double keep : Solids.schedule(LAST_LIQUID)) {
            int cut = y0 + PyMath.round((1 - keep) * (y1 - y0 + 1));
            Mask gone = new Mask();
            for (Point p : liquid) {
                if (p.y() < cut) {
                    gone.add(p);
                }
            }
            RgbaImage layer = tint(overlay, new int[] {255, 255, 255}, gone);
            RgbaImage highlight = new RgbaImage(overlay.width, overlay.height);
            Mask left = liquid.minus(gone);
            if (!left.isEmpty() && MENISCUS != 0) {
                int top = left.first().y();
                for (Point p : left) {
                    if (p.y() == top) {
                        highlight.set(p.x(), p.y(), 255, 255, 255, shine);
                    }
                }
            }
            RgbaImage glass = alphaComposite(highlight, bottle);
            for (Point p : stopper) {
                layer.set(p.x(), p.y(), 0, 0, 0, 0);
                glass.set(p.x(), p.y(), 0, 0, 0, 0);
            }
            frames.add(List.of(layer, glass));
        }
        return frames;
    }
}
