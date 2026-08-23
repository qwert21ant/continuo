package dev.continuo.movement;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the caller grants a search.
 *
 * <p>Immutable, and copies its input on construction. A public API that aliased a caller's
 * mutable set would let the caller change which movements a registry considered active after the
 * filtering decision was made; {@code BlockData} copies its tags for the same reason.
 */
public final class CapabilitySet {

    private static final CapabilitySet NONE =
        new CapabilitySet(EnumSet.noneOf(Capability.class));

    private final Set<Capability> capabilities;

    private CapabilitySet(EnumSet<Capability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    /** @return a set granting nothing; every movement with no requirements is still active */
    public static CapabilitySet none() {
        return NONE;
    }

    /**
     * @param capabilities the capabilities to grant; never {@code null}, and no element may be
     *                     {@code null}
     * @return a set granting exactly those
     * @throws IllegalArgumentException if the argument or any element is {@code null}
     */
    public static CapabilitySet of(Capability... capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (int i = 0; i < capabilities.length; i++) {
            if (capabilities[i] == null) {
                throw new IllegalArgumentException("capability " + i + " must not be null");
            }
            set.add(capabilities[i]);
        }
        return new CapabilitySet(set);
    }

    /**
     * @param capabilities the capabilities to grant; never {@code null}, copied
     * @return a set granting exactly those
     * @throws IllegalArgumentException if the argument is {@code null}
     */
    public static CapabilitySet copyOf(Set<Capability> capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        set.addAll(capabilities);
        return new CapabilitySet(set);
    }

    /**
     * @param required what a movement declares it needs; never {@code null}
     * @return whether every required capability is granted. An empty requirement is always
     *         granted, which is what keeps a movement that needs nothing active for every caller
     */
    public boolean grants(Set<Capability> required) {
        if (required == null) {
            throw new IllegalArgumentException("required must not be null");
        }
        return capabilities.containsAll(required);
    }

    /** @return the granted capabilities, unmodifiable; never {@code null} */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CapabilitySet)) {
            return false;
        }
        return capabilities.equals(((CapabilitySet) o).capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities;
    }
}
