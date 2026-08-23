package dev.continuo.pathfinder;

import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscendMoveTest {

    private final IMovementType move = new AscendMove();

    /** A step up to the east of a player standing at (0, 65, 0). */
    private static final String STEP =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "##\n"
            + "--- y=65\n"
            + ".#\n"
            + "--- y=66\n"
            + "..\n"
            + "--- y=67\n"
            + "..\n";

    @Test
    void offersTheBlockAboveAStep() {
        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(FixtureWorld.parse(STEP));
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertEquals(1, sink.size());
        assertEquals(new Pos(1, 66, 0), sink.positions().get(0));
    }

    @Test
    void climbingCostsAnAscend() {
        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(FixtureWorld.parse(STEP));
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertEquals(MovementCosts.ASCEND, sink.costOf(new Pos(1, 66, 0)), 1.0e-9);
    }

    @Test
    void aCeilingOverTheOriginBlocksTheJump() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".#\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "#.\n");

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertEquals(0, sink.size(),
            "y+2 above the origin is where the head goes during the jump");
    }

    @Test
    void aCeilingOverTheLandingBlocksTheClimb() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".#\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + ".#\n");

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertEquals(0, sink.size());
    }

    @Test
    void thereIsNothingToClimbOnFlatGround() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + "..\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertEquals(0, sink.size());
    }

    @Test
    void aFenceIsNotClimbedOnto() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".f\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(0, 65, 0);
        move.expand(ctx, sink);

        assertTrue(!sink.positions().contains(new Pos(1, 66, 0)),
            "a fence is 1.5 tall and cannot be jumped onto");
    }

    @Test
    void offersInTheFixedCardinalOrder() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "###\n"
                + "#.#\n"
                + "###\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=67\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(1, 65, 1);
        move.expand(ctx, sink);

        assertEquals(4, sink.size());
        assertEquals(new Pos(1, 66, 0), sink.positions().get(0), "north first");
        assertEquals(new Pos(2, 66, 1), sink.positions().get(1), "then east");
        assertEquals(new Pos(1, 66, 2), sink.positions().get(2), "then south");
        assertEquals(new Pos(0, 66, 1), sink.positions().get(3), "then west");
    }
}
