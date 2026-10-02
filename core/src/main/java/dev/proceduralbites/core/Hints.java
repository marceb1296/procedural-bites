package dev.proceduralbites.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pistas por ítem, de los archivos {@code assets/<ns>/proceduralbites/hints.json}:
 * <pre>{"top_cup": ["mod:patrón", ...], "none": [...], "solid": [...], "auto": [...]}</pre>
 * {@code *} vale por cualquier tramo. Si varios calzan gana: sin {@code *}, la capa de más
 * arriba, más caracteres fuera de los {@code *}, el mayor como texto y el último tipo.
 */
public final class Hints {
    public enum Type {
        /** Taza, vasito o balde visto desde arriba: baja la superficie. */
        TOP_CUP,
        /** Sin animación. */
        NONE,
        /** Mordidas, aunque la textura parezca un recipiente. */
        SOLID,
        /** Sin pista: para que un pack anule una pista de más abajo. */
        AUTO;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Hints EMPTY = new Hints(List.of());

    private record Rule(String pattern, Type type, int level, boolean exact, int literal) {
        boolean beats(Rule other) {
            if (exact != other.exact) {
                return exact;
            }
            if (level != other.level) {
                return level > other.level;
            }
            if (literal != other.literal) {
                return literal > other.literal;
            }
            int text = pattern.compareTo(other.pattern);
            if (text != 0) {
                return text > 0;
            }
            return type.ordinal() > other.type.ordinal();
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    /** {@code layers}: de abajo arriba, tipo -> patrones; lo desconocido se ignora. */
    public Hints(List<Map<String, List<String>>> layers) {
        for (int level = 0; level < layers.size(); level++) {
            Map<String, List<String>> layer = layers.get(level);
            if (layer == null) {
                continue;
            }
            for (Type type : Type.values()) {
                List<String> patterns = layer.get(type.key());
                if (patterns == null) {
                    continue;
                }
                for (String pattern : patterns) {
                    if (pattern != null) {
                        rules.add(new Rule(pattern, type, level, pattern.indexOf('*') < 0,
                                pattern.replace("*", "").length()));
                    }
                }
            }
        }
    }

    public int size() {
        return rules.size();
    }

    /** {@code null} si no tiene pista o si es AUTO. */
    public Type lookup(String id) {
        Rule best = null;
        for (Rule rule : rules) {
            if (matches(rule.pattern, id) && (best == null || rule.beats(best))) {
                best = rule;
            }
        }
        return best == null || best.type == Type.AUTO ? null : best.type;
    }

    /** Sin retroceso exponencial: un patrón de un pack no puede colgar la carga. */
    public static boolean matches(String pattern, String text) {
        int p = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
