package dev.proceduralbites.core;

/** Parámetros del algoritmo. */
public final class Params {
    private Params() {
    }

    public static final int NUM_FRAMES = 3;
    /** Fracción de comida que queda en el último fotograma. */
    public static final double LAST_REMAINING = 0.28;
    /** Lado desde el que entra la mordida en el patrón FRONT. */
    public static final double[] BITE_SIDE = {1, -1};
    /** Relación largo/ancho desde la que AUTO come desde la punta. */
    public static final double ELONGATED = 1.5;
    /** Radio de la mordida, como fracción del tamaño del ítem. */
    public static final double BITE_SIZE = 0.42;
    /** Radio de cada diente del borde de la mordida (px a 16x). */
    public static final double TOOTH_RADIUS = 1.3;
    /** Separación entre dientes (px a 16x). */
    public static final double TOOTH_SPACING = 2.6;
    /** 0 = círculos perfectos, 1 = borde muy irregular. */
    public static final double IRREGULARITY = 0.35;
    public static final EdgeMode EDGE_MODE = EdgeMode.INTERIOR;
    public static final double EDGE_STRENGTH = 0.55;
    /** Piezas sueltas más chicas que esto se borran siempre. */
    public static final int MIN_ISLAND = 4;
    /** Grosor mínimo (px a 16x) de lo que queda tras morder. */
    public static final int MIN_THICKNESS = 3;
    /** Último bocado: coseno respecto a la mordida desde el que un borde cuenta como mordido. */
    public static final double BITE_SIDE_COS = 0.0;
    /** Último bocado: el centro se busca donde el grosor es al menos esta fracción del máximo. */
    public static final double LAST_ANCHOR = 0.5;
    /** Luminancia que el contorno repintado puede alejarse del objetivo antes de corregirlo. */
    public static final int CONTOUR_TOLERANCE = 12;
    /** Las partes separadas desde el original se conservan si no se mordieron. */
    public static final boolean KEEP_WHOLE_PARTS = true;

    /** Nivel de líquido que queda en el último fotograma. */
    public static final double LAST_LIQUID = 0.10;
    /** Distancia RGB hasta la que un color es del recipiente. */
    public static final int PALETTE_TOLERANCE = 24;
    /** Grados de tono alrededor del color del líquido. */
    public static final int LIQUID_HUE_RANGE = 15;
    /** Cuánto se aclara la fila de arriba del líquido. */
    public static final double MENISCUS = 0.20;
    public static final boolean REMOVE_CORK = true;
    /** Bebida sin recipiente: parte mínima de la silueta con colores de vidrio para tratarla como botella. */
    public static final double GLASS_SHARE = 0.2;
    /** Comida sin recipiente: parte mínima de la silueta con los colores exactos del vidrio. */
    public static final double EXACT_GLASS_SHARE = 0.2;
    /** Un recipiente opaco más alto que esto por su ancho es una botella, no un tazón. */
    public static final double TALL_VESSEL = 1.5;
    /** Taza vista desde arriba: ancho mínimo de la superficie respecto al de la silueta. */
    public static final double TOP_VIEW_WIDTH = 0.5;
    /** Lo que asoma por encima del vaso con menos saturación que esto es espuma o crema y se consume. */
    public static final double FOAM_SATURATION = 0.35;
    /** Brillo mínimo de la espuma y la crema. */
    public static final double FOAM_VALUE = 0.75;
    /** Si menos de esta parte del tazón tiene sus colores exactos, el mod lo dibujó con los suyos. */
    public static final double EXACT_BOWL_SHARE = 0.1;
    /** Otra comida del mod usa la misma plantilla si al menos esta parte de la silueta es igual. */
    public static final double TEMPLATE_SHARE = 0.4;
    /** Para reconocer el tazón: píxel igual en al menos esta parte de la plantilla. */
    public static final double TEMPLATE_VOTE = 0.5;
    /** Para dibujar el tazón: píxel igual en al menos esta cantidad de ítems. */
    public static final int TEMPLATE_MIN = 2;
    /** Filas mínimas (x k) de pared de adelante bajo el contenido, en el tercio central. */
    public static final double SHAPE_FRONT = 4;
    /** Filas máximas (x k) de tazón sobre el contenido. */
    public static final double SHAPE_BACK = 4;
    /** Ancho mínimo de la fila más ancha del contenido respecto al del tazón. */
    public static final double SHAPE_WIDTH = 0.65;
    /** Parte mínima del contenido en su pieza más grande. */
    public static final double SHAPE_MAIN = 0.7;
    /** Parte mínima del contenido con tazón a los dos lados de su fila. */
    public static final double SHAPE_SIDED = 0.6;
    /** Vidrio con colores propios del mod: azul grisáceo con tono, saturación y brillo en estos rangos. */
    public static final int OWN_GLASS_HUE_MIN = 190;
    public static final int OWN_GLASS_HUE_MAX = 250;
    public static final double OWN_GLASS_SATURATION_MIN = 0.1;
    public static final double OWN_GLASS_SATURATION_MAX = 0.6;
    public static final double OWN_GLASS_VALUE = 0.45;
    /** Parte mínima de las filas de la silueta con pared de vidrio en los dos extremos. */
    public static final double OWN_GLASS_ROWS = 0.3;
    /** Saturación bajo la que un píxel que no es contenido es parte del envase (borde, tapa, vapor). */
    public static final double OWN_NEUTRAL = 0.12;

    /** Pista top_cup: ancho mínimo de la superficie respecto al de la silueta. */
    public static final double TOP_CUP_WIDTH = 0.4;
    /** Pista top_cup: veces que se quita además el borde de una pieza que abarca la pared. */
    public static final int TOP_CUP_PEELS = 2;
    /** Pista top_cup: oscurecido de la pared interior. */
    public static final double TOP_CUP_SHADE = 0.45;

    public enum EdgeMode { INTERIOR, DARK, NONE }

    /**
     * De dónde entran las mordidas. AUTO: con tallo, lados alternos hasta el corazón;
     * alargado, desde la punta; si no, desde BITE_SIDE.
     */
    public enum BitePattern {
        AUTO,
        ALTERNATE,
        ALTERNATE_TOP,
        FRONT
    }
}
