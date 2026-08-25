package dev.continuo.pathfinder;

import dev.continuo.movement.Capability;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement with declared numbers and a scripted single offer, for tests that inject a registry
 * into {@link AStarPathfinder} directly.
 *
 * <p>Mirrors {@code dev.continuo.movement.MovementRegistryTest}'s own {@code FakeMovement}, which
 * lives in {@code :core-movement}'s test source set and is not reachable from here.
 */
final class FakeMovement implements IMovementType {

    private final String id;
    private final Set<Capability> requires;
    private final double minCostPerAxisStep;
    private final int spanX;
    private final double cost;

    /**
     * @param id the id this movement registers under
     * @param minCostPerAxisStep the declared lower bound this movement reports
     * @param spanX how far along X each offer moves, in blocks
     * @param cost what each offer costs
     * @param requires the capabilities a caller must grant for this movement to be active
     */
    FakeMovement(String id, double minCostPerAxisStep, int spanX, double cost,
                 Capability... requires) {
        this.id = id;
        this.minCostPerAxisStep = minCostPerAxisStep;
        this.spanX = spanX;
        this.cost = cost;
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (int i = 0; i < requires.length; i++) {
            set.add(requires[i]);
        }
        this.requires = set;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return requires;
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return minCostPerAxisStep;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + spanX, ctx.y(), ctx.z(), cost);
    }
}
