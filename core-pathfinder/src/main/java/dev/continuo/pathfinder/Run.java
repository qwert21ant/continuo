package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;

import java.util.ArrayList;
import java.util.List;

/**
 * One segmented run, advanced in bounded slices: the current search, the route so far, and the
 * world it all reads.
 *
 * <p><b>This is {@code SegmentedSearch.run}'s loop with its locals lifted into fields</b>, exactly
 * as {@code Search} is for {@code AStarPathfinder.findPath}. {@code run} is this class driven with
 * an unbounded slice, so there is one loop rather than two and the design's D7 extends to segment
 * counts by construction.
 *
 * <p><b>It owns the world it reads, and that is a lifecycle obligation.</b> Under C5 a run lives
 * for hundreds of milliseconds rather than for one call, and the source it holds may be a snapshot
 * still filling from the client level. A level change during a run must therefore
 * {@link #cancel} it, or the run keeps the old level reachable. C3 §4.7's "this object has no
 * lifecycle" was true only while nothing outlived a tick; this is the thing that does.
 */
public final class Run {

    /**
     * How many nodes one slice expands.
     *
     * <p><b>2,000, set by the in-game run of 2026-08-31 (C5 design §13) and confirmed by measurement
     * on 2026-09-02 (D1 design §15.3).</b> It was 4,000, from C5 §5.4's arithmetic, which predicted
     * a slice near 7 ms. Measured on the C4 §13 route, 4,000 gave a worst slice of <b>11.4 ms warm
     * and 35.6 ms cold</b> — the cold figure being 71% of a 50 ms tick, once per session on the
     * first path.
     *
     * <p><b>At 2,000 the measured worst slice is 22.3 ms cold and 6.1–8.8 ms warm</b>, on the same
     * route. Warm landed on the prediction; <b>cold ran 24% over it</b>, so the assumption that
     * halving the budget halves the first slice's first-touch fill is <b>not</b> quite true — the
     * fill does not scale linearly with slice size. The value stands anyway: 22.3 ms is 45% of a
     * tick against 4,000's 71%, it happens once per session, and the next press is under 9 ms.
     * Halving again would buy a few milliseconds of cold worst case for another 0.65 s of latency
     * on every path, which is the wrong trade.
     *
     * <p>The cost is latency, and it is small: the same route takes about 13 slices instead of 7,
     * so roughly 0.65 s rather than 0.35 s to compute.
     *
     * <p><b>Why the worst slice and not the average.</b> Per-slice cost is strongly non-uniform:
     * early slices touch all-new terrain and pay the fill, later ones hit the snapshot's memo, and
     * C4 §13.3 measured that non-linearity before C5 existed. The average slice on that route was
     * 8.6 ms while the worst was 11.4; sizing to the average would put a hitch in every path.
     *
     * <p>A node budget buys determinism — C1 §5.1, and a wall-clock slice boundary would make every
     * path assertion in the suite flaky — at the price of exactly this variable millisecond cost.
     *
     * <p>It lives here rather than on a caller because {@code PathProbe} and D2's {@code
     * PathExecutor} are in sibling modules and both spend slices against this class. A second copy
     * of a measured constant is a copy that drifts.
     */
    public static final int SLICE_NODES = 2000;

    private final AStarPathfinder pathfinder;
    private final Goal goal;
    private final CapabilitySet caps;
    private final double minProgress;
    private final int cap;

    private final List<Pos> path = new ArrayList<Pos>();
    private final List<Pos> expanded = new ArrayList<Pos>();

    /** Nulled by {@link #cancel}, which is what stops a cancelled run pinning a level. */
    private BlockSource world;

    private double cost;
    private int segments;
    private int x;
    private int y;
    private int z;
    private Search current;
    private SegmentedResult result;
    private boolean cancelled;

