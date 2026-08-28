package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementRegistry;
import dev.continuo.movement.HeuristicRates;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementRegistry;
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
 * <p><b>The node budget is a stopping condition with a fallback.</b> Exhausting it yields
 * {@link PathOutcome#PARTIAL} and the path to the reached node closest to the goal, provided one
 * beat the start by {@code minProgressBlocks}; otherwise {@link PathOutcome#BUDGET_EXCEEDED} and
 * no path. Chaining those segments into a run is {@code SegmentedSearch}.
 */
public final class AStarPathfinder {

    /**
     * The node budget a search uses when none is given.
     *
     * <p>25,000, set from the per-route expansion needs measured on real terrain (design §6):
     * {@code c-short-hop} 94, {@code d-cliff} 2,082, {@code b-cave-climb} 3,474,
     * {@code a-big-obstacle} 4,445, and the 111-block {@code e-long-range} route 17,423 — the
     * hardest of them at 143% of its need, so every route measured so far fits inside a single
     * search. It is not fitted to a millisecond figure: the in-game timing instrumentation this
     * branch ships has not yet been run in a Minecraft client, so whether 25,000 expansions also
     * fits a tick's time budget is confirmed by a later in-game run, not by this number.
     */
    public static final int DEFAULT_NODE_BUDGET = 25000;

    /**
     * How much closer to the goal a backoff candidate must be, in blocks, when none is given.
     *
     * <p>In blocks rather than ticks because blocks are the unit a person reasons in; it is
     * multiplied by {@code HeuristicRates.horizontal()} at search time, so it stays meaningful
     * when a changed movement set changes the cheapest rate.
     *
     * <p>4.0, from {@code MinProgressSweepTest}'s table (design §6.1): margins of 1, 2, 4 and 8
     * blocks reached {@code FOUND} on all three fixtures swept ({@code d-cliff},
     * {@code b-cave-climb}, {@code a-big-obstacle}) at identical quality ratios — 1.000, 1.476 and
     * 1.064 respectively, bit-for-bit the same across that whole range because the same backoff
     * candidate cleared every margin up to 8. 16 blocks broke two of the three fixtures, returning
     * an empty {@code BUDGET_EXCEEDED} where a useful segment existed — the failure mode this
     * constant exists to avoid. The sweep does not pick a unique winner: 1, 2, 4 and 8 are an
     * exact tie on both of its own criteria (fixtures reaching {@code FOUND}, then quality ratio),
     * so the choice among them is a judgment call made on grounds outside the sweep. The risk
     * either side of the tie is asymmetric — too large fails outright at 16, too small has no
     * measured cost anywhere in the tied range — so 4.0 is kept: two doublings short of the
     * failure at 16 rather than one, and the value every other piece of evidence in this branch
     * was measured under, including {@code BackoffTest}'s in-game-derived cost assertions and
     * {@code SegmentedSearchTest}'s mutation-checked {@code 274.41707435261833}.
     */
    public static final double DEFAULT_MIN_PROGRESS_BLOCKS = 4.0;

    private final int nodeBudget;
    private final IMovementRegistry registry;
    private final double minProgressBlocks;

    /**
     * The registry a pathfinder uses when given none: {@code walk.traverse}, {@code walk.ascend},
     * {@code walk.descend} and {@code walk.diagonal}, registered in that order, plus whatever
     * {@link MovementRegistry#discover()} then finds on the classpath.
     *
     * <p>The order is load-bearing. A* breaks cost ties by the order neighbours were discovered,
     * so registering these four in any other sequence would change which of two equal-cost paths
     * comes back.
     *
     * <p><b>Public because the four movements themselves are not.</b> They are package-private, so
     * a module outside {@code dev.continuo.pathfinder} — a movement plugin's own tests, for
     * instance, asserting what granting a capability does to the heuristic's multiplier — has no
     * way to assemble this registry for itself. Each call returns a fresh, independently mutable
     * registry, so a caller may {@link MovementRegistry#register(dev.continuo.movement.IMovementType)
     * register} onto it without affecting anyone else's.
     *
     * @return a fresh registry; never {@code null}
     */
    public static MovementRegistry defaultRegistry() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new TraverseMove());
        registry.register(new AscendMove());
        registry.register(new DescendMove());
        registry.register(new DiagonalMove());
        registry.discover();
        return registry;
    }

    /** Creates a pathfinder with {@link #DEFAULT_NODE_BUDGET} and {@link #defaultRegistry()}. */
    public AStarPathfinder() {
        this(DEFAULT_NODE_BUDGET);
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @throws IllegalArgumentException if the budget is not positive
     */
    public AStarPathfinder(int nodeBudget) {
        this(nodeBudget, defaultRegistry());
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @param registry the movements this pathfinder may use; never {@code null}
     * @throws IllegalArgumentException if the budget is not positive or the registry is null
     */
    public AStarPathfinder(int nodeBudget, IMovementRegistry registry) {
        this(nodeBudget, registry, DEFAULT_MIN_PROGRESS_BLOCKS);
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @param registry the movements this pathfinder may use; never {@code null}
     * @param minProgressBlocks how much closer a backoff candidate must be; must be positive
     * @throws IllegalArgumentException if the budget or the margin is not positive, or the
     *         registry is null
     */
    public AStarPathfinder(int nodeBudget, IMovementRegistry registry, double minProgressBlocks) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (!(minProgressBlocks > 0.0)) {
            throw new IllegalArgumentException(
                "minProgressBlocks must be positive, got " + minProgressBlocks);
        }
        this.nodeBudget = nodeBudget;
        this.registry = registry;
        this.minProgressBlocks = minProgressBlocks;
    }

    /** @return the movements this pathfinder uses; for {@code SegmentedSearch} */
    IMovementRegistry registry() {
        return registry;
    }

    /** @return the backoff margin in blocks; for {@code SegmentedSearch} */
    double minProgressBlocks() {
        return minProgressBlocks;
    }

    /**
     * Searches with no capabilities granted, so only movements that require none are used.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal) {
        return findPath(world, startX, startY, startZ, goal, CapabilitySet.none());
    }

    /**
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal,
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

        final ActiveMovements active = registry.activeFor(caps);
        final HeuristicRates rates = active.rates();
        final List<IMovementType> moves = active.movements();

        final double hStart = goal.heuristic(startX, startY, startZ, rates);
        final SegmentSelector selector =
            new SegmentSelector(hStart, minProgressBlocks * rates.horizontal());

        final Map<Long, PathNode> nodes = new HashMap<Long, PathNode>();
        final List<Pos> expanded = new ArrayList<Pos>();
        final int[] discovered = {0};

        final PriorityQueue<QueuedNode> open =
            new PriorityQueue<QueuedNode>(64, QueuedNodeOrder.INSTANCE);

        long startPacked = Pos.pack(startX, startY, startZ);
        PathNode start = new PathNode(startPacked);
        start.g = 0.0;
        nodes.put(Long.valueOf(startPacked), start);
        open.add(new QueuedNode(startPacked, hStart, 0.0, discovered[0]++));

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

            // h is recomputed rather than taken as entry.f - entry.g. The subtraction is exact,
            // but only by an argument about stale heap entries, and this project's reviews exist
            // to catch invariants that subtle. The saving is arithmetic that reads no world.
            selector.consider(current.packed, goal.heuristic(cx, cy, cz, rates));

            if (goal.isReached(cx, cy, cz)) {
                return new PathResult(PathOutcome.FOUND, reconstruct(current), expanded, current.g);
            }
            if (expanded.size() >= nodeBudget) {
                if (selector.hasCandidate()) {
                    PathNode best = nodes.get(Long.valueOf(selector.candidate()));
                    return new PathResult(PathOutcome.PARTIAL,
                        reconstruct(best), expanded, best.g);
                }
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
                        tentative + goal.heuristic(nx, ny, nz, rates), tentative,
                        discovered[0]++));
                }
            };

            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).expand(ctx, sink);
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
