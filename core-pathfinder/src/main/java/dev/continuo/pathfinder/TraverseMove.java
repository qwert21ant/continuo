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

/** Walking one block to a cardinal neighbour at the same height. */
final class TraverseMove implements IMovementType {

    @Override
    public String id() {
        return "walk.traverse";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.TRAVERSE;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int y = ctx.y();
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = ctx.x() + Cardinals.dx(i);
            int nz = ctx.z() + Cardinals.dz(i);
            if (Standability.standable(ctx.world(), nx, y, nz)) {
                sink.offer(nx, y, nz, MovementCosts.TRAVERSE);
            }
        }
    }
}
