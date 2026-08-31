package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;

/**
 * Runs a search, walks the segment it returns, and searches again from the segment's end, until
 * the goal is reached or the run cannot continue.
 *
 * <p><b>Segmentation is a safety net, not the way to reach a distant goal.</b> Measured across
 * real terrain, backoff reaches the goal only when the budget is a large fraction of what the
 * search needs, and fails below roughly 70% of it. The primary answer to a far goal is a budget
 * big enough for it; this keeps the bot moving usefully when even that is exceeded. Section 2.1
 * of the design has the measurements.
 *
 * <p><b>A run can span ticks.</b> {@code begin} returns a {@code Run} that advances a slice at a
 * time, and every segment reads the one {@code BlockSource} the run was given, so a snapshot passed
 * here outlives the tick it was created in. That run owns the source's lifecycle: see
 * {@code Run.cancel()}.
 *
 * <p>{@code SegmentedResult.expanded()} accumulates across every segment rather than reporting only
 * the last one, so it is bounded by roughly {@code cap × nodeBudget} entries, all allocated on the
 * calling thread — worth naming because client cost is this sub-project's whole subject.
 */
public final class SegmentedSearch {

    private final AStarPathfinder pathfinder;

    /**
     * @param pathfinder the search each segment uses; never {@code null}
     * @throws IllegalArgumentException if it is null
     */
    public SegmentedSearch(AStarPathfinder pathfinder) {
        if (pathfinder == null) {
            throw new IllegalArgumentException("pathfinder must not be null");
        }
        this.pathfinder = pathfinder;
    }

    /**
     * @param world the world to read; never {@code null}
     * @param startX where the run begins
     * @param startY where the run begins
     * @param startZ where the run begins
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return the run's result; never {@code null}
     */
    public SegmentedResult run(BlockSource world, int startX, int startY, int startZ,
                               Goal goal, CapabilitySet caps) {
        Run run = begin(world, startX, startY, startZ, goal, caps);
        run.advance(Integer.MAX_VALUE);
        return run.result();
    }

    /**
     * Starts a run without expanding anything, so a caller can spend it a slice at a time.
     *
     * <p>{@link #run} is this method plus one unbounded slice.
     *
     * @param world the world to read; never {@code null}
     * @param startX where the run begins
     * @param startY where the run begins
     * @param startZ where the run begins
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return a run that has expanded nothing yet; never {@code null}
     * @throws IllegalArgumentException if {@code world}, {@code goal} or {@code caps} is null
     */
    public Run begin(BlockSource world, int startX, int startY, int startZ, Goal goal,
                     CapabilitySet caps) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (caps == null) {
            throw new IllegalArgumentException("caps must not be null; use CapabilitySet.none()");
        }
        return new Run(pathfinder, world, startX, startY, startZ, goal, caps);
    }
}
