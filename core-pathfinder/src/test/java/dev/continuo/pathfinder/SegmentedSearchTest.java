package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chaining segments into a run. */
class SegmentedSearchTest {

    /** Unbounded flat ground: stone at {@link #FLOOR_Y}, air everywhere above. */
    private static final class FlatWorld implements BlockSource {
        static final int FLOOR_Y = 63;

        @Override
        public BlockData at(int x, int y, int z) {
            return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    /**
     * A heuristic that keeps decreasing without bound as {@code x} grows, so it is never
     * admissible for long. Exists to prove the run stops -- at the design's own cap, as
     * {@code BUDGET_EXCEEDED} -- when the termination proof's premise (h bounded below by
     * zero) fails, rather than looping forever.
     */
    private static final class UnboundedGoal implements Goal {
        @Override
        public boolean isReached(int x, int y, int z) {
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z, HeuristicRates rates) {
            return -x;
        }
    }

    private static SegmentedResult run(String fixture, int budget) {
        FixtureWorld world = TerrainFixture.load(fixture);
        Pos s = world.start();
        Pos g = world.goal();
        return new SegmentedSearch(new AStarPathfinder(budget))
            .run(world, s.x(), s.y(), s.z(), new GoalBlock(g.x(), g.y(), g.z()),
                CapabilitySet.none());
    }

    @Test
    void aRunReachesAGoalOneSearchCannotAtTheSameBudget() {
        // 232 is 84% of d-cliff's 273. Section 2.1 measured this reaching the goal in two
        // segments. PREDICTED: segments() == 2 and cost() == 274.42..., which is the unsegmented
        // optimum exactly -- a ratio of 1.000. Report what you actually see.
        SegmentedResult r = run("d-cliff.txt", 232);

        assertEquals(PathOutcome.FOUND, r.outcome());
        assertTrue(r.segments() > 1, "a single search at this budget cannot reach the goal");
        // Tolerance rather than exact equality: splitting one sum into two partial sums is
        // expected to move the last significant digit through floating-point reassociation.
        assertEquals(274.41707435261833, r.cost(), 1e-9,
            "the accumulated cost across segments must equal the unsegmented optimum");
    }

    @Test
    void theConcatenatedPathIsContiguous() {
        SegmentedResult r = run("d-cliff.txt", 232);
        assertEquals(PathOutcome.FOUND, r.outcome());

        for (int i = 1; i < r.path().size(); i++) {
            Pos a = r.path().get(i - 1);
            Pos b = r.path().get(i);
            int dx = Math.abs(a.x() - b.x());
            int dz = Math.abs(a.z() - b.z());
            assertTrue(dx <= 1 && dz <= 1 && !a.equals(b),
                "segments must join without a gap or a repeat at index " + i
                    + ": " + a + " then " + b);
        }
    }

    @Test
    void theRunStartsWhereItWasAskedTo() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos s = world.start();
        SegmentedResult r = run("d-cliff.txt", 232);
        assertEquals(s, r.path().get(0));
    }

    @Test
    void aSingleSearchThatSucceedsIsOneSegment() {
        // 400 is above d-cliff's 273, so the first search finds the goal outright.
        SegmentedResult r = run("d-cliff.txt", 400);
        assertEquals(PathOutcome.FOUND, r.outcome());
        assertEquals(1, r.segments());
        assertEquals(274.4170743526183, r.cost(), 1e-9);
    }

    @Test
    void aRunThatCannotProceedFailsSafeRatherThanLooping() {
        // 498 is 39% of a-big-obstacle's 1,247. Section 2.1 measured every rule failing here; the
        // required behaviour is that it STOPS, with no path, rather than ping-ponging.
        SegmentedResult r = run("a-big-obstacle.txt", 498);

        assertTrue(r.outcome() != PathOutcome.FOUND,
            "this budget is too small to reach the goal; a FOUND here would be a surprise worth"
                + " reporting rather than accepting");
        assertTrue(r.segments() < 20,
            "the run must terminate quickly, not ping-pong; saw " + r.segments() + " segments");
    }

