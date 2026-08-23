package dev.continuo.pathfinder;

/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here multiply {@code cheapestMove()} by a
 * Chebyshev distance, which holds that guarantee only under a condition on the movement set:
 * every movement {@code m} must satisfy {@code cost(m) >= axisSpan(m) × cheapestMove()}, where
 * {@code axisSpan(m)} is the largest number of steps {@code m} takes along any single axis. That
 * is a checked numeric property of the cost table and not a structural one — the movements are
 * free to span more than one block per axis, and {@link DescendMove} does. See
 * {@link dev.continuo.movement.MovementCosts#cheapestMove()}.
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
