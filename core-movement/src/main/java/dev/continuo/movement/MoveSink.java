package dev.continuo.movement;

/**
 * Receives the neighbours a movement generates.
 *
 * <p>A sink rather than a returned collection, which buys two things: no collection is allocated
 * per expansion, and a movement can offer a neighbour the moment it finds one instead of building
 * a list to hand back.
 */
public interface MoveSink {

    /**
     * @param x the neighbour's X
     * @param y the neighbour's Y
     * @param z the neighbour's Z
     * @param cost the cost of getting there from the position being expanded, in ticks; must be
     *             positive, and must respect the movement's declared
     *             {@link IMovementType#minCostPerHorizontalUnit()} for the horizontal component of
     *             the offer and {@link IMovementType#minCostPerVerticalStep()} for the vertical one
     */
    void offer(int x, int y, int z, double cost);
}
