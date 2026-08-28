package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a segmented run produced: how it ended, the whole route, and how many searches it took. */
public final class SegmentedResult {

    private final PathOutcome outcome;
    private final List<Pos> path;
    private final List<Pos> expanded;
    private final double cost;
    private final int segments;

    SegmentedResult(PathOutcome outcome, List<Pos> path, List<Pos> expanded,
                    double cost, int segments) {
        this.outcome = outcome;
        this.path = Collections.unmodifiableList(new ArrayList<Pos>(path));
        this.expanded = Collections.unmodifiableList(new ArrayList<Pos>(expanded));
        this.cost = cost;
        this.segments = segments;
    }

    /** @return how the run ended: {@code FOUND}, {@code NO_PATH} or {@code BUDGET_EXCEEDED} */
    public PathOutcome outcome() {
        return outcome;
    }

    /**
     * @return every segment joined end to end, start to wherever the run stopped, unmodifiable;
     *         empty when the first search produced nothing
     */
    public List<Pos> path() {
        return path;
    }

    /** @return every node expanded across every segment, in order, unmodifiable */
    public List<Pos> expanded() {
        return expanded;
    }

    /** @return the whole route's cost in ticks */
    public double cost() {
        return cost;
    }

    /** @return how many searches ran; 1 when the first one settled it */
    public int segments() {
        return segments;
    }

    /**
     * @return this run as a single {@link PathResult}, so the renderer and the bounds calculator
     *         can draw a whole run exactly as they draw one search
     */
    public PathResult asPathResult() {
        return new PathResult(outcome, path, expanded, cost);
    }

    @Override
    public String toString() {
        return "SegmentedResult[" + outcome + ", " + segments + " segments, "
            + path.size() + " steps, cost " + cost + "]";
    }
}
