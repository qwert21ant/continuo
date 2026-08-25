package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.HeuristicRates;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

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
                + "..#..\n"
                + "..#..\n"
                + ".....\n"
                + "--- y=67\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + "--- y=68\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertTrue(!result.path().contains(new Pos(2, 66, 0)),
            "the wall is two tall and must be routed around, not climbed");
        assertTrue(!result.path().contains(new Pos(2, 66, 1)));
        assertTrue(result.path().contains(new Pos(2, 65, 2)),
            "the only gap is the third row, so the path must pass through it");
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
        // The wall is two blocks tall with clear air above it, so the refusal comes from the
        // wall itself rather than from running out of declared extent. A one-block wall in a
        // three-slice world is refused only because y+2 reads UNKNOWN off the top, which would
        // make this test pass for a reason that has nothing to do with walls.
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "--- y=66\n"
                + "..#..\n"
                + "--- y=67\n"
                + ".....\n"
                + "--- y=68\n"
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
    void cardinalsStepOrderIsPinnedSoAReorderingCannotPassUnnoticed() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "S..\n"
                + ".#.\n"
                + "..G\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        // A golden path, over a fixture with a genuine tie. The centre pillar rules out the
        // two-diagonal route, leaving two four-traverse routes of identical cost — around the
        // north-east corner, or around the south-west. Which one comes back is decided by the
        // order the movements offer their neighbours in, so reversing Cardinals' step order fails
        // this test — measured, and it returns the mirror-image route around the other corner.
        //
        // It does NOT pin the comparator, despite the tie. Reducing the comparator to f alone,
        // reversing its g leg, and stubbing its sequence leg to 0 each leave this test green,
        // because the two candidate routes are discovered in an order the surviving legs already
        // agree on. The comparator's three legs are pinned directly in QueuedNodeOrderTest; this
        // test is the regression guard for Cardinals' step order and is named for that.
        //
        // Nor does it pin the order the *movements* are registered in: swapping Traverse and
        // Diagonal in defaultRegistry() leaves this green, because neither route uses a diagonal.
        // Since C2 that is a registry concern, and DefaultRegistryTest is what guards it.
        //
        // An open 3x3 will not do, and this is worth stating because it was tried: there the
        // two-diagonal route is the unique optimum, so every iteration order returns it.
        // Repeating a search proves only determinism, which this code has regardless; pinning a
        // path is only meaningful where a tie exists to break.
        assertEquals(
            Arrays.asList(new Pos(0, 65, 0), new Pos(1, 65, 0), new Pos(2, 65, 0),
                new Pos(2, 65, 1), new Pos(2, 65, 2)),
            run(pathfinder, world).path());
    }

    @Test
    void aStraightRunCostsExactlyItsTraverses() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        assertEquals(4 * MovementCosts.TRAVERSE, run(pathfinder, world).cost(), 1.0e-9);
    }

    @Test
    void aStaircaseCostsExactlyItsAscents() {
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

        assertEquals(3 * MovementCosts.ASCEND, run(pathfinder, world).cost(), 1.0e-9);
    }

    @Test
    void theReturnedPathIsTheCheapestOneOverManyRandomWorlds() {
        // These parameters are not arbitrary. An 8x8 world at 25% pillar density over seeds
        // 1..400 was measured to separate the two implementations: the shipped search matches
        // Dijkstra on all 364 solvable worlds, while the pre-fix mutate-and-requeue version
        // disagrees on 4 of them, first at seed 6. A 6x6 world at the same density diverges on
        // none of them — which is why an earlier version of this test passed against the bug.
        for (long seed = 1; seed <= 400; seed++) {
            FixtureWorld world = randomWorld(seed, 8);
            GoalBlock goal = new GoalBlock(7, 65, 7);

            PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0, goal);
            if (result.outcome() != PathOutcome.FOUND) {
                continue;
            }

            assertEquals(optimalCost(world, new Pos(0, 65, 0), goal), result.cost(), 1.0e-9,
                "A* returned a costlier path than Dijkstra over the same movements, seed " + seed);
        }
    }

    /**
     * A deterministic pseudorandom world: solid floor, scattered pillars, generous headroom.
     * Both corners are kept clear so the start and goal are always standable.
     */
    private static FixtureWorld randomWorld(long seed, int size) {
        Random random = new Random(seed);
        StringBuilder art = new StringBuilder("origin: 0,64,0\n--- y=64\n");
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                art.append('#');
            }
            art.append('\n');
        }
        for (int y = 65; y <= 69; y++) {
            art.append("--- y=").append(y).append('\n');
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    boolean corner = (x == 0 && z == 0) || (x == size - 1 && z == size - 1);
                    art.append(!corner && y <= 66 && random.nextInt(100) < 25 ? '#' : '.');
                }
                art.append('\n');
            }
        }
        return FixtureWorld.parse(art.toString());
    }

    /**
     * The cheapest cost from start to goal, by Dijkstra over C1's four movements.
     *
     * <p>The oracle itself lives in {@link DijkstraOracle}, unchanged in substance, because a
     * second test needs to run it over a different movement list — see
     * {@link HeuristicMultiplierAdmissibilityTest}, which is the guard for the one admissibility
     * bug these four movements cannot express. What that class needs is a registry whose
     * <em>cheapest</em> movement is the one declaring wrongly; over C1's four, traverse is always
     * the cheapest per axis step, so no declaration any of the other three could make would move
     * the multiplier at all.
     */
    private static double optimalCost(BlockSource world, Pos start, Goal goal) {
        return DijkstraOracle.optimalCost(world, start, goal, Arrays.<IMovementType>asList(
            new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()));
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
        // The search runs on the multiplier *derived* from the active set, not on TRAVERSE. This
        // assertion is only about the same heuristic the search used because
        // DefaultRegistryTest.theMultiplierOverC1sMovementsIsWhatC1sConstantWas pins the two
        // equal over the default registry with no capabilities granted. If that pin ever goes,
        // this line silently starts checking a different heuristic than the one under test.
        assertTrue(goal.heuristic(0, 65, 0,
            new HeuristicRates(MovementCosts.TRAVERSE, MovementCosts.TRAVERSE)) <= result.cost(),
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
