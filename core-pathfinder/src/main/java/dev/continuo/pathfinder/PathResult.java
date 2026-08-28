package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a search produced: the outcome, the path if there is one, and enough to draw it.
 *
 * <p><b>{@code PARTIAL} carries a real, non-empty path to somewhere that is not the goal.</b> Every
 * other non-{@code FOUND} outcome ({@code NO_PATH}, {@code BUDGET_EXCEEDED}) carries an empty path
 * and a zero cost — {@code PARTIAL} is the one exception, and deliberately: it is what a
 * budget-exhausted search returns when it reached somewhere meaningfully closer to the goal than
 * where it started. See {@link PathOutcome#PARTIAL}.
 */
public final class PathResult {

    private final PathOutcome outcome;
    private final List<Pos> path;
    private final List<Pos> expanded;
    private final double cost;

    /**
     * @param outcome how the search ended; never {@code null}
     * @param path start-to-goal inclusive when the outcome is {@link PathOutcome#FOUND}; the
     *             start-to-segment-end prefix when it is {@link PathOutcome#PARTIAL}; empty for
     *             {@code NO_PATH} and {@code BUDGET_EXCEEDED}
     * @param expanded every node taken off the open set, in expansion order
     * @param cost the path's total cost in ticks: the whole route's cost for {@code FOUND}, the
     *             segment's own cost for {@code PARTIAL}, {@code 0} when {@code path} is empty
     */
    PathResult(PathOutcome outcome, List<Pos> path, List<Pos> expanded, double cost) {
        this.outcome = outcome;
        // Copied, not merely wrapped: the search hands over the live list it was appending to,
        // so an unmodifiable view alone would leave this object's contents defined by whether
        // the caller happens to stop using it.
        this.path = Collections.unmodifiableList(new ArrayList<Pos>(path));
        this.expanded = Collections.unmodifiableList(new ArrayList<Pos>(expanded));
        this.cost = cost;
    }

    /** @return how the search ended; never {@code null} */
    public PathOutcome outcome() {
        return outcome;
    }

    /**
     * @return the path, unmodifiable: start to goal inclusive for {@code FOUND}, start to the
     *         segment's end for {@code PARTIAL}, empty for {@code NO_PATH} and
     *         {@code BUDGET_EXCEEDED}
     */
    public List<Pos> path() {
        return path;
    }

    /** @return every expanded node in expansion order, unmodifiable; for the renderer */
    public List<Pos> expanded() {
        return expanded;
    }

    /**
     * @return the path's total cost in ticks: the whole route's cost for {@code FOUND}, the
     *         segment's own true cost (not an estimate of the whole route) for {@code PARTIAL},
     *         {@code 0} for {@code NO_PATH} and {@code BUDGET_EXCEEDED}
     */
    public double cost() {
        return cost;
    }

    /** @return how many nodes were expanded */
    public int nodesExpanded() {
        return expanded.size();
    }

    @Override
    public String toString() {
        return "PathResult[" + outcome + ", " + path.size() + " steps, "
            + expanded.size() + " expanded, cost " + cost + "]";
    }
}
