package dev.continuo.movement;

/**
 * Holds the known movements and decides which of them a search may use.
 *
 * <p>Replaces the fixed array C1's search iterated. Adding a movement is a registration, or a
 * jar on the classpath, rather than an edit to the search.
 */
public interface IMovementRegistry {

    /**
     * @param type the movement to add; never {@code null}
     * @throws IllegalArgumentException if {@code type} is {@code null}, its {@link
     *         IMovementType#id()} is null, empty or already registered, its
     *         {@link IMovementType#minCostPerHorizontalUnit()} or
     *         {@link IMovementType#minCostPerVerticalStep()} is not positive, or both are infinite
     */
    void register(IMovementType type);

    /**
     * @param caps what the caller grants; never {@code null}
     * @return the movements whose requirements are met, in registration order, bound to the
     *         heuristic multiplier they imply; never {@code null}
     * @throws IllegalArgumentException if {@code caps} is {@code null}; pass
     *         {@link CapabilitySet#none()} to grant nothing
     * @throws IllegalStateException if no movement is active
     */
    ActiveMovements activeFor(CapabilitySet caps);
}
