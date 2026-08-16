package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraverseMoveTest {

    private final Move move = new TraverseMove();

    @Test
    void offersAllFourNeighboursOnAnOpenFloor() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(4, sink.size());
        assertTrue(sink.positions().containsAll(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1))));
    }

    @Test
    void offersNeighboursInAFixedOrderSoTheSearchIsDeterministic() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1)),
            sink.positions(), "north, east, south, west");
    }

    @Test
    void everyStepCostsOneTraverse() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(MovementCosts.TRAVERSE, sink.costOf(new Pos(2, 65, 1)), 1.0e-9);
    }

    @Test
    void aWallIsNotOffered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "..#\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(3, sink.size());
        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)));
    }

    @Test
    void aHoleIsNotOfferedBecauseTraverseDoesNotFall() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "##.\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)));
    }

    @Test
    void unknownAtBodyHeightIsNeverEntered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "..?\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)),
            "an unreadable block where the body would go might be solid");
    }

    @Test
    void unknownGroundIsNeverWalkedOnto() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "##?\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)),
            "unreadable ground might not be there at all; stepping onto it is a guess");
    }

    private static FixtureWorld openFloor() {
        return FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");
    }
}
