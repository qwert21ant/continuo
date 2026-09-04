package dev.continuo.engine;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;

/**
 * A world that answers {@code UNKNOWN} everywhere.
 *
 * <p>Enough for the drive tests, which hand the executor a path directly and never search. A
 * search against this returns {@code NO_PATH}, which is what the search tests rely on.
 */
final class EmptyWorld implements BlockSource {

    @Override
    public BlockData at(int x, int y, int z) {
        return BlockData.UNKNOWN;
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        return 255;
    }
}
