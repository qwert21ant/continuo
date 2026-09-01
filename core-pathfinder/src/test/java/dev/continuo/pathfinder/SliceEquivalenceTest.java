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

    /**
     * A cave climb, for the multi-segment equivalence: 726 expansions of real terrain, where the
     * A* equivalence above uses {@code d-cliff}'s 273. Both are committed fixtures already pinned
     * by C4's own tests, so neither can quietly become unwalkable.
     */
    private static FixtureWorld cave() {
        return TerrainFixture.load("b-cave-climb.txt");
    }

    /**
     * The budget C4 already proved segments {@code d-cliff}: 232 is 84% of its 273 expansions, and
     * {@code SegmentedSearchTest} pins the two-segment result it produces at cost
     * 274.41707435261833. Reusing that number rather than inventing one means this test inherits a
     * configuration known to segment instead of hoping one does.
     */
    private static final int SEGMENTING_BUDGET = 232;

    private static SegmentedResult unslicedRun(FixtureWorld world, int budget) {
        return new SegmentedSearch(new AStarPathfinder(budget)).run(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
    }

    private static SegmentedResult slicedRun(FixtureWorld world, int budget, int sliceSize) {
        Run run = new SegmentedSearch(new AStarPathfinder(budget)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        int guard = 0;
        while (!run.advance(sliceSize)) {
            guard++;
            if (guard > 1000000) {
                throw new AssertionError("run never finished at slice size " + sliceSize);
            }
        }
        return run.result();
    }

    @Test
    void everySliceSizeProducesTheIdenticalSegmentedRun() {
        FixtureWorld world = cave();
        SegmentedResult expected = unslicedRun(world, 25000);

        // The same vacuity guard the A* equivalence test carries, for the same reason: an
        // unwalkable fixture makes every slice size agree on NO_PATH and passes for free.
        assertEquals(PathOutcome.FOUND, expected.outcome(),
            "the fixture must be pathable, or this compares two NO_PATHs");
        assertTrue(expected.expanded().size() > 100,
            "the fixture must need enough search that a slice boundary falls inside it; got "
                + expected.expanded().size() + " expansions");

        for (int i = 0; i < SLICE_SIZES.length; i++) {
            int slice = SLICE_SIZES[i];
            SegmentedResult actual = slicedRun(world, 25000, slice);

            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0, "cost at slice " + slice);
            assertEquals(expected.path(), actual.path(), "path at slice " + slice);
            assertEquals(expected.expanded(), actual.expanded(), "expansions at slice " + slice);
            assertEquals(expected.segments(), actual.segments(), "segments at slice " + slice);
        }
    }

    @Test
    void aSliceBoundaryLandingOnASegmentBoundaryIsNotObservable() {
        // Spec §5.5. A slice boundary and a segment boundary can coincide, and when they do the
        // run's PARTIAL must be the segment's own, never an artefact of tick scheduling. The
        // budget is chosen so the first segment ends mid-run, then swept across slice sizes that
        // bracket that expansion count from both sides.
        FixtureWorld world = cliff();
        SegmentedResult expected = unslicedRun(world, SEGMENTING_BUDGET);
        assertTrue(expected.segments() > 1,
            "d-cliff at budget " + SEGMENTING_BUDGET + " must segment or this test proves nothing;"
                + " got " + expected.segments() + " segments");

        // 1..40 walk a boundary through the first segment's interior; 231, 232 and 233 bracket the
        // exact expansion where the first segment ends, which is the coincidence this test exists
        // for; 464 swallows two whole segments in one slice.
        int[] boundarySlices = {1, 2, 3, 7, 40, 115, 231, 232, 233, 464};
        for (int b = 0; b < boundarySlices.length; b++) {
            int slice = boundarySlices[b];
            SegmentedResult actual = slicedRun(world, SEGMENTING_BUDGET, slice);
            assertEquals(expected.segments(), actual.segments(), "segments at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0, "cost at slice " + slice);
            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
        }
    }

    @Test
    void aCancelledRunReleasesItsWorldAndRefusesToContinue() {
        // The level-pinning hazard, asserted by reference rather than by behaviour. A run holds
        // the world it reads; under slicing that run lives for hundreds of milliseconds, so a
        // level change during one must not leave the old level reachable.
        FixtureWorld world = cave();
        final Run run = new SegmentedSearch(new AStarPathfinder(25000)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        run.advance(5);

        run.cancel();

        assertTrue(run.cancelled());
        assertTrue(run.finished(), "a cancelled run is over, not merely paused");
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                run.result();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                run.advance(5);
            }
        });

        // The gap no test above closes: every assertion so far can pass with cancel() leaving
        // Run.world pointing at the old level, because nothing routes back through it once
        // cancelled is set. Reached by reflection since the field is private and Run exposes no
        // accessor for it -- the point is that nothing outside this test should ever need one.
        try {
            java.lang.reflect.Field worldField = Run.class.getDeclaredField("world");
            worldField.setAccessible(true);
            assertEquals(null, worldField.get(run),
                "cancel() must null out the world reference, or a cancelled run keeps the old"
                    + " level reachable through it");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not reach Run.world by reflection", e);
        }
    }

    @Test
    void aSliceNeverExpandsMoreNodesThanItWasGiven() {
        // The per-slice node bound, which is the guarantee the whole sub-project exists to
        // provide -- spec §5.4 sizes SLICE_NODES so that a slice costs about 7 ms, and that
        // reasoning is void if a slice can overrun. Slice equivalence does not cover this: a Run
        // that overspends still visits the same segments in the same order and returns the
        // identical result, so every other test here passes while the bound is broken.
        //
        // The budget is SEGMENTING_BUDGET so that segments genuinely COMPLETE inside a slice,
        // which is the only case that can overrun -- a slice that merely suspends mid-segment is
        // bounded by Search.advance itself.
        FixtureWorld world = cliff();
        Run run = new SegmentedSearch(new AStarPathfinder(SEGMENTING_BUDGET)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        // 58, not 50: d-cliff's first segment at this budget ends 32 nodes into whichever slice
        // it falls in (232 mod slice), and slice 50 happens to leave an 18-node correct remainder
        // that the terminal segment (7 real nodes) never overruns either way -- verified by
        // instrumented sweep of every slice from 2 to 232, not asserted from reasoning about the
        // search's behaviour. 58 leaves a remainder the terminal segment's real length exceeds, so
        // the two accountings diverge and only the buggy one lets the slice run long.
        int slice = 58;
        int before = 0;
        int guard = 0;
        boolean done = false;
        while (!done) {
            done = run.advance(slice);
            int now = run.expandedCount();
            assertTrue(now - before <= slice,
                "a slice of " + slice + " expanded " + (now - before) + " nodes");
            before = now;
            guard++;
            if (guard > 100000) {
                throw new AssertionError("run never finished");
            }
        }
        assertTrue(run.expandedCount() > slice,
            "the run must take several slices or this test proves nothing; got "
                + run.expandedCount() + " expansions in total");
    }

    @Test
    void cancellingTwiceIsHarmless() {
        // The adapter calls onLevel every tick and compares by identity; a defensive second cancel
        // is the normal path on the tick after a transition, exactly as ContinuoCore.stop() is.
        FixtureWorld world = cave();
        Run run = new SegmentedSearch(new AStarPathfinder(25000)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        run.cancel();
        run.cancel();

        assertTrue(run.cancelled());
    }
}
