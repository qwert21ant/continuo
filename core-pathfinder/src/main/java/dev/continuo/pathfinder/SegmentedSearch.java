package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;

import java.util.ArrayList;
import java.util.List;

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
 * <p><b>Single-tick.</b> Every segment reads the same {@code BlockSource}, so nothing here holds a
 * world across ticks and none of C3's snapshot-lifetime questions apply.
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
        HeuristicRates rates = pathfinder.registry().activeFor(caps).rates();
        double minProgress = pathfinder.minProgressBlocks() * rates.horizontal();
        double hStart = goal.heuristic(startX, startY, startZ, rates);

        // The design's own termination bound, evaluated once, with no margin added: h falls by at
        // least minProgress per segment and cannot go below zero. A correct implementation never
        // reaches this. Reaching it means h stopped being admissible -- C1 section 5.3 records
        // that admissibility here is a checked numeric property, not a structural one -- so it is
        // reported rather than swallowed.
        int cap = (int) Math.ceil(hStart / minProgress) + 1;

        List<Pos> path = new ArrayList<Pos>();
        List<Pos> expanded = new ArrayList<Pos>();
        double cost = 0.0;
        int segments = 0;
        int x = startX;
        int y = startY;
        int z = startZ;

        while (segments < cap) {
            PathResult r = pathfinder.findPath(world, x, y, z, goal, caps);
            segments++;
            expanded.addAll(r.expanded());

            if (r.outcome() != PathOutcome.PARTIAL) {
                append(path, r.path());
                return new SegmentedResult(r.outcome(), path, expanded,
                    cost + r.cost(), segments);
            }

            append(path, r.path());
            cost += r.cost();
            Pos end = r.path().get(r.path().size() - 1);
            x = end.x();
            y = end.y();
            z = end.z();
        }

        return new SegmentedResult(PathOutcome.BUDGET_EXCEEDED, path, expanded, cost, segments);
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
