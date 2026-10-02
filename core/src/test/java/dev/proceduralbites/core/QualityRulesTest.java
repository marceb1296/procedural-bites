package dev.proceduralbites.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.proceduralbites.core.Params.BitePattern;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Reglas de calidad sobre los fotogramas: sin pedazos sueltos ni partes delgadas, contraste
 * del contorno, etiquetas y adornos intactos, frascos cerrados, tazones sin agujeros, copas y
 * envases propios que no quedan abiertos y pociones con el vidrio opaco. Usa las texturas de
 * {@link FramesTest}: sintéticas siempre, locales con {@code -PexpectedDir}.
 */
class QualityRulesTest {
    @TestFactory
    Stream<DynamicTest> synthetic() throws IOException, URISyntaxException {
        Path dir = Paths.get(QualityRulesTest.class.getResource("/frames").toURI());
        List<Path> files = FramesTest.binFiles(dir);
        assertTrue(files.size() >= 30, "faltan texturas sintéticas: " + files.size());
        return files.stream().map(f -> {
            String name = f.getFileName().toString().replace(".bin", "");
            return DynamicTest.dynamicTest(name, () -> check(f, "sintética/" + name));
        });
    }

    @TestFactory
    Stream<DynamicTest> local() throws IOException {
        String prop = System.getProperty("expectedDir", "");
        Assumptions.assumeTrue(!prop.isEmpty() && Files.isDirectory(Paths.get(prop, "solids")),
                "sin datos locales (-PexpectedDir)");
        List<Path> files = new ArrayList<>(FramesTest.binFiles(Paths.get(prop, "solids")));
        files.addAll(FramesTest.binFiles(Paths.get(prop, "drinks")));
        files.addAll(FramesTest.binFiles(Paths.get(prop, "potions")));
        return files.stream().map(f -> {
            String key = f.getFileName().toString().replace(".bin", "").replace("__", "/");
            return DynamicTest.dynamicTest(f.getParent().getFileName() + "/" + key, () -> check(f, key));
        });
    }

    private static void check(Path file, String key) throws IOException {
        FramesTest.Expected e = FramesTest.read(file);
        RgbaImage input = e.images().get(0);
        List<String> bad = switch (e.kind()) {
            case 1 -> {
                List<String> out = islands(input, e.name());
                out.addAll(contrast(input, e.name()));
                yield out;
            }
            case 2, 3, 5, 6, 7 -> {
                if (e.own()) {
                    // las reglas de un envase con dibujo propio
                    OwnGlass.Parts parts = OwnGlass.ownGlass(input);
                    yield parts == null ? new ArrayList<>() : ownGlass(input, parts);
                }
                if (e.empty() == null) {
                    yield container(input, e.liquid(), null, null, null);
                }
                // las reglas del camino que elige el mod
                Cups.Choice choice = Cups.choose(input, e.empty(), e.eaten());
                if (choice.skipped() != null) {
                    yield new ArrayList<>();   // se omite: no hay fotogramas que revisar
                }
                List<RgbaImage> frames = Cups.frames(input, e.empty(), choice);
                yield choice.kind() == BiteFrames.Kind.CUP
                        ? cup(input, choice.contents(), e.empty(), frames)
                        : container(input, choice.contents(), e.empty(), choice.kind() == BiteFrames.Kind.BOWL, frames);
            }
            case 4 -> potion(e.bottle(), e.overlay(), e.color());
            case FramesTest.TOP_CUP -> topCup(input);
            case FramesTest.NO_SURFACE, FramesTest.NO_TEMPLATE -> new ArrayList<>();   // sin fotogramas que revisar
            case FramesTest.TEMPLATE -> {
                // tazón por plantilla: las reglas de los tazones
                Bowls.Own own = Templates.shapeBowl(input, e.group());
                yield container(input, own.contents(), null, true, Templates.frames(input, own));
            }
            default -> throw new AssertionError("tipo " + e.kind());
        };
        if (!bad.isEmpty()) {
            fail(key + ": " + String.join("; ", bad));
        }
    }

