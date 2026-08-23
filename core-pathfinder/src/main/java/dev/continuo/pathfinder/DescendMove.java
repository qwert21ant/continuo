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
 * Walking off a ledge to a cardinal neighbour and falling to the first floor below.
 *
 * <p>Only the landing is offered, not every level passed through: the player has no control
 * during a fall, so the intermediate positions are not choices the search can make.
 *
 * <p>Stops at the first non-passable block in the shaft. If that block is not a floor — because
 * it is unreadable, lava, or a bottom slab — nothing is offered at all, rather than the search
 * assuming it can land somewhere it cannot.
 */
final class DescendMove implements IMovementType {

    /**
     * The deepest fall gives the worst cost per axis step, because a fall accelerates: its
     * marginal cost per block falls away while the heuristic's credit per block does not.
     * Computed rather than written as a literal, so that re-deriving {@code MAX_SAFE_FALL} or
     * {@code fallTicks} cannot leave a stale figure behind — which is the whole reason the
     * search derives its multiplier instead of trusting a constant.
     */
    private static final double MIN_COST_PER_AXIS_STEP = worstRatio();

    private static double worstRatio() {
        double worst = Double.POSITIVE_INFINITY;
        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            double ratio = (MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop)) / drop;
            if (ratio < worst) {
                worst = ratio;
            }
        }
        return worst;
    }

    @Override
    public String id() {
        return "walk.descend";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MIN_COST_PER_AXIS_STEP;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = x + Cardinals.dx(i);
            int nz = z + Cardinals.dz(i);

            if (!Standability.passable(ctx.world().at(nx, y, nz))
                || !Standability.passable(ctx.world().at(nx, y + 1, nz))) {
                continue;
            }

            for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
                int landingY = y - drop;
                if (Standability.standable(ctx.world(), nx, landingY, nz)) {
                    sink.offer(nx, landingY, nz,
                        MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop));
                    break;
                }
                if (!Standability.passable(ctx.world().at(nx, landingY, nz))) {
                    break;
                }
            }
        }
    }
}
