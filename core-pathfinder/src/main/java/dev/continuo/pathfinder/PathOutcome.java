package dev.continuo.pathfinder;

/** How a search ended. */
public enum PathOutcome {

    /** A path to the goal was found. */
    FOUND,

    /** Everything reachable was searched and the goal was not among it. */
    NO_PATH,

    /**
     * The node budget ran out first.
     *
     * <p>Distinct from {@link #NO_PATH} because a path may well exist — the search simply did not
     * get to it. Nothing partial is returned; salvaging the best node reached is incremental cost
     * backoff, which is C4's subject.
     */
    BUDGET_EXCEEDED
}
