package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementRegistry;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression guard for the heuristic's multiplier: A* pinned against a Dijkstra optimum over a
 * registry whose <em>cheapest</em> movement is a wide one.
 *
 * <p><b>Why this class exists rather than another seed range on
 * {@code AStarPathfinderTest.theReturnedPathIsTheCheapestOneOverManyRandomWorlds}.</b> Task 8
 * mutated {@code DescendMove.minCostPerAxisStep()} to return its cheapest absolute cost
 * ({@code TRAVERSE + fallTicks(1)} &asymp; 8.1783) instead of its worst per-axis-step ratio
 * (&asymp; 4.0108) — a genuine admissibility bug — and the 400-seed oracle stayed green. That is not
 * a sizing problem and more seeds would never have fixed it, because under the default registry the
 * mutation is invisible <em>by construction</em>: {@link ActiveMovements#rates()} is a
 * minimum, and {@code walk.traverse}'s 3.5636 sits below both 4.0108 and 8.1783, so the multiplier
 * — and therefore every {@code Goal.heuristic} call, and therefore every search — is bit-identical
 * under both.
 *
 * <p>The principle that follows, which is worth stating because it is nowhere in the spec:
 *
 * <blockquote>A too-high {@code minCostPerHorizontalUnit()} declaration can only break admissibility if
 * that movement would otherwise have been the minimum.</blockquote>
 *
 * <p>So the fixture below is a registry in which the mis-declarable movement <em>is</em> the
 * minimum. {@code rail.glide} carries three axis steps for 3.0, so its true cost per axis step is
 * 1.0 — well under {@code walk.plain}'s and {@code rail.ramp}'s 3.0. Declared honestly it is the
 * registry's minimum, the heuristic is loose but consistent, and A* must return the true optimum.
 * Declared as its whole cost instead, the multiplier becomes 3.0 while the glide still delivers
 * three axis steps for 3.0, so the heuristic credits 3.0 per axis step against a true 1.0,
 * overestimates, and A* — which never reopens a closed node — walks the expensive corridor straight
 * to the goal and returns it.
 *
 * <p><b>The corridor is what makes the overestimate bite.</b> A world where the wide movement is
 * usable everywhere is not enough: a wide edge lowers {@code f} whichever multiplier is in force, so
 * A* follows it greedily and stumbles onto the optimum anyway. The glide is therefore reachable only
 * from {@code z = 1}, behind a ramp that costs a full step and buys no progress toward the goal —
 * which an inflated multiplier prices out of the running entirely.
 */
class HeuristicMultiplierAdmissibilityTest {

    /** The far end of the corridor, and the goal's X. */
    private static final int FAR = 9;

    /** What one plain step, one ramp, and one whole glide each cost. */
    private static final double STEP = 3.0;

    /** A glide carries this many axis steps for {@link #STEP}. */
    private static final int GLIDE_SPAN = 3;

    /**
     * What {@code rail.glide} declares.
     *
     * <p><b>The honest figure is {@code STEP / GLIDE_SPAN} = 1.0.</b> Replacing it with {@code STEP}
     * — the whole cost, ignoring the span, which is exactly the mistake spec §5.3 warns about — is
     * the mutation this class exists to fail on.
     */
    private static final double GLIDE_DECLARES = STEP / GLIDE_SPAN;

    /** No movement here reads the world, so its contents are irrelevant. */
    private static final BlockSource DUMMY_WORLD = new BlockSource() {
        @Override
        public BlockData at(int x, int y, int z) {
            return BlockData.UNKNOWN;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    /** A movement over the synthetic corridor, with a declaration it is free to lie about. */
    private abstract static class CorridorMove implements IMovementType {
        private final String id;
        private final double declares;

        CorridorMove(String id, double declares) {
            this.id = id;
            this.declares = declares;
        }

        @Override
        public final String id() {
            return id;
        }

        @Override
        public final Set<Capability> requires() {
            return Collections.<Capability>emptySet();
        }

        @Override
        public final double minCostPerHorizontalUnit() {
            return declares;
        }

        @Override
        public final double minCostPerVerticalStep() {
            return Double.POSITIVE_INFINITY;
        }
    }

    /** One block along X for a full {@link #STEP}: honest, dear, and available on both lanes. */
    private static final class PlainStep extends CorridorMove {
        PlainStep() {
            super("walk.plain", STEP);
        }

        @Override
        public void expand(ExpansionContext ctx, MoveSink sink) {
            if (ctx.x() + 1 <= FAR) {
                sink.offer(ctx.x() + 1, ctx.y(), ctx.z(), STEP);
            }
        }
    }

    /** Three blocks along X for one {@link #STEP}, on the {@code z = 1} lane only. */
    private static final class RailGlide extends CorridorMove {
        RailGlide() {
            super("rail.glide", GLIDE_DECLARES);
        }

        @Override
        public void expand(ExpansionContext ctx, MoveSink sink) {
            if (ctx.z() == 1 && ctx.x() + GLIDE_SPAN <= FAR) {
                sink.offer(ctx.x() + GLIDE_SPAN, ctx.y(), ctx.z(), STEP);
            }
        }
    }

    /** Between the two lanes, for a full {@link #STEP} and no progress along X. */
    private static final class RailRamp extends CorridorMove {
        RailRamp() {
            super("rail.ramp", STEP);
        }

        @Override
        public void expand(ExpansionContext ctx, MoveSink sink) {
            sink.offer(ctx.x(), ctx.y(), 1 - ctx.z(), STEP);
        }
    }

    private static MovementRegistry corridorRegistry() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new PlainStep());
        registry.register(new RailGlide());
        registry.register(new RailRamp());
        return registry;
    }

    @Test
    void theWideMovementIsStrictlyTheRegistrysMinimumSoItsDeclarationIsTheMultiplier() {
        ActiveMovements active = corridorRegistry().activeFor(CapabilitySet.none());

        for (int i = 0; i < active.movements().size(); i++) {
            IMovementType type = active.movements().get(i);
            if (!"rail.glide".equals(type.id())) {
                assertTrue(type.minCostPerHorizontalUnit() > active.rates().horizontal(),
                    type.id() + " must declare strictly more per axis step than rail.glide, or "
                        + "this fixture silently degrades into the same blind spot the default "
                        + "registry has — where the mis-declaring movement is not the minimum, so "
                        + "a too-high declaration moves no multiplier and changes no search");
            }
        }

        assertEquals(1.0, active.rates().horizontal(), 1.0e-9,
            "three axis steps for a cost of 3.0 is 1.0 per axis step, and being the minimum it is "
                + "what Goal.heuristic is handed");
    }

    @Test
    void aTooHighDeclarationOnTheCheapestMovementCostsAStarItsShortestPath() {
        MovementRegistry registry = corridorRegistry();
        Pos start = new Pos(0, 0, 0);
        Goal goal = new GoalBlock(FAR, 0, 0);

        PathResult result = new AStarPathfinder(10000, registry)
            .findPath(DUMMY_WORLD, start.x(), start.y(), start.z(), goal);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(
            DijkstraOracle.optimalCost(DUMMY_WORLD, start, goal,
                registry.activeFor(CapabilitySet.none()).movements()),
            result.cost(), 1.0e-9,
            "A* returned a costlier path than Dijkstra over the same movements. The optimum is "
                + "ramp up, three glides, ramp down; the expensive corridor is nine plain steps. "
                + "A multiplier that credits the glide's whole cost to each of its three axis "
                + "steps makes every node on the expensive corridor look free and the ramp look "
                + "ruinous, so A* runs the corridor to the end and — never reopening a closed "
                + "node — returns it");
        assertEquals(6, result.path().size(),
            "start, ramp up, three glides, ramp down — the expensive corridor would be ten");
    }

    @Test
    void theTwoRoutesReallyDoDifferSoTheOracleHasSomethingToCatch() {
        MovementRegistry registry = corridorRegistry();
        Pos start = new Pos(0, 0, 0);
        Goal goal = new GoalBlock(FAR, 0, 0);

        assertEquals(15.0,
            DijkstraOracle.optimalCost(DUMMY_WORLD, start, goal,
                registry.activeFor(CapabilitySet.none()).movements()),
            1.0e-9,
            "ramp up (3.0), three glides (3.0 each), ramp down (3.0)");
        assertEquals(27.0,
            DijkstraOracle.optimalCost(DUMMY_WORLD, start, goal,
                Collections.<IMovementType>singletonList(new PlainStep())),
            1.0e-9,
            "and the plain corridor — the route an inflated multiplier sends A* down — really is "
                + "reachable on its own, at nearly twice the cost. An A* that takes it is not "
                + "merely tie-breaking differently, it is wrong by 12 ticks");
    }
}
