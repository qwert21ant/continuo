package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

/**
 * Walking off a ledge to a cardinal neighbour and falling to the first floor below.
 *
 * <p>Only the landing is offered, not every level passed through: the player has no control
 * during a fall, so the intermediate positions are not choices the search can make.
 *
 * <p>Stops at the first non-passable block in the shaft. If that block is not a floor — because
 * it is unreadable, lava, or a bottom slab — nothing is offered at all, rather than the search
 * assuming it can land somewhere it cannot.
 */
final class DescendMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];

            if (!Standability.passable(world.at(nx, y, nz))
                || !Standability.passable(world.at(nx, y + 1, nz))) {
                continue;
            }

            for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
                int landingY = y - drop;
                if (Standability.standable(world, nx, landingY, nz)) {
                    sink.offer(nx, landingY, nz,
                        MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop));
                    break;
                }
                if (!Standability.passable(world.at(nx, landingY, nz))) {
                    break;
                }
            }
        }
    }
}