    @Test
    void aBudgetExceededRunKeepsTheProgressItMadeBeforeFailing() {
        // I1: run() appends the accumulated prefix before returning the terminal search's outcome,
        // so a BUDGET_EXCEEDED run can still carry a real path and a non-zero cost -- that is a
        // deliberate, kept behaviour (see SegmentedResult.outcome()'s javadoc), and this pins it.
        // 498 is 39% of a-big-obstacle's 1,247 expansions -- verified by direct execution, not
        // merely predicted, to reach BUDGET_EXCEEDED in exactly two segments carrying a nine-step,
        // 41.96-tick prefix.
        SegmentedResult r = run("a-big-obstacle.txt", 498);

        assertEquals(PathOutcome.BUDGET_EXCEEDED, r.outcome());
        assertEquals(2, r.segments());
        assertEquals(9, r.path().size(),
            "the run stopped, but the prefix it walked before stopping must survive\n" + r);
        assertEquals(41.96329145087276, r.cost(), 1e-9,
            "the prefix's real cost must survive alongside it\n" + r);
    }

    @Test
    void asPathResultReportsPartialRatherThanLaunderingABudgetExceededPathAsReal() {
        // I1: PathResult's own contract says a non-FOUND outcome carries an empty path and a zero
        // cost. Handing SegmentedResult.outcome() straight through would violate that contract the
        // moment a BUDGET_EXCEEDED or NO_PATH run carries a real prefix -- exactly what the previous
        // test just pinned happens on this fixture. asPathResult() must remap to PARTIAL instead.
        SegmentedResult r = run("a-big-obstacle.txt", 498);
        assertEquals(PathOutcome.BUDGET_EXCEEDED, r.outcome(),
            "fixture assumption: the run itself must still report its terminal outcome\n" + r);

        PathResult combined = r.asPathResult();

        assertEquals(PathOutcome.PARTIAL, combined.outcome(),
            "a PathResult with a non-empty path must not claim a non-FOUND outcome\n" + combined);
        assertEquals(r.path(), combined.path());
        assertEquals(r.cost(), combined.cost(), 0.0);
    }

    @Test
    void asPathResultLeavesFoundAndEmptyOutcomesAlone() {
        // The remapping in asPathResult() must be narrow: a FOUND run's outcome must pass through
        // unchanged, and so must a run whose path is genuinely empty (NO_PATH, or BUDGET_EXCEEDED
        // with nothing salvageable) -- neither of those violates PathResult's contract, so relabelling
        // either would be a needless lie in the other direction.
        SegmentedResult found = run("d-cliff.txt", 400);
        assertEquals(PathOutcome.FOUND, found.asPathResult().outcome());

        FixtureWorld world = TerrainFixture.load("e-long-range.txt");
        Pos s = world.start();
        SegmentedResult noPath = new SegmentedSearch(new AStarPathfinder(100000))
            .run(world, s.x(), s.y(), s.z(), new GoalBlock(1737, 72, -786), CapabilitySet.none());
        assertEquals(PathOutcome.NO_PATH, noPath.outcome());
        assertTrue(noPath.path().isEmpty());
        assertEquals(PathOutcome.NO_PATH, noPath.asPathResult().outcome(),
            "an empty path must not be relabelled PARTIAL");
    }

    @Test
    void expandedAccumulatesAcrossEverySegmentRatherThanReportingOnlyTheLast() {
        // I3: three independent mutations survive with zero test failures -- one of them drops the
        // per-segment accumulation entirely. Recomputing each segment's own expansion count directly
        // and summing catches that without hardcoding either segment's exact figure: the sum must
        // match SegmentedResult.expanded() bit for bit, and it must exceed path().size(), since a
        // search always expands at least as many nodes as the route it returns and this fixture's
        // budget forces it to expand many more.
        FixtureWorld world = TerrainFixture.load("a-big-obstacle.txt");
        Pos s = world.start();
        Pos g = world.goal();
        GoalBlock goal = new GoalBlock(g.x(), g.y(), g.z());
        AStarPathfinder pathfinder = new AStarPathfinder(498);

        PathResult firstSegment =
            pathfinder.findPath(world, s.x(), s.y(), s.z(), goal, CapabilitySet.none());
        assertEquals(PathOutcome.PARTIAL, firstSegment.outcome(),
            "fixture assumption: the first segment alone must not settle the run\n" + firstSegment);
        Pos end = firstSegment.path().get(firstSegment.path().size() - 1);
        PathResult secondSegment =
            pathfinder.findPath(world, end.x(), end.y(), end.z(), goal, CapabilitySet.none());

        SegmentedResult r = run("a-big-obstacle.txt", 498);

        assertEquals(firstSegment.nodesExpanded() + secondSegment.nodesExpanded(),
            r.expanded().size(),
            "SegmentedResult.expanded() must be the sum of every segment's own expansions, not"
                + " just the terminal segment's\n" + r);
        assertTrue(r.expanded().size() > r.path().size(),
            "expansion count must not collapse to the step count\n" + r);
    }

