package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a budget-exhausted search returns.
 *
 * <p>These run on committed real terrain rather than hand-drawn worlds, because the design's
 * synthetic traps were built to pin predictions that section 2.1 then measured as false. Real
 * terrain is what disproved them and is what pins the rule now.
 */
class BackoffTest {

    /** A world with no route out, so nothing can ever beat the start's h. */
    private static final String BOXED = "origin: 0,64,0\n"
        + "--- y=64\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "--- y=65\n"
        + "#####\n"
        + "#S..#\n"
        + "#...#\n"
        + "#...#\n"
        + "#####\n"
        + "--- y=66\n"
        + "#####\n"
        + "#...#\n"
        + "#...#\n"
        + "#...#\n"
        + "#####\n"
        + "--- y=67\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n";

    private static PathResult search(FixtureWorld world, Goal goal, int budget) {
        Pos s = world.start();
        return new AStarPathfinder(budget).findPath(world, s.x(), s.y(), s.z(), goal);
    }

    @Test
    void aBudgetHitWithSomewhereWorthGoingReturnsPartial() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos g = world.goal();
        // 232 is 84% of the 273 expansions this fixture's search needs. Section 2.1 measured the
        // whole run reaching the goal in two segments at this budget, so the FIRST search must be
        // the partial one.
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(PathOutcome.PARTIAL, r.outcome());
        assertTrue(r.path().size() > 1, "a partial path must go somewhere: " + r.path().size());
        assertTrue(r.cost() > 0.0, "a partial path has a real cost, got " + r.cost());
    }

    @Test
    void thePartialPathStartsAtTheStartAndDoesNotReachTheGoal() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos s = world.start();
        Pos g = world.goal();
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(s, r.path().get(0));
        Pos end = r.path().get(r.path().size() - 1);
        assertTrue(!end.equals(g), "a PARTIAL must not end on the goal, ended at " + end);
    }

    @Test
    void aBudgetHitWithNowhereWorthGoingStillReturnsNothing() {
        FixtureWorld world = FixtureWorld.parse(BOXED);
        Pos s = world.start();
        // The goal is outside the box, so nothing reachable improves h by minProgress. The budget
        // is larger than the box, so the open set empties first -- which is NO_PATH, not
        // BUDGET_EXCEEDED. Budget 3 forces the budget branch instead.
        PathResult r = new AStarPathfinder(3)
            .findPath(world, s.x(), s.y(), s.z(), new GoalBlock(100, 65, 100));

        assertEquals(PathOutcome.BUDGET_EXCEEDED, r.outcome());
        assertTrue(r.path().isEmpty(), "BUDGET_EXCEEDED still means no path at all");
        assertEquals(0.0, r.cost(), 0.0);
    }

    @Test
    void openSetExhaustionNeverBacksOff() {
        // D5, on real terrain. e-long-range is clamped, so its goal is outside the map and must be
        // retyped from the clamp notice in the file. The search advances a long way -- h at the
        // start is about 509 -- and still returns NO_PATH when the open set empties.
        FixtureWorld world = TerrainFixture.load("e-long-range.txt");
        Pos s = world.start();
        assertNotNull(s, "e-long-range must still carry its S marker");

        PathResult r = new AStarPathfinder(100000)
            .findPath(world, s.x(), s.y(), s.z(), new GoalBlock(1737, 72, -786));

        assertEquals(PathOutcome.NO_PATH, r.outcome(),
            "proving a goal unreachable is the one thing a search can say definitively;"
                + " backing off would destroy it");
        assertTrue(r.path().isEmpty());
    }

    @Test
    void aReachableGoalIsUnaffected() {
        FixtureWorld world = TerrainFixture.load("c-short-hop.txt");
        Pos g = world.goal();
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 10000);

        assertEquals(PathOutcome.FOUND, r.outcome());
        assertEquals(73.08618290174553, r.cost(), 0.0);
    }

    @Test
    void theSameSearchTwiceReturnsTheSameSegment() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos g = world.goal();
        PathResult a = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);
        PathResult b = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(a.outcome(), b.outcome());
        assertEquals(a.path(), b.path());
        assertEquals(a.cost(), b.cost(), 0.0);
    }
}
