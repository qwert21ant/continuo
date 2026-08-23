package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementDiscoveryTest {

    private static List<String> idsOf(ActiveMovements active) {
        List<String> ids = new ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        return ids;
    }

    @Test
    void aMovementOnTheClasspathIsFound() {
        MovementRegistry registry = new MovementRegistry();
        registry.discover();

        assertTrue(idsOf(registry.activeFor(CapabilitySet.none())).contains("test.discovered"),
            "the META-INF/services entry in this module's test resources must be picked up");
    }

    @Test
    void aFreshRegistryDiscoversNothingUntilAsked() {
        MovementRegistry registry = new MovementRegistry();

        assertEquals(Arrays.asList("a.only"), idsOf(
            registryWith(registry, new FakeMovement("a.only", 3.0))
                .activeFor(CapabilitySet.none())),
            "discovery must never be implicit, or a caller cannot know what a search will use");
    }

    @Test
    void discoveredMovementsAreSortedByIdWhateverOrderTheLoaderYieldsThem() {
        MovementRegistry forward = new MovementRegistry();
        forward.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("a.first", 3.0),
            new FakeMovement("m.middle", 4.0),
            new FakeMovement("z.last", 5.0)));

        MovementRegistry reversed = new MovementRegistry();
        reversed.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("z.last", 5.0),
            new FakeMovement("m.middle", 4.0),
            new FakeMovement("a.first", 3.0)));

        List<String> expected = Arrays.asList("a.first", "m.middle", "z.last");
        assertEquals(expected, idsOf(forward.activeFor(CapabilitySet.none())));
        assertEquals(expected, idsOf(reversed.activeFor(CapabilitySet.none())),
            "ServiceLoader's iteration order is unspecified and follows classpath order, so "
                + "without a sort the search's tie-breaking would vary by environment while "
                + "every test stayed green");
    }

    @Test
    void discoveryAppendsAfterWhatWasRegisteredExplicitly() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("z.explicit", 3.0));
        registry.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("a.discovered", 4.0)));

        assertEquals(Arrays.asList("z.explicit", "a.discovered"),
            idsOf(registry.activeFor(CapabilitySet.none())),
            "built-ins keep C1's expansion order; discovered movements land after them");
    }

    private static MovementRegistry registryWith(MovementRegistry registry, IMovementType type) {
        registry.register(type);
        return registry;
    }
}
