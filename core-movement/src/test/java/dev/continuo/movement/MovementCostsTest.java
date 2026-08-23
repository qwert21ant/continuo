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
