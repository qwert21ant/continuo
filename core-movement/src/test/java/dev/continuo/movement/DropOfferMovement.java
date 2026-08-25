package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** Offers exactly one three-block drop, at a cost the test chooses. */
final class DropOfferMovement implements IMovementType {

    private final String id;
    private final double vertical;
    private final double cost;

    DropOfferMovement(String id, double vertical, double cost) {
        this.id = id;
        this.vertical = vertical;
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
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double minCostPerVerticalStep() {
        return vertical;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x(), ctx.y() - 3, ctx.z(), cost);
    }
}
