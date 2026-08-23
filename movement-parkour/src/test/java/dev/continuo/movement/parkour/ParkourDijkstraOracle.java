package dev.continuo.movement.parkour;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MutableExpansionContext;
import dev.continuo.pathfinder.Goal;
import dev.continuo.pathfinder.Pos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The cheapest cost from a start to a goal, by Dijkstra over a given movement list.
 *
 * <p>A second copy of {@code dev.continuo.pathfinder.DijkstraOracle}, and the duplication is
 * forced rather than careless. That class is package-private in {@code :core-pathfinder}'s test
 * source set, so nothing here can see it; and it could not be moved somewhere shared without
 * either publishing an oracle on a production surface or giving {@code :core-pathfinder}'s tests
 * sight of {@link ParkourMove}, which would defeat the seam this whole module exists to prove.
 *
 * <p><b>Kept faithful to the original in every detail that decides an answer</b>, because a
 * divergence would show up as a spurious A* defect rather than as a bug here: the same
 * {@link Double#compare} comparator on cost, the same {@code 1.0e-12} staleness tolerance on poll
 * and {@code 1.0e-12} tolerance on relaxation, the same immutable frontier entries with no
 * decrease-key and no closed set, and the same termination — the goal test happens when a node is
 * <em>polled</em>, never when it is generated.
 *
 * <p>No heuristic, no closed set. That is the point: it cannot share A*'s admissibility argument,
 * so it is a genuinely independent answer to "what does the cheapest route cost".
 */
final class ParkourDijkstraOracle {

    private ParkourDijkstraOracle() {
    }

    /** An immutable frontier entry. */
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
     * @param start where the search begins; never {@code null}
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
