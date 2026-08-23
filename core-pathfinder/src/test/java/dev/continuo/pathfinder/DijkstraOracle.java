package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MutableExpansionContext;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The cheapest cost from a start to a goal, by Dijkstra over a given movement list.
 *
 * <p>An oracle independent of A*: no heuristic, no closed set, and immutable queue entries. This is
 * what catches a search that returns a walkable but not-cheapest path — the class of defect that a
 * suite asserting only path <em>lengths</em> cannot see.
 *
 * <p><b>It takes the movement list as a parameter, and that is load-bearing.</b> Run over C1's four
 * built-ins it is the 400-seed optimality guard in {@code AStarPathfinderTest}. Run over a registry
 * whose cheapest movement is a synthetic one, it is what
 * {@link HeuristicMultiplierAdmissibilityTest} uses to catch an inadmissible heuristic — which the
 * built-in four cannot express, because their multiplier is pinned to {@code TRAVERSE} by the fact
 * that traverse is already the cheapest of them per axis step.
 */
final class DijkstraOracle {

    private DijkstraOracle() {
    }

    /** An immutable frontier entry for the Dijkstra oracle. */
    private static final class Entry {
        final long packed;
        final double cost;

        Entry(long packed, double cost) {
            this.packed = packed;
            this.cost = cost;
        }
    }

    /**
     * @param world the world the movements read; never {@code null}
     * @param start where the search begins
     * @param goal what to reach; never {@code null}
     * @param moves the movements to expand, in any order — Dijkstra does not care
     * @return the cheapest reachable cost, or {@link Double#POSITIVE_INFINITY} if unreachable
     */
    static double optimalCost(final BlockSource world, Pos start, final Goal goal,
                              final List<IMovementType> moves) {
        final MutableExpansionContext ctx = new MutableExpansionContext(world);
        final Map<Long, Double> best = new HashMap<Long, Double>();
        final PriorityQueue<Entry> frontier =
            new PriorityQueue<Entry>(64, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return Double.compare(a.cost, b.cost);
                }
            });

        best.put(Long.valueOf(start.packed()), Double.valueOf(0.0));
        frontier.add(new Entry(start.packed(), 0.0));

        while (!frontier.isEmpty()) {
            final Entry current = frontier.poll();
            Double known = best.get(Long.valueOf(current.packed));
            if (known == null || current.cost > known.doubleValue() + 1.0e-12) {
                continue;
            }

            int cx = Pos.unpackX(current.packed);
            int cy = Pos.unpackY(current.packed);
            int cz = Pos.unpackZ(current.packed);
            if (goal.isReached(cx, cy, cz)) {
                return current.cost;
            }

            MoveSink sink = new MoveSink() {
                @Override
                public void offer(int nx, int ny, int nz, double cost) {
                    Long key = Long.valueOf(Pos.pack(nx, ny, nz));
                    double next = current.cost + cost;
                    Double previous = best.get(key);
                    if (previous == null || next < previous.doubleValue() - 1.0e-12) {
                        best.put(key, Double.valueOf(next));
                        frontier.add(new Entry(key.longValue(), next));
                    }
                }
            };

            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).expand(ctx, sink);
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
