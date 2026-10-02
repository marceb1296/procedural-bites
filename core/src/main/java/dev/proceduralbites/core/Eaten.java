package dev.proceduralbites.core;

/** Píxeles comidos de un fotograma; en el último bocado, la dirección desde la que se mordió. */
public record Eaten(Mask pixels, double[] biteDir) {
}
