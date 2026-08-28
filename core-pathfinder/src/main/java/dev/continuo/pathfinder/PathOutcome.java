package dev.continuo.pathfinder;

/** How a search ended. */
public enum PathOutcome {

    /** A path to the goal was found. */
    FOUND,

    /**
     * A real path, to somewhere that is not the goal.
     *
     * <p>The node budget ran out, and the search had reached somewhere meaningfully closer to the
     * goal than where it started. That prefix is returned as a segment: walk it, search again from
     * its end, repeat. {@link PathResult#cost()} is the segment's own cost, not an estimate of the
     * whole route.
     */
    PARTIAL,

    /** Everything reachable was searched and the goal was not among it. */
    NO_PATH,

    /**
     * The node budget ran out and nothing was salvageable.
     *
     * <p>Distinct from {@link #NO_PATH} because a path may well exist — the search simply did not
     * get to it. Distinct from {@link #PARTIAL} because no expanded node was closer to the goal
     * than the start by a useful margin, so there is nothing worth walking to.
     */
    BUDGET_EXCEEDED
}
