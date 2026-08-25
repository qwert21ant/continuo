package dev.continuo.movement;

import java.util.Set;

/**
 * One kind of movement: what it needs, what it costs, and which neighbours it reaches.
 *
 * <p>A movement is a plugin. It may live in any module or jar that depends on this one, and
 * {@link MovementRegistry#discover()} finds it through {@link java.util.ServiceLoader}. A
 * discovered implementation therefore needs a <b>public no-argument constructor</b>; without one
 * the failure surfaces at runtime in a consumer's build rather than at compile time here.
 *
 * <p><b>Neighbours must be offered in a fixed order.</b> The search breaks cost ties by the order
 * neighbours were discovered, so expansion order is what makes a path reproducible rather than
 * merely optimal. Use {@link Cardinals} for cardinal movements and the search's determinism
 * guarantee carries over unchanged.
 */
public interface IMovementType {

    /**
     * A stable identifier, dotted and lower case — {@code "walk.traverse"},
     * {@code "mod.jetpack.fly"}.
     *
     * <p>Not decoration. It is the key a registry rejects duplicate registrations on, and the
     * key discovery sorts by so that classpath order cannot leak into a search.
     *
     * @return the identifier; never {@code null} or empty, and constant for the instance's life
     */
    String id();

    /**
     * What the caller must grant before this movement is used.
     *
     * @return the required capabilities; never {@code null}, empty for a movement that is always
     *         available
     */
    Set<Capability> requires();

    /**
     * A lower bound on what one <em>octile unit</em> of horizontal travel costs this movement.
     *
     * <p><b>Get this wrong and the search silently stops returning shortest paths.</b> The
     * heuristic is an octile distance times the smallest value any active movement declares here.
     * Declaring too high a figure makes the heuristic overestimate, which costs admissibility with
     * no test failing anywhere else.
     *
     * <p>Concretely, the contract is: <b>the smallest
     * {@code cost / HeuristicRates.octileUnits(dx, dz)} of any neighbour this movement can ever
     * offer.</b> A movement stepping one block along one axis declares its cheapest cost. One
     * stepping diagonally divides by {@code √2}. One jumping two blocks along an axis divides
     * by two.
     *
     * <p>Return {@link Double#POSITIVE_INFINITY} if this movement never displaces horizontally.
     *
     * <p>It is a declaration, so it is checked rather than trusted:
     * {@code MovementContract#violations(IMovementType)} audits it against real expansions.
     *
     * @return the lower bound, in ticks; must be positive, and may be infinite
     */
    double minCostPerHorizontalUnit();

    /**
     * A lower bound on what one block of vertical travel costs this movement.
     *
     * <p>The smallest {@code cost / |dy|} of any neighbour this movement can ever offer.
     * Minimised separately from {@link #minCostPerHorizontalUnit()} so that a movement which is
     * cheap per block of height — a fall, or a ladder later — cannot degrade the estimate for
     * horizontal travel it may not be capable of at all.
     *
     * <p>Return {@link Double#POSITIVE_INFINITY} if this movement never displaces vertically.
     * A movement declaring both rates infinite reaches nothing and is rejected at registration.
     *
     * @return the lower bound, in ticks; must be positive, and may be infinite
     */
    double minCostPerVerticalStep();

    /**
     * Offers every neighbour reachable from the context's position.
     *
     * @param ctx where to expand from; never {@code null}, and MUST NOT be retained past this
     *            call
     * @param sink receives each reachable neighbour, in a fixed order; never {@code null}
     */
    void expand(ExpansionContext ctx, MoveSink sink);
}
