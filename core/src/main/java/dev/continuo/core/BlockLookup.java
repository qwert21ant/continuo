package dev.continuo.core;

import dev.continuo.platform.IBlockView;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads classified blocks from a live {@link IBlockView}, classifying each state once.
 *
 * <p>The hot path is an {@code int}: {@link IBlockView#stateId} is called per block, and the
 * far more expensive {@code describe}-and-classify path only when a state id has not been seen
 * before. Over a session that is a few thousand classifications rather than one per query.
 *
 * <p><b>Lifecycle.</b> State ids are session-scoped, so this memo must not outlive the level it
 * was built against. {@link #clear()} is called from {@code ContinuoCore.stop()}, which global
 * rule 2 already requires the adapter to call on every level transition — so no new machinery
 * and no new condition. A {@code HashMap} rather than an array because 1.21.11's state id space
 * is around 26,000 entries of which a session touches a small fraction.
 *
 * <p>This is the live implementation of {@link BlockSource}. Its reads are subject to
 * {@code IBlockView}'s delivery window; a caller holding only the interface cannot know that,
 * which is why the restriction is documented on both.
 */
public final class BlockLookup implements BlockSource {

    private final IBlockView view;
    private final BlockClassifier classifier;
    private final Map<Integer, BlockData> byStateId = new HashMap<Integer, BlockData>();

    /**
     * @param view the live reader; never {@code null}
     * @param classifier the shared classifier; never {@code null}
     */
    public BlockLookup(IBlockView view, BlockClassifier classifier) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (classifier == null) {
            throw new IllegalArgumentException("classifier must not be null");
        }
        this.view = view;
        this.classifier = classifier;
    }

    /**
     * The classified block at a position.
     *
     * <p>May only be called while {@code IGameEvents.onClientTick}'s delivery window is open;
     * that restriction comes from {@link IBlockView} and is not restated here.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, or {@link BlockData#UNKNOWN} if the position is unreadable
     */
    @Override
    public BlockData at(int x, int y, int z) {
        int stateId = view.stateId(x, y, z);
        if (stateId == -1) {
            return BlockData.UNKNOWN;
        }
        Integer key = Integer.valueOf(stateId);
        BlockData cached = byStateId.get(key);
        if (cached != null) {
            return cached;
        }
        BlockData classified = classifier.classify(view.describe(x, y, z));
        byStateId.put(key, classified);
        return classified;
    }

    /** Discards everything classified so far. Called on every level transition. */
    public void clear() {
        byStateId.clear();
    }

    /** @return the world's inclusive lower bound */
    @Override
    public int minY() {
        return view.minY();
    }

    /** @return the world's exclusive upper bound */
    @Override
    public int maxY() {
        return view.maxY();
    }
}
