package dev.continuo.core;

/**
 * A block's collision geometry, reduced to the categories movement cares about.
 *
 * <p>These are <b>behavioural categories, not literal geometry.</b> {@link #FENCE} means "you
 * cannot walk over this and cannot jump it", not a specific set of boxes — which matters
 * because on 1.7.10 a fence's actual boxes depend on its neighbours and are not recoverable
 * from its state alone. Code that needs a real measurement should read
 * {@link BlockData#collisionTop()}.
 *
 * <p>Classification is exact-match: a shape that does not match a category's rule becomes
 * {@link #PARTIAL} rather than the nearest category. A near-miss classified as a slab would be
 * a silent wrong answer; {@code PARTIAL} with a truthful collision top is a correct answer that
 * merely carries less information.
 */
public enum BlockShape {

    /** The position could not be read — outside the world, or in an unloaded chunk. */
    UNKNOWN,

    /** No collision at all. Air, and also blocks like cobweb that you can move into. */
    AIR,

    /** One box filling the whole cube. */
    FULL,

    /** One full-footprint box occupying the lower half. */
    SLAB_BOTTOM,

    /** One full-footprint box occupying the upper half. */
    SLAB_TOP,

    /** One full-footprint box no more than a quarter high. Carpet, a snow layer. */
    THIN_LAYER,

    /** A full-footprint lower half plus at least one partial box above it. */
    STAIR,

    /** Collision extending above the cube. Fences, walls, and panes on some versions. */
    FENCE,

    /** Has collision, but matches no other category. Includes unrecognised modded geometry. */
    PARTIAL
}
