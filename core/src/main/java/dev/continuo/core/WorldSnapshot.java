package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A world copy being built: every position it reads, it remembers.
 *
 * <p><b>It decorates a {@link BlockSource}, not the SPI.</b> So it needs no state ids, no
 * classifier and no per-version table — it stores the {@link BlockData} the source handed back,
 * and {@link BlockLookup} has already interned one instance per state id, so a snapshot of forty
 * thousand positions holds a few dozen distinct objects and forty thousand references to them.
 *
 * <p><b>Why this is cheaper than reading the world.</b> {@code BlockLookup} memoises
 * classification by state id and never by position, so a block read sixteen times costs sixteen
 * {@code IBlockView.stateId} calls. Measured against real terrain, a search reads each position it
 * touches between four and sixteen times. A snapshot turns all of them into one.
 *
 * <p><b>Main thread only, while filling.</b> The restriction is inherited from whatever source is
 * wrapped rather than declared here: a live source carries {@code IBlockView}'s delivery window,
 * a fixture carries nothing. Once {@link #seal() sealed} the restriction is gone with the
 * reference that caused it.
 *
 * <p><b>This object has no lifecycle.</b> Nothing holds one across ticks, so there is nothing to
 * discard on a level transition and global rule 2 gains no new condition. The first thing that
 * keeps a snapshot alive between ticks inherits that question.
 */
public final class WorldSnapshot implements BlockSource {

    /** Cleared by {@link #seal()}, which is what makes a later fill impossible rather than wrong. */
    private BlockSource live;

    /** Handed to the sealed snapshot rather than copied; {@code null} once that has happened. */
    private Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();

    private final int minY;
    private final int maxY;

    private int reads;

    /**
     * @param live the source to copy from; never {@code null}
     * @throws IllegalArgumentException if {@code live} is {@code null}
     */
    public WorldSnapshot(BlockSource live) {
        if (live == null) {
            throw new IllegalArgumentException("live must not be null");
        }
        this.live = live;
        this.minY = live.minY();
        this.maxY = live.maxY();
    }

    /**
     * The block at a position, read from the live source at most once.
     *
     * <p>A position outside {@link #minY}/{@link #maxY} yields {@code UNKNOWN} without asking the
     * source and without being stored: that answer is permanent and computable, and storing it
     * would let a search probing above or below grow the map with entries carrying nothing.
     *
     * <p>Everything else is stored verbatim, {@link BlockData#UNKNOWN} included. An unloaded
     * chunk is a real answer at the moment it was read, and re-asking would both break the
     * point-in-time guarantee and spend SPI calls on exactly the positions a search probes
     * hardest.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block; never {@code null}
     * @throws IllegalStateException if this snapshot has been sealed
     */
    @Override
    public BlockData at(int x, int y, int z) {
        requireFilling();
        reads++;
        if (y < minY || y >= maxY) {
            return BlockData.UNKNOWN;
        }
        Long key = Long.valueOf(PositionKey.pack(x, y, z));
        BlockData cached = blocks.get(key);
        if (cached != null) {
            return cached;
        }
        BlockData fresh = live.at(x, y, z);
        blocks.put(key, fresh);
        return fresh;
    }

    /**
     * @return the world's inclusive lower bound, captured at construction. Still answers after
     *         sealing, because it is a value this object was given rather than state it gave away
     */
    @Override
    public int minY() {
        return minY;
    }

    /** @return the world's exclusive upper bound, captured at construction */
    @Override
    public int maxY() {
        return maxY;
    }

    /**
     * @return how many positions have been stored
     * @throws IllegalStateException if this snapshot has been sealed
     */
    public int size() {
        requireFilling();
        return blocks.size();
    }

    /**
     * @return how many {@link #at} calls have been served, including out-of-world ones
     * @throws IllegalStateException if this snapshot has been sealed
     */
    public int reads() {
        requireFilling();
        return reads;
    }

    /**
     * Freezes this snapshot, one way.
     *
     * <p>The map is handed over rather than copied, so this costs nothing and doubles no memory —
     * and this object is invalidated in the same breath, because a {@link SealedSnapshot} whose
     * map somebody else can still write to is neither immutable nor safely publishable.
     *
     * @return the frozen snapshot; never {@code null}
     * @throws IllegalStateException if this snapshot has already been sealed
     */
    public SealedSnapshot seal() {
        requireFilling();
        SealedSnapshot sealed = new SealedSnapshot(blocks, minY, maxY, reads);
        blocks = null;
        live = null;
        return sealed;
    }

    private void requireFilling() {
        if (blocks == null) {
            throw new IllegalStateException("this WorldSnapshot has been sealed;"
                + " read the SealedSnapshot that seal() returned instead");
        }
    }
}
