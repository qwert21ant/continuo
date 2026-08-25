package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.core.SealedSnapshot;
import dev.continuo.core.WorldSnapshot;
import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot must be invisible to the search. If searching through one returns a different route
 * from searching live, the snapshot is not a copy of the world — and because A* is deterministic
 * by construction, "different" here means any difference at all, not a worse one.
 */
class SnapshotSearchTest {

    /**
     * Flat ground with two walls the route has to thread, so the path is not a straight line.
     *
     * <p><b>The y=65 and y=66 slices are load-bearing, not padding.</b> A position outside a
     * {@code FixtureWorld}'s declared extent reads as {@code UNKNOWN}, which is impassable, so a
     * world that stops at the floor the player stands on gives every square zero head clearance
     * and the search returns {@code NO_PATH} over open ground.
     */
    private static final String WORLD =
        "origin: 0,63,0\n"
            + "--- y=63\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "--- y=64\n"
            + "S.........\n"
            + "####.#####\n"
            + "..........\n"
            + "#####.####\n"
            + ".........G\n"
            + "--- y=65\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "--- y=66\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n";

    @Test
    void searchingThroughASnapshotReturnsTheSameRouteAsSearchingLive() {
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();
        GoalBlock target = new GoalBlock(goal.x(), goal.y(), goal.z());

        PathResult live = new AStarPathfinder(10000).findPath(
            world, start.x(), start.y(), start.z(), target, CapabilitySet.none());

        WorldSnapshot snapshot = new WorldSnapshot(world);
        PathResult through = new AStarPathfinder(10000).findPath(
            snapshot, start.x(), start.y(), start.z(), target, CapabilitySet.none());

        assertEquals(PathOutcome.FOUND, live.outcome(), "the fixture must have a route at all");
        assertEquals(live.outcome(), through.outcome());
        assertEquals(live.cost(), through.cost(), 0.0, "cost must be bit-identical, not close");
        assertEquals(live.nodesExpanded(), through.nodesExpanded());
        assertEquals(live.path(), through.path(), "A* is deterministic; any difference is a bug");
    }

    @Test
    void theSnapshotServedManyMoreReadsThanItStoredPositions() {
        // C3's cost claim, asserted rather than asserted-about. Measured on real terrain the
        // repeat factor runs 4x to 16x; this fixture is tiny, so the bound is deliberately weak
        // and only has to show the effect exists.
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();

        WorldSnapshot snapshot = new WorldSnapshot(world);
        new AStarPathfinder(10000).findPath(snapshot, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.none());
        SealedSnapshot sealed = snapshot.seal();

        assertTrue(sealed.size() > 0, "the search must have read something");
        assertTrue(sealed.reads() > sealed.size(),
            "a search re-reads positions, which is the whole reason this class exists;"
                + " served " + sealed.reads() + " reads over " + sealed.size() + " positions");
    }

    @Test
    void aSealedSnapshotOfASearchCoversTheRouteItFound() {
        // What M5 gets: the region a search of this shape needs is exactly what the sealed
        // snapshot holds. Every position on the returned route reads back without going anywhere.
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();

        WorldSnapshot snapshot = new WorldSnapshot(world);
        PathResult result = new AStarPathfinder(10000).findPath(
            snapshot, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.none());
        SealedSnapshot sealed = snapshot.seal();

        for (int i = 0; i < result.path().size(); i++) {
            Pos step = result.path().get(i);
            assertTrue(sealed.covers(step.x(), step.y(), step.z()),
                "the route ran through " + step + " but the snapshot has no answer there");
            assertEquals(world.at(step.x(), step.y(), step.z()),
                sealed.at(step.x(), step.y(), step.z()), "at " + step);
        }
    }

    /** Guards the fixture itself: a world whose start or goal failed to parse proves nothing. */
    @Test
    void theFixtureHasBothMarkers() {
        FixtureWorld world = FixtureWorld.parse(WORLD);

        assertEquals(new Pos(0, 64, 0), world.start());
        assertEquals(new Pos(9, 64, 4), world.goal());
    }

    /**
     * A snapshot must be usable everywhere a {@link BlockSource} is, since that is the only type
     * the search knows about. Assigning to the interface is the assertion.
     */
    @Test
    void aWorldSnapshotIsABlockSource() {
        FixtureWorld world = FixtureWorld.parse(WORLD);
        BlockSource source = new WorldSnapshot(world);

        assertEquals(world.minY(), source.minY());
        assertEquals(world.maxY(), source.maxY());
    }
}
