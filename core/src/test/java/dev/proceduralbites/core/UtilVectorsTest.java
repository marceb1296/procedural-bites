package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Compara las utilidades con los vectores de referencia, caso por caso y sin tolerancia. */
class UtilVectorsTest {

    @Test
    void matchesPrototype() throws IOException {
        List<String> lines = readLines();
        TreeSet<String> kinds = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split(" \\| ", -1);
            String where = "línea " + (i + 1) + ": " + f[0];
            check(f, where);
            kinds.add(f[0]);
        }
        assertEquals(18, kinds.size(), kinds.toString());
    }

    private static void check(String[] f, String where) {
        switch (f[0]) {
            case "erode" -> assertEquals(mask(f[2]), Masks.erode(mask(f[1])), where);
            case "dilate" -> assertEquals(mask(f[2]), Masks.dilate(mask(f[1])), where);
            case "opening" -> assertEquals(mask(f[3]), Masks.opening(mask(f[2]), Integer.parseInt(f[1])), where);
            case "components8" -> assertEquals(masks(f[2]), Masks.bySize(Masks.components(mask(f[1]))), where);
            case "components4" ->
                assertEquals(masks(f[2]), Masks.bySize(Masks.components(mask(f[1]), Masks.N4)), where);
            case "thick_part" ->
                assertEquals(mask(f[3]), Masks.thickPart(mask(f[2]), Integer.parseInt(f[1])), where);
            case "depth_map" -> assertEquals(depth(f[2]), new TreeMap<>(Masks.depthMap(mask(f[1]))), where);
            case "outer_border" -> assertEquals(mask(f[2]), Masks.outerBorder(mask(f[1])), where);
            case "visual_components" ->
                assertEquals(masks(f[3]), Masks.bySize(Masks.visualComponents(mask(f[1]), mask(f[2]))), where);
            case "hsv" -> assertArrayEquals(doubles(f[2]), Colors.hsv(ints(f[1])), where);
            case "luma" -> assertEquals(Double.parseDouble(f[2]), Colors.luma(ints(f[1])), where);
            case "lerp" -> assertArrayEquals(ints(f[4]),
                Colors.lerp(ints(f[1]), ints(f[2]), Double.parseDouble(f[3])), where);
            case "lerpf" -> assertArrayEquals(ints(f[4]),
                Colors.lerp(doubles(f[1]), ints(f[2]), Double.parseDouble(f[3])), where);
            case "hue_gap" -> assertEquals(Double.parseDouble(f[3]),
                Colors.hueGap(Double.parseDouble(f[1]), Double.parseDouble(f[2])), where);
            case "round" -> assertEquals(Integer.parseInt(f[2]), PyMath.round(Double.parseDouble(f[1])), where);
            case "mod" -> assertEquals(Double.parseDouble(f[3]),
                PyMath.mod(Double.parseDouble(f[1]), Double.parseDouble(f[2])), where);
            case "sum" -> assertEquals(num(f[2]), PyMath.sum(doubles(f[1])), where);
            case "norm" -> assertEquals(Double.parseDouble(f[3]),
                PyMath.norm(Double.parseDouble(f[1]), Double.parseDouble(f[2])), where);
            default -> throw new AssertionError(where + ": tipo de caso desconocido");
        }
    }

    private static List<String> readLines() throws IOException {
        try (InputStream in = UtilVectorsTest.class.getResourceAsStream("/util-vectors.txt")) {
            assertTrue(in != null, "falta util-vectors.txt");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return r.lines().filter(l -> !l.isEmpty()).toList();
        }
    }

    private static Mask mask(String s) {
        Mask m = new Mask();
        if (!s.equals("-")) {
            for (String p : s.split(";")) {
                String[] xy = p.split(",");
                m.add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
            }
        }
        return m;
    }

    private static List<Mask> masks(String s) {
        List<Mask> out = new ArrayList<>();
        if (!s.equals("-")) {
            for (String m : s.split(" / ")) {
                out.add(mask(m));
            }
        }
        return out;
    }

    private static Map<Point, Integer> depth(String s) {
        Map<Point, Integer> out = new TreeMap<>();
        if (!s.equals("-")) {
            for (String e : s.split(";")) {
                String[] kv = e.split("=");
                String[] xy = kv[0].split(",");
                out.put(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])), Integer.parseInt(kv[1]));
            }
        }
        return out;
    }

    private static int[] ints(String s) {
        return Arrays.stream(s.split(" ")).mapToInt(Integer::parseInt).toArray();
    }

    private static double[] doubles(String s) {
        return s.equals("-") ? new double[0] : Arrays.stream(s.split(" ")).mapToDouble(UtilVectorsTest::num).toArray();
    }

    /** Números como los escribe Python, incluidos {@code inf}, {@code -inf} y {@code nan}. */
    private static double num(String s) {
        return switch (s) {
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }
}
