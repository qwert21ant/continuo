package dev.continuo.movement;

import dev.continuo.core.BlockSource;

/**
 * The one {@link ExpansionContext} implementation: created once per search and moved between
 * positions.
 *
 * <p>One allocation per search rather than one per expansion. It is public because more than one
 * module needs to build one — the search, and {@code MovementContract} when it audits a
 * movement — not because callers of a movement are expected to implement the interface
 * themselves.
 */
public final class MutableExpansionContext implements ExpansionContext {

    private final BlockSource world;
    private int x;
    private int y;
    private int z;

    /**
     * @param world the world to read; never {@code null}
     * @throws IllegalArgumentException if {@code world} is {@code null}
     */
    public MutableExpansionContext(BlockSource world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        this.world = world;
    }

    /**
     * Points this context at another position.
     *
     * @param x the X to expand from
     * @param y the Y to expand from
     * @param z the Z to expand from
     */
    public void moveTo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public BlockSource world() {
        return world;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }
}
