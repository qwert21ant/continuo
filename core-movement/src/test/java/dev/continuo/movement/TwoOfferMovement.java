package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement whose {@code expand} offers two neighbours in a single call, both of which violate
 * its declared {@link IMovementType#minCostPerAxisStep()}.
 *
 * <p>{@link FakeMovement} offers only one neighbour per call, so no test built on it can tell
 * whether {@link MovementContract#violations} keeps checking every offer a single {@code expand}
 * call makes after it has already found one violation from that call, or stops there. That
 * distinction is exactly what protects the "one counterexample, not all of them" guarantee: a
 * movement with a structurally wrong declaration is wrong at every offer it makes, and the four
 * built-in movements each offer up to four neighbours per call, so the real case has more than
 * one violating offer per call, not one. This double is the smallest reproduction of that case.
 */
final class TwoOfferMovement implements IMovementType {

    private final String id;
    private final double minCostPerAxisStep;

    TwoOfferMovement(String id, double minCostPerAxisStep) {
        this.id = id;
        this.minCostPerAxisStep = minCostPerAxisStep;
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
    public double minCostPerAxisStep() {
        return minCostPerAxisStep;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        // Both offers understate their per-axis cost against the declaration, at different
        // spans so each would report a distinct per-step figure if reached. A movement whose
        // declaration is structurally wrong (as opposed to wrong for one specific offer) is
        // wrong at every offer it makes in a call, which is what this reproduces.
        sink.offer(ctx.x() + 4, ctx.y(), ctx.z(), minCostPerAxisStep);
        sink.offer(ctx.x() + 3, ctx.y(), ctx.z(), minCostPerAxisStep);
    }
}
