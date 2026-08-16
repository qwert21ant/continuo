package dev.continuo.pathfinder;

/**
 * One exact block position.
 *
 * <p>The heuristic is {@code cheapestMove × max(|dx|, |dy|, |dz|)}. Every movement changes each
 * axis by at most one, so the largest single-axis gap is a lower bound on the number of moves
 * still needed, and multiplying it by the cheapest possible move cannot overestimate. Taking the
 * maximum rather than the sum is what makes a diagonal — which closes X and Z together — free of
 * double-counting.
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
