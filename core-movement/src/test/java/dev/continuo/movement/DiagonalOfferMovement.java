package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** Offers exactly one diagonal neighbour, at a cost the test chooses. */
final class DiagonalOfferMovement implements IMovementType {

    private final String id;
    private final double horizontal;
    private final double cost;

    DiagonalOfferMovement(String id, double horizontal, double cost) {
        this.id = id;
        this.horizontal = horizontal;
        this.cost = cost;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return horizontal;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + 1, ctx.y(), ctx.z() + 1, cost);
    }
}
