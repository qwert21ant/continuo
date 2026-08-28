package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chaining segments into a run. */
class SegmentedSearchTest {

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
}
