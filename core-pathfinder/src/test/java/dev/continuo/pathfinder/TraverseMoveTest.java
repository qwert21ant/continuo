package dev.continuo.pathfinder;

import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraverseMoveTest {

    private final IMovementType move = new TraverseMove();

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
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

        assertEquals(4, sink.size());
        assertTrue(sink.positions().containsAll(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1))));
    }

    @Test
    void offersNeighboursInAFixedOrderSoTheSearchIsDeterministic() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

        assertEquals(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1)),
            sink.positions(), "north, east, south, west");
    }

    @Test
    void everyStepCostsOneTraverse() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

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
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

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
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

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
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

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
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)),
            "unreadable ground is not a floor - enforced here by the support band, "
                + "since UNKNOWN's collision top is 0.0");
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
