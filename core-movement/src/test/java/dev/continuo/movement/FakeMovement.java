package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** A movement with declared numbers and a scripted single offer, for registry tests. */
final class FakeMovement implements IMovementType {

    private final String id;
    private final Set<Capability> requires;
    private final double minCostPerAxisStep;
    private final int spanX;
    private final double cost;

    FakeMovement(String id, double minCostPerAxisStep, Capability... requires) {
        this(id, minCostPerAxisStep, 1, minCostPerAxisStep, requires);
    }

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
