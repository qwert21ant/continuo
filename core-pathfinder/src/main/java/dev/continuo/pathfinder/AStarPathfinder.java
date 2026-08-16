package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over an implicit graph of block positions.
 *
 * <p><b>Deterministic by construction.</b> Movements expand in a fixed order, each node carries
 * the sequence number it was discovered at, and the open set orders by {@code f}, then by the
 * heuristic, then by that sequence. Every comparison is therefore total, so an identical search
 * over an identical world returns an identical path — which is what makes it possible to assert
 * *which* path a test expects rather than merely that one exists.
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

    private static final Move[] MOVES = {
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

        PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(64, new Comparator<PathNode>() {
            @Override
            public int compare(PathNode a, PathNode b) {
                int byF = Double.compare(a.f, b.f);
                if (byF != 0) {
                    return byF;
                }
                int byG = Double.compare(b.g, a.g);
                if (byG != 0) {
                    return byG;
                }
                return Integer.compare(a.sequence, b.sequence);
            }
        });

        PathNode start = new PathNode(Pos.pack(startX, startY, startZ), discovered[0]++);
        start.g = 0.0;
        start.f = goal.heuristic(startX, startY, startZ);
        nodes.put(Long.valueOf(start.packed), start);
        open.add(start);

        while (!open.isEmpty()) {
            final PathNode current = open.poll();
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

            final PriorityQueue<PathNode> openRef = open;
            MoveSink sink = new MoveSink() {
                @Override
                public void offer(int nx, int ny, int nz, double cost) {
                    long key = Pos.pack(nx, ny, nz);
                    PathNode neighbour = nodes.get(Long.valueOf(key));
                    if (neighbour == null) {
                        neighbour = new PathNode(key, discovered[0]++);
                        nodes.put(Long.valueOf(key), neighbour);
                    }
                    if (neighbour.closed) {
                        return;
                    }
                    double tentative = current.g + cost;
                    if (tentative >= neighbour.g) {
                        return;
                    }
                    neighbour.g = tentative;
                    neighbour.f = tentative + goal.heuristic(nx, ny, nz);
                    neighbour.parent = current;
                    openRef.add(neighbour);
                }
            };

            for (int i = 0; i < MOVES.length; i++) {
                MOVES[i].expand(world, cx, cy, cz, sink);
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
