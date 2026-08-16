package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescendMoveTest {

    private final Move move = new DescendMove();

    /**
     * A shaft of the given depth to the east of a player standing at (0, 100, 0).
     * The floor of the shaft is {@code depth} blocks below the player's feet.
     */
    private static FixtureWorld shaft(int depth) {
        StringBuilder art = new StringBuilder();
        int floorY = 100 - depth - 1;
        art.append("origin: 0,").append(floorY).append(",0\n");
        art.append("--- y=").append(floorY).append("\n##\n");
        for (int y = floorY + 1; y < 100; y++) {
            art.append("--- y=").append(y).append("\n#.\n");
        }
        art.append("--- y=100\n..\n");
        art.append("--- y=101\n..\n");
        return FixtureWorld.parse(art.toString());
    }

    @Test
    void steppingDownOneIsOffered() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(1), 0, 100, 0, sink);

        assertEquals(1, sink.size());
        assertEquals(new Pos(1, 99, 0), sink.positions().get(0));
    }

    @Test
    void aDropCostsATraversePlusTheFallTimeForItsDepth() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(3), 0, 100, 0, sink);

        assertEquals(MovementCosts.TRAVERSE + MovementCosts.fallTicks(3),
            sink.costOf(new Pos(1, 97, 0)), 1.0e-9);
    }

    @Test
    void deeperDropsCostStrictlyMoreThanShallowerOnes() {
        RecordingSink shallow = new RecordingSink();
        move.expand(shaft(1), 0, 100, 0, shallow);

        RecordingSink deep = new RecordingSink();
        move.expand(shaft(3), 0, 100, 0, deep);

        assertTrue(deep.costOf(new Pos(1, 97, 0)) > shallow.costOf(new Pos(1, 99, 0)),
            "falling further takes longer; a per-depth table must preserve that ordering");
    }

    @Test
    void onlyTheFirstFloorBelowIsOffered() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(2), 0, 100, 0, sink);

        assertEquals(1, sink.size(),
            "the search descends to the floor it lands on, not to every level above it");
    }

    @Test
    void aDropDeeperThanTheSafeLimitIsRefused() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(MovementCosts.MAX_SAFE_FALL + 1), 0, 100, 0, sink);

        assertEquals(0, sink.size(), "falling further than the safe limit takes damage");
    }

    @Test
    void aDropOfExactlyTheSafeLimitIsAccepted() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(MovementCosts.MAX_SAFE_FALL), 0, 100, 0, sink);

        assertEquals(1, sink.size(), "the limit itself is safe; this pins the off-by-one");
    }

    @Test
    void aWallBesideTheLedgeBlocksTheStepOff() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,98,0\n"
                + "--- y=98\n"
                + "##\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + ".#\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(), "you cannot walk off a ledge through a wall");
    }

    @Test
    void aWallAtBodyHeightBlocksAShaftBehindIt() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,96,0\n"
                + "--- y=96\n"
                + "##\n"
                + "--- y=97\n"
                + "#.\n"
                + "--- y=98\n"
                + "#.\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + ".#\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(),
            "there is a reachable floor three below, but a wall stands where the step would go");
    }

    @Test
    void anObstructionPartWayDownStopsTheScanRatherThanFallingPastIt() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,96,0\n"
                + "--- y=96\n"
                + "##\n"
                + "--- y=97\n"
                + "#.\n"
                + "--- y=98\n"
                + "#.\n"
                + "--- y=99\n"
                + "#_\n"
                + "--- y=100\n"
                + "..\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(),
            "the slab is neither a floor nor passable; the fall stops there, it does not continue"
                + " to the standable ledge two blocks further down");
    }

    @Test
    void unknownTerrainInTheShaftIsNotDescendedInto() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,97,0\n"
                + "--- y=97\n"
                + "##\n"
                + "--- y=98\n"
                + "#?\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + "..\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(),
            "an unreadable block in the shaft might be solid, or might be a ledge");
    }

    @Test
    void lavaAtTheBottomIsNotALandingSite() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,98,0\n"
                + "--- y=98\n"
                + "#!\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + "..\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertTrue(!sink.positions().contains(new Pos(1, 99, 0)));
    }
}
