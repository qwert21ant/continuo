package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7: a sliced search returns bit-identical results to an unsliced one, at every slice size.
 *
 * <p>This is the whole proof obligation for slicing, and it is cheap enough to test exhaustively
 * rather than at a sample. Slice size 1 is the interesting one: a boundary between every pair of
 * expansions is the strongest available test that suspension leaves no state in a local.
 */
class SliceEquivalenceTest {

    private static final int[] SLICE_SIZES = {1, 2, 3, 7, 64, 1000, Integer.MAX_VALUE};

    /**
     * Committed real terrain rather than a hand-written fixture, and that is a correction rather
     * than a preference.
     *
     * <p>A hand-written world whose topmost slice is the one the player walks on gives every square
     * zero head clearance: a position outside a {@code FixtureWorld}'s declared extent reads as
     * {@code UNKNOWN}, never air, and {@code Standability.standable} requires the block above the
     * feet to be passable. The search then returns {@code NO_PATH} over what looks like open
     * ground — and a slice-equivalence test built on it passes vacuously, every slice size agreeing
     * on {@code NO_PATH} after one expansion. {@code SnapshotSearchTest}'s own fixture carries the
     * same warning. {@code d-cliff} needs 273 expansions and is pinned by {@code BackoffTest} and
     * {@code SegmentedSearchTest} already, so it cannot quietly become unwalkable.
     */
    private static FixtureWorld cliff() {
        return TerrainFixture.load("d-cliff.txt");
    }

    private static Goal goalOf(FixtureWorld world) {
        return new GoalBlock(world.goal().x(), world.goal().y(), world.goal().z());
    }

    private static PathResult unsliced(FixtureWorld world) {
        return new AStarPathfinder(25000).findPath(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
    }

    private static PathResult sliced(FixtureWorld world, int sliceSize) {
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        int guard = 0;
        while (!search.advance(sliceSize)) {
            guard++;
            if (guard > 1000000) {
                throw new AssertionError("advance never finished at slice size " + sliceSize);
            }
        }
        return search.result();
    }

    @Test
    void everySliceSizeProducesTheIdenticalSearch() {
        FixtureWorld world = cliff();
        PathResult expected = unsliced(world);

        // The guard that stops this test passing vacuously, and it is not hypothetical: the first
        // fixture written for it was unwalkable, so both sides returned NO_PATH after one
        // expansion and every assertion below held while proving nothing about slicing.
        assertEquals(PathOutcome.FOUND, expected.outcome(),
            "the fixture must be pathable, or this compares two NO_PATHs and passes for free");
        assertTrue(expected.nodesExpanded() > 100,
            "the fixture must need enough search that a slice boundary falls inside it; got "
                + expected.nodesExpanded() + " expansions");

        for (int i = 0; i < SLICE_SIZES.length; i++) {
            int slice = SLICE_SIZES[i];
            PathResult actual = sliced(world, slice);

            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0,
                "cost must be bit-identical at slice " + slice);
            assertEquals(expected.path(), actual.path(), "path at slice " + slice);
            // The expansion sequence, not merely its length. A slice boundary that reordered the
            // open set would keep the count and change the order, and the path could still come
            // back the same on a world with one optimal route.
            assertEquals(expected.expanded(), actual.expanded(),
                "expansion order at slice " + slice);
        }
    }

    @Test
    void aSearchThatHasNotFinishedRefusesToGiveAResult() {
        FixtureWorld world = cliff();
        final Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertFalse(search.finished(), "a search that has not advanced cannot be finished");
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                search.result();
            }
        });
    }

    @Test
    void suspendingDoesNotProduceAnOutcome() {
        // D6, the design's sharpest failure mode. If a slice boundary produced PARTIAL, every
        // search longer than one slice would trigger C4's backoff and return a worse route for a
        // reason with nothing to do with the budget C4 calibrated.
        FixtureWorld world = cliff();
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertFalse(search.advance(1), "one expansion cannot finish this search");
        assertFalse(search.finished());
        assertEquals(1, search.expandedCount(), "one slice of one node expands exactly one node");
    }

    @Test
    void advancingAFinishedSearchIsANoOp() {
        FixtureWorld world = cliff();
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        while (!search.advance(1000)) {
            // drive to completion
        }
        PathResult first = search.result();
        int expandedAfterFinish = search.expandedCount();

        assertTrue(search.advance(1000), "a finished search stays finished");
        assertEquals(expandedAfterFinish, search.expandedCount(),
            "advancing past the end must not expand another node");
        assertEquals(first.cost(), search.result().cost(), 0.0);
        List<Pos> path = search.result().path();
        assertEquals(first.path(), path, "the result must not change after it is settled");
    }

    @Test
    void aNonPositiveSliceIsRejectedRatherThanLoopingForever() {
        FixtureWorld world = cliff();
        final Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                search.advance(0);
            }
        });
    }
}
