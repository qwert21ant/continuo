package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarPathfinderTest {

    private final AStarPathfinder pathfinder = new AStarPathfinder();

    private static PathResult run(AStarPathfinder pathfinder, FixtureWorld world) {
        Pos start = world.start();
        Pos goal = world.goal();
        return pathfinder.findPath(world, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()));
    }

    @Test
    void walksStraightAcrossOpenGround() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(), "start plus four steps");
        assertEquals(new Pos(0, 65, 0), result.path().get(0));
        assertEquals(new Pos(4, 65, 0), result.path().get(4));
    }

    @Test
    void routesAroundAWall() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "..#..\n"
                + ".....\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertTrue(!result.path().contains(new Pos(2, 65, 0)));
        assertTrue(!result.path().contains(new Pos(2, 65, 1)));
        assertEquals(new Pos(4, 65, 0), result.path().get(result.path().size() - 1));
    }

    @Test
    void climbsAStaircase() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "####\n"
                + "--- y=65\n"
                + "S###\n"
                + "--- y=66\n"
                + "..##\n"
                + "--- y=67\n"
                + "...#\n"
                + "--- y=68\n"
                + "...G\n"
                + "--- y=69\n"
                + "....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(new Pos(3, 68, 0), result.path().get(result.path().size() - 1));
        assertEquals(4, result.path().size());
    }

    @Test
    void descendsASafeDrop() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + "##\n"
                + "--- y=66\n"
                + "G#\n"
                + "--- y=67\n"
                + ".S\n"
                + "--- y=68\n"
                + "..\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(new Pos(0, 66, 0), result.path().get(result.path().size() - 1));
        assertEquals(2, result.path().size(), "one step west, falling one block");
    }

    @Test
    void reportsNoPathWhenWalledOff() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome());
        assertTrue(result.path().isEmpty());
    }

    @Test
    void neverRoutesThroughUnknownTerrain() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##?##\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "unreadable ground might not be there; the search must not walk over it");
    }

    @Test
    void neverRoutesThroughLava() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.!.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome());
    }

    @Test
    void neverRoutesThroughWater() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.~.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "C1 has no swimming movement, so water is an obstacle rather than a shortcut");
    }

    @Test
    void carpetIsWalkedThroughOnBothVersionsValues() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.c.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(), "carpet is not an obstacle and not a step up");
    }

    @Test
    void aPartialFloorIsWalkedOnJustLikeAFullBlock() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##p##\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(),
            "farmland's 1.21.11 value must be a floor, or modern farms become walls");
    }

    @Test
    void aBottomSlabIsAnObstacleInC1() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S._.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "a documented C1 limitation: an integer node cannot hold feet at y+0.5");
    }

    @Test
    void reportsBudgetExceededWithoutAPartialPath() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = new AStarPathfinder(2).findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.BUDGET_EXCEEDED, result.outcome());
        assertTrue(result.path().isEmpty(),
            "returning the best node so far is incremental cost backoff, which is C4's");
    }

    @Test
    void theSameSearchReturnsTheIdenticalPathEveryTime() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + "....G\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        List<Pos> first = run(pathfinder, world).path();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, run(new AStarPathfinder(), world).path(),
                "ties must break identically or the tests become flaky");
        }
    }

    @Test
    void theHeuristicNeverExceedsTheCostActuallyPaid() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#..\n"
                + "..#..\n"
                + "....G\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);
        Goal goal = new GoalBlock(4, 65, 2);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertTrue(goal.heuristic(0, 65, 0) <= result.cost(),
            "an overestimating heuristic silently gives up the shortest-path guarantee");
    }

    @Test
    void aGoalXzIsSatisfiedAtAnyHeight() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "####\n"
                + "--- y=65\n"
                + "S###\n"
                + "--- y=66\n"
                + "..##\n"
                + "--- y=67\n"
                + "...#\n"
                + "--- y=68\n"
                + "....\n"
                + "--- y=69\n"
                + "....\n");

        PathResult result = pathfinder.findPath(world, 0, 65, 0, new GoalXZ(3, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(3, result.path().get(result.path().size() - 1).x());
    }

    @Test
    void theStartItselfSatisfiesAnAlreadyReachedGoal() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#\n"
                + "--- y=65\n"
                + ".\n"
                + "--- y=66\n"
                + ".\n");

        PathResult result = pathfinder.findPath(world, 0, 65, 0, new GoalBlock(0, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(1, result.path().size());
    }

    @Test
    void expandedNodesAreReportedForTheRenderer() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertTrue(result.nodesExpanded() > 0);
        assertEquals(result.nodesExpanded(), result.expanded().size());
        assertTrue(result.expanded().contains(new Pos(0, 65, 0)), "the start is expanded first");
    }
}
