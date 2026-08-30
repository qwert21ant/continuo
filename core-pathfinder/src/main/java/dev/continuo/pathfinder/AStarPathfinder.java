package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementRegistry;
import dev.continuo.movement.MovementRegistry;

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
        Search search = begin(world, startX, startY, startZ, goal, caps);
        search.advance(Integer.MAX_VALUE);
        return search.result();
    }

    /**
     * Starts a search without expanding anything, so a caller can spend it a slice at a time.
     *
     * <p>{@link #findPath} is this method plus one unbounded slice. There is no second
     * implementation and no second loop — which is what makes a sliced search returning the same
     * answer as an unsliced one a property of the construction rather than of the tests.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return a search that has expanded nothing yet; never {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public Search begin(BlockSource world, int startX, int startY, int startZ, Goal goal,
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
        return new Search(world, startX, startY, startZ, goal, caps, nodeBudget,
            minProgressBlocks, registry.activeFor(caps));
    }
}
