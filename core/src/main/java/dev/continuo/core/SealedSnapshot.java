package dev.continuo.core;

import java.util.Map;

/**
 * A frozen {@code WorldSnapshot}: the part of the world one search read, and nothing else.
 *
 * <p>Readable from any thread. It holds no reference to a live source and has no method that
 * would use one, so it cannot call the SPI — that is a property of the type rather than a check
 * it performs. Its fields are {@code final} and its map is never mutated after construction, so
 * the JMM's final-field freeze guarantees that a thread seeing a properly constructed instance
 * sees the whole map. Handing one to an executor therefore needs no happens-before argument of
 * its own.
 *
 * <p><b>Staleness is the contract, not a bug.</b> A snapshot is a point-in-time copy by
 * definition. Nothing invalidates it and nothing refreshes it. When the world moves under a
 * computed path, M5's position resync notices and repaths.
 *
 * <p>Created only by {@code WorldSnapshot.seal()}, which hands over its map and invalidates
 * itself. There is deliberately no public constructor: an instance built over a map somebody else
 * still holds would be neither immutable nor safely publishable, and both of those are the whole
 * point.
 */
public final class SealedSnapshot implements BlockSource {

    private final Map<Long, BlockData> blocks;
    private final int minY;
    private final int maxY;
    private final int reads;

    /**
     * @param blocks the frozen positions; ownership passes to this object and the caller must
     *               never touch the map again
     * @param minY the world's inclusive lower bound at the time of filling
     * @param maxY the world's exclusive upper bound at the time of filling
     * @param reads the filling handle's {@code reads()} at the moment of sealing
     */
    SealedSnapshot(Map<Long, BlockData> blocks, int minY, int maxY, int reads) {
        this.blocks = blocks;
        this.minY = minY;
        this.maxY = maxY;
        this.reads = reads;
    }

    /**
     * The block frozen at a position.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, or {@link BlockData#UNKNOWN} if this snapshot has no answer — which
     *         {@link #covers} distinguishes from the world's own {@code UNKNOWN}
     */
    @Override
    public BlockData at(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return BlockData.UNKNOWN;
        }
        BlockData held = blocks.get(Long.valueOf(PositionKey.pack(x, y, z)));
        return held == null ? BlockData.UNKNOWN : held;
    }

    /** @return the world's inclusive lower bound, as it was when this was filled */
    @Override
    public int minY() {
        return minY;
    }

    /** @return the world's exclusive upper bound, as it was when this was filled */
    @Override
    public int maxY() {
        return maxY;
    }

    /**
     * Whether this snapshot can answer for a position authoritatively.
     *
     * <p>Four situations collapse to two answers. A position read while filling is covered,
     * whether the world gave a block or gave {@code UNKNOWN} for an unloaded chunk. A position
     * outside {@link #minY}/{@link #maxY} is covered too: there is no terrain there and there
     * never will be. Only a position that was never read is <em>not</em> covered.
     *
     * <p><b>Why this is not on {@link BlockSource}.</b> That interface has one rule — every
     * unreadable position yields {@code UNKNOWN}, no position-dependent special cases — and it
     * keeps it. This method exists here alone because an off-thread search needs the distinction
     * and nothing holding the bare interface does: an uncovered position is a question for the
     * main thread, a covered {@code UNKNOWN} is terrain to route around.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return whether {@link #at} speaks for the world here rather than for this snapshot's limits
     */
    public boolean covers(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return true;
        }
        return blocks.containsKey(Long.valueOf(PositionKey.pack(x, y, z)));
    }

    /** @return how many positions this snapshot holds */
    public int size() {
        return blocks.size();
    }

    /**
     * @return how many reads the filling handle served before sealing. Divided by {@link #size},
     *         this is how many times the average position was read — the figure that makes a
     *         snapshot cheaper than reading the world live
     */
    public int reads() {
        return reads;
    }
}
