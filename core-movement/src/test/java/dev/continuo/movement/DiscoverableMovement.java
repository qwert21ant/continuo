package dev.continuo.movement;

/**
 * A movement discovered through the test source set's own {@code META-INF/services} file, so
 * that discovery is exercised end to end rather than simulated.
 */
public final class DiscoverableMovement implements IMovementType {

    /** Public and no-argument, which is what {@link java.util.ServiceLoader} requires. */
    public DiscoverableMovement() {
    }

    @Override
    public String id() {
        return "test.discovered";
    }

    @Override
    public java.util.Set<Capability> requires() {
        return java.util.EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return 7.0;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + 1, ctx.y(), ctx.z(), 7.0);
    }
}