    @Test
    void anUnreachableGoalEndsTheRunWithoutSegmenting() {
        FixtureWorld world = TerrainFixture.load("e-long-range.txt");
        Pos s = world.start();
        SegmentedResult r = new SegmentedSearch(new AStarPathfinder(100000))
            .run(world, s.x(), s.y(), s.z(), new GoalBlock(1737, 72, -786),
                CapabilitySet.none());

        assertEquals(PathOutcome.NO_PATH, r.outcome());
        assertEquals(1, r.segments());
        assertTrue(r.path().isEmpty());
    }

    @Test
    void aRunWhoseHeuristicIsNeverAdmissibleHitsTheCapInsteadOfLoopingForever() {
        // The cap is provably unreachable while h is admissible and non-negative: each PARTIAL
        // segment lowers h by at least minProgress, and h cannot fall below zero. No ordinary
        // fixture can drive it. UnboundedGoal breaks that premise on purpose -- its h keeps
        // falling without bound as x grows, on unbounded flat ground where isReached is always
        // false and the open set never empties -- so nothing but the cap can end the run.
        AStarPathfinder pathfinder = new AStarPathfinder(5000);
        HeuristicRates rates = pathfinder.registry().activeFor(CapabilitySet.none()).rates();
        UnboundedGoal goal = new UnboundedGoal();
        int startX = -50;
        int startY = FlatWorld.FLOOR_Y + 1;
        int startZ = 0;

        double hStart = goal.heuristic(startX, startY, startZ, rates);
        double minProgress = pathfinder.minProgressBlocks() * rates.horizontal();
        int expectedCap = (int) Math.ceil(hStart / minProgress) + 1;

        SegmentedResult r = new SegmentedSearch(pathfinder)
            .run(new FlatWorld(), startX, startY, startZ, goal, CapabilitySet.none());

        assertEquals(PathOutcome.BUDGET_EXCEEDED, r.outcome());
        assertEquals(expectedCap, r.segments(),
            "a heuristic that stops being admissible must still terminate, at the design's own"
                + " cap, rather than looping forever");
    }

    @Test
    void pathAndExpandedAreUnmodifiableAndIndependentOfTheCallersLists() {
        // m3: SegmentedResult's defensive copy and unmodifiableList wrapping both pass every other
        // test in this file with zero failures if deleted -- nothing else in the suite mutates the
        // lists it was handed or tries to mutate what came back. This pins both halves directly.
        List<Pos> path = new ArrayList<Pos>(Collections.singletonList(new Pos(0, 64, 0)));
        List<Pos> expanded = new ArrayList<Pos>(Collections.singletonList(new Pos(0, 64, 0)));

        final SegmentedResult r = new SegmentedResult(PathOutcome.FOUND, path, expanded, 3.5636, 1);

        // Mutating the caller's own list afterward must not reach into the result: only a copy
        // matters if this passed by reference.
        path.add(new Pos(1, 64, 0));
        expanded.add(new Pos(1, 64, 0));
        assertEquals(1, r.path().size(),
            "mutating the caller's list after construction must not change the result");
        assertEquals(1, r.expanded().size(),
            "mutating the caller's list after construction must not change the result");

        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                r.path().add(new Pos(2, 64, 0));
            }
        });
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                r.expanded().add(new Pos(2, 64, 0));
            }
        });
    }
}
