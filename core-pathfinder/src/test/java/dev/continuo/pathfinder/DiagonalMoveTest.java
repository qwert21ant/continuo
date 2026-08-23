package dev.continuo.pathfinder;

import dev.continuo.movement.MovementCosts;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagonalMoveTest {

    private final Move move = new DiagonalMove();

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

    @Test
    void offersAllFourDiagonalsOnAnOpenFloor() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(4, sink.size());
        assertTrue(sink.positions().containsAll(Arrays.asList(
            new Pos(2, 65, 0), new Pos(2, 65, 2), new Pos(0, 65, 2), new Pos(0, 65, 0))));
    }

    @Test
    void offersDiagonalsInAFixedOrder() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(Arrays.asList(
            new Pos(2, 65, 0), new Pos(2, 65, 2), new Pos(0, 65, 2), new Pos(0, 65, 0)),
            sink.positions(), "north-east, south-east, south-west, north-west");
    }

    @Test
    void aDiagonalCostsMoreThanAStraightStep() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(MovementCosts.DIAGONAL, sink.costOf(new Pos(2, 65, 0)), 1.0e-9);
        assertTrue(MovementCosts.DIAGONAL > MovementCosts.TRAVERSE);
    }

    @Test
    void aCornerCannotBeCutThroughOneBlockedSide() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + ".#.\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the destination is standable but the north side of the corner is solid");
    }

    @Test
    void aCornerCannotBeCutThroughTheOtherBlockedSide() {
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

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the destination is standable but the east side of the corner is solid");
    }

    @Test
    void aCornerBlockedAtHeadHeightAloneStillBlocks() {
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
                + "..#\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the player is two blocks tall; a corner blocked at head height blocks the squeeze");
    }

    @Test
    void aDiagonalOntoNothingIsNotOffered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##.\n"
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

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)));
    }
}
