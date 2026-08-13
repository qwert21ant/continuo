package dev.continuo.runtime;

import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.TickPhase;

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
     * Set when a core call throws, per global rule 3. While set, no ticks are delivered.
     * Cleared on the next world load.
     */
    private boolean faulted;

    /**
     * Set when {@code PRE} is delivered for the current tick; cleared the moment
     * {@link #tickEnd} next runs, whether or not it goes on to deliver {@code POST}. The
     * window and the fault state are re-read independently by each phase, so either can change
     * between the two halves of one tick — a mid-tick dimension change, or a disconnect
     * processed inside the game's own tick. This latch is what stops {@code POST} from ever
     * firing without a same-tick {@code PRE}. It cannot wedge across ticks: it is
     * unconditionally cleared on every {@link #tickEnd} call, so a {@code PRE} that loses its
     * {@code POST} mid-tick never leaves the latch set for the next one.
     */
    private boolean preDelivered;

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
        if (!inWorld(level, player)) {
            return;
        }
        if (faulted) {
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                core.onClientTick(TickPhase.PRE);
                preDelivered = true;
            }
        });
    }

    /** Call from the game's tick-end hook. */
    public void tickEnd(Object level, Object player) {
        if (!started) {
            return;
        }
        boolean deliverPost = preDelivered;
        preDelivered = false;
        if (!deliverPost || !inWorld(level, player) || faulted) {
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                core.onClientTick(TickPhase.POST);
            }
        });
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
        guarded(new Runnable() {
            @Override
            public void run() {
                core.stop();
            }
        });
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

        // Clear the fault BEFORE stopping, never after. If stop() throws, guarded() sets
        // faulted again and it must stay set — clearing afterwards would let the fault handler
        // swallow its own fault, which rule 3 forbids.
        if (level != null && faulted) {
            log.info("Continuo fault cleared by world load");
            faulted = false;
        }

        log.info("Continuo stopping: client level changed");
        // Not redundant on a world load: in the ordinary case the core was already stopped by
        // the transition to null and this is a no-op, but if that earlier stop() threw, this
        // is what clears the stale state.
        guarded(new Runnable() {
            @Override
            public void run() {
                core.stop();
            }
        });
    }

    /**
     * The tick window from {@code IGameEvents.onClientTick}: ticks are delivered only while a
     * world is loaded and a local player exists. Global rule 2 is lifecycle only and does not
     * state this window.
     *
     * <p>It lives here rather than in each adapter so that two conformant adapters cannot
     * drift on it — the same reason global rule 2's unload trigger is stated as one observable
     * condition.
     */
    private static boolean inWorld(Object level, Object player) {
        return level != null && player != null;
    }

    /**
     * Runs a core call under global rule 3. Nothing the core throws reaches the game's tick
     * loop, the core is stopped so it cannot leave a movement key held, and no further ticks
     * are delivered until the next world load.
     */
    private void guarded(Runnable coreCall) {
        try {
            coreCall.run();
        } catch (Throwable thrown) {
            // Set before stopping: if stop() throws too, the faulted state must still hold.
            faulted = true;
            log.error("Continuo core faulted; no further ticks until the next world load", thrown);
            try {
                core.stop();
            } catch (Throwable stopFailure) {
                log.error("Continuo core.stop() also failed while handling a fault", stopFailure);
            }
        }
    }
}
