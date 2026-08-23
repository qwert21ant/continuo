package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementCostsTest {

    @Test
    void everyCostIsPositive() {
        assertTrue(MovementCosts.TRAVERSE > 0, "TRAVERSE");
        assertTrue(MovementCosts.ASCEND > 0, "ASCEND");
        assertTrue(MovementCosts.DIAGONAL > 0, "DIAGONAL");
        assertTrue(MovementCosts.MAX_SAFE_FALL >= 1, "MAX_SAFE_FALL");

        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            assertTrue(MovementCosts.fallTicks(drop) > 0, "fallTicks(" + drop + ")");
        }
    }

    @Test
    void aDiagonalCostsMoreThanAStraightStep() {
        assertTrue(MovementCosts.DIAGONAL > MovementCosts.TRAVERSE,
            "a diagonal covers more ground and must not be a free shortcut");
    }

    @Test
    void climbingCostsMoreThanWalkingOnTheLevel() {
        assertTrue(MovementCosts.ASCEND > MovementCosts.TRAVERSE);
    }

    @Test
    void theCheapestMoveIsALowerBoundOnEveryMove() {
        double cheapest = MovementCosts.cheapestMove();

        assertTrue(cheapest <= MovementCosts.TRAVERSE, "TRAVERSE");
        assertTrue(cheapest <= MovementCosts.ASCEND, "ASCEND");
        assertTrue(cheapest <= MovementCosts.DIAGONAL, "DIAGONAL");
        assertTrue(cheapest <= MovementCosts.TRAVERSE + MovementCosts.fallTicks(1),
            "the cheapest possible descend");
        assertTrue(cheapest > 0, "a zero lower bound would make the heuristic useless");
    }

    /**
     * The real admissibility condition, which is per axis step rather than per movement.
     *
     * <p>{@link #theCheapestMoveIsALowerBoundOnEveryMove} above is the weaker per-movement form
     * and passes even when the heuristic overestimates, because the heuristic is a Chebyshev
     * distance: a descend of {@code k} blocks moves it by {@code k} steps in one move, so it must
     * pay for {@code k} of them. Today's margin at {@code k = 3} is {@code +1.3415} ticks and
     * goes negative at {@code k = 4}.
     */
    @Test
    void everyMovementCostsAtLeastItsAxisSpanTimesTheCheapestMove() {
        double cheapest = MovementCosts.cheapestMove();

        assertTrue(MovementCosts.TRAVERSE >= cheapest, "traverse spans one axis step");
        assertTrue(MovementCosts.ASCEND >= cheapest, "ascend spans one axis step");
        assertTrue(MovementCosts.DIAGONAL >= cheapest, "diagonal spans one axis step");

        for (int k = 1; k <= MovementCosts.MAX_SAFE_FALL; k++) {
            assertTrue(MovementCosts.TRAVERSE + MovementCosts.fallTicks(k) >= k * cheapest,
                "a descend of " + k + " blocks moves the heuristic by " + k + " steps, so it must"
                    + " cost at least that many cheapest moves — otherwise the heuristic"
                    + " overestimates and A* stops returning shortest paths");
        }
    }

    @Test
    void aDeeperFallCostsMoreButLessPerBlock() {
        for (int drop = 2; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            double shallower = MovementCosts.fallTicks(drop - 1);
            double deeper = MovementCosts.fallTicks(drop);

            assertTrue(deeper > shallower,
                "falling " + drop + " blocks must cost more than falling " + (drop - 1));
            assertTrue(deeper - shallower < shallower / (drop - 1),
                "a fall accelerates, so the " + drop + "th block must cost less than the average "
                    + "of the ones above it — this is what a single per-block rate got wrong");
        }
    }

    @Test
    void fallTicksRefusesDropsTheSearchCannotPlan() {
        assertThrows(IllegalArgumentException.class, () -> MovementCosts.fallTicks(0));
        assertThrows(IllegalArgumentException.class, () -> MovementCosts.fallTicks(-1));
        assertThrows(IllegalArgumentException.class,
            () -> MovementCosts.fallTicks(MovementCosts.MAX_SAFE_FALL + 1));
    }
}
