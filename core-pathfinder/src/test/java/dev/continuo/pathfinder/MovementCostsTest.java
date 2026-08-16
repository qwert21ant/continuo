package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementCostsTest {

    @Test
    void everyCostIsPositive() {
        assertTrue(MovementCosts.TRAVERSE > 0, "TRAVERSE");
        assertTrue(MovementCosts.ASCEND > 0, "ASCEND");
        assertTrue(MovementCosts.DIAGONAL > 0, "DIAGONAL");
        assertTrue(MovementCosts.FALL_PER_BLOCK > 0, "FALL_PER_BLOCK");
        assertTrue(MovementCosts.MAX_SAFE_FALL >= 1, "MAX_SAFE_FALL");
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
        assertTrue(cheapest <= MovementCosts.TRAVERSE + MovementCosts.FALL_PER_BLOCK,
            "the cheapest possible descend");
        assertTrue(cheapest > 0, "a zero lower bound would make the heuristic useless");
    }
}
