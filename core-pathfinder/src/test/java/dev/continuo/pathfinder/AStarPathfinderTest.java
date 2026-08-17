package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
    void theTieBreakOrderIsPinnedSoAComparatorChangeCannotPassUnnoticed() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "S..\n"
                + "...\n"
                + "..G\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        // A golden path. Repeating a search proves only that the code is deterministic — it
        // passes even for a comparator with no tie-break at all, because nothing in the search
        // varies between runs. Pinning the actual sequence is what makes a change in tie-break
        // order visible. Run it once and encode exactly what comes back.
        assertEquals(
            Arrays.asList(new Pos(0, 65, 0), new Pos(1, 65, 1), new Pos(2, 65, 2)),
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
        for (long seed = 1; seed <= 200; seed++) {
            FixtureWorld world = randomWorld(seed, 6);
            GoalBlock goal = new GoalBlock(5, 65, 5);

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
        for (int y = 65; y <= 68; y++) {
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
     * The cheapest cost from start to goal, by Dijkstra over the same four movements.
     *
     * <p>An oracle independent of A*: no heuristic, no closed set, and immutable queue entries.
     * This is what catches a search that returns a walkable but not-cheapest path — the class of
     * defect that a suite asserting only path <em>lengths</em> cannot see.
     */
    private static double optimalCost(final BlockSource world, Pos start, final Goal goal) {
        final Move[] moves = {
            new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
        };
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

            for (int i = 0; i < moves.length; i++) {
                moves[i].expand(world, cx, cy, cz, sink);
            }
        }
        return Double.POSITIVE_INFINITY;
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
