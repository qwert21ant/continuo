package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MutableExpansionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One A* search, advanced in bounded slices rather than run to completion.
 *
 * <p><b>This is the same loop {@code AStarPathfinder} always ran, with its locals lifted into
 * fields.</b> Not a second implementation: {@code findPath} is this class driven with an unbounded
 * slice, so every existing caller and fixture exercises exactly this code. That is what makes the
 * design's D7 — a sliced search returns bit-identical results to an unsliced one — provable by
 * construction rather than by hope.
 *
 * <p><b>A slice boundary is not an outcome.</b> {@link #advance} returning {@code false} means "ask
 * again", nothing more: no {@link PathOutcome}, no partial path, no change to the node budget's
 * accounting. The node budget and the slice budget share a unit and are otherwise unrelated — the
 * first is a property of the search, the second of how it is being driven. Conflating them would
 * turn every search longer than one slice into a {@link PathOutcome#PARTIAL}, firing C4's backoff
 * for a reason that has nothing to do with the budget it was calibrated against.
 *
 * <p><b>Single-threaded.</b> It reads a {@code BlockSource} that may be filling from the live world,
 * so it inherits {@code IBlockView}'s main-thread delivery window, and its storage keeps a
 * last-section memo that two readers would race on.
 */
public final class Search {

    private final int nodeBudget;
    private final Goal goal;
    private final HeuristicRates rates;
    private final List<IMovementType> moves;
    private final SegmentSelector selector;
    private final Map<Long, PathNode> nodes = new HashMap<Long, PathNode>();
    private final List<Pos> expanded = new ArrayList<Pos>();
    private final PriorityQueue<QueuedNode> open =
        new PriorityQueue<QueuedNode>(64, QueuedNodeOrder.INSTANCE);
    private final MutableExpansionContext ctx;
    private final MoveSink sink;

    private int discovered;
    private PathNode current;
    private PathResult result;

    Search(BlockSource world, int startX, int startY, int startZ, Goal goal, CapabilitySet caps,
           int nodeBudget, double minProgressBlocks, ActiveMovements active) {
        this.nodeBudget = nodeBudget;
        this.goal = goal;
        this.rates = active.rates();
        this.moves = active.movements();

        double hStart = goal.heuristic(startX, startY, startZ, rates);
        this.selector = new SegmentSelector(hStart, minProgressBlocks * rates.horizontal());

        long startPacked = Pos.pack(startX, startY, startZ);
        PathNode start = new PathNode(startPacked);
        start.g = 0.0;
        nodes.put(Long.valueOf(startPacked), start);
        open.add(new QueuedNode(startPacked, hStart, 0.0, discovered++));

        this.ctx = new MutableExpansionContext(world);
        // An anonymous class rather than a lambda: main source is Java 8 bytecode and lambda-free
        // by house rule. It reads this.current, which is why current is a field.
        this.sink = new MoveSink() {
            @Override
            public void offer(int nx, int ny, int nz, double cost) {
                Long key = Long.valueOf(Pos.pack(nx, ny, nz));
                PathNode neighbour = nodes.get(key);
                if (neighbour == null) {
                    neighbour = new PathNode(key.longValue());
                    nodes.put(key, neighbour);
                }
                if (neighbour.closed) {
                    return;
                }
                double tentative = current.g + cost;
                if (tentative >= neighbour.g) {
                    return;
                }
                neighbour.g = tentative;
                neighbour.parent = current;
                // A fresh immutable entry, never a mutation of one already queued -- see
                // QueuedNode. The old entry stays in the heap and is discarded on poll,
                // because by then this node is closed.
                open.add(new QueuedNode(neighbour.packed,
                    tentative + goal.heuristic(nx, ny, nz, rates), tentative, discovered++));
            }
        };
    }

    /**
     * Expands up to {@code maxNodes} nodes.
     *
     * <p>A stale heap entry for a node already closed is discarded without spending any of the
     * slice: the budget counts expansions, exactly as {@code nodeBudget} does.
     *
     * @param maxNodes how many nodes this slice may expand; must be positive
     * @return whether the search has finished and {@link #result} is available
     * @throws IllegalArgumentException if {@code maxNodes} is not positive
     */
    public boolean advance(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive, got " + maxNodes);
        }
        if (result != null) {
            return true;
        }
        int remaining = maxNodes;
        while (!open.isEmpty()) {
            if (remaining == 0) {
                return false;
            }
            QueuedNode entry = open.poll();
            current = nodes.get(Long.valueOf(entry.packed));
            if (current.closed) {
                continue;
            }
            current.closed = true;
            remaining--;

            final int cx = Pos.unpackX(current.packed);
            final int cy = Pos.unpackY(current.packed);
            final int cz = Pos.unpackZ(current.packed);
            expanded.add(new Pos(cx, cy, cz));

            // h is recomputed rather than taken as entry.f - entry.g. The subtraction is exact,
            // but only by an argument about stale heap entries, and this project's reviews exist
            // to catch invariants that subtle. The saving is arithmetic that reads no world.
            selector.consider(current.packed, goal.heuristic(cx, cy, cz, rates));

            if (goal.isReached(cx, cy, cz)) {
                result = new PathResult(PathOutcome.FOUND, reconstruct(current), expanded,
                    current.g);
                return true;
            }
            if (expanded.size() >= nodeBudget) {
                if (selector.hasCandidate()) {
                    PathNode best = nodes.get(Long.valueOf(selector.candidate()));
                    result = new PathResult(PathOutcome.PARTIAL, reconstruct(best), expanded,
                        best.g);
                    return true;
                }
                result = new PathResult(PathOutcome.BUDGET_EXCEEDED,
                    Collections.<Pos>emptyList(), expanded, 0.0);
                return true;
            }

            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).expand(ctx, sink);
            }
        }

        result = new PathResult(PathOutcome.NO_PATH, Collections.<Pos>emptyList(), expanded, 0.0);
        return true;
    }

    /** @return whether this search has produced its result */
    public boolean finished() {
        return result != null;
    }

    /**
     * @return what the search produced
     * @throws IllegalStateException if it has not finished
     */
    public PathResult result() {
        if (result == null) {
            throw new IllegalStateException("this search has not finished;"
                + " advance(int) until it returns true");
        }
        return result;
    }

    /** @return how many nodes have been expanded so far, across every slice */
    public int expandedCount() {
        return expanded.size();
    }

    private static List<Pos> reconstruct(PathNode goalNode) {
        List<Pos> path = new ArrayList<Pos>();
        for (PathNode n = goalNode; n != null; n = n.parent) {
            path.add(Pos.unpack(n.packed));
        }
        Collections.reverse(path);
        return path;
    }
}
