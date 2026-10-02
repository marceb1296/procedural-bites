package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.proceduralbites.core.Params.BitePattern;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Compara los fotogramas con los datos de referencia, píxel a píxel: los sintéticos de
 * {@code frames/} siempre y los de texturas locales desde la propiedad {@code expectedDir}.
 * Reporta por separado las diferencias de forma (alfa) y de color.
 */
class FramesTest {
    private static final int SOLID = 1;
    private static final int DRINK = 2;
    private static final int BOWL = 3;
    private static final int POTION = 4;
    private static final int CUP = 5;
    /** Se omite: recipiente opaco de otra forma. Sin fotogramas, solo la entrada. */
    static final int OPAQUE = 6;
    /** Se omite: frasco con dibujo propio y una etiqueta que tapa el contenido. Solo la entrada. */
    static final int LABEL = 7;
    /** Taza vista desde arriba (pista {@code top_cup}): el contenido es la superficie. */
    static final int TOP_CUP = 8;
    /** Con la pista {@code top_cup} no se encuentra la superficie. Solo la entrada. */
    static final int NO_SURFACE = 9;
    /** Tazón por plantilla: lleva las texturas de la plantilla y el contenido. */
    static final int TEMPLATE = 10;
    /** No es un tazón por plantilla: solo la entrada y las texturas de la plantilla. */
    static final int NO_TEMPLATE = 11;

    static BiteFrames.Kind kind(int type) {
        return switch (type) {
            case DRINK -> BiteFrames.Kind.DRINK;
            case BOWL -> BiteFrames.Kind.BOWL;
            case CUP -> BiteFrames.Kind.CUP;
            default -> throw new AssertionError("tipo " + type);
        };
    }

