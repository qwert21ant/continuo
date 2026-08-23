package dev.continuo.pathfinder;

/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here multiply a Chebyshev distance by the
 * multiplier the search supplies, which is a minimum over exactly the movements that search may
 * use. That is what makes the guarantee structural: every movement satisfies
 * {@code cost(m) >= axisSpan(m) × cheapestAxisStep} by the definition of a minimum. C1 could only
 * assert it as a checked numeric property of a closed cost table — see
 * {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}.
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
     * @param cheapestAxisStep the cheapest cost of one axis step over the movements this search
     *                         may use, from
     *                         {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z, double cheapestAxisStep);
}
