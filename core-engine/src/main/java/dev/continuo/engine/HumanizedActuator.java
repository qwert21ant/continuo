package dev.continuo.engine;

import dev.continuo.core.Yaw;
import dev.continuo.platform.IActuator;
import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Input;

/**
 * The humanizer seam: an {@link IActuator} that bounds how fast the player turns.
 *
 * <p><b>Core-side, and a decorator.</b> In an adapter this would be per-version, duplicated,
 * untestable, and would fail M5's SPI audit on sight — an adapter is translation, not behaviour.
 * Here it is a pure class with a single seam, which is what makes it the place anticheat
 * plausibility gets added later without touching the core or the movements.
 *
 * <p><b>Stateless, and that is the design rather than an economy.</b> It stores no target and
 * accumulates no rotation: each call steps from the player's <em>actual</em> yaw toward the
 * requested one. So a server rotation correction or a nudge of the user's mouse is absorbed on the
 * next tick rather than detected, and there is no remembered value that can go stale.
 *
 * <p><b>This only works because actuation is level-triggered.</b> Global rule 4 obliges a driving
 * core to re-state its desired facing every tick, so this never needs to remember what it was aiming
 * at. A decorator like this under an edge-triggered core would have to keep the target and tick
 * itself.
 *
 * <p><b>Call window — stricter than {@link IActuator}'s.</b> {@link IActuator#setLook} MUST NOT
 * throw outside {@link IGameEvents#onClientTick}'s delivery window, but {@link IPlayerView#yaw()},
 * which this reads, is unspecified there. <b>Call this only inside that window.</b> That is
 * legitimate because it is a core-side type with one caller, the executor, which runs only in
 * {@code PRE} — but it is stated here rather than left as a trap for whoever wraps it next.
 *
 * <p><b>{@link #setInput} is passthrough, deliberately.</b> Jittering presses would contradict rule
 * 4's "state the full desired input set every tick", and the two cannot both be true. Whatever input
 * humanization eventually looks like, it is not a decorator that drops presses.
 */
public final class HumanizedActuator implements IActuator {

    /**
     * The most the player's yaw may change in one tick, in degrees.
     *
     * <p>Thirty, so a 180° reversal takes six ticks — about 0.3 s, slow enough not to be a single
     * frame's teleport and fast enough not to be felt as lag while path-following.
     *
     * <p><b>Extrapolated, not measured.</b> D2's in-game run observes it and this javadoc is
     * rewritten with what was seen, exactly as {@code Run.SLICE_NODES} was after D1.
     */
    public static final float MAX_DEG_PER_TICK = 30.0f;

    private final IActuator delegate;
    private final IPlayerView player;

    /**
     * @param delegate the adapter's actuator, which this wraps; never {@code null}
     * @param player   where the turn starts from, read on every call; never {@code null}
     * @throws IllegalArgumentException if either is null
     */
    public HumanizedActuator(IActuator delegate, IPlayerView player) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        this.delegate = delegate;
        this.player = player;
    }

    /** Passed through untouched. See the class documentation for why. */
    @Override
    public void setInput(Input input, boolean pressed) {
        delegate.setInput(input, pressed);
    }

    /**
     * Turns toward {@code yaw} by at most {@link #MAX_DEG_PER_TICK}, taking the shortest arc.
     *
     * @param yaw   the heading wanted; any finite value
     * @param pitch passed through unchanged, so it MUST already be in {@code [-90, 90]}
     */
    @Override
    public void setLook(float yaw, float pitch) {
        float current = player.yaw();
        float unwrappedDelta = yaw - current;
        float delta = Yaw.wrap(unwrappedDelta);
        float absDelta = Math.abs(delta);
        if (absDelta > MAX_DEG_PER_TICK) {
            delta = (unwrappedDelta >= 0) ? MAX_DEG_PER_TICK : -MAX_DEG_PER_TICK;
        }
        delegate.setLook(current + delta, pitch);
    }
}
