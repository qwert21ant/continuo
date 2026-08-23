package dev.continuo.pathfinder;

import dev.continuo.movement.Capability;
import dev.continuo.movement.Cardinals;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

import java.util.EnumSet;
import java.util.Set;

/**
 * Jumping up one block onto a cardinal neighbour.
 *
 * <p>Needs clearance two blocks above the origin as well as a landing: the player's head passes
 * through {@code y + 2} during the jump, and a ceiling there stops the movement even though the
 * destination itself is standable.
 */
final class AscendMove implements IMovementType {

    @Override
    public String id() {
        return "walk.ascend";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.ASCEND;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        if (!Standability.passable(ctx.world().at(x, y + 2, z))) {
            return;
        }
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = x + Cardinals.dx(i);
            int nz = z + Cardinals.dz(i);
            if (Standability.standable(ctx.world(), nx, y + 1, nz)) {
                sink.offer(nx, y + 1, nz, MovementCosts.ASCEND);
            }
        }
    }
}
