package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sweeps {@code Search} and {@code Run} slicing across 5 terrain fixtures, 18 budgets and 17 slice
 * sizes, asserting on every one of the resulting 1,530 configurations that a sliced run: (1) never
 * expands more than {@code slice} nodes in a single {@code advance()} call, and (2) reaches the
 * same outcome, cost, path, expansion count and (for {@code Run}) segment count as an unsliced
 * search over the same budget.
 *
 * <p>This closes two gaps {@link SliceEquivalenceTest} leaves open. First, that suite's fixtures
 * and budgets never drive {@code Search} into its own {@code PARTIAL} or {@code BUDGET_EXCEEDED}
 * exits — both are asserted reached here via the {@code partialSeen}/{@code budgetExceededSeen}
 * guards, so a slicing bug that only shows up on a truncated search has somewhere to be caught.
 * Second, that suite pins the per-slice node bound at a single hand-picked slice size (58) whose
 * discrimination margin — how far a broken bound would have to drift before 58 stopped catching it
 * — is 7 nodes; sweeping 17 slice sizes per budget instead of one hand-picked value removes the
 * dependence on that margin holding up as the code around it changes.
 */
class SliceBoundSweepTest {

    private static final String[] FIXTURES = {
        "a-big-obstacle.txt", "b-cave-climb.txt", "c-short-hop.txt",
        "d-cliff.txt", "e-long-range.txt"};

    private static final int[] BUDGETS = {
        1, 2, 3, 5, 9, 17, 40, 94, 100, 232, 273, 500, 726, 2082, 3474, 4445, 17423, 25000};

    private static final int[] SLICES = {
        1, 2, 3, 5, 7, 13, 40, 63, 64, 65, 115, 231, 232, 233, 464, 1000, Integer.MAX_VALUE};

    private static Goal goalOf(FixtureWorld w) {
        return new GoalBlock(w.goal().x(), w.goal().y(), w.goal().z());
    }

    @Test
    void searchSlicedEqualsUnslicedAtEveryBudgetAndSliceSizeIncludingPartialExits() {
        int partialSeen = 0;
        int budgetExceededSeen = 0;
        int foundSeen = 0;
        int noPathSeen = 0;
        for (int f = 0; f < FIXTURES.length; f++) {
            FixtureWorld world = TerrainFixture.load(FIXTURES[f]);
            if (world.goal() == null || world.start() == null) {
                System.out.println("skipping " + FIXTURES[f]);
                continue;
            }
            for (int b = 0; b < BUDGETS.length; b++) {
                int budget = BUDGETS[b];
                // One pathfinder per (fixture, budget) pair, reused for the expected run and every
                // slice size below it. AStarPathfinder's constructor runs MovementRegistry.discover(),
                // an uncached ServiceLoader scan; building a fresh one per slice size re-ran that scan
                // 17 times per budget for no reason the assertions below depend on.
                AStarPathfinder pathfinder = new AStarPathfinder(budget);
                PathResult expected = pathfinder.findPath(world,
                    world.start().x(), world.start().y(), world.start().z(),
                    goalOf(world), CapabilitySet.none());
                if (expected.outcome() == PathOutcome.PARTIAL) {
                    partialSeen++;
                } else if (expected.outcome() == PathOutcome.BUDGET_EXCEEDED) {
                    budgetExceededSeen++;
                } else if (expected.outcome() == PathOutcome.FOUND) {
                    foundSeen++;
                } else {
                    noPathSeen++;
                }
                for (int s = 0; s < SLICES.length; s++) {
                    int slice = SLICES[s];
                    Search search = pathfinder.begin(world,
                        world.start().x(), world.start().y(), world.start().z(),
                        goalOf(world), CapabilitySet.none());
                    int before = 0;
                    int guard = 0;
                    while (!search.advance(slice)) {
                        int now = search.expandedCount();
                        assertTrue(now - before <= slice, "SEARCH BOUND BROKEN " + FIXTURES[f]
                            + " budget " + budget + " slice " + slice + ": expanded "
                            + (now - before));
                        before = now;
                        if (++guard > 200000) {
                            fail("no termination " + FIXTURES[f] + " b" + budget + " s" + slice);
                        }
                    }
                    int now = search.expandedCount();
                    assertTrue(now - before <= slice, "SEARCH BOUND BROKEN (final) " + FIXTURES[f]
                        + " budget " + budget + " slice " + slice + ": expanded " + (now - before));
                    PathResult actual = search.result();
                    String where = FIXTURES[f] + " budget " + budget + " slice " + slice;
                    assertEquals(expected.outcome(), actual.outcome(), "outcome " + where);
                    assertEquals(expected.cost(), actual.cost(), 0.0, "cost " + where);
                    assertEquals(expected.path(), actual.path(), "path " + where);
                    assertEquals(expected.expanded(), actual.expanded(), "expansions " + where);
                }
            }
        }
        System.out.println("SEARCH SWEEP outcomes: FOUND=" + foundSeen + " PARTIAL=" + partialSeen
            + " BUDGET_EXCEEDED=" + budgetExceededSeen + " NO_PATH=" + noPathSeen);
        assertTrue(partialSeen > 0, "the sweep must cross Search's PARTIAL exit");
        assertTrue(budgetExceededSeen > 0, "the sweep must cross Search's BUDGET_EXCEEDED exit");
    }

