package dev.continuo.runtime;

import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;

/**
 * Discharges the adapter-side obligations of the four global rules documented in
 * {@code dev.continuo.platform}'s {@code package-info}.
 *
 * <p>An adapter constructs one of these, forwards four calls to it, and holds no conformance
 * state of its own.
 */
public final class AdapterRuntime {

    private final CoreApi core;
    private final RuntimeLog log;
    private final ClickSource clicks;
    private final Runnable onClick;

    private boolean started;

    /**
     * The client level instance last seen by {@link #tickStart}, compared by identity. Holding
     * it does not leak an unloaded world: it is overwritten with the current level the moment
     * a change is detected, so it only ever names the level that is loaded now, or
     * {@code null}.
     */
    private Object lastLevel;

    /**
     * @param core    the core to drive
     * @param log     where rule 2 and rule 3 messages go
     * @param clicks  the keybind click poll
     * @param onClick run once per consumed click, inside the rule 3 guard
     */
    public AdapterRuntime(CoreApi core, RuntimeLog log, ClickSource clicks, Runnable onClick) {
        if (core == null || log == null || clicks == null || onClick == null) {
            throw new IllegalArgumentException("no constructor argument may be null");
        }
        this.core = core;
        this.log = log;
        this.clicks = clicks;
        this.onClick = onClick;
    }

    /**
     * Discharges global rule 2's "exactly once, before any other core method".
     *
     * <p>Rule 2 binds adapters, not the core. {@code ContinuoCore} still tolerates a second
     * {@code start} and a test pins that; this guard is the adapter-side obligation, and an
     * adapter MUST NOT rely on the core's leniency.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start(IPlatformContext context) {
        if (started) {
            throw new IllegalStateException("start(IPlatformContext) has already been called");
        }
        started = true;
        core.start(context);
    }

    /** Call from the game's tick-start hook. */
    public void tickStart(Object level, Object player) {
        if (!started) {
            return;
        }
        updateLevel(level);
    }

    /** Call from the game's tick-end hook. */
    public void tickEnd(Object level, Object player) {
        if (!started) {
            return;
        }
    }

    /**
     * Call from a main-thread client-stopping event.
     *
     * <p>Global rule 2 makes this MUST-where-available: an adapter on a platform with no such
     * event, as Forge 1.7.10 has none, simply never calls this and is conformant by omission.
     */
    public void clientStopping() {
        if (!started) {
            return;
        }
        log.info("Continuo stopping: client shutting down");
        core.stop();
    }

    /**
     * Global rule 2's world-unload trigger, stated as one observable condition: the client
     * level instance being replaced or becoming {@code null}. A dimension change replaces it
     * without ending the session and counts.
     */
    private void updateLevel(Object level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;

        log.info("Continuo stopping: client level changed");
        // Not redundant on a world load: in the ordinary case the core was already stopped by
        // the transition to null and this is a no-op, but if that earlier stop() threw, this
        // is what clears the stale state.
        core.stop();
    }
}
