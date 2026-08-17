package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a search produced: the outcome, the path if there is one, and enough to draw it. */
public final class PathResult {

    private final PathOutcome outcome;
    private final List<Pos> path;
    private final List<Pos> expanded;
    private final double cost;

    /**
     * @param outcome how the search ended; never {@code null}
     * @param path start-to-goal inclusive, empty unless the outcome is {@link PathOutcome#FOUND}
     * @param expanded every node taken off the open set, in expansion order
     * @param cost the path's total cost in ticks, {@code 0} when there is no path
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

    /** @return the path from start to goal inclusive, unmodifiable; empty if none was found */
    public List<Pos> path() {
        return path;
    }

    /** @return every expanded node in expansion order, unmodifiable; for the renderer */
    public List<Pos> expanded() {
        return expanded;
    }

    /** @return the path's total cost in ticks; {@code 0} when no path was found */
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
