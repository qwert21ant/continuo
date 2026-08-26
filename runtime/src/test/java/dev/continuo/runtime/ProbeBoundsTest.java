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
    void aWorldWhoseYLimitsAreInvertedStillYieldsABoxWithMaxAboveMin() {
        // The degenerate-box guard was recorded as "untested and provably unreachable". It is
        // testable and it is reachable: lowY comes from world.minY() and highY from
        // world.maxY() - 1, and nothing in ProbeBounds validates that a BlockSource reports those
        // the right way round. A platform returning them inverted - or a fixture written wrong -
        // produces maxY < minY without the guard, and the renderer's `for (y = minY; y <= maxY)`
        // then draws nothing while PathProbe's drawnCells() reports a negative cell count. An
        // empty map that says it covers -3 cells is the worst of both: it looks like a search
        // that found nothing rather than a world description that is impossible.
        ProbeBounds bounds = ProbeBounds.around(world(100, 50),
            new Pos(0, 64, 0), new Pos(5, 64, 0), Collections.<Pos>emptyList());

        assertTrue(bounds.maxY >= bounds.minY,
            "a box must never be inverted on Y; got minY=" + bounds.minY
                + " maxY=" + bounds.maxY);
        assertEquals(bounds.minY, bounds.maxY,
            "and the guard collapses it to a single layer rather than inventing extra ones");
    }

    @Test
    void containsSaysWhetherAPositionIsInsideTheDrawnWindow() {
        ProbeBounds bounds = ProbeBounds.around(world(-64, 320),
            new Pos(0, 64, 0), new Pos(5, 64, 0), Collections.<Pos>emptyList());

        assertTrue(bounds.contains(new Pos(0, 64, 0)), "the start is always drawn");
        assertTrue(bounds.contains(new Pos(5, 64, 0)), "and so is a nearby goal");
        assertFalse(bounds.contains(new Pos(400, 64, 0)), "a distant position is not");
        assertFalse(bounds.contains(new Pos(0, 200, 0)),
            "and neither is one that leaves the window on Y alone");
    }

    @Test
    void aClampedWindowCanStillContainTheGoal() {
        // Clamping is driven by the whole box - start, goal AND path - so a route that wanders
        // 200 blocks sideways to reach a goal five blocks away clamps the X axis while leaving
        // the goal inside the window. Without this case the clamp notice could say "the goal is
        // outside" unconditionally and still pass every other test in this file.
        List<Pos> detour = new ArrayList<Pos>();
        detour.add(new Pos(200, 64, 0));

        ProbeBounds bounds = ProbeBounds.around(world(-64, 320),
            new Pos(0, 64, 0), new Pos(5, 64, 0), detour);

        assertTrue(bounds.clamped, "a 200-block detour must clamp the X axis");
        assertTrue(bounds.contains(new Pos(5, 64, 0)),
            "but the goal is five blocks from the start and stays inside the window");
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
    void aClampedAxisIsAnchoredOnTheStartAndExtendsTowardTheGoal() {
        // Centring the window on the span's midpoint put it in the empty space between a distant
        // start and goal, so the map came back blank - no start, no goal, no route. Anchoring on
        // the start keeps the beginning of the search drawn, whichever way the goal lies.
        ProbeBounds ahead = ProbeBounds.around(world(0, 256),
            new Pos(0, 64, 0), new Pos(500, 64, 0), Collections.<Pos>emptyList());

        assertEquals(-ProbeBounds.PADDING, ahead.minX, "the start sits at the near edge");
        assertEquals(-ProbeBounds.PADDING + ProbeBounds.MAX_EXTENT - 1, ahead.maxX);

        ProbeBounds behind = ProbeBounds.around(world(0, 256),
            new Pos(0, 64, 0), new Pos(-500, 64, 0), Collections.<Pos>emptyList());

        assertEquals(ProbeBounds.PADDING, behind.maxX, "and at the far edge going the other way");
        assertEquals(ProbeBounds.PADDING - ProbeBounds.MAX_EXTENT + 1, behind.minX);
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
