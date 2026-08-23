package dev.continuo.pathfinder;

import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderAcceptanceTest {

    /**
     * A world with a wall, a staircase over it, and a drop on the far side. The only route is
     * up and over, which exercises all four movements in one search.
     */
    private static final String OBSTACLE_COURSE =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "#######\n"
            + "#######\n"
            + "#######\n"
            + "--- y=65\n"
            + "S..#..G\n"
            + "...#...\n"
            + "...#...\n"
            + "--- y=66\n"
            + "...#...\n"
            + ".......\n"
            + "...#...\n"
            + "--- y=67\n"
            + ".......\n"
            + ".......\n"
            + ".......\n";

    @Test
    void findsTheOnlyRouteOverTheWall() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome(), render(world, result));
        assertEquals(new Pos(0, 65, 0), result.path().get(0), render(world, result));
        assertEquals(new Pos(6, 65, 0), result.path().get(result.path().size() - 1),
            render(world, result));
        assertTrue(result.path().contains(new Pos(3, 66, 1)),
            "the only way past the wall is over its top at z=1\n" + render(world, result));
    }

    @Test
    void everyStepOfEveryPathIsStandable() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        for (Pos pos : result.path()) {
            assertTrue(Standability.standable(world, pos.x(), pos.y(), pos.z()),
                "the search returned a position the player cannot occupy: " + pos + "\n"
                    + render(world, result));
        }
    }

    @Test
    void consecutiveStepsAreAlwaysAdjacent() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        for (int i = 1; i < result.path().size(); i++) {
            Pos from = result.path().get(i - 1);
            Pos to = result.path().get(i);
            int dx = Math.abs(to.x() - from.x());
            int dz = Math.abs(to.z() - from.z());
            int dy = to.y() - from.y();

            assertTrue(dx <= 1 && dz <= 1, "teleported horizontally " + from + " -> " + to
                + "\n" + render(world, result));
            assertTrue(dy <= 1, "climbed more than one block " + from + " -> " + to
                + "\n" + render(world, result));
            assertTrue(-dy <= MovementCosts.MAX_SAFE_FALL, "fell further than the safe limit "
                + from + " -> " + to + "\n" + render(world, result));
            assertTrue(dx + dz > 0, "did not move horizontally " + from + " -> " + to
                + "\n" + render(world, result));
        }
    }

    @Test
    void theHeuristicNeverOverestimatesFromAnyPointOnAFoundPath() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        Goal goal = new GoalBlock(6, 65, 0);
        AStarPathfinder pathfinder = new AStarPathfinder();
        PathResult whole = pathfinder.findPath(world, 0, 65, 0, goal);

        assertEquals(PathOutcome.FOUND, whole.outcome(), render(world, whole));

        for (Pos pos : whole.path()) {
            PathResult fromHere = pathfinder.findPath(world, pos.x(), pos.y(), pos.z(), goal);
            assertEquals(PathOutcome.FOUND, fromHere.outcome(), render(world, fromHere));
            assertTrue(
                goal.heuristic(pos.x(), pos.y(), pos.z(), MovementCosts.TRAVERSE)
                    <= fromHere.cost() + 1.0e-9,
                "the heuristic overestimates from " + pos + ": "
                    + goal.heuristic(pos.x(), pos.y(), pos.z(), MovementCosts.TRAVERSE) + " > "
                    + fromHere.cost() + render(world, fromHere));
        }
    }

    private static String render(FixtureWorld world, PathResult result) {
        return "\n" + PathRenderer.render(world, result);
    }
}
