package dev.continuo.core;

import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;

/**
 * The entire core, for now: on request, hold FORWARD for {@link #WALK_TICKS} ticks.
 *
 * <p>Deliberately has no static state and no knowledge of its owner. The adapter
 * constructs it and holds it, which is exactly why this class can be tested with no
 * Minecraft on the classpath.
 */
public final class ContinuoCore implements IGameEvents {

    /** Roughly 8.6 blocks at vanilla walking speed. */
    public static final int WALK_TICKS = 40;

    private IPlatformContext context;
    private boolean walking;
    private int tick;

    /** Called once by the adapter, before any other method. */
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
     * call this on all three of world unload, disconnect and client shutdown. Without it, a
     * disconnect mid-walk leaves the client holding a movement key.
     */
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