    static List<String> islands(RgbaImage im, String name) {
        List<String> bad = new ArrayList<>();
        Mask orig = Masks.opaque(im);
        int base = Masks.visualComponents(orig, orig).size();
        int size = Params.MIN_THICKNESS * im.pxScale();
        Mask thick0 = Masks.thickPart(orig, size);
        List<Eaten> geometry = Solids.solidGeometry(im, name, BitePattern.AUTO);
        for (int i = 0; i < geometry.size(); i++) {
            Mask rem = orig.minus(geometry.get(i).pixels());
            Mask thin = rem.and(thick0).minus(Masks.thickPart(rem, size));
            if (!thin.isEmpty()) {
                bad.add("frame " + (i + 1) + ": " + thin.size() + " px más delgados que "
                        + Params.MIN_THICKNESS + " px, primero " + thin.first());
            }
            List<Mask> comps = Masks.visualComponents(rem, orig);
            if (comps.size() > base) {
                bad.add("frame " + (i + 1) + ": " + comps.size() + " piezas (original " + base + ")");
            }
        }
        // fruta con tallo: el corazón va de la primera a la última fila del original (si no
        // llegaría, termina en un último bocado, que tiene dirección de mordida)
        int k = im.pxScale();
        if (Solids.hasStem(orig, k) && geometry.get(geometry.size() - 1).biteDir() == null) {
            Mask core = orig.minus(geometry.get(geometry.size() - 1).pixels());
            int top = core.first().y();
            int bottom = Integer.MIN_VALUE;
            int origBottom = Integer.MIN_VALUE;
            for (Point p : core) {
                bottom = Math.max(bottom, p.y());
            }
            for (Point p : orig) {
                origBottom = Math.max(origBottom, p.y());
            }
            if (top != orig.first().y() || bottom != origBottom) {
                bad.add("el corazón va de la fila " + top + " a la " + bottom + ", el original de la "
                        + orig.first().y() + " a la " + origBottom);
            }
        }
        return bad;
    }

    /** Vacío si cumple la regla o si no aplica. */
    static List<String> contrast(RgbaImage im, String name) {
        double[] r = contrastOf(im, name);
        List<String> bad = new ArrayList<>();
        if (r == null) {
            return bad;
        }
        String numbers = String.format(java.util.Locale.ROOT, "original %.1f ±%.1f, diferencia %+.1f, repintados %s",
                r[0], r[1], r[2], Double.isNaN(r[3]) ? "-" : String.format(java.util.Locale.ROOT, "%+.1f", r[3]));
        if (Math.abs(r[2]) > r[1] || (!Double.isNaN(r[3]) && Math.abs(r[3]) > r[1])) {
            bad.add("contraste fuera de la regla: " + numbers);
        }
        return bad;
    }

