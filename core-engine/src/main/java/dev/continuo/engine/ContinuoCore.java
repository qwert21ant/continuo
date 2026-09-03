package dev.continuo.engine;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockData;
import dev.continuo.core.BlockLookup;
import dev.continuo.core.BlockTableLoader;
import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;

/**
 * The entire core, for now: on request, hold FORWARD for {@link #WALK_TICKS} ticks.
 *
 * <p>Deliberately has no static state and no knowledge of its owner. The adapter constructs
 * it and hands it to the shared {@code AdapterRuntime}, which is what holds it and drives it,
 * and this class is none the wiser — which is exactly why it can be tested with no Minecraft
 * on the classpath.
 */
public final class ContinuoCore implements CoreApi {

    /**
     * Roughly 8.6 blocks at steady-state vanilla walking speed. Measured travel from a
     * standing start is a little under that — 8 blocks in the 2026-08-11 smoke run — because
     * the first few ticks are spent accelerating. Both figures describe the same 40 ticks.
     */
    public static final int WALK_TICKS = 40;

    private IPlatformContext context;
    private boolean walking;
    private int tick;
    private BlockLookup blocks;

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
    }

    /**
     * Releases any held input and resets state.
     *
     * <p>Global rule 2 of the {@code dev.continuo.platform} package requires the adapter to
     * call this on each of three client level-instance transitions (to {@code null}, between
     * two different non-{@code null} instances, and from {@code null} to non-{@code null}),
     * and on client shutdown where the platform exposes a main-thread client-stopping event.
     * Without it, a disconnect mid-walk leaves the client holding a movement key.
     */
    @Override
    public void stop() {
        if (context == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        if (walking) {
            context.actuator().setInput(Input.FORWARD, false);
        }
        if (blocks != null) {
            blocks.clear();
        }
        walking = false;
        tick = 0;
    }

    /** Begins a walk. Ignored if a walk is already in progress. */
    public void requestWalk() {
        if (context == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        if (walking) {
            return;
        }
        walking = true;
        tick = 0;
    }

    /**
     * Holds {@code FORWARD} for {@link #WALK_TICKS} ticks, re-asserting it every tick.
     *
     * <p><b>Level-triggered, per global rule 4.</b> The desired input is re-stated on every tick of
     * the walk rather than pressed once at the start: both target versions clear held key state
     * whenever a screen opens ({@code KeyMapping.releaseAll}; 1.7.10's
     * {@code KeyBinding.unPressAllKeys}), and an edge-triggered core never learns that it happened.
     * The walk would silently truncate and present as a wrong distance.
     *
     * <p><b>Nothing is written while idle</b> — not even a release. A core that held every input at
     * {@code false} every tick would fight the user's own keyboard whenever the bot is not running.
     */
    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE || !walking) {
            return;
        }
        tick++;
        if (tick > WALK_TICKS) {
            context.actuator().setInput(Input.FORWARD, false);
            walking = false;
            tick = 0;
            return;
        }
        context.actuator().setInput(Input.FORWARD, true);
    }

    /**
     * Classified block reads for the current level.
     *
     * <p>Nothing in the core consumes this yet — M4's pathfinder is its first reader. It is
     * wired now so the whole chain, from an adapter's raw facts through the shared classifier
     * to a memoised {@link BlockData}, is exercised and its lifecycle is real rather than
     * hypothetical.
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
