package dev.continuo.testkit;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An array-free, map-backed {@link IBlockView} for headless tests.
 *
 * <p>Assigns each distinct {@link BlockDescription} a state id in insertion order, which is
 * enough to exercise every caller that treats state ids as opaque session-scoped integers —
 * which is all of them, by contract.
 *
 * <p>Counts its calls, so a test can assert that a memo actually memoised rather than merely
 * returning the right answer.
 */
public final class FakeBlockView implements IBlockView {

    private final Map<Long, Integer> stateIdByPosition = new HashMap<Long, Integer>();
    private final Map<Integer, BlockDescription> descriptionByStateId = new HashMap<Integer, BlockDescription>();
    // Keyed by BlockDescription.stateKey(), not by the description instance: BlockDescription
    // defines no equals/hashCode, so a Map<BlockDescription, Integer> would use identity
    // semantics and hand two structurally identical descriptions two different state ids. The
    // state key is by definition the identity of a block state, and it is exactly what both
    // real adapters use to decide "same state, same state id" — so this is the correct key,
    // not a shortcut. Do not change this back to keying on the description itself.
    private final Map<String, Integer> stateIdByStateKey = new HashMap<String, Integer>();
    private final Set<Long> unloadedChunks = new HashSet<Long>();

    private int nextStateId;
    private int stateIdCalls;
    private int describeCalls;
    private boolean failIfTouched;
    private int minY;
    private int maxY = 256;

    /**
     * Places a block.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @param description what {@link #describe} will return there; never {@code null}
     * @return this, for chaining
     */
    public FakeBlockView put(int x, int y, int z, BlockDescription description) {
        Integer id = stateIdByStateKey.get(description.stateKey());
        if (id == null) {
            id = Integer.valueOf(nextStateId++);
            stateIdByStateKey.put(description.stateKey(), id);
            descriptionByStateId.put(id, description);
        }
        stateIdByPosition.put(Long.valueOf(key(x, y, z)), id);
        return this;
    }

    /**
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @param loaded whether that chunk should report as loaded
     * @return this, for chaining
     */
    public FakeBlockView setChunkLoaded(int chunkX, int chunkZ, boolean loaded) {
        Long k = Long.valueOf(key(chunkX, 0, chunkZ));
        if (loaded) {
            unloadedChunks.remove(k);
        } else {
            unloadedChunks.add(k);
        }
        return this;
    }

    /**
     * Sets the vertical range this view reports.
     *
     * @param min inclusive lower bound
     * @param max exclusive upper bound
     * @return this, for chaining
     */
    public FakeBlockView setVerticalRange(int min, int max) {
        this.minY = min;
        this.maxY = max;
        return this;
    }

    /**
     * Makes every subsequent call throw. Used to assert that a sealed or cleared consumer
     * stops calling back into the platform.
     */
    public void failIfTouched() {
        this.failIfTouched = true;
    }

    /** @return how many times {@link #stateId} has been called */
    public int stateIdCallCount() {
        return stateIdCalls;
    }

    /** @return how many times {@link #describe} has been called */
    public int describeCallCount() {
        return describeCalls;
    }

    @Override
    public int stateId(int x, int y, int z) {
        guard();
        stateIdCalls++;
        if (y < minY || y >= maxY) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        Integer id = stateIdByPosition.get(Long.valueOf(key(x, y, z)));
        return id == null ? -1 : id.intValue();
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        guard();
        describeCalls++;
        Integer id = stateIdByPosition.get(Long.valueOf(key(x, y, z)));
        if (id == null) {
            throw new IllegalStateException("describe called for an unreadable position " + x + "," + y + "," + z);
        }
        return descriptionByStateId.get(id);
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        guard();
        return !unloadedChunks.contains(Long.valueOf(key(chunkX, 0, chunkZ)));
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    private void guard() {
        if (failIfTouched) {
            throw new AssertionError("the view was called after failIfTouched()");
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
