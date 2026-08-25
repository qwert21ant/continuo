package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicRatesTest {

    private static final double SQRT2 = Math.sqrt(2.0);

    @Test
    void aCardinalStepIsOneUnitAndADiagonalStepIsRootTwo() {
        // The whole point of the unit. If a diagonal were one unit like a cardinal step, the
        // heuristic would credit a diagonal move at the cardinal rate, which is exactly the
        // defect this change exists to fix.
        assertEquals(1.0, HeuristicRates.octileUnits(1, 0), 1.0e-9);
        assertEquals(1.0, HeuristicRates.octileUnits(0, 1), 1.0e-9);
        assertEquals(SQRT2, HeuristicRates.octileUnits(1, 1), 1.0e-9);
    }

    @Test
    void anLShapedGapCostsItsDiagonalLegPlusItsStraightRemainder() {
        // (2,1) is one diagonal step and one cardinal step, not two of either.
        assertEquals(1.0 + SQRT2, HeuristicRates.octileUnits(2, 1), 1.0e-9);
        assertEquals(1.0 + SQRT2, HeuristicRates.octileUnits(1, 2), 1.0e-9);
    }

    @Test
    void theUnitIgnoresSign() {
        assertEquals(SQRT2, HeuristicRates.octileUnits(-1, -1), 1.0e-9);
        assertEquals(3.0, HeuristicRates.octileUnits(-3, 0), 1.0e-9);
    }

    @Test
    void aPureDiagonalIsRootTwoPerBlock() {
        // The measured case: 180 blocks diagonal is 254.5584 units, not 180.
        assertEquals(180.0 * SQRT2, HeuristicRates.octileUnits(180, 180), 1.0e-9);
    }

    @Test
    void theEstimateTakesTheLargerOfItsHorizontalAndVerticalHalves() {
        // Max rather than sum, because walk.ascend closes a horizontal axis and a vertical one
        // in a single move; summing would double-count it and overestimate.
        HeuristicRates rates = new HeuristicRates(2.0, 5.0);
        assertEquals(10.0, rates.estimate(3, 2, 0), 1.0e-9, "vertical 2*5=10 beats horizontal 3*2=6");
        assertEquals(8.0, rates.estimate(4, 1, 0), 1.0e-9, "horizontal 4*2=8 beats vertical 1*5=5");
    }

    @Test
    void anInfiniteRateContributesNothingWhenThatAxisDoesNotMove() {
        // THE TRAP THIS METHOD EXISTS FOR. A registry whose movements never travel vertically
        // yields vertical() == POSITIVE_INFINITY, and Infinity * 0 is NaN, not 0. A NaN estimate
        // poisons the priority queue's ordering and A* silently stops being A*. Guarding it here
        // means the two Goal implementations cannot each get it wrong separately.
        HeuristicRates rates = new HeuristicRates(2.0, Double.POSITIVE_INFINITY);
        double estimate = rates.estimate(3, 0, 0);
        assertFalse(Double.isNaN(estimate), "a zero vertical gap must not produce NaN");
        assertEquals(6.0, estimate, 1.0e-9);
    }

    @Test
    void anInfiniteHorizontalRateContributesNothingWhenThatAxisDoesNotMove() {
        HeuristicRates rates = new HeuristicRates(Double.POSITIVE_INFINITY, 4.0);
        double estimate = rates.estimate(0, 2, 0);
        assertFalse(Double.isNaN(estimate), "a zero horizontal gap must not produce NaN");
        assertEquals(8.0, estimate, 1.0e-9);
    }

    @Test
    void theHorizontalOnlyEstimateIgnoresYEntirely() {
        // GoalXZ targets a column, so it must not be charged for height at all.
        HeuristicRates rates = new HeuristicRates(2.0, 5.0);
        assertEquals(6.0, rates.horizontalEstimate(3, 0), 1.0e-9);
        assertFalse(Double.isNaN(new HeuristicRates(Double.POSITIVE_INFINITY, 5.0)
            .horizontalEstimate(0, 0)));
    }

    @Test
    void aNonPositiveOrNaNRateIsRejected() {
        // A zero rate drags the heuristic to zero and turns A* into an exhaustive search; a NaN
        // one makes every comparison false and the ordering arbitrary.
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(0.0, 1.0);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(1.0, -1.0);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(Double.NaN, 1.0);
            }
        });
    }

    @Test
    void ratesInfiniteOnBothAxesAreRejected() {
        // Such a set can reach nothing at all, so accepting it would produce a search that
        // expands the start node and reports NO_PATH for a reason no message explains.
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
        });
    }

    @Test
    void theUnitIsSubadditiveSoAPerEdgeBoundSumsAlongAPath() {
        // Consistency, which A* needs for its closed set to be safe: the estimate may never drop
        // by more than an edge costs. Subadditivity of the distance is what guarantees it, and
        // asserting it directly is cheaper and stronger than sampling searches for the symptom.
        for (int ax = -4; ax <= 4; ax++) {
            for (int az = -4; az <= 4; az++) {
                for (int bx = -4; bx <= 4; bx++) {
                    for (int bz = -4; bz <= 4; bz++) {
                        double whole = HeuristicRates.octileUnits(ax + bx, az + bz);
                        double parts = HeuristicRates.octileUnits(ax, az)
                            + HeuristicRates.octileUnits(bx, bz);
                        assertTrue(whole <= parts + 1.0e-9,
                            "octileUnits(" + (ax + bx) + "," + (az + bz) + ")=" + whole
                                + " exceeds the sum of its legs " + parts);
                    }
                }
            }
        }
    }

    @Test
    void oneInfiniteRateIsAccepted() {
        // The ordinary case: no built-in movement travels vertically without also travelling
        // horizontally, and walk.traverse never travels vertically at all.
        HeuristicRates rates = new HeuristicRates(3.5636, Double.POSITIVE_INFINITY);
        assertTrue(Double.isInfinite(rates.vertical()));
        assertEquals(3.5636, rates.horizontal(), 1.0e-9);
    }
}
