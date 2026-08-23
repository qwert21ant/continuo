package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * The one message every other assertion in the suite depends on and none of them can produce.
     *
     * <p>Every cost assertion anywhere in this repository is written <em>in terms of</em> these
     * constants — {@code 4 * TRAVERSE}, {@code 3 * ASCEND}, {@code 2 * TRAVERSE + COST}. That is
     * the right way to write them, and it means a corrupted figure propagates consistently
     * through all of them and changes nothing: setting {@code TRAVERSE} to {@code 3.9636} was
     * measured to leave the entire suite green. Only a literal can catch a literal.
     *
     * <p>Since C2 the stakes are higher than one cost among four: {@code TRAVERSE} is the value
     * {@link ActiveMovements#cheapestAxisStep()} derives for every default search, so it now
     * scales the heuristic as well as pricing a step.
     */
    @Test
    void theDerivedPhysicsFiguresArePinnedToTheirLiteralValues() {
        String why = "\n\nThis pin exists because every other cost assertion in the suite is "
            + "expressed in terms of these constants, so a wrong figure propagates consistently "
            + "and no other test can see it. If you have deliberately re-derived this number from "
            + "the decompiled sources, update the literal here and the citation on the constant "
            + "together — that is the whole point of the pin, not an obstacle to it.";

        // MovementCosts.TRAVERSE: (1 - 0.6 * 0.91) / (0.98 * 0.1 * 1.3), the 1.7.10 sprint
        // figure 3.5635793 rounded to four decimals.
        assertEquals(3.5636, MovementCosts.TRAVERSE, 0.0, "TRAVERSE" + why);

        // MovementCosts.JUMP_SURCHARGE: 2 + (1 - 0.75320) / 0.24814, the tick at which a jump
        // from v=0.42 under (v - 0.08) * 0.98 clears one block.
        assertEquals(2.9946, MovementCosts.JUMP_SURCHARGE, 0.0, "JUMP_SURCHARGE" + why);

        // MovementCosts.MAX_SAFE_FALL: safe_fall_distance 3.0 in both versions.
        assertEquals(3, MovementCosts.MAX_SAFE_FALL, "MAX_SAFE_FALL" + why);

        // MovementCosts.fallTicks: the cumulative-drop table on fallTicks' javadoc, crossing
        // 1.0 at 4.6147, 2.0 at 6.7881 and 3.0 at 8.4687 ticks.
        assertEquals(4.6147, MovementCosts.fallTicks(1), 0.0, "fallTicks(1)" + why);
        assertEquals(6.7881, MovementCosts.fallTicks(2), 0.0, "fallTicks(2)" + why);
        assertEquals(8.4687, MovementCosts.fallTicks(3), 0.0, "fallTicks(3)" + why);
    }

    /**
     * ASCEND and DIAGONAL are pinned by their derivations rather than by literals, because they
     * are declared as arithmetic over the two figures above rather than as figures of their own.
     * Pinning them to literals would only restate what the compiler already computes.
     */
    @Test
    void theDerivedCompositesAreExactlyTheirStatedArithmetic() {
        assertEquals(MovementCosts.TRAVERSE + MovementCosts.JUMP_SURCHARGE, MovementCosts.ASCEND,
            0.0, "ASCEND is the horizontal crossing plus the jump surcharge");
        assertEquals(MovementCosts.TRAVERSE * Math.sqrt(2.0), MovementCosts.DIAGONAL, 0.0,
            "DIAGONAL is TRAVERSE times the geometric ratio");
    }

    @Test
    void fallTicksRefusesDropsTheSearchCannotPlan() {
        assertThrows(IllegalArgumentException.class, () -> MovementCosts.fallTicks(0));
        assertThrows(IllegalArgumentException.class, () -> MovementCosts.fallTicks(-1));
        assertThrows(IllegalArgumentException.class,
            () -> MovementCosts.fallTicks(MovementCosts.MAX_SAFE_FALL + 1));
    }
}
