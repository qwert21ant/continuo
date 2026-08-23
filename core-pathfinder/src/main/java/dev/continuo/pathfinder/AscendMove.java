package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

/**
 * Jumping up one block onto a cardinal neighbour.
 *
 * <p>Needs clearance two blocks above the origin as well as a landing: the player's head passes
 * through {@code y + 2} during the jump, and a ceiling there stops the movement even though the
 * destination itself is standable.
 */
final class AscendMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        if (!Standability.passable(world.at(x, y + 2, z))) {
            return;
        }
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];
            if (Standability.standable(world, nx, y + 1, nz)) {
                sink.offer(nx, y + 1, nz, MovementCosts.ASCEND);
            }
        }
    }
}
