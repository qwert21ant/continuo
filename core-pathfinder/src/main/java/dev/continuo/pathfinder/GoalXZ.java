package dev.continuo.pathfinder;

import dev.continuo.movement.MovementCosts;

/**
 * A column: any height at one X and Z.
 *
 * <p>The heuristic ignores Y entirely, which is what keeps it admissible — a candidate far above
 * the target column may still be one move from satisfying the goal if the terrain drops away.
 */
public final class GoalXZ implements Goal {

    private final int x;
    private final int z;

    /**
     * @param x target X
     * @param z target Z
     */
    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean isReached(int px, int py, int pz) {
        return px == x && pz == z;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        int moves = Math.max(Math.abs(x - px), Math.abs(z - pz));
        return moves * MovementCosts.cheapestMove();
    }

    @Override
    public String toString() {
        return "GoalXZ(" + x + ", " + z + ")";
    }
}
