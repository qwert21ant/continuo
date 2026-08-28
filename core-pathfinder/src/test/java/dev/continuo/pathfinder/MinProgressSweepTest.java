package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence behind {@link AStarPathfinder#DEFAULT_MIN_PROGRESS_BLOCKS}.
 *
 * <p>Prints quality = cost(segmented run) / cost(single unbounded search) for each fixture at each
 * candidate margin. The assertions are deliberately weak: this exists to produce a table a person
 * reads, and pinning a tuned figure here would turn a measurement into a regression test for
 * itself.
 */
class MinProgressSweepTest {

    private static final String[] FIXTURES = {
        "d-cliff.txt", "b-cave-climb.txt", "a-big-obstacle.txt"};
    private static final double[] MARGINS = {1.0, 2.0, 4.0, 8.0, 16.0};

    @Test
    void sweep() {
        for (int f = 0; f < FIXTURES.length; f++) {
            FixtureWorld world = TerrainFixture.load(FIXTURES[f]);
            Pos s = world.start();
            Pos g = world.goal();
            GoalBlock goal = new GoalBlock(g.x(), g.y(), g.z());

            PathResult optimal = new AStarPathfinder(200000)
                .findPath(world, s.x(), s.y(), s.z(), goal);
            int need = optimal.nodesExpanded();
            int budget = need * 84 / 100;

            System.out.println(FIXTURES[f] + ": needs " + need + " expansions, optimal cost "
                + optimal.cost() + ", sweeping at budget " + budget + " (84% of need)");

            for (int m = 0; m < MARGINS.length; m++) {
                SegmentedResult r = new SegmentedSearch(
                    new AStarPathfinder(budget, AStarPathfinder.defaultRegistry(), MARGINS[m]))
                    .run(world, s.x(), s.y(), s.z(), goal, CapabilitySet.none());
                String quality = r.outcome() == PathOutcome.FOUND
                    ? String.format(java.util.Locale.ROOT, "%.3f",
                        Double.valueOf(r.cost() / optimal.cost()))
                    : "-";
                System.out.println("  minProgress " + MARGINS[m] + " blocks: " + r.outcome()
                    + ", " + r.segments() + " segments, cost " + r.cost()
                    + ", quality " + quality);
                assertTrue(r.segments() >= 1);
            }
        }
    }
}
