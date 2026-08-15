package dev.continuo.core;

/**
 * Semantic properties of a block that its geometry cannot express.
 *
 * <p>Almost none of these are derivable from collision boxes — soul sand is shaped exactly
 * like stone, and cobweb has no collision at all — so most arrive from the per-version
 * override table rather than from the classifier's geometry rules.
 */
public enum BlockTag {

    /** Harmful to occupy or touch. Fire, cactus, magma. */
    AVOID,

    /** Affected by gravity, so it may not be there later. Sand, gravel. */
    FALLING,

    /** Can be climbed. Ladders, vines. */
    CLIMBABLE,

    /** Slows movement through or across it. Soul sand, cobweb, honey. */
    SLOW
}
