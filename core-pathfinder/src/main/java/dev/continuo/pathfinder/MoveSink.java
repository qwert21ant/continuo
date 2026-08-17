package dev.continuo.pathfinder;

/**
 * Receives the neighbours a movement generates.
 *
 * <p>A sink rather than a returned collection, which buys two things: no collection is allocated
 * per expansion, and a movement can offer a neighbour the moment it finds one instead of building
 * a list to hand back. It does not make expansion allocation-free — {@link AStarPathfinder}
 * allocates a sink and a {@link Pos} per expansion, and boxes a {@code Long} per node lookup.
 * Removing those is a profiling exercise for C4, which owns search effort.
 */
interface MoveSink {

    /**
     * @param x the neighbour's X
     * @param y the neighbour's Y
     * @param z the neighbour's Z
     * @param cost the cost of getting there from the node being expanded, in ticks
     */
    void offer(int x, int y, int z, double cost);
}
