package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitySetTest {

    @Test
    void anEmptySetGrantsNothing() {
        assertFalse(CapabilitySet.none().grants(EnumSet.of(Capability.PARKOUR)));
    }

    @Test
    void everySetGrantsAnEmptyRequirement() {
        assertTrue(CapabilitySet.none().grants(EnumSet.noneOf(Capability.class)),
            "a movement that requires nothing must be active for every caller");
    }

    @Test
    void aSetGrantsWhatItContains() {
        assertTrue(CapabilitySet.of(Capability.PARKOUR).grants(EnumSet.of(Capability.PARKOUR)));
    }

    @Test
    void mutatingTheCallersSetCannotChangeWhatWasGranted() {
        EnumSet<Capability> caller = EnumSet.of(Capability.PARKOUR);
        CapabilitySet caps = CapabilitySet.copyOf(caller);
        caller.clear();

        assertTrue(caps.grants(EnumSet.of(Capability.PARKOUR)),
            "CapabilitySet must copy on construction, or a caller can retroactively change "
                + "which movements a registry considered active");
    }

    @Test
    void theExposedSetCannotBeMutated() {
        Set<Capability> exposed = CapabilitySet.of(Capability.PARKOUR).capabilities();

        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                exposed.clear();
            }
        });
    }

    @Test
    void equalSetsAreEqualAndHashAlike() {
        assertEquals(CapabilitySet.of(Capability.PARKOUR), CapabilitySet.of(Capability.PARKOUR));
        assertEquals(CapabilitySet.of(Capability.PARKOUR).hashCode(),
            CapabilitySet.of(Capability.PARKOUR).hashCode());
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                CapabilitySet.copyOf(null);
            }
        });
    }
}