    Run(AStarPathfinder pathfinder, BlockSource world, int startX, int startY, int startZ,
        Goal goal, CapabilitySet caps) {
        this.pathfinder = pathfinder;
        this.world = world;
        this.goal = goal;
        this.caps = caps;
        this.x = startX;
        this.y = startY;
        this.z = startZ;

        HeuristicRates rates = pathfinder.registry().activeFor(caps).rates();
        this.minProgress = pathfinder.minProgressBlocks() * rates.horizontal();
        double hStart = goal.heuristic(startX, startY, startZ, rates);
        // The design's own termination bound, evaluated once, with no margin added: h falls by at
        // least minProgress per segment and cannot go below zero. A correct implementation never
        // reaches this. Reaching it means h stopped being admissible -- C1 section 5.3 records
        // that admissibility here is a checked numeric property, not a structural one -- so it is
        // reported rather than swallowed.
        this.cap = (int) Math.ceil(hStart / minProgress) + 1;
    }

    /**
     * Expands up to {@code maxNodes} nodes, starting a new segment whenever the current one ends.
     *
     * @param maxNodes how many nodes this slice may expand; must be positive
     * @return whether the run has finished and {@link #result} is available
     * @throws IllegalArgumentException if {@code maxNodes} is not positive
     * @throws IllegalStateException if this run was cancelled
     */
    public boolean advance(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive, got " + maxNodes);
        }
        if (cancelled) {
            throw new IllegalStateException("this run was cancelled");
        }
        if (result != null) {
            return true;
        }
        int remaining = maxNodes;
        while (remaining > 0) {
            if (current == null) {
                if (segments >= cap) {
                    result = new SegmentedResult(PathOutcome.BUDGET_EXCEEDED, path, expanded,
                        cost, segments);
                    return true;
                }
                current = pathfinder.begin(world, x, y, z, goal, caps);
            }
            int before = current.expandedCount();
            boolean done = current.advance(remaining);
            remaining -= current.expandedCount() - before;
            if (!done) {
                return false;
            }

            PathResult r = current.result();
            current = null;
            segments++;
            expanded.addAll(r.expanded());

            if (r.outcome() != PathOutcome.PARTIAL) {
                append(path, r.path());
                result = new SegmentedResult(r.outcome(), path, expanded, cost + r.cost(),
                    segments);
                return true;
            }

            append(path, r.path());
            cost += r.cost();
            Pos end = r.path().get(r.path().size() - 1);
            x = end.x();
            y = end.y();
            z = end.z();
        }
        return false;
    }

    /**
     * Ends this run and releases the world it was reading.
     *
     * <p>Idempotent, because the adapter poll that triggers it compares the client level by
     * identity every tick and a second call on the following tick is the normal path — the same
     * shape {@code ContinuoCore.stop()} has under global rule 2.
     */
    public void cancel() {
        cancelled = true;
        current = null;
        world = null;
    }

    /** @return whether {@link #cancel} was called */
    public boolean cancelled() {
        return cancelled;
    }

    /** @return whether this run is over, by finishing or by cancellation */
    public boolean finished() {
        return result != null || cancelled;
    }

    /**
     * @return what the run produced
     * @throws IllegalStateException if it has not finished, or was cancelled
     */
    public SegmentedResult result() {
        if (cancelled) {
            throw new IllegalStateException("this run was cancelled and has no result");
        }
        if (result == null) {
            throw new IllegalStateException("this run has not finished;"
                + " advance(int) until it returns true");
        }
        return result;
    }

    /** @return how many nodes have been expanded across every segment so far */
    public int expandedCount() {
        return expanded.size() + (current == null ? 0 : current.expandedCount());
    }

    /**
     * Joins a segment onto the route, dropping its first position.
     *
     * <p>Every segment after the first begins where the previous one ended, so appending whole
     * would repeat that position and make the route non-contiguous by its own test.
     */
    private static void append(List<Pos> path, List<Pos> segment) {
        if (segment.isEmpty()) {
            return;
        }
        if (path.isEmpty()) {
            path.addAll(segment);
            return;
        }
        path.addAll(segment.subList(1, segment.size()));
    }
}
