package dev.continuo.engine;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockLookup;
import dev.continuo.core.BlockTableLoader;
import dev.continuo.core.CoreApi;
import dev.continuo.core.RuntimeLog;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.TickPhase;

/**
 * The entire core, for now: drive a {@link PathExecutor} toward whatever destination the owner
 * last asked for.
 *
 * <p>Deliberately has no static state and no knowledge of its owner. The adapter constructs
 * it and hands it to the shared {@code AdapterRuntime}, which is what holds it and drives it,
 * and this class is none the wiser — which is exactly why it can be tested with no Minecraft
 * on the classpath.
 */
public final class ContinuoCore implements CoreApi {

    private final RuntimeLog log;

    private IPlatformContext context;
    private BlockLookup blocks;
    private PathExecutor executor;

    /**
     * @param log where the executor explains why it stopped; never {@code null}
     * @throws IllegalArgumentException if {@code log} is {@code null}
     */
    public ContinuoCore(RuntimeLog log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        this.log = log;
    }

    /** Called once by the adapter, before any other method. */
    @Override
    public void start(IPlatformContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        this.blocks = new BlockLookup(
            context.blocks(),
            new BlockClassifier(BlockTableLoader.forVersion(context.info().gameVersion())));
        this.executor = new PathExecutor(blocks, context.actuator(), context.player(), log);
    }

    /**
     * Releases any held input and resets state.
     *
     * <p>Global rule 2 of the {@code dev.continuo.platform} package requires the adapter to
     * call this on each of three client level-instance transitions (to {@code null}, between
     * two different non-{@code null} instances, and from {@code null} to non-{@code null}),
     * and on client shutdown where the platform exposes a main-thread client-stopping event.
     * Without it, a disconnect mid-walk leaves the client holding a movement key.
     *
     * <p><b>{@code executor} and {@code blocks} are guarded symmetrically</b>, both against being
     * {@code null}, even though {@code start} always assigns both before either is used. The
     * guard is not for an ordinary caller -- {@code context == null} above already rejects one --
     * but for a {@code start} that itself threw partway through (an SPI implementation is free to
     * throw from {@code context.blocks()} or {@code context.actuator()}), which would leave
     * {@code context} assigned but one or both of the others still {@code null}. A caller in that
     * position is already in an unspecified state, but {@code stop()} should not itself NPE on
     * top of it.
     */
    @Override
    public void stop() {
        if (context == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        if (executor != null) {
            executor.stop();
        }
        if (blocks != null) {
            blocks.clear();
        }
    }

    /**
     * Walks to a block position, replacing any walk in progress.
     *
     * @param x the destination's X
     * @param y the destination's Y — the block the feet will occupy, per {@code IPlayerView.y()}
     * @param z the destination's Z
     * @throws IllegalStateException if {@code start} has not been called
     */
    public void walkTo(int x, int y, int z) {
        if (executor == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        executor.walkTo(x, y, z);
    }

    /**
     * Drives the executor one tick.
     *
     * <p><b>Nothing is written while idle</b> — not even a release. A core that held every input at
     * {@code false} every tick would fight the user's own keyboard whenever the bot is not running.
     *
     * <p><b>{@code executor} and {@code context} are guarded, symmetrically with {@link #stop}</b>,
     * for exactly the same partially-failed-{@code start} case: a caller in that position is
     * already in an unspecified state, but this should not itself NPE on top of it.
     */
    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE) {
            return;
        }
        if (executor == null || context == null) {
            return;
        }
        // Spec design §6.1's guard, restored: the shipped code had dropped `!executor.active()`,
        // which left PathExecutor.active() with no production caller at all. Behaviourally
        // identical today -- nothing else calls tick() while the executor is inactive -- but this
        // matches what was actually designed, and gives active() a real caller.
        if (!executor.active()) {
            return;
        }
        executor.tick(context.player());
    }

    /**
     * Classified block reads for the current level.
     *
     * <p>{@link #executor} reads through this as its {@code BlockSource}, and the dev-only
     * {@code PathProbe} an adapter drives separately reads through it too, so the classification
     * memo and its level-transition lifecycle are shared by every reader rather than duplicated.
     *
     * @return the lookup; never {@code null} after {@code start}
     * @throws IllegalStateException if {@code start} has not been called
     */
    public BlockLookup blocks() {
        if (blocks == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        return blocks;
    }
}
