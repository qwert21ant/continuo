package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/**
 * One kind of movement, able to generate the neighbours it can reach from a position.
 *
 * <p><b>Package-private on purpose.</b> C2 turns this into the public {@code IMovementType} with
 * a registry, capability filtering and {@code ServiceLoader} loading. Keeping it internal until
 * then means the published signature gets shaped by a real registry rather than frozen by the
 * four movements that happen to exist first.
 *
 * <p>Implementations must offer neighbours in a fixed order. The search breaks cost ties by
 * insertion sequence, so expansion order is what makes a path reproducible.
 */
interface Move {

    /** North, east, south and west as {@code {dx, dz}}, in the order every movement uses. */
    int[][] CARDINALS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * @param world the world to read; never {@code null}
     * @param x the X of the position being expanded
     * @param y the Y of the position being expanded
     * @param z the Z of the position being expanded
     * @param sink receives each reachable neighbour
     */
    void expand(BlockSource world, int x, int y, int z, MoveSink sink);
}
