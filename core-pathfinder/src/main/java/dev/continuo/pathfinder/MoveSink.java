package dev.continuo.pathfinder;

/**
 * Receives the neighbours a movement generates.
 *
 * <p>A sink rather than a returned collection so that expansion allocates nothing per node in
 * the search's hot loop.
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
