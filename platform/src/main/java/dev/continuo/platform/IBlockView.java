package dev.continuo.platform;

/**
 * Reads blocks from the live world.
 *
 * <p>Note the direction: the adapter implements this and the core calls it — the opposite of
 * {@link IGameEvents}.
 *
 * <p><b>Call window.</b> Every method here MUST only be called while
 * {@link IGameEvents#onClientTick}'s delivery window is open — a world loaded and a local
 * player present. Outside that window the behaviour is unspecified. This deliberately reuses
 * that existing condition rather than stating a new one, so there is nothing extra for an
 * adapter to evaluate or get wrong.
 *
 * <p>Subject to all four global rules in this package's documentation, in particular rule 1:
 * these are main-thread calls and no implementation may block.
 */
public interface IBlockView {

    /**
     * The platform's own identifier for the block state at this position.
     *
     * <p>Cheap by contract. The core calls this once per block per pathfinding node, and
     * caches {@link #describe} results against the value it returns, so an implementation
     * MUST NOT do per-call work beyond reading the world. Both target versions have such an
     * identifier natively — 1.7.10 composes one from a block id and its metadata, and 1.21.11
     * has a global block-state registry id — so no adapter needs to invent one.
     *
     * <p>Values are <b>session-scoped</b>. They are stable while a client runs and MUST NOT
     * be persisted or compared across versions, mod sets, or runs.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the state id, or {@code -1} if the position is unreadable — outside
     *         {@link #minY()}/{@link #maxY()}, or in a chunk that is not loaded
     */
    int stateId(int x, int y, int z);

    /**
     * The full raw facts about the block at this position.
     *
     * <p>Expensive by contract, and called rarely: the core calls this only when it has not
     * yet seen the state id at that position, so it runs a few thousand times per session
     * rather than once per query.
     *
     * <p><b>Why this takes a position rather than a state id.</b> On 1.7.10 a block's collision
     * geometry is not a function of its state alone — fences, walls and panes compute their
     * boxes from their neighbours at a specific coordinate, and the metadata does not record
     * the result. An implementation handed only a state id could not answer for those blocks.
     *
     * <p>The core caches the result against {@link #stateId}, so for a neighbour-dependent
     * block the cached geometry is whichever instance was described first. That is sound only
     * because the core's shape categories are behavioural rather than literal.
     *
     * <p>MUST NOT be called for a position where {@link #stateId} returns {@code -1}.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the description; never {@code null}
     */
    BlockDescription describe(int x, int y, int z);

    /**
     * Whether the chunk at these chunk coordinates is loaded.
     *
     * <p>Distinct from {@link #stateId} returning {@code -1}, which conflates "not loaded"
     * with "outside the world". A caller planning a route needs to tell those apart: unloaded
     * terrain is unknown and might be solid, whereas above or below the world there is
     * definitely nothing.
     *
     * @param chunkX world X shifted right by four
     * @param chunkZ world Z shifted right by four
     * @return whether that chunk is loaded
     */
    boolean isChunkLoaded(int chunkX, int chunkZ);

    /**
     * The lowest Y coordinate that can hold a block, inclusive.
     *
     * <p>Follows Minecraft's own convention. 1.7.10 reports {@code 0}; 1.21.11 reports the
     * current dimension's floor, which is {@code -64} in the overworld.
     *
     * @return the inclusive lower bound
     */
    int minY();

    /**
     * One past the highest Y coordinate that can hold a block, exclusive.
     *
     * <p>1.7.10 reports {@code 256}; 1.21.11 reports the current dimension's ceiling, which is
     * {@code 320} in the overworld.
     *
     * @return the exclusive upper bound
     */
    int maxY();
}
