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

    /**
     * How the run ended: {@code FOUND}, {@code NO_PATH} or {@code BUDGET_EXCEEDED} — the terminal
     * search's own outcome.
     *
     * <p><b>This describes how the run ended, not what it produced.</b> {@link #path()} and
     * {@link #cost()} may be non-empty and non-zero even when this is {@code NO_PATH} or
     * {@code BUDGET_EXCEEDED}: every segment before the terminal one was real progress, walked and
     * accumulated, and that prefix survives regardless of how the terminal search went. A caller
     * that wants "is there anything worth walking" must check {@link #path()}, not this alone.
     *
     * <p><b>Why the terminal value is kept rather than relabelled.</b> {@code NO_PATH} proving the
     * goal is unreachable is the only definitive "stop retrying" signal the search produces — the
     * same reasoning D5 rests on. Collapsing it into {@code PARTIAL} because a prefix happens to
     * exist would destroy that signal and leave a caller re-searching a goal already proven
     * impossible. {@link #asPathResult()} is where a caller that only wants a {@link PathResult}'s
     * simpler contract gets one.
     *
     * @return the terminal search's outcome
     */
    public PathOutcome outcome() {
        return outcome;
    }

    /**
     * Every segment joined end to end, start to wherever the run stopped.
     *
     * <p>Non-empty even when {@link #outcome()} is {@code NO_PATH} or {@code BUDGET_EXCEEDED}, if
     * an earlier segment made progress before the terminal search failed: this is the best-effort
     * prefix the run walked, not a promise that it reaches the goal.
     *
     * @return the route, unmodifiable; empty only when the very first search produced nothing
     */
    public List<Pos> path() {
        return path;
    }

    /** @return every node expanded across every segment, in order, unmodifiable */
    public List<Pos> expanded() {
        return expanded;
    }

    /**
     * The accumulated cost of {@link #path()} in ticks — real cost of real progress made, not an
     * estimate, and not zeroed just because {@link #outcome()} is {@code NO_PATH} or
     * {@code BUDGET_EXCEEDED}.
     *
     * @return the route's cost in ticks; {@code 0} only when {@link #path()} is empty
     */
    public double cost() {
        return cost;
    }

    /** @return how many searches ran; 1 when the first one settled it */
    public int segments() {
        return segments;
    }

    /**
     * This run as a single {@link PathResult}, so the renderer and the bounds calculator can draw
     * a whole run exactly as they draw one search.
     *
     * <p><b>The outcome is remapped, not copied.</b> {@link #outcome()} keeps this run's terminal
     * search outcome even when {@link #path()} is non-empty — see that method's javadoc for why.
     * {@link PathResult}'s own contract does not permit that: a non-{@code FOUND} outcome there
     * promises an empty path. Passing {@link #outcome()} straight through would hand the renderer
     * a {@code PathResult} violating its own type's contract. So this method maps instead: whenever
     * {@link #path()} is non-empty and {@link #outcome()} is not {@code FOUND}, the returned
     * {@link PathResult} carries {@link PathOutcome#PARTIAL} — which is truthful, since a non-empty
     * path to somewhere that is not the goal is exactly what {@code PARTIAL} means. An empty path
     * keeps this run's own outcome unchanged, {@code FOUND} keeps it unchanged, and only that one
     * combination is remapped.
     *
     * @return this run as a {@link PathResult} that satisfies {@link PathResult}'s own contract
     */
    public PathResult asPathResult() {
        PathOutcome resultOutcome = (outcome != PathOutcome.FOUND && !path.isEmpty())
            ? PathOutcome.PARTIAL
            : outcome;
        return new PathResult(resultOutcome, path, expanded, cost);
    }

    @Override
    public String toString() {
        return "SegmentedResult[" + outcome + ", " + segments + " segments, "
            + path.size() + " steps, cost " + cost + "]";
    }
}
