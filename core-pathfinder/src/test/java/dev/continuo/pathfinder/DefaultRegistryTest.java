package dev.continuo.pathfinder;

import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultRegistryTest {

    @Test
    void theDefaultRegistryHoldsC1sFourMovementsInC1sOrder() {
        ActiveMovements active =
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none());

        List<String> ids = new ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        assertEquals(
            Arrays.asList("walk.traverse", "walk.ascend", "walk.descend", "walk.diagonal"),
            ids,
            "A* breaks ties by discovery order, so C1's expansion order must survive verbatim");
    }

    @Test
    void theMultiplierOverC1sMovementsIsWhatC1sConstantWas() {
        assertEquals(MovementCosts.TRAVERSE,
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).rates().horizontal(),
            1.0e-9,
            "traverse is the cheapest axis step, so deriving the multiplier must reproduce the "
                + "figure C1 hard-coded — otherwise every C1 search result would change");
    }

    @Test
    void theDerivedVerticalRateIsDescendsWorstFallRatio() {
        // Pins the vertical half the way theMultiplierOverC1sMovementsIsWhatC1sConstantWas pins
        // the horizontal half. Nothing else in the suite catches a movement that cannot travel
        // vertically (an infinite minCostPerVerticalStep()) declaring a cheap one instead: that
        // would silently loosen every vertical estimate the search makes, the same mechanism as
        // the diagonal defect this branch removes, just on the other axis. Expressed as the
        // derivation rather than the literal so it stays tied to DescendMove's worst fall ratio.
        assertEquals(
            (MovementCosts.TRAVERSE + MovementCosts.fallTicks(MovementCosts.MAX_SAFE_FALL))
                / MovementCosts.MAX_SAFE_FALL,
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).rates().vertical(),
            1.0e-9,
            "DescendMove's worst-case fall ratio must remain the registry's vertical rate");
    }
}