    @Test
    void runSlicedEqualsUnslicedAtEveryBudgetAndSliceSizeAndNeverOverrunsASlice() {
        int multiSegment = 0;
        for (int f = 0; f < FIXTURES.length; f++) {
            FixtureWorld world = TerrainFixture.load(FIXTURES[f]);
            if (world.goal() == null || world.start() == null) {
                continue;
            }
            for (int b = 0; b < BUDGETS.length; b++) {
                int budget = BUDGETS[b];
                // One SegmentedSearch (and the AStarPathfinder inside it) per (fixture, budget) pair,
                // reused for the expected run and every slice size below it — see the note in the
                // method above.
                SegmentedSearch search = new SegmentedSearch(new AStarPathfinder(budget));
                SegmentedResult expected = search.run(
                    world, world.start().x(), world.start().y(), world.start().z(),
                    goalOf(world), CapabilitySet.none());
                if (expected.segments() > 1) {
                    multiSegment++;
                }
                for (int s = 0; s < SLICES.length; s++) {
                    int slice = SLICES[s];
                    Run run = search.begin(world,
                        world.start().x(), world.start().y(), world.start().z(),
                        goalOf(world), CapabilitySet.none());
                    int before = 0;
                    int guard = 0;
                    boolean done = false;
                    while (!done) {
                        done = run.advance(slice);
                        int now = run.expandedCount();
                        assertTrue(now - before <= slice, "RUN BOUND BROKEN " + FIXTURES[f]
                            + " budget " + budget + " slice " + slice + ": one advance expanded "
                            + (now - before));
                        before = now;
                        if (++guard > 200000) {
                            fail("no termination " + FIXTURES[f] + " b" + budget + " s" + slice);
                        }
                    }
                    SegmentedResult actual = run.result();
                    String where = FIXTURES[f] + " budget " + budget + " slice " + slice;
                    assertEquals(expected.outcome(), actual.outcome(), "outcome " + where);
                    assertEquals(expected.cost(), actual.cost(), 0.0, "cost " + where);
                    assertEquals(expected.path(), actual.path(), "path " + where);
                    assertEquals(expected.expanded(), actual.expanded(), "expansions " + where);
                    assertEquals(expected.segments(), actual.segments(), "segments " + where);
                }
            }
        }
        System.out.println("RUN SWEEP multi-segment configurations: " + multiSegment);
        assertTrue(multiSegment > 0);
    }
}
