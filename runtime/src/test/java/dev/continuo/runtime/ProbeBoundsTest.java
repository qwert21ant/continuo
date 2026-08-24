package dev.continuo.runtime;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeBoundsTest {

    /** A world with generous Y limits, so only the explicit clamps are under test here. */
    private static BlockSource world(final int minY, final int maxY) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                return BlockData.UNKNOWN;
            }

            @Override
            public int minY() {
                return minY;
            }

            @Override
            public int maxY() {
                return maxY;
            }
        };
    }

    @Test
    void theBoxCoversStartAndGoalWithPadding() {
        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(10, 64, 10), new Pos(14, 64, 12), Collections.<Pos>emptyList());

        assertEquals(10 - ProbeBounds.PADDING, bounds.minX);
        assertEquals(14 + ProbeBounds.PADDING, bounds.maxX);
        assertEquals(10 - ProbeBounds.PADDING, bounds.minZ);
        assertEquals(12 + ProbeBounds.PADDING, bounds.maxZ);
        assertFalse(bounds.clamped, "a small box is not clamped");
    }

    @Test
    void thePathIsIncludedEvenWhereItLeavesTheStartToGoalBox() {
        // A route that detours well outside the straight line between start and goal. Bounding
        // only start and goal would draw a map with the interesting half of the route missing,
        // which is exactly the case worth looking at.
        List<Pos> path = Arrays.asList(new Pos(10, 64, 10), new Pos(10, 64, 30),
            new Pos(14, 64, 12));

        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(10, 64, 10), new Pos(14, 64, 12), path);

        assertEquals(30 + ProbeBounds.PADDING, bounds.maxZ);
    }

    @Test
    void anAxisLongerThanTheMaximumExtentIsClampedAndSaysSo() {
        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(0, 64, 0), new Pos(500, 64, 0), Collections.<Pos>emptyList());

        assertEquals(ProbeBounds.MAX_EXTENT, bounds.maxX - bounds.minX + 1,
            "the X axis is reduced to the maximum extent");
        assertTrue(bounds.clamped,
            "and the caller is told, because a silently truncated map looks like a search that "
                + "stopped for no reason");
    }

    @Test
    void theBoxNeverLeavesTheWorldsOwnYLimits() {
        // maxY is one past the top, per BlockSource. The box's maxY is inclusive, so the
        // highest legal layer is maxY() - 1.
        ProbeBounds bounds = ProbeBounds.around(world(60, 70),
            new Pos(0, 61, 0), new Pos(4, 69, 0), Collections.<Pos>emptyList());

        assertTrue(bounds.minY >= 60, "minY was " + bounds.minY);
        assertTrue(bounds.maxY <= 69, "maxY was " + bounds.maxY);
    }

    @Test
    void aBoxThatOnlyFitsBecauseOfTheWorldsYLimitsIsNotReportedAsClamped() {
        // Clamping to the world's own limits is ordinary, not truncation: there is no terrain
        // beyond them to lose. Only MAX_EXTENT reduction means the reader is missing something.
        ProbeBounds bounds = ProbeBounds.around(world(60, 70),
            new Pos(0, 61, 0), new Pos(4, 69, 0), new ArrayList<Pos>());

        assertFalse(bounds.clamped);
    }
}
