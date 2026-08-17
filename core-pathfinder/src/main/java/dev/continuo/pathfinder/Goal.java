package dev.continuo.pathfinder;

/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here keep that guarantee by construction
 * rather than by argument: they count the fewest moves that could possibly close the gap and
 * multiply by the cheapest possible move.
 */
public interface Goal {

    /**
     * @param x candidate X
     * @param y candidate Y
     * @param z candidate Z
     * @return whether standing here satisfies the goal
     */
    boolean isReached(int x, int y, int z);

    /**
     * @param x candidate X
     * @param y candidate Y
     * @param z candidate Z
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z);
}
