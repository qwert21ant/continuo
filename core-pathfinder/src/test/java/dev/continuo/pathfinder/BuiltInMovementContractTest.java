package dev.continuo.pathfinder;

import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementContract;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInMovementContractTest {

    @Test
    void everyBuiltInMovementDeclaresItsCostPerAxisStepHonestly() {
        IMovementType[] movements = {
            new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
        };

        for (int i = 0; i < movements.length; i++) {
            List<String> violations = MovementContract.violations(movements[i]);
            assertEquals(java.util.Collections.<String>emptyList(), violations,
                movements[i].id() + " violated its own declaration");
        }
    }

    @Test
    void descendDeclaresItsWorstRatioNotItsCheapestCost() {
        assertEquals(
            (dev.continuo.movement.MovementCosts.TRAVERSE
                + dev.continuo.movement.MovementCosts.fallTicks(
                    dev.continuo.movement.MovementCosts.MAX_SAFE_FALL))
                / dev.continuo.movement.MovementCosts.MAX_SAFE_FALL,
            new DescendMove().minCostPerAxisStep(), 1.0e-9,
            "a one-block descend is cheaper in absolute terms but spans one axis step; the "
                + "binding ratio is the deepest fall, and declaring the cheap one would push the "
                + "heuristic's multiplier up and cost admissibility");
    }

    /**
     * An empty violations list is also what a completely broken audit returns if the seeded
     * worlds never let a movement offer anything at all. This builds one small world containing
     * a flat floor (traverse, diagonal), a one-block step up (ascend) and a drop through a hole
     * in the floor (descend), expands each movement over it directly, and asserts each one
     * actually offers a neighbour — so the audit above is proven to be exercising real
     * expansions rather than passing on silence.
     */
    @Test
    void theContractAuditIsNotVacuousBecauseEveryMovementActuallyOffersSomething() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 1,62,1\n"
                + "--- y=62\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=63\n"
                + "###\n"
                + "###\n"
                + "#.#\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "#.#\n"
                + "--- y=65\n"
                + "...\n"
                + "..#\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=67\n"
                + "...\n"
                + "...\n"
                + "...\n");

        IMovementType[] movements = {
            new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
        };

        for (int i = 0; i < movements.length; i++) {
            MutableExpansionContext ctx = new MutableExpansionContext(world);
            ctx.moveTo(2, 65, 2);
            final boolean[] offeredSomething = {false};
            movements[i].expand(ctx, new MoveSink() {
                @Override
                public void offer(int x, int y, int z, double cost) {
                    offeredSomething[0] = true;
                }
            });
            assertTrue(offeredSomething[0],
                movements[i].id() + " offered nothing over a world built with a floor, a step "
                    + "up and a drop for it to find");
        }
    }
}