    @TestFactory
    Stream<DynamicTest> synthetic() throws IOException, URISyntaxException {
        Path dir = Paths.get(FramesTest.class.getResource("/frames").toURI());
        List<Path> files = binFiles(dir);
        assertTrue(files.size() >= 30, "faltan texturas sintéticas: " + files.size());
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> check(f)));
    }

    @TestFactory
    Stream<DynamicTest> local() throws IOException {
        String prop = System.getProperty("expectedDir", "");
        Assumptions.assumeTrue(!prop.isEmpty() && Files.isDirectory(Paths.get(prop, "solids")),
                "sin datos locales (-PexpectedDir)");
        List<Path> files = new ArrayList<>(binFiles(Paths.get(prop, "solids")));
        files.addAll(binFiles(Paths.get(prop, "drinks")));
        files.addAll(binFiles(Paths.get(prop, "potions")));
        return files.stream()
                .map(f -> DynamicTest.dynamicTest(f.getParent().getFileName() + "/" + f.getFileName(), () -> check(f)));
    }

    static List<Path> binFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".bin")).sorted().toList();
        }
    }

    /** {@code empty}, {@code liquid} y {@code eaten} en recipientes; {@code own} sin vacío; el resto, en pociones. */
    record Expected(String name, int kind, List<RgbaImage> images, RgbaImage empty, Mask liquid, boolean eaten,
                    boolean own, RgbaImage bottle, RgbaImage overlay, int[] color, List<RgbaImage> group) {
    }

    private static RgbaImage readImage(DataInputStream d, Path file) throws IOException {
        int w = d.readInt();
        int h = d.readInt();
        byte[] raw = d.readNBytes(w * h * 4);
        assertEquals(w * h * 4, raw.length, "archivo truncado: " + file);
        return new RgbaImage(w, h, raw);
    }

    static Expected read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); DataInputStream d = new DataInputStream(in)) {
            byte[] magic = d.readNBytes(4);
            assertEquals("PBF1", new String(magic, StandardCharsets.US_ASCII), "cabecera de " + file);
            String name = new String(d.readNBytes(d.readUnsignedShort()), StandardCharsets.UTF_8);
            int kind = d.readUnsignedByte();
            int w = d.readInt();
            int h = d.readInt();
            int count = d.readInt();
            List<RgbaImage> images = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                byte[] raw = d.readNBytes(w * h * 4);
                assertEquals(w * h * 4, raw.length, "archivo truncado: " + file);
                images.add(new RgbaImage(w, h, raw));
            }
            RgbaImage empty = null;
            Mask liquid = null;
            RgbaImage bottle = null;
            RgbaImage overlay = null;
            int[] color = null;
            boolean eaten = false;
            boolean own = false;
            List<RgbaImage> group = List.of();
            if (kind == TEMPLATE || kind == NO_TEMPLATE) {
                int n = d.readInt();
                group = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    group.add(readImage(d, file));
                }
                if (kind == TEMPLATE) {
                    liquid = readMask(d, file, w, h);
                }
            }
            if (kind == POTION) {
                bottle = readImage(d, file);
                overlay = readImage(d, file);
                color = new int[] {d.readUnsignedByte(), d.readUnsignedByte(), d.readUnsignedByte()};
            }
            if (kind == DRINK || kind == BOWL || kind == CUP || kind == OPAQUE || kind == LABEL || kind == TOP_CUP
                    || kind == NO_SURFACE) {
                int flag = d.readUnsignedByte();
                assertTrue(flag <= 3, "vacío " + flag + " en " + file);
                if (flag == 1 || flag == 2) {
                    empty = readImage(d, file);
                }
                eaten = flag == 2;
                // en las tazas con pista no hay vacío, pero no es un envase propio
                own = flag == 3 && kind != TOP_CUP && kind != NO_SURFACE;
                liquid = readMask(d, file, w, h);
            }
            assertEquals(-1, d.read(), "bytes de más en " + file);
            return new Expected(name, kind, images, empty, liquid, eaten, own, bottle, overlay, color, group);
        }
    }

    private static Mask readMask(DataInputStream d, Path file, int w, int h) throws IOException {
        byte[] bits = d.readNBytes(w * h);
        assertEquals(w * h, bits.length, "archivo truncado: " + file);
        Mask mask = new Mask();
        for (int i = 0; i < bits.length; i++) {
            if (bits[i] == 1) {
                mask.add(new Point(i % w, i / w));
            }
        }
        return mask;
    }

    private static void check(Path file) throws IOException {
        Expected e = read(file);
        RgbaImage input = e.images().get(0);
        List<RgbaImage> want = e.images().subList(1, e.images().size());
        List<RgbaImage> got;
        if (e.kind() == SOLID) {
            got = Solids.frames(input, e.name(), BitePattern.AUTO);
        } else if (e.kind() == POTION) {
            // la imagen 0 (el ítem lleno) también sale del algoritmo: se compara como un frame más
            Potions.Potion potion = Potions.potionFrames(e.bottle(), e.overlay(), e.color());
            want = e.images();
            got = new java.util.ArrayList<>(List.of(potion.full()));
            got.addAll(potion.frames());
        } else if (e.kind() == TEMPLATE || e.kind() == NO_TEMPLATE) {
            Templates.Group group = Templates.group(input, e.group());
            assertEquals(e.group().size(), group.size(), e.name() + ": todas las texturas son de la plantilla");
            Bowls.Own own = Templates.shapeBowl(input, e.group());
            BiteFrames.Result result = BiteFrames.template(input, e.group());
            if (e.kind() == NO_TEMPLATE) {
                assertEquals(null, own, e.name() + ": no es un tazón por plantilla");
                assertEquals(null, result, e.name() + ": no es un tazón por plantilla");
                got = List.of();
            } else {
                assertTrue(own != null, e.name() + ": tazón por plantilla");
                assertEquals(e.liquid().size() + " px " + e.liquid().first(),
                        own.contents().size() + " px " + own.contents().first(), e.name() + ": contenido");
                assertEquals(e.liquid(), own.contents(), e.name() + ": contenido");
                assertEquals(BiteFrames.Kind.BOWL, result.kind(), e.name() + ": tipo");
                got = new ArrayList<>();
                for (List<RgbaImage> layers : result.frames()) {
                    got.add(layers.get(0));
                }
                // el camino completo de la comida sin recipiente llega igual
                BiteFrames.Result guessed = BiteFrames.guessed(input, e.name(), false, List.of(), List.of(), e.group());
                assertEquals(BiteFrames.Kind.BOWL, guessed.kind(), e.name() + ": camino de guessed");
            }
        } else if (e.kind() == TOP_CUP || e.kind() == NO_SURFACE) {
            Mask surface = TopCups.surface(input);
            BiteFrames.Result result = BiteFrames.topCup(input);
            if (e.kind() == NO_SURFACE) {
                assertEquals(null, surface, e.name() + ": sin superficie");
                assertEquals(BiteFrames.NO_SURFACE, result.skipped(), e.name() + ": se omite");
                got = List.of();
            } else {
                assertTrue(surface != null, e.name() + ": superficie encontrada");
                assertEquals(e.liquid().size() + " px " + e.liquid().first(), surface.size() + " px " + surface.first(),
                        e.name() + ": superficie");
                assertEquals(e.liquid(), surface, e.name() + ": superficie");
                assertEquals(BiteFrames.Kind.CUP, result.kind(), e.name() + ": tipo");
                assertEquals(null, result.skipped(), e.name() + ": no se omite");
                got = new ArrayList<>();
                for (List<RgbaImage> layers : result.frames()) {
                    assertEquals(1, layers.size(), e.name() + ": una capa por fotograma");
                    got.add(layers.get(0));
                }
            }
        } else {
            if (e.empty() != null || e.own()) {
                Cups.Choice choice = e.own() ? Cups.chooseOwn(input) : Cups.choose(input, e.empty(), e.eaten());
                assertTrue(choice != null, e.name() + ": dibuja su propio envase");
                if (e.kind() == OPAQUE) {
                    assertEquals(Cups.OPAQUE, choice.skipped(), e.name() + ": se omite por recipiente opaco");
                } else if (e.kind() == LABEL) {
                    assertEquals(OwnGlass.LABELED, choice.skipped(), e.name() + ": se omite por la etiqueta");
                } else {
                    assertEquals(null, choice.skipped(), e.name() + ": no se omite");
                    assertEquals(kind(e.kind()), choice.kind(), e.name() + ": camino");
                }
                Mask found = choice.contents();
                assertEquals(e.liquid().size() + " px " + (e.liquid().isEmpty() ? "-" : e.liquid().first()),
                        found.size() + " px " + (found.isEmpty() ? "-" : found.first()),
                        e.name() + ": contenido detectado");
                assertEquals(e.liquid(), found, e.name() + ": contenido detectado");
                got = Cups.frames(input, e.empty(), choice);
            } else {
                assertEquals(DRINK, e.kind(), "tipo sin recipiente vacío");
                got = Drinks.liquidFrames(input, e.liquid(), null);
            }
        }
        assertEquals(want.size(), got.size(), "cantidad de fotogramas");
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < want.size(); i++) {
            String diff = diff(want.get(i), got.get(i));
            if (!diff.isEmpty()) {
                report.append("\n  frame ").append(i + 1).append(": ").append(diff);
            }
        }
        if (report.length() > 0) {
            fail(e.name() + report);
        }
    }

    /** Vacío si son iguales; si no, cuántos píxeles cambian de forma (alfa) y cuántos solo de color. */
    static String diff(RgbaImage want, RgbaImage got) {
        int shape = 0;
        int color = 0;
        String firstShape = "";
        String firstColor = "";
        for (int y = 0; y < want.height; y++) {
            for (int x = 0; x < want.width; x++) {
                int[] a = want.get(x, y);
                int[] b = got.get(x, y);
                if (a[3] != b[3]) {
                    if (shape++ == 0) {
                        firstShape = " primero (" + x + ", " + y + ") alfa " + a[3] + " -> " + b[3];
                    }
                } else if (a[3] != 0 && (a[0] != b[0] || a[1] != b[1] || a[2] != b[2])) {
                    if (color++ == 0) {
                        firstColor = " primero (" + x + ", " + y + ") " + a[0] + "," + a[1] + "," + a[2]
                                + " -> " + b[0] + "," + b[1] + "," + b[2];
                    }
                }
            }
        }
        if (shape == 0 && color == 0) {
            return "";
        }
        return "forma " + shape + firstShape + "; color " + color + firstColor;
    }
}
