package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementRegistryTest {

    private static List<String> idsOf(ActiveMovements active) {
        List<String> ids = new java.util.ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        return ids;
    }

    @Test
    void aMovementRequiringNothingIsActiveForACallerGrantingNothing() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));

        assertEquals(Arrays.asList("a.free"), idsOf(registry.activeFor(CapabilitySet.none())));
    }

    @Test
    void aMovementIsInactiveWhenItsCapabilityIsNotGranted() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));
        registry.register(new FakeMovement("b.gated", 9.0, Capability.PARKOUR));

        assertEquals(Arrays.asList("a.free"), idsOf(registry.activeFor(CapabilitySet.none())),
            "a movement whose requirement is not granted must be filtered out entirely");
        assertEquals(Arrays.asList("a.free", "b.gated"),
            idsOf(registry.activeFor(CapabilitySet.of(Capability.PARKOUR))));
    }

    @Test
    void iterationOrderIsRegistrationOrderNotIdOrder() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("z.last", 3.0));
        registry.register(new FakeMovement("a.first", 4.0));

        assertEquals(Arrays.asList("z.last", "a.first"),
            idsOf(registry.activeFor(CapabilitySet.none())),
            "A* breaks ties by discovery order, so registration order is load-bearing and must "
                + "not be re-sorted");
    }

    @Test
    void theMultiplierIsTheSmallestDeclaredCostPerAxisStep() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.cheap", 3.5636));
        registry.register(new FakeMovement("b.dear", 6.5582));

        assertEquals(3.5636, registry.activeFor(CapabilitySet.none()).cheapestAxisStep(), 1.0e-9);
    }

    @Test
    void aWideCheapMovementLowersTheMultiplierForEveryone() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.walk", 3.5636));
        registry.register(new FakeMovement("b.glide", 1.2, 4, 4.8, Capability.PARKOUR));

        assertEquals(3.5636, registry.activeFor(CapabilitySet.none()).cheapestAxisStep(), 1.0e-9,
            "while it is filtered out it must not affect the multiplier");
        assertEquals(1.2,
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).cheapestAxisStep(), 1.0e-9,
            "once active, the cheapest axis step is its axis step, or the heuristic "
                + "overestimates and A* stops returning shortest paths");
    }

    @Test
    void aDuplicateIdIsRejected() {
        final MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.dup", 3.0));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    registry.register(new FakeMovement("a.dup", 4.0));
                }
            });
        assertEquals(true, thrown.getMessage().contains("a.dup"));
    }

    @Test
    void anEmptyActiveSetIsRejectedRatherThanGivenAnUndefinedMultiplier() {
        final MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.gated", 3.0, Capability.PARKOUR));

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                registry.activeFor(CapabilitySet.none());
            }
        });
    }

    @Test
    void theActiveListCannotBeMutated() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));
        final List<IMovementType> movements = registry.activeFor(CapabilitySet.none()).movements();

        assertThrows(UnsupportedOperationException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    movements.clear();
                }
            });
    }

    @Test
    void aNonPositiveDeclaredCostIsRejectedAtRegistration() {
        final MovementRegistry registry = new MovementRegistry();

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                registry.register(new FakeMovement("a.free", 0.0));
            }
        });
    }
}
