package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathRendererTest {

    private static final String FLAT =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "#####\n"
            + "--- y=65\n"
            + "S...G\n"
            + "--- y=66\n"
            + ".....\n";

    @Test
    void drawsTerrainStartGoalAndPath() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.contains("origin: 0,64,0"), rendered);
        assertTrue(rendered.contains("--- y=65"), rendered);
        assertTrue(rendered.contains("S**"), "the path between start and goal is marked\n" + rendered);
        assertTrue(rendered.contains("G"), rendered);
    }

    @Test
    void marksExpandedNodesThatAreNotOnThePath() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + ".....\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n");
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.indexOf(FixtureWorld.EXPANDED) >= 0,
            "detouring around the wall expands nodes that the final path does not use\n"
                + rendered);
    }

    @Test
    void terrainSurvivesARenderParseRoundTrip() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        FixtureWorld reparsed = FixtureWorld.parse(PathRenderer.render(world, result));

        for (int y = world.minY(); y < world.maxY(); y++) {
            for (int x = world.minX(); x <= world.maxX(); x++) {
                for (int z = world.minZ(); z <= world.maxZ(); z++) {
                    assertEquals(world.at(x, y, z), reparsed.at(x, y, z),
                        "terrain differs at " + new Pos(x, y, z));
                }
            }
        }
    }

    @Test
    void theRoundTripPreservesTheExtent() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        FixtureWorld reparsed = FixtureWorld.parse(PathRenderer.render(world, result));

        assertEquals(world.minX(), reparsed.minX());
        assertEquals(world.maxX(), reparsed.maxX());
        assertEquals(world.minY(), reparsed.minY());
        assertEquals(world.maxY(), reparsed.maxY());
        assertEquals(world.minZ(), reparsed.minZ());
        assertEquals(world.maxZ(), reparsed.maxZ());
    }

    @Test
    void aFailedSearchStillRendersSoTheFailureCanBeRead() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "--- y=66\n"
                + ".....\n");
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.NO_PATH, result.outcome());

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.contains("NO_PATH"), "the outcome belongs in the dump\n" + rendered);
        assertTrue(rendered.contains("#"), rendered);
        assertTrue(rendered.indexOf(FixtureWorld.START) >= 0,
            "a failed render must still say where the search began\n" + rendered);
        assertTrue(rendered.indexOf(FixtureWorld.GOAL) >= 0,
            "and where it was trying to get to — otherwise the dump cannot be pasted back in\n"
                + rendered);
    }

    @Test
    void aFailedSearchRoundTripsWithItsStartAndGoalIntact() {
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
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.NO_PATH, result.outcome());

        // The whole point of the renderer: a failure pastes straight back in and reproduces the
        // same question. That is only true if the query survives, not just the terrain.
        FixtureWorld reparsed = FixtureWorld.parse(PathRenderer.render(world, result));

        assertEquals(world.start(), reparsed.start());
        assertEquals(world.goal(), reparsed.goal());
    }
}
