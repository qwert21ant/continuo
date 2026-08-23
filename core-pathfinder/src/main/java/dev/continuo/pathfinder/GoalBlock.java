package dev.continuo.pathfinder;

/**
 * One exact block position.
 *
 * <p>The heuristic is {@code cheapestAxisStep × max(|dx|, |dy|, |dz|)}, where the multiplier is a
 * minimum over the movements the search may use. One movement can close at most its own axis span
 * of that Chebyshev gap, and by the definition of the minimum it pays at least that many cheapest
 * axis steps for it — so the estimate cannot exceed the true remaining cost.
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
    public double heuristic(int px, int py, int pz, double cheapestAxisStep) {
        int moves = Math.max(Math.abs(x - px), Math.max(Math.abs(y - py), Math.abs(z - pz)));
        return moves * cheapestAxisStep;
    }

    @Override
    public String toString() {
        return "GoalBlock(" + x + ", " + y + ", " + z + ")";
    }
}
