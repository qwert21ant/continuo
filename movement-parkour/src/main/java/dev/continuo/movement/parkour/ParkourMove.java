package dev.continuo.movement.parkour;

import dev.continuo.movement.Capability;
import dev.continuo.movement.Cardinals;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Sprint-jumping a one-block gap to the block beyond, on the level.
 *
 * <p><b>The gap must not be standable.</b> If it were, {@code walk.traverse} already reaches the
 * far side in two cheaper steps, and offering a parkour edge as well would add a duplicate at a
 * worse cost — which makes the search prefer jumping to walking for no reason.
 *
 * <p>One block and the same height only. A sprint jump clears more, but every further block is a
 * claim about momentum that nothing in this codebase can check and M5 has not measured.
 *
 * <p><b>This movement spans two axis steps, and that is why it exists.</b> It is the first
 * movement to exercise the search's per-axis-step admissibility condition non-trivially:
 * declaring its whole cost rather than its per-step cost would raise the heuristic's multiplier
 * and silently stop A* returning shortest paths.
 */
public final class ParkourMove implements IMovementType {

    /**
     * Two horizontal blocks at the sprint figure, plus the jump surcharge.
     *
     * <p><b>Declared, not derived.</b> Adding the surcharge rather than overlapping it is an
     * upper bound — the rise and the crossing really do happen together — and that is the same
     * bound, taken for the same stated reason, that {@link MovementCosts#ASCEND} takes. Only M5
     * can measure the truth. The bound errs toward over-costing, whose failure mode is a quality
     * loss rather than a wrong path.
     */
    public static final double COST = 2 * MovementCosts.TRAVERSE + MovementCosts.JUMP_SURCHARGE;

    /** Two blocks along one axis, so half the cost. */
    private static final double MIN_COST_PER_AXIS_STEP = COST / 2.0;

    private static final Set<Capability> REQUIRES =
        Collections.unmodifiableSet(EnumSet.of(Capability.PARKOUR));

    /** Public and no-argument, which is what {@link java.util.ServiceLoader} requires. */
    public ParkourMove() {
    }

    @Override
    public String id() {
        return "walk.parkour";
    }

    @Override
    public Set<Capability> requires() {
        return REQUIRES;
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

        if (!Standability.passable(ctx.world().at(x, y + 2, z))) {
            return;
        }

        for (int i = 0; i < Cardinals.count(); i++) {
            int dx = Cardinals.dx(i);
            int dz = Cardinals.dz(i);
            int gapX = x + dx;
            int gapZ = z + dz;

            if (!Standability.passable(ctx.world().at(gapX, y, gapZ))
                || !Standability.passable(ctx.world().at(gapX, y + 1, gapZ))) {
                continue;
            }
            if (Standability.standable(ctx.world(), gapX, y, gapZ)) {
                continue;
            }

            int landX = x + 2 * dx;
            int landZ = z + 2 * dz;
            if (Standability.standable(ctx.world(), landX, y, landZ)) {
                sink.offer(landX, y, landZ, COST);
            }
        }
    }
}
