package dev.continuo.pathfinder;

import dev.continuo.movement.HeuristicRates;

/**
 * A single block position.
 *
 * <p>The heuristic is the larger of an octile horizontal estimate and a vertical one — never their
 * sum, because {@code walk.ascend} closes a horizontal axis and a vertical one in one move and
 * summing would charge twice for it.
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
    public double heuristic(int px, int py, int pz, HeuristicRates rates) {
        return rates.estimate(x - px, y - py, z - pz);
    }

    @Override
    public String toString() {
        return "GoalBlock(" + x + ", " + y + ", " + z + ")";
    }
}
