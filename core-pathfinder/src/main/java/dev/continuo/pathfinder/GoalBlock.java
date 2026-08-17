package dev.continuo.pathfinder;

/**
 * One exact block position.
 *
 * <p>The heuristic is {@code cheapestMove × max(|dx|, |dy|, |dz|)}. It never overestimates only
 * while every movement {@code m} the search can make satisfies
 * {@code cost(m) >= axisSpan(m) × cheapestMove()}, where {@code axisSpan(m)} is the largest number
 * of steps {@code m} takes along any single axis: one movement can close at most
 * {@code axisSpan(m)} of that Chebyshev gap, so it has to pay at least that many cheapest moves
 * for it. <b>It is not enough that {@code cheapestMove()} is the cheapest movement.</b>
 * {@link DescendMove} takes up to {@link MovementCosts#MAX_SAFE_FALL} steps of Y at once, so the
 * per-movement reading of the condition is too weak — see {@link MovementCosts#cheapestMove()} for
 * the margins and the test that holds them.
 *
 * <p>Taking the maximum rather than the sum is what makes a diagonal — which closes X and Z
 * together — free of double-counting.
 */
public final class GoalBlock implements Goal {

    private final int x;
    private final int y;
    private final int z;

    /**
     * @param x target X
     * @param y target Y
     * @param z target Z
     */
    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isReached(int px, int py, int pz) {
        return px == x && py == y && pz == z;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        int moves = Math.max(Math.abs(x - px), Math.max(Math.abs(y - py), Math.abs(z - pz)));
        return moves * MovementCosts.cheapestMove();
    }

    @Override
    public String toString() {
        return "GoalBlock(" + x + ", " + y + ", " + z + ")";
    }
}
