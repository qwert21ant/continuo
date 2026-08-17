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

    /**
     * The round trip over a fixture whose only blocks are {@code #} and {@code .}.
     *
     * <p>That is a real property and worth pinning, but it is narrower than it used to be named:
     * every cell an overlay covers here holds air, so equality across the round trip says nothing
     * about what happens when an overlay covers something else. It cannot witness the limitation
     * that {@link #aPassableNonAirBlockUnderAnOverlayComesBackAsAir} pins.
     */
    @Test
    void terrainNotCoveredByAnOverlaySurvivesARenderParseRoundTrip() {
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

    /**
     * The stated limit of the round trip, pinned so it is not rediscovered.
     *
     * <p>An overlay replaces the terrain character rather than accompanying it, so the carpet the
     * path walks over comes back as air. This is a documented limitation rather than a bug — see
     * {@link PathRenderer}'s class javadoc and spec §7.2 for why dropping the overlay instead
     * would be worse — and the second half of the test is the reason it is tolerable: air routes
     * identically to carpet, so the re-parsed fixture still reproduces the same search.
     */
    @Test
    void aPassableNonAirBlockUnderAnOverlayComesBackAsAir() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.c.G\n"
                + "--- y=66\n"
                + ".....\n");
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(FixtureBlocks.CARPET, world.at(2, 65, 0), "the fixture really has carpet");

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.contains("S***G"),
            "the path walks straight over the carpet, so an overlay covers it\n" + rendered);
        // Only the terrain slices, not the trailing "// ... cost ..." summary, which has its own
        // letter c and is skipped by the parser anyway.
        String terrain = rendered.substring(0, rendered.indexOf("// "));
        assertTrue(terrain.indexOf('c') < 0,
            "and the overlay replaces the carpet's character rather than accompanying it\n"
                + rendered);

        FixtureWorld reparsed = FixtureWorld.parse(rendered);

        assertEquals(FixtureBlocks.AIR, reparsed.at(2, 65, 0),
            "so the carpet degrades to air on re-parse — the round trip preserves terrain only"
                + " where no overlay covers it, and carpet is one of the two blocks spec 4.3"
                + " makes a centrepiece");

        // Why the limitation is tolerable rather than merely admitted: only passable,
        // non-supporting blocks can sit under an overlay, and air is passable and non-supporting
        // too, so the pasted-back fixture still poses the same routing question.
        PathResult again = new AStarPathfinder().findPath(reparsed, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(result.outcome(), again.outcome());
        assertEquals(result.path(), again.path(),
            "what is lost is which passable block was there, not how the search behaves");
        assertEquals(result.cost(), again.cost(), 1.0e-9);
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
