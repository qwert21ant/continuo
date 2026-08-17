package dev.continuo.core;

/**
 * A read-only view of classified blocks, with no assumption about where they come from.
 *
 * <p>This is the interface the pathfinder codes against. {@link BlockLookup} implements it over
 * a live world; test fixtures implement it directly over an array or a map, which is what makes
 * headless pathfinding tests possible without an {@code IBlockView}, the classifier, or a
 * per-version table.
 *
 * <p><b>Unreadable positions.</b> {@link #at} returns {@link BlockData#UNKNOWN} rather than
 * {@code null} or an exception, for every reason a position might be unreadable: outside
 * {@link #minY()}/{@link #maxY()}, in an unloaded chunk, or outside whatever region an
 * implementation happens to cover. One rule, no position-dependent special cases.
 *
 * <p><b>Call restrictions belong to the implementation, not to this interface.</b> A live
 * implementation inherits {@code IBlockView}'s main-thread delivery window; a frozen one has no
 * such restriction. Callers that hold a {@code BlockSource} of unknown provenance must assume
 * the stricter of the two.
 */
public interface BlockSource {

    /**
     * The classified block at a position.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, never {@code null}; {@link BlockData#UNKNOWN} if unreadable
     */
    BlockData at(int x, int y, int z);

    /** @return the lowest Y that can hold a block, inclusive */
    int minY();

    /** @return one past the highest Y that can hold a block, exclusive */
    int maxY();
}
