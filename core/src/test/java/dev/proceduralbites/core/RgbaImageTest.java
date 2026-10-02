package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RgbaImageTest {

    @Test
    void getAndSetKeepUnsignedChannels() {
        RgbaImage im = new RgbaImage(2, 2);
        im.set(1, 0, 255, 128, 0, 200);
        assertArrayEquals(new int[] {255, 128, 0, 200}, im.get(1, 0));
        assertArrayEquals(new int[] {0, 0, 0, 0}, im.get(0, 1));
    }

    @Test
    void abgrMatchesNativeImageLayout() {
        // NativeImage guarda R, G, B, A y getPixelRGBA los lee como int little-endian
        RgbaImage im = new RgbaImage(2, 1);
        im.set(1, 0, 0x11, 0x22, 0x33, 0xf4);
        assertEquals(0xf4332211, im.abgr(1, 0));
        im.setAbgr(0, 0, 0x80ff4020);
        assertArrayEquals(new int[] {0x20, 0x40, 0xff, 0x80}, im.get(0, 0));
        assertArrayEquals(new byte[] {0x20, 0x40, (byte) 0xff, (byte) 0x80}, java.util.Arrays.copyOf(im.rgba(), 4));
    }

    @Test
    void firstFrameCropsAnimatedStrips() {
        RgbaImage strip = new RgbaImage(16, 48);
        strip.set(3, 15, 1, 2, 3, 255);
        strip.set(3, 16, 9, 9, 9, 255);
        RgbaImage frame = strip.firstFrame();
        assertEquals(16, frame.height);
        assertArrayEquals(new int[] {1, 2, 3, 255}, frame.get(3, 15));
        RgbaImage square = new RgbaImage(16, 16);
        assertSame(square, square.firstFrame());
    }

    @Test
    void pxScaleAndOpaque() {
        assertEquals(1, new RgbaImage(8, 8).pxScale());
        assertEquals(1, new RgbaImage(16, 16).pxScale());
        assertEquals(2, new RgbaImage(32, 32).pxScale());
        RgbaImage im = new RgbaImage(3, 3);
        im.set(2, 0, 0, 0, 0, 1);
        im.set(0, 2, 0, 0, 0, 255);
        assertEquals(Mask.of(new Point(2, 0), new Point(0, 2)), Masks.opaque(im));
    }

    /** Al escribir un píxel cada canal se recorta a 0..255; un cast a byte daría la vuelta. */
    @Test
    void setClampsLikePillow() {
        RgbaImage im = new RgbaImage(1, 1);
        im.set(0, 0, 300, -5, 256, 255);
        assertArrayEquals(new int[] {255, 0, 255, 255}, im.get(0, 0));
    }

    /** Medidas negativas o que desbordan {@code int} (32768 * 32768 * 4 = 2^32 da 0) deben rechazarse. */
    @Test
    void rejectsInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(-1, -4));
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(-2, 2, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(32768, 32768));
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(32768, 32768, new byte[0]));
    }
}
