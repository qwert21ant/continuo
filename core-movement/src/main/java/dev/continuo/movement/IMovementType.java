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
     * A lower bound on what one <em>axis step</em> of this movement costs.
     *
     * <p><b>Get this wrong and the search silently stops returning shortest paths.</b> The
     * heuristic is a Chebyshev distance times the smallest value any active movement declares
     * here, so one movement can shrink the heuristic by this value times the number of blocks it
     * travels along its longest axis. Declaring too high a figure makes the heuristic
     * overestimate, which costs admissibility with no test failing anywhere else.
     *
     * <p>Concretely, the contract is: <b>the smallest
     * {@code cost / max(|dx|, |dy|, |dz|)} of any neighbour this movement can ever offer.</b>
     * For a movement that travels one block along each axis this is simply its cheapest cost. For
     * one that spans further — a fall of three blocks, a jump across two — divide.
     *
     * <p>It is a declaration, so it is checked rather than trusted:
     * {@code MovementContract#violations(IMovementType)} audits it against real expansions.
     *
     * @return the lower bound, in ticks; must be positive
     */
    double minCostPerAxisStep();

    /**
     * Offers every neighbour reachable from the context's position.
     *
     * @param ctx where to expand from; never {@code null}, and MUST NOT be retained past this
     *            call
     * @param sink receives each reachable neighbour, in a fixed order; never {@code null}
     */
    void expand(ExpansionContext ctx, MoveSink sink);
}
