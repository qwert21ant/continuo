package dev.continuo.runtime;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.BlockLegend;

import java.util.HashMap;
import java.util.Map;

/**
 * A settable world: a square stone floor at {@link #FLOOR_Y} with air above, plus whatever a
 * test puts on top of it.
 *
 * <p>Hand-built rather than parsed from text art, because {@code FixtureWorld} lives in
 * {@code :core-pathfinder}'s test sources and this module cannot see it. That is the seam
 * working rather than an inconvenience — the probe codes against {@code BlockSource} and so does
 * this.
 *
 * <p><b>The floor is finite, and that is load-bearing rather than tidy.</b> An unbounded floor
 * has no such thing as a blocked route: {@link #wallAcross} could span any number of blocks and
 * the search would simply walk around the end of it, so a test meaning to witness
 * {@code NO_PATH} would quietly witness {@code FOUND} by a longer road. {@link #RADIUS} gives
 * the world an edge for a wall to reach.
 */
final class ProbeWorld implements BlockSource {

    static final int FLOOR_Y = 63;
    static final int WALK_Y = 64;

    /** The floor spans {@code -RADIUS..RADIUS} on both horizontal axes, inclusive. */
    static final int RADIUS = 12;

    private final Map<Long, BlockData> overrides = new HashMap<Long, BlockData>();

    /** Puts a block at a position, replacing whatever the floor rule would give. */
    void put(int x, int y, int z, BlockData data) {
        overrides.put(Long.valueOf(key(x, y, z)), data);
    }

    /**
     * Builds a two-tall wall along the whole Z extent of the floor at one X, so it genuinely
     * separates the world rather than being something to walk around.
     */
    void wallAcross(int x) {
        for (int z = -RADIUS; z <= RADIUS; z++) {
            put(x, WALK_Y, z, BlockLegend.STONE);
            put(x, WALK_Y + 1, z, BlockLegend.STONE);
        }
    }

    @Override
    public BlockData at(int x, int y, int z) {
        BlockData override = overrides.get(Long.valueOf(key(x, y, z)));
        if (override != null) {
            return override;
        }
        if (x < -RADIUS || x > RADIUS || z < -RADIUS || z > RADIUS) {
            return BlockLegend.AIR;
        }
        return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        return 128;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ (long) z;
    }
}
