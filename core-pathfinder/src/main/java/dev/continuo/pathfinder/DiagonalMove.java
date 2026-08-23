package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

/**
 * Walking one block diagonally on the level.
 *
 * <p><b>Both orthogonal sides of the corner must be clear, at feet and at head height.</b>
 * Minecraft does not let a player squeeze between two blocks meeting at a corner, and a search
 * that allows it produces paths that look shorter and cannot be walked. Checking only the
 * destination is the classic version of this bug.
 */
final class DiagonalMove implements Move {

    /** North-east, south-east, south-west, north-west as {@code {dx, dz}}. */
    private static final int[][] DIAGONALS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < DIAGONALS.length; i++) {
            int dx = DIAGONALS[i][0];
            int dz = DIAGONALS[i][1];
            int nx = x + dx;
            int nz = z + dz;

            if (!Standability.standable(world, nx, y, nz)) {
                continue;
            }
            if (!clear(world, nx, y, z) || !clear(world, x, y, nz)) {
                continue;
            }
            sink.offer(nx, y, nz, MovementCosts.DIAGONAL);
        }
    }

    /** Whether a two-block-tall body fits at this column, feet at {@code y}. */
    private static boolean clear(BlockSource world, int x, int y, int z) {
        return Standability.passable(world.at(x, y, z))
            && Standability.passable(world.at(x, y + 1, z));
    }
}
