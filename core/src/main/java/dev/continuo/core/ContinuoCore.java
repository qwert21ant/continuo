package dev.continuo.core;

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

    /** Called once by the adapter, before any other method. */
    @Override
    public void start(IPlatformContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
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

    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE || !walking) {
            return;
        }
        tick++;
        if (tick == 1) {
            context.actuator().setInput(Input.FORWARD, true);
        } else if (tick == WALK_TICKS + 1) {
            context.actuator().setInput(Input.FORWARD, false);
            walking = false;
            tick = 0;
        }
    }
}