    /**
     * {contraste original, desviación, diferencia media del último bocado, diferencia de los
     * repintados}, o {@code null} si el último fotograma no es un bocado compacto.
     */
    static double[] contrastOf(RgbaImage im, String name) {
        Mask mask = Masks.opaque(im);
        Map<Point, Double> orig = perPixel(im, Masks.outerBorder(mask), mask);
        List<Eaten> geometry = Solids.solidGeometry(im, name, BitePattern.AUTO);
        Eaten last = geometry.get(geometry.size() - 1);
        double[] d = last.biteDir();
        if (d == null) {
            return null;
        }
        Mask rem = mask.minus(last.pixels());
        long sx = 0;
        long sy = 0;
        for (Point p : rem) {
            sx += p.x();
            sy += p.y();
        }
        double[] center = {(double) sx / rem.size(), (double) sy / rem.size()};
        Mask border = new Mask();
        for (Point p : Masks.outerBorder(rem)) {
            if (!Solids.biteSide(p, center, d)) {
                border.add(p);
            }
        }
        RgbaImage rendered = Solids.renderSolid(im, last, Params.EDGE_MODE);
        Map<Point, Double> now = perPixel(rendered, border, rem);
        if (orig.isEmpty() || now.isEmpty()) {
            return null;
        }
        double[] values = orig.values().stream().mapToDouble(Double::doubleValue).toArray();
        double mean = PyMath.sum(values) / values.length;
        double[] sq = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).toArray();
        List<Double> diffs = new ArrayList<>();
        List<Double> painted = new ArrayList<>();
        for (Map.Entry<Point, Double> e : now.entrySet()) {
            Double ref = orig.get(e.getKey());
            diffs.add(e.getValue() - (ref != null ? ref : mean));
            // límite físico: un repintado que ya es negro no puede oscurecerse más; no cuenta
            int[] c = rendered.get(e.getKey());
            boolean black = c[0] == 0 && c[1] == 0 && c[2] == 0;
            if (ref == null && !(black && e.getValue() < mean)) {
                painted.add(e.getValue() - mean);
            }
        }
        return new double[] {mean, Math.sqrt(PyMath.sum(sq) / values.length), average(diffs),
            painted.isEmpty() ? Double.NaN : average(painted)};
    }

    private static double average(List<Double> xs) {
        return PyMath.sum(xs.stream().mapToDouble(Double::doubleValue).toArray()) / xs.size();
    }

    /** Por píxel del borde, luminancia media del interior vecino menos la del borde. */
    private static Map<Point, Double> perPixel(RgbaImage px, Mask border, Mask region) {
        Mask fullBorder = Masks.outerBorder(region);
        Map<Point, Double> out = new LinkedHashMap<>();
        for (Point b : border) {
            List<Double> inner = new ArrayList<>();
            for (Point n : Masks.nbrs(b)) {
                if (region.contains(n) && !fullBorder.contains(n)) {
                    inner.add(Colors.luma(px.get(n)));
                }
            }
            if (!inner.isEmpty()) {
                out.put(b, average(inner) - Colors.luma(px.get(b)));
            }
        }
        return out;
    }

    /**
     * Envase propio: sin tapón, el envase no cambia, cuerpo y adorno bajan con su corte, el
     * vaso no queda abierto y no aparecen restos ni nada fuera de la silueta.
     */
    static List<String> ownGlass(RgbaImage full, OwnGlass.Parts parts) {
        List<String> bad = new ArrayList<>();
        int k = full.pxScale();
        int w = full.width;
        int h = full.height;
        Mask vessel = parts.vessel();
        Mask body = parts.body();
        Mask garnish = parts.garnish();
        Mask stopper = parts.stopper();
        Mask food = parts.contents();
        List<RgbaImage> frames = OwnGlass.frames(full, parts);
        Mask all = Masks.opaque(full);
        int level = body.first().y();
        List<Integer> glassRows = new ArrayList<>();
        for (Map.Entry<Integer, List<int[]>> row : OwnGlass.rowRuns(all).entrySet()) {
            int[] run = row.getValue().get(0);
            boolean glass = row.getValue().size() == 1;
            for (int x = run[0]; glass && x <= run[1]; x++) {
                glass = OwnGlass.glassLike(full.get(x, row.getKey()));
            }
            if (glass) {
                glassRows.add(row.getKey());
            }
        }
        java.util.function.Predicate<Mask> overGlass = c -> {
            int low = maxY(c);
            return glassRows.stream().anyMatch(y -> low < y && y < level);
        };
        for (Mask c : Masks.components(stopper)) {
            if (!overGlass.test(c) || c.stream().anyMatch(q -> OwnGlass.glassLike(full.get(q)))) {
                bad.add("tapón que no está sobre una fila de vidrio o es vidrio, en " + c.first());
            }
        }
        Mask plain = new Mask();
        for (Point q : vessel) {
            if (!OwnGlass.glassLike(full.get(q))) {
                plain.add(q);
            }
        }
        for (Mask c : Masks.components(plain)) {
            if (overGlass.test(c)) {
                bad.add("tapón que queda como envase, en " + c.first());
            }
        }
        for (int i = 0; i < frames.size(); i++) {
            RgbaImage fr = frames.get(i);
            List<Point> left = stopper.stream().filter(p -> fr.alpha(p.x(), p.y()) > 0).toList();
            if (!left.isEmpty()) {
                bad.add("frame " + (i + 1) + ": queda tapón en " + left.size() + " px, primero " + left.get(0));
            }
        }
        Mask mask = all.minus(stopper);
        Set<Mask> drawn = new HashSet<>(Masks.components(mask));
        Set<Integer> colors = new HashSet<>();
        for (Point p : vessel) {
            colors.add(rgba(full.get(p)));
        }
        Mask closed = Masks.enclosed(vessel.union(Cups.cupEdge(vessel, food)), w, h);
        boolean sealable = closed.size() > Masks.enclosed(vessel, w, h).size();
        Map<Point, int[]> mirror = new LinkedHashMap<>();
        for (Point p : garnish) {
            Point q = new Point(parts.axis() - p.x(), p.y());
            if (vessel.contains(q)) {
                mirror.put(p, full.get(q));
            }
        }
        int[] prev = {body.first().y(), garnish.isEmpty() ? 0 : garnish.first().y()};
        String[] labels = {"cuerpo", "adorno"};
        Mask[] both = {body, garnish};
        for (int i = 0; i < frames.size(); i++) {
            RgbaImage fr = frames.get(i);
            boolean last = i == frames.size() - 1;
            String at = "frame " + (i + 1) + ": ";
            Mask now = Masks.opaque(fr);
            if (!now.minus(mask).isEmpty()) {
                bad.add(at + now.minus(mask).size() + " px fuera de la silueta");
            }
            List<Point> changed = vessel.stream().filter(p -> !Arrays.equals(fr.get(p), full.get(p))).toList();
            if (!changed.isEmpty()) {
                bad.add(at + "el envase cambia en " + changed.size() + " px, primero " + changed.get(0));
            }
            for (Mask c : Masks.components(now)) {
                if (c.size() <= 2 * k * k && !drawn.contains(c)
                        && !c.stream().allMatch(p -> OwnGlass.glassLike(full.get(p)))) {
                    bad.add(at + "resto flotando de " + c.size() + " px en " + c.first());
                }
            }
            if (sealable) {
                Mask leak = closed.minus(now).minus(Masks.enclosed(now, w, h));
                if (!leak.isEmpty()) {
                    bad.add(at + "el vaso queda abierto (" + leak.size() + " px del interior ya no están encerrados)");
                }
            }
            for (int j = 0; j < both.length; j++) {
                String label = labels[j];
                Mask part = both[j];
                Mask left = new Mask();
                List<Point> odd = new ArrayList<>();
                for (Point p : part) {
                    int[] c = fr.get(p);
                    if (Arrays.equals(c, full.get(p))) {
                        left.add(p);
                    } else if (c[3] > 0 && !colors.contains(rgba(c))) {
                        odd.add(p);
                    }
                }
                if (!odd.isEmpty()) {
                    bad.add(at + label + " repintado con un color que no es del envase en " + odd.size()
                            + " px, primero " + odd.get(0));
                }
                if (left.isEmpty()) {
                    if (j == 0) {
                        bad.add(at + "no queda contenido");
                    } else if (!last && Drinks.rowsOf(part).size() >= frames.size()) {
                        bad.add(at + "no queda adorno");
                    }
                    continue;
                }
                int cut = left.first().y();
                if (cut <= prev[j]) {
                    bad.add(at + "el " + label + " no baja (corte en la fila " + cut + ", antes " + prev[j] + ")");
                }
                prev[j] = cut;
                Mask missing = new Mask();
                for (Point p : part) {
                    if (p.y() >= cut && !left.contains(p)) {
                        missing.add(p);
                    }
                }
                for (Mask c : Masks.components(missing)) {
                    if (c.size() > 2 * k * k) {
                        bad.add(at + "falta " + label + " bajo el corte, " + c.size() + " px en " + c.first());
                        break;
                    }
                }
                if (last && j == 1) {
                    bad.add(at + "queda adorno en " + left.size() + " px, primero " + left.first());
                }
                if (last && j == 0 && left.size() * 2 >= body.size()) {
                    bad.add(at + "queda " + left.size() + " de " + body.size() + " px de contenido");
                }
            }
            if (last) {
                List<Point> wrong = mirror.entrySet().stream()
                        .filter(m -> !Arrays.equals(fr.get(m.getKey()), m.getValue())).map(Map.Entry::getKey).toList();
                if (!wrong.isEmpty()) {
                    bad.add(at + wrong.size() + " px del envase tapados por el adorno no se reponen, primero "
                            + wrong.get(0));
                }
            }
        }
        return bad;
    }

    private static int rgba(int[] c) {
        return (c[3] << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /** Taza vista desde arriba: la silueta no cambia, el nivel nunca sube y queda superficie. */
    static List<String> topCup(RgbaImage full) {
        List<String> bad = new ArrayList<>();
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        Mask surface = TopCups.surface(full);
        if (surface == null) {
            return List.of("no se encontró la superficie");
        }
        Mask shell = mask.minus(surface);
        if (shell.isEmpty()) {
            return List.of("no hay envase alrededor de la superficie");
        }
        List<RgbaImage> frames = TopCups.frames(full, surface);
        Set<Integer> colors = new HashSet<>();
        Set<Integer> rows = new HashSet<>();
        for (Point p : surface) {
            colors.add(rgba(full.get(p)));
            rows.add(p.y());
        }
        Mask prev = surface;
        for (int i = 1; i <= frames.size(); i++) {
            RgbaImage fr = frames.get(i - 1);
            Mask now = Masks.opaque(fr);
            if (!now.equals(mask)) {
                bad.add("frame " + i + ": la silueta cambia en "
                        + (now.minus(mask).size() + mask.minus(now).size()) + " px");
            }
            int changed = 0;
            Point firstChanged = null;
            for (Point p : shell) {
                if (rgba(fr.get(p)) != rgba(full.get(p))) {
                    if (changed++ == 0) {
                        firstChanged = p;
                    }
                }
            }
            if (changed > 0) {
                bad.add("frame " + i + ": el envase cambia en " + changed + " px, primero " + firstChanged);
            }
            Mask left = new Mask();
            Set<Integer> walls = new HashSet<>();
            for (Point p : surface) {
                if (rgba(fr.get(p)) == rgba(full.get(p))) {
                    left.add(p);
                } else {
                    walls.add(rgba(fr.get(p)));
                }
            }
            Mask gone = surface.minus(left);
            boolean shared = false;
            for (int c : walls) {
                shared |= colors.contains(c);
            }
            if (walls.size() > 1 || shared) {
                bad.add("frame " + i + ": lo destapado tiene " + walls.size() + " colores o uno de la superficie");
            }
            if (left.isEmpty()) {
                bad.add("frame " + i + ": no queda superficie");
                continue;
            }
            Mask back = left.minus(prev);
            if (!back.isEmpty()) {
                bad.add("frame " + i + ": el nivel sube (" + back.size() + " px vuelven)");
            }
            for (Point p : gone) {
                boolean above = false;
                for (Point q : left) {
                    above |= q.x() == p.x() && q.y() < p.y();
                }
                if (above) {
                    bad.add("frame " + i + ": queda superficie por encima de lo destapado en " + p);
                    break;
                }
            }
            prev = left;
        }
        if (prev.equals(surface)) {
            bad.add("el nivel no baja");
        }
        if (rows.size() > 2 * k && prev.size() * 2 >= surface.size()) {
            bad.add("último frame: queda " + prev.size() + " de " + surface.size() + " px de superficie");
        }
        return bad;
    }

    static List<String> cup(RgbaImage full, Mask contents, RgbaImage empty, List<RgbaImage> frames) {
        List<String> bad = new ArrayList<>();
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        Set<Integer> palette = new HashSet<>();
        for (int[] c : Cups.glassPalette(empty)) {
            palette.add((c[0] << 16) | (c[1] << 8) | c[2]);
        }
        java.util.function.Predicate<Point> glass = p -> {
            int[] c = full.get(p);
            return palette.contains((c[0] << 16) | (c[1] << 8) | c[2]);
        };
        int rim = mask.stream().filter(glass).mapToInt(Point::y).min().orElse(0);
        Set<Mask> drawn = new HashSet<>(Masks.components(mask));
        Mask shell = mask.minus(contents);
        int w = full.width;
        int h = full.height;
        Mask seal = Cups.cupSeal(full, contents);
        Mask closed = shell.isEmpty() ? new Mask() : Masks.enclosed(shell.union(Cups.cupEdge(shell, contents)), w, h);
        boolean sealable = closed.size() > Masks.enclosed(shell, w, h).size();
        int[] box = shell.isEmpty() ? null : Cups.cupBox(shell);
        java.util.function.Predicate<Point> inside =
                p -> p.x() >= box[0] && p.x() <= box[2] && p.y() >= box[1] && p.y() <= box[3];
        int prevCut = contents.first().y();
        for (int i = 0; i < frames.size(); i++) {
            RgbaImage fr = frames.get(i);
            boolean last = i == frames.size() - 1;
            String at = "frame " + (i + 1) + ": ";
            List<Point> changed = shell.stream().filter(p -> !Arrays.equals(fr.get(p), full.get(p))).toList();
            if (!changed.isEmpty()) {
                bad.add(at + "el vidrio cambia en " + changed.size() + " px, primero " + changed.get(0));
            }
            Mask now = Masks.opaque(fr);
            for (Mask c : Masks.components(now)) {
                if (c.size() <= 2 * k * k && !drawn.contains(c) && !c.stream().allMatch(glass)) {
                    bad.add(at + "resto flotando de " + c.size() + " px en " + c.first());
                }
            }
            // el vaso no queda abierto: lo que el vidrio encierra con la comida que está sobre
            // los lados o la base de su rectángulo sigue encerrado (u opaco) en el frame
            if (sealable) {
                Mask leak = closed.minus(now).minus(Masks.enclosed(now, w, h));
                if (!leak.isEmpty()) {
                    bad.add(at + "el vaso queda abierto (" + leak.size() + " px del interior ya no están encerrados)");
                }
            }
            // el sello (comida sobre el vidrio que lo cierra) toma color de vidrio: ya no es comida
            Mask painted = new Mask();
            for (Point p : seal) {
                if (!Arrays.equals(fr.get(p), full.get(p))) {
                    painted.add(p);
                    int[] c = fr.get(p);
                    if (c[3] == 0 || !palette.contains((c[0] << 16) | (c[1] << 8) | c[2])) {
                        bad.add(at + "el sello no es vidrio en " + p);
                    }
                }
            }
            Mask left = new Mask();
            for (Point p : contents) {
                if (fr.alpha(p.x(), p.y()) > 0 && !painted.contains(p)) {
                    left.add(p);
                }
            }
            if (left.isEmpty()) {
                // en el último frame puede no quedar nada: lo que quedaba eran restos sueltos
                // de hasta 2 px o comida fuera del vaso, que se borran
                if (!last) {
                    bad.add(at + "no queda comida");
                }
                continue;
            }
            int cut = left.first().y();
            if (cut <= prevCut) {
                bad.add(at + "no baja (corte en la fila " + cut + ", antes " + prevCut + ")");
            }
            prevCut = cut;
            List<Point> wrong = left.stream().filter(p -> !Arrays.equals(fr.get(p), full.get(p))).toList();
            if (!wrong.isEmpty()) {
                bad.add(at + "comida repintada en " + wrong.size() + " px, primero " + wrong.get(0));
            }
            // bajo el corte solo faltan restos sueltos chicos y, en el último frame, la comida
            // de fuera del vaso
            Mask missing = new Mask();
            for (Point p : contents) {
                if (p.y() >= cut && !seal.contains(p) && !left.contains(p) && (!last || inside.test(p))) {
                    missing.add(p);
                }
            }
            if (last) {
                List<Point> outside = left.stream().filter(inside.negate()).toList();
                if (!outside.isEmpty()) {
                    bad.add(at + "queda comida fuera del vaso en " + outside.size() + " px, primero " + outside.get(0));
                }
            }
            for (Mask c : Masks.components(missing)) {
                if (c.size() > 2 * k * k) {
                    bad.add(at + "falta comida bajo el corte, " + c.size() + " px en " + c.first());
                    break;
                }
            }
            if (last) {
                List<Point> over = now.stream().filter(p -> p.y() < rim).toList();
                if (!over.isEmpty()) {
                    bad.add(at + "queda algo sobre la boca en " + over.size() + " px, primero " + over.get(0));
                }
                if (left.size() * 4 >= contents.size()) {
                    bad.add(at + "queda " + left.size() + " de " + contents.size() + " px de comida");
                }
            }
        }
        return bad;
    }

    /**
     * {@code asBowl}: revisar como tazón ({@code null} = lo decide el recipiente); {@code given}:
     * fotogramas ya hechos.
     */
    static List<String> container(RgbaImage full, Mask liquid, RgbaImage empty, Boolean asBowl, List<RgbaImage> given) {
        List<String> bad = new ArrayList<>();
        int k = full.pxScale();
        Mask mask = Masks.opaque(full);
        boolean bowl = asBowl != null ? asBowl
                : empty != null && !Drinks.isGlass(empty) && !Drinks.aligned(full, empty, liquid);
        List<RgbaImage> frames = given != null ? given
                : bowl ? Bowls.bowlFrames(full, liquid, empty) : Drinks.liquidFrames(full, liquid, empty);
        if (bowl) {
            Mask bowlSil = Masks.opaque(frames.get(frames.size() - 1));   // el tazón, ya sin montón
            Bowls.Ellipse rim = Bowls.rimEllipse(bowlSil, k);
            Set<Mask> original = new HashSet<>(Masks.components(mask));
            for (int i = 0; i < frames.size(); i++) {
                Mask m = Masks.opaque(frames.get(i));
                // una sola pieza; las que ya estaban sueltas en el original (migas junto al
                // tazón) pueden seguir, enteras, hasta el penúltimo frame
                boolean lastFrame = i == frames.size() - 1;
                List<Mask> comps = Masks.bySize(Masks.components(m));
                long loose = comps.stream().skip(1).filter(c -> lastFrame || !original.contains(c)).count();
                if (loose != 0) {
                    bad.add("frame " + (i + 1) + ": " + (loose + 1) + " piezas");
                }
                // agujeros nuevos: los que ya trae la textura se respetan
                int holes = Masks.enclosed(m, full.width, full.height)
                        .minus(Masks.enclosed(mask, full.width, full.height)).size();
                if (holes != 0) {
                    bad.add("frame " + (i + 1) + ": " + holes + " px de agujero");
                }
                // la silueta solo se achica (el montón baja) y lo que sobra respecto del
                // tazón final es comida del original, con su color
                if (i > 0) {
                    Mask grown = m.minus(Masks.opaque(frames.get(i - 1)));
                    if (!grown.isEmpty()) {
                        bad.add("frame " + (i + 1) + ": la silueta crece en " + grown.size() + " px");
                    }
                }
                RgbaImage fr = frames.get(i);
                List<Point> extra = m.minus(bowlSil).stream()
                        .filter(p -> !liquid.contains(p) || !Arrays.equals(fr.get(p), full.get(p))).toList();
                if (!extra.isEmpty()) {
                    bad.add("frame " + (i + 1) + ": " + extra.size()
                            + " px fuera del tazón que no son comida del original, primero " + extra.get(0));
                }
                List<Point> wall = mask.minus(liquid).stream()
                        .filter(p -> !rim.contains(p) && !Arrays.equals(fr.get(p), full.get(p))).toList();
                if (!wall.isEmpty()) {
                    bad.add("frame " + (i + 1) + ": pared o dibujo fuera de la boca cambia en " + wall.size()
                            + " px, primero " + wall.get(0));
                }
            }
            return bad;
        }
        Set<Mask> drawn = new HashSet<>(Masks.components(mask));
        if (empty != null && empty.width == full.width && empty.height == full.height) {
            drawn.addAll(Masks.components(Masks.opaque(empty)));
        }
        boolean sideView = empty != null ? Drinks.isGlass(empty)
                : maxY(liquid) >= maxY(mask) - 2 * k;
        boolean sameShape = empty != null && Drinks.aligned(full, empty, liquid);
        Mask label = new Mask();
        Mask closed = new Mask();
        Mask foam = new Mask();
        if (sideView) {
            Drinks.Split split = Drinks.splitLabel(full, liquid);
            Mask protectedPx = new Mask(split.label());
            Mask shell = mask.minus(liquid);
            if (!shell.isEmpty()) {
                // lo que asoma, calculado aquí y no con splitLabel: espuma o crema si es
                // clara y poco saturada; si no, adorno
                double[] v = new double[split.core().size()];
                int n = 0;
                for (Point p : split.core()) {
                    v[n++] = Colors.hsv(full.get(p))[2];
                }
                double bright = v.length == 0 ? 0.0 : PyMath.sum(v) / v.length;
                List<Mask> garnish = new ArrayList<>();
                for (Mask c : Masks.components(liquid.minus(split.core()))) {
                    if (c.first().y() < shell.first().y()) {
                        if (Drinks.isFoam(full, c, bright)) {
                            foam.addAll(c);
                        } else {
                            garnish.add(c);
                        }
                    }
                }
                protectedPx = protectedPx.minus(foam);
                for (Mask c : garnish) {
                    protectedPx.addAll(c);
                }
                // tapa: pieza aparte del contenido, entera por encima del recipiente
                for (Mask c : Masks.components(liquid)) {
                    if (c.size() != liquid.size() && maxY(c) < shell.first().y() && !Drinks.isFoam(full, c, bright)) {
                        protectedPx.addAll(c);
                    }
                }
            }
            // el corcho se quita aunque el contenido lo incluya: no es adorno ni pared
            Mask stopper = empty != null ? Drinks.cork(full, liquid, empty) : new Mask();
            for (Mask c : Masks.components(protectedPx.minus(stopper))) {
                if (c.size() > 2 * k * k) {
                    label.addAll(c);
                }
            }
            Mask walls = mask.minus(liquid).minus(stopper);
            for (Point p : Masks.outerBorder(mask).and(liquid.minus(stopper))) {
                if (betweenWalls(p, walls) && !(sameShape && empty.alpha(p.x(), p.y()) == 0)) {
                    closed.add(p);
                }
            }
        }
        // la espuma o la crema se consumen: nada queda igual en el último frame
        RgbaImage last = frames.get(frames.size() - 1);
        List<Point> floating = foam.stream().filter(p -> Arrays.equals(last.get(p), full.get(p))).toList();
        if (!floating.isEmpty()) {
            bad.add("frame " + frames.size() + ": espuma o crema que queda en " + floating.size() + " px, primero "
                    + floating.get(0));
        }
        for (int i = 0; i < frames.size(); i++) {
            RgbaImage fr = frames.get(i);
            List<Point> broken = label.stream().filter(p -> !Arrays.equals(fr.get(p), full.get(p))).toList();
            if (!broken.isEmpty()) {
                bad.add("frame " + (i + 1) + ": etiqueta cambia en " + broken.size() + " px, primero " + broken.get(0));
            }
            List<Point> opened = closed.stream().filter(p -> fr.alpha(p.x(), p.y()) == 0).toList();
            if (!opened.isEmpty()) {
                bad.add("frame " + (i + 1) + ": frasco abierto en " + opened.size() + " px, primero " + opened.get(0));
            }
            for (Mask c : Masks.components(Masks.opaque(fr))) {
                if (c.size() <= 2 * k * k && !drawn.contains(c)) {
                    bad.add("frame " + (i + 1) + ": resto flotando de " + c.size() + " px en " + c.first());
                }
            }
        }
        return bad;
    }

    static List<String> potion(RgbaImage bottle, RgbaImage overlay, int[] color) {
        List<String> bad = new ArrayList<>();
        List<RgbaImage> frames = Potions.potionFrames(bottle, overlay, color).frames();
        Mask glass = Masks.opaque(bottle).minus(Drinks.cork(bottle, new Mask(), null));
        for (int i = 0; i < frames.size(); i++) {
            Mask open = glass.minus(Masks.opaque(frames.get(i)));
            if (!open.isEmpty()) {
                bad.add("frame " + (i + 1) + ": vidrio abierto en " + open.size() + " px, primero " + open.first());
            }
        }
        return bad;
    }

    private static int maxY(Mask m) {
        int y = Integer.MIN_VALUE;
        for (Point p : m) {
            y = Math.max(y, p.y());
        }
        return y;
    }

    /** Hay pared a ambos lados en la fila, o arriba y abajo en la columna. */
    static boolean betweenWalls(Point p, Mask walls) {
        boolean left = false;
        boolean right = false;
        boolean up = false;
        boolean down = false;
        for (Point q : walls) {
            if (q.y() == p.y()) {
                left |= q.x() < p.x();
                right |= q.x() > p.x();
            }
            if (q.x() == p.x()) {
                up |= q.y() < p.y();
                down |= q.y() > p.y();
            }
        }
        return (left && right) || (up && down);
    }

}
