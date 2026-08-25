package dev.continuo.pathfinder;

import dev.continuo.movement.HeuristicRates;

/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here delegate the arithmetic to
 * {@link dev.continuo.movement.HeuristicRates}, whose rates are minima over exactly the movements
 * the search may use. That is what makes the guarantee structural: every movement satisfies
 * {@code cost >= horizontal × octileUnits(dx, dz)} and {@code cost >= vertical × |dy|} by the
 * definition of a minimum.
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
     * @param rates the rates this search may scale a distance by, from
     *              {@link dev.continuo.movement.ActiveMovements#rates()}; never {@code null}
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z, HeuristicRates rates);
}
