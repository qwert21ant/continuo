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
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).cheapestAxisStep(),
            1.0e-9,
            "traverse is the cheapest axis step, so deriving the multiplier must reproduce the "
                + "figure C1 hard-coded — otherwise every C1 search result would change");
    }
}
