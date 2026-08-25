package dev.continuo.movement;

/**
 * The rates A* scales its distance estimate by: one for horizontal travel, one for vertical.
 *
 * <p><b>Two rates rather than one, and they are minimised independently.</b> A single multiplier
 * over all axes means the cheapest movement on any axis degrades the estimate on every other one —
 * a ladder, which cannot travel horizontally at all, would loosen every horizontal estimate the
 * search ever makes. Separating them is what keeps a cheap vertical movement's cost where it
 * belongs.
 *
 * <p><b>Horizontal distance is measured in octile units</b>, not Chebyshev steps: one cardinal
 * step is one unit and one diagonal step is {@code √2} units. That matters because
 * {@code walk.diagonal} costs {@code TRAVERSE × √2}, so measuring its move as one step credited it
 * at the cardinal rate and left the estimate short by {@code √2} on any diagonal. A* then degrades
 * toward Dijkstra: before this, a 180-block diagonal on flat open ground exhausted a 10,000-node
 * budget outright.
 *
 * <p>Octile distance is the true shortest-path metric on an 8-connected grid with step weights
 * {@code 1} and {@code √2}, and since {@code 1 ≤ √2 ≤ 2} it is a metric — in particular
 * subadditive, which is what lets a per-edge bound sum along a path into a whole-path bound. That
 * holds for displacements of any shape, so a movement spanning two blocks or an L needs no special
 * case.
 *
 * <p>Immutable.
 */
public final class HeuristicRates {

    private static final double SQRT2 = Math.sqrt(2.0);

    private final double horizontal;
    private final double vertical;

    /**
     * @param horizontal ticks per octile unit of horizontal travel; positive, and
     *                   {@link Double#POSITIVE_INFINITY} if no active movement travels
     *                   horizontally
     * @param vertical   ticks per block of vertical travel; positive, and
     *                   {@link Double#POSITIVE_INFINITY} if no active movement travels vertically
     * @throws IllegalArgumentException if either is not positive, either is {@code NaN}, or both
     *                                  are infinite
     */
    public HeuristicRates(double horizontal, double vertical) {
        // Written as !(x > 0.0) rather than x <= 0.0 so that NaN is caught here too: every
        // comparison against NaN is false, so "NaN <= 0" would pass and a NaN rate would make the
        // priority queue's ordering arbitrary with nothing failing anywhere else.
        if (!(horizontal > 0.0)) {
            throw new IllegalArgumentException("horizontal must be positive, got " + horizontal);
        }
        if (!(vertical > 0.0)) {
            throw new IllegalArgumentException("vertical must be positive, got " + vertical);
        }
        if (Double.isInfinite(horizontal) && Double.isInfinite(vertical)) {
            throw new IllegalArgumentException("a movement set that travels along neither axis"
                + " class can reach nothing; both rates are infinite");
        }
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    /** @return ticks per octile unit of horizontal travel */
    public double horizontal() {
        return horizontal;
    }

    /** @return ticks per block of vertical travel */
    public double vertical() {
        return vertical;
    }

    /**
     * Horizontal distance in octile units: one per cardinal step, {@code √2} per diagonal step.
     *
     * <p>Shared by the heuristic and by {@code MovementContract}'s audit of the declarations that
     * feed it. <b>One definition on purpose</b> — two that agree today would drift the first time
     * either side changed, and the drift would be silent, because a movement would simply be
     * audited against a different distance than the search charges it for.
     *
     * @param dx signed X displacement
     * @param dz signed Z displacement
     * @return the distance in units; zero only when both displacements are zero
     */
    public static double octileUnits(int dx, int dz) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        int lo = Math.min(adx, adz);
        int hi = Math.max(adx, adz);
        return (hi - lo) + SQRT2 * lo;
    }

    /**
     * The estimated remaining cost for a displacement.
     *
     * <p><b>The larger of the two halves, never their sum.</b> {@code walk.ascend} closes a
     * horizontal axis and a vertical one in one move, so summing would charge twice for a single
     * movement and overestimate — which costs admissibility, and with it A*'s shortest-path
     * guarantee.
     *
     * @param dx signed X displacement
     * @param dy signed Y displacement
     * @param dz signed Z displacement
     * @return the estimate in ticks; never {@code NaN}
     */
    public double estimate(int dx, int dy, int dz) {
        return Math.max(horizontalEstimate(dx, dz), scaled(vertical, Math.abs(dy)));
    }

    /**
     * The horizontal half alone, for a goal that does not constrain Y.
     *
     * @param dx signed X displacement
     * @param dz signed Z displacement
     * @return the estimate in ticks; never {@code NaN}
     */
    public double horizontalEstimate(int dx, int dz) {
        return scaled(horizontal, octileUnits(dx, dz));
    }

    /**
     * Multiplies a rate by a distance, treating a zero distance as costing nothing.
     *
     * <p><b>The zero case is not defensive, it is arithmetic.</b> An axis class no movement
     * travels has an infinite rate, and {@code Infinity × 0} is {@code NaN} rather than zero. A
     * {@code NaN} estimate makes every priority-queue comparison false, so the open set orders
     * arbitrarily and A* silently stops being A*. Centralised here so the two {@code Goal}
     * implementations cannot each get it wrong separately.
     */
    private static double scaled(double rate, double distance) {
        return distance == 0.0 ? 0.0 : rate * distance;
    }
}
