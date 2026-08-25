package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement that declares both rates explicitly and offers nothing.
 *
 * <p>{@link FakeMovement} always declares an infinite vertical rate, so no test built on it can
 * show that the two rates are minimised independently — which is the whole reason
 * {@code HeuristicRates} carries two.
 */
final class TwoRateMovement implements IMovementType {

    private final String id;
    private final double horizontal;
    private final double vertical;

    TwoRateMovement(String id, double horizontal, double vertical) {
        this.id = id;
        this.horizontal = horizontal;
        this.vertical = vertical;
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
        return vertical;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        // Declarations only; nothing here is audited by these tests.
    }
}
