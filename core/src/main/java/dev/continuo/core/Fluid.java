package dev.continuo.core;

/**
 * The fluid occupying a block, if any.
 *
 * <p>Separate from {@link BlockShape} because 1.21.11 lets a block be both solid and
 * waterlogged, while 1.7.10 has no such concept and simply never produces the combination.
 * That is an absent capability rather than a difference in shape.
 */
public enum Fluid {

    /** No fluid. */
    NONE,

    /** Water, still or flowing. */
    WATER,

    /** Lava, still or flowing. */
    LAVA,

    /** A fluid the table names but the core has no dedicated constant for. */
    OTHER
}
