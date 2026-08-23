package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.ExpansionContext;
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
 * A* over an implicit graph of block positions.
 *
 * <p><b>Deterministic by construction.</b> Movements expand in a fixed order and the open set is
 * ordered by {@link QueuedNodeOrder}, which is total over distinct entries. An identical search
 * over an identical world therefore returns an identical path — which is what lets a test assert
 * <em>which</em> path it expects rather than merely that one exists. The two halves are
 * independent and are tested apart: {@code QueuedNodeOrderTest} pins the comparator's three legs,
 * and a golden-path fixture pins the movement iteration order.
 *
 * <p><b>The node budget is a stopping condition, not a fallback.</b> Exhausting it yields
 * {@link PathOutcome#BUDGET_EXCEEDED} and no path at all. Returning the best node reached so far
 * is incremental cost backoff, which is C4's subject and not something to half-build here.
 */
public final class AStarPathfinder {

    /**
     * The node budget a search uses when none is given.
     *
     * <p>Chosen to be far above anything a fixture world can need and far below anything that
     * would hang a test. C4 replaces this with a real search-effort policy.
     */
    public static final int DEFAULT_NODE_BUDGET = 100000;

    private static final IMovementType[] MOVES = {
        new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
    };

    private final int nodeBudget;

    /** Creates a pathfinder with {@link #DEFAULT_NODE_BUDGET}. */
    public AStarPathfinder() {
        this(DEFAULT_NODE_BUDGET);
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @throws IllegalArgumentException if the budget is not positive
     */
    public AStarPathfinder(int nodeBudget) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        this.nodeBudget = nodeBudget;
    }

    /**
     * Searches for a path.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }

        final Map<Long, PathNode> nodes = new HashMap<Long, PathNode>();
        final List<Pos> expanded = new ArrayList<Pos>();
        final int[] discovered = {0};

        final PriorityQueue<QueuedNode> open =
            new PriorityQueue<QueuedNode>(64, QueuedNodeOrder.INSTANCE);

        long startPacked = Pos.pack(startX, startY, startZ);
        PathNode start = new PathNode(startPacked);
        start.g = 0.0;
        nodes.put(Long.valueOf(startPacked), start);
        open.add(new QueuedNode(
            startPacked, goal.heuristic(startX, startY, startZ), 0.0, discovered[0]++));

        final MutableExpansionContext ctx = new MutableExpansionContext(world);

        while (!open.isEmpty()) {
            QueuedNode entry = open.poll();
            final PathNode current = nodes.get(Long.valueOf(entry.packed));
            if (current.closed) {
                continue;
            }
            current.closed = true;

            final int cx = Pos.unpackX(current.packed);
            final int cy = Pos.unpackY(current.packed);
            final int cz = Pos.unpackZ(current.packed);
            expanded.add(new Pos(cx, cy, cz));

            if (goal.isReached(cx, cy, cz)) {
                return new PathResult(PathOutcome.FOUND, reconstruct(current), expanded, current.g);
            }
            if (expanded.size() >= nodeBudget) {
                return new PathResult(PathOutcome.BUDGET_EXCEEDED,
                    Collections.<Pos>emptyList(), expanded, 0.0);
            }

            MoveSink sink = new MoveSink() {
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
                    // A fresh immutable entry, never a mutation of one already queued — see
                    // QueuedNode. The old entry stays in the heap and is discarded on poll,
                    // because by then this node is closed.
                    open.add(new QueuedNode(neighbour.packed,
                        tentative + goal.heuristic(nx, ny, nz), tentative, discovered[0]++));
                }
            };

            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < MOVES.length; i++) {
                MOVES[i].expand(ctx, sink);
            }
        }

        return new PathResult(PathOutcome.NO_PATH, Collections.<Pos>emptyList(), expanded, 0.0);
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
