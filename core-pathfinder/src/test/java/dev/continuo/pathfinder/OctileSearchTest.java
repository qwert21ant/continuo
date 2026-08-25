package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression that would have caught C1a's defect.
 *
 * <p>A* returned correct paths throughout; what it did wrong was explore far too much of the world
 * to find them, because the heuristic credited a diagonal step at the cardinal rate. No test
 * asserted anything about how much was explored, so the defect survived C1's review, C2's, and a
 * whole-branch review, and only surfaced when the region a search touches had to be measured for
 * C3.
 */
class OctileSearchTest {

    /** Unbounded flat ground: stone at {@link #FLOOR_Y}, air everywhere above. */
    private static final class FlatWorld implements BlockSource {
        static final int FLOOR_Y = 63;

        @Override
        public BlockData at(int x, int y, int z) {
            return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    private static PathResult diagonalRun(int distance, int budget) {
        return new AStarPathfinder(budget).findPath(
            new FlatWorld(), 0, FlatWorld.FLOOR_Y + 1, 0,
            new GoalBlock(distance, FlatWorld.FLOOR_Y + 1, distance),
            CapabilitySet.none());
    }

    @Test
    void aDiagonalRunOnOpenGroundDoesNotFanOut() {
        // Before C1a this expanded 4,506 nodes for a 91-step path. The bound is deliberately
        // generous -- the exact figure is not the contract and an unrelated tie-break change
        // would move it -- but 10x the path length still fails by two orders of magnitude
        // against the old behaviour.
        PathResult result = diagonalRun(90, 10000);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(91, result.path().size());
        assertTrue(result.nodesExpanded() <= 10 * result.path().size(),
            "a diagonal run across open flat ground must not explore the plane around it;"
                + " expanded " + result.nodesExpanded() + " nodes for a "
                + result.path().size() + "-step path");
    }

    @Test
    void aLongDiagonalRunCompletesInsideTheProbesBudget() {
        // Before C1a this returned BUDGET_EXCEEDED on empty flat ground, which is the symptom
        // an owner would meet in game the first time they marked a goal 180 blocks out on a
        // diagonal. No terrain is involved; the search simply gave up.
        PathResult result = diagonalRun(180, 10000);

        assertEquals(PathOutcome.FOUND, result.outcome(),
            "a straight diagonal over empty ground is the easiest long path there is");
        assertEquals(181, result.path().size());
    }

    @Test
    void theDiagonalMovementDeclaresTheSameRateAsTheStraightOne() {
        // Spec 5.1. DIAGONAL is defined as TRAVERSE * sqrt(2), so once the unit is octile the two
        // must declare an identical per-unit figure. This is the test that fails if someone later
        // "corrects" DiagonalMove back to declaring the whole diagonal cost, and it is the
        // cheapest possible statement of what the unit means.
        double straight = Double.NaN;
        double diagonal = Double.NaN;
        java.util.List<dev.continuo.movement.IMovementType> ms =
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).movements();
        for (int i = 0; i < ms.size(); i++) {
            if ("walk.traverse".equals(ms.get(i).id())) {
                straight = ms.get(i).minCostPerHorizontalUnit();
            }
            if ("walk.diagonal".equals(ms.get(i).id())) {
                diagonal = ms.get(i).minCostPerHorizontalUnit();
            }
        }
        assertEquals(straight, diagonal, 1.0e-9,
            "a diagonal step is sqrt(2) units costing sqrt(2) times as much, so its per-unit rate"
                + " is the same as a cardinal step's; if these differ the unit is wrong");
    }

    @Test
    void anAxisAlignedRunIsUnaffected() {
        // Octile and Chebyshev agree on an axis-aligned gap, so this pins that the change is
        // confined to diagonals rather than being a general retune.
        PathResult result = new AStarPathfinder(10000).findPath(
            new FlatWorld(), 0, FlatWorld.FLOOR_Y + 1, 0,
            new GoalBlock(256, FlatWorld.FLOOR_Y + 1, 0),
            CapabilitySet.none());

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(257, result.path().size());
        assertEquals(257, result.nodesExpanded(),
            "an axis-aligned run was already optimal and must stay exactly so");
    }
}
