package dev.continuo.engine;

import dev.continuo.core.BlockSource;
import dev.continuo.core.RuntimeLog;
import dev.continuo.core.Yaw;
import dev.continuo.movement.MovementKind;
import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Input;

import java.util.List;

/**
 * Turns a path into per-tick game inputs.
 *
 * <p><b>A function of the path and the player's current state, not a state machine.</b> Every tick
 * it recomputes where the player is on the path and writes the full input set that follows from
 * that, having remembered nothing about what it wrote last tick. Global rule 4 chose level-triggered
 * actuation specifically to make this shape possible: because the core re-states everything each
 * tick, a screen opening — which clears held key state on both target versions — costs one lost tick
 * and self-heals, instead of silently truncating a walk.
 *
 * <p><b>Driven from {@code PRE}.</b> {@link IActuator#setInput} and {@link IActuator#setLook} take
 * effect at the game's next input read, which is the current tick when called from
 * {@code TickPhase.PRE}.
 *
 * <p><b>While idle it writes nothing at all</b> — not even a release. Rule 4's second clause: a core
 * holding every input at {@code false} every tick would fight the user's own keyboard whenever the
 * bot is not running.
 */
public final class PathExecutor {

    private final BlockSource world;
    private final IActuator actuator;
    private final RuntimeLog log;

    private PathFollower follower;

    /**
     * @param world    the world searches read; never {@code null}
     * @param actuator the adapter's actuator, wrapped in the humanizer here; never {@code null}
     * @param player   the player the humanizer turns from; never {@code null}
     * @param log      where a termination is explained; never {@code null}
     * @throws IllegalArgumentException if any argument is null
     */
    public PathExecutor(BlockSource world, IActuator actuator, IPlayerView player, RuntimeLog log) {
        if (world == null || actuator == null || player == null || log == null) {
            throw new IllegalArgumentException("no constructor argument may be null");
        }
        this.world = world;
        // The humanizer reads yaw from THIS player -- the constructor's -- on every setLook call,
        // while drive() below reads position from tick(IPlayerView)'s own argument. Identical
        // today because the one caller passes the same instance both ways; a future caller handing
        // tick a different IPlayerView would make the humanizer turn from a stale yaw while drive
        // steers from a live position.
        this.actuator = new HumanizedActuator(actuator, player);
        this.log = log;
    }

    /**
     * Begins driving a path, replacing whatever was being driven.
     *
     * <p>Package-private because a caller outside this package names a destination rather than a
     * route; {@code walkTo} is the public entry.
     *
     * @param path  the route, start to end; never empty
     * @param steps its moves
     */
    void follow(List<Pos> path, List<Step> steps) {
        follower = new PathFollower(path, steps);
    }

    /** @return whether this executor is driving or searching, and therefore owes inputs */
    public boolean active() {
        return follower != null;
    }

    /**
     * Releases every input, once, and goes idle.
     *
     * <p>Idempotent, and writes nothing when already idle: global rule 2 has an adapter call the
     * core's {@code stop} on every client level transition, including the ordinary world load where
     * nothing was running, and releasing there would stamp on the user's keyboard.
     */
    public void stop() {
        if (follower == null) {
            return;
        }
        follower = null;
        releaseAll();
    }

    /**
     * Spends one tick: re-anchor, then drive.
     *
     * @param player where the player is now; never {@code null}
     */
    public void tick(IPlayerView player) {
        if (follower == null) {
            return;
        }
        if (!follower.reanchor(player)) {
            offPath(player);
            return;
        }
        if (follower.arrived(player)) {
            log.info("Continuo executor: arrived");
            stop();
            return;
        }
        Step step = follower.current();
        if (step == null) {
            // The anchor is the final node -- current() returns null past the last step -- but
            // arrived() said no. This is a real state, not a bug in the follower: reanchor's
            // window (OFF_PATH_RADIUS, 2.0 blocks) is wider than arrived's tolerance
            // (ARRIVE_RADIUS, 0.5 blocks), so there is an annulus around the last node where the
            // player is close enough to anchor there but not close enough to have arrived.
            //
            // Treating "no step left" as arrival would let the bot declare success up to two
            // blocks short of a goal the search chose as one exact block, so instead this drives
            // straight at the final node and lets arrived() fire once the player actually closes
            // the gap. Task 8's stuck detector bounds the case where it cannot.
            driveToward(follower.last(), player);
            return;
        }
        MovementKind kind = step.kind();
        if (kind == null || kind == MovementKind.PARKOUR) {
            String reason = kind == null
                ? "a step whose movement cannot be named"
                : "a parkour step, which this executor does not grant or execute";
            log.info("Continuo executor: stopping at " + step.to() + " -- " + reason);
            stop();
            return;
        }
        drive(kind, step, player);
    }

    /** Writes the whole input set and the desired facing for one step. */
    private void drive(MovementKind kind, Step step, IPlayerView player) {
        // Only ASCEND jumps, and only from the ground. Both versions gate the jump on their own
        // ground flag and arm a ten-tick cooldown that is cleared the instant the key is released
        // (1.7.10 EntityLivingBase.onLivingUpdate:1998-2016; 1.21.11 LivingEntity.aiStep:2926-2950),
        // so holding JUMP through a staircase throttles it to one block per ten ticks. Conditioning
        // on onGround() produces the release that clears the cooldown, without remembering anything.
        boolean jump = kind == MovementKind.ASCEND && player.onGround();
        actuate(jump, step.to(), player);
    }

    /**
     * Closes the last stretch of a path once there is no further step to drive.
     *
     * <p>Never jumps: the follower is already at the final node's row, and this exists only to
     * cover the gap {@link PathFollower#arrived} left open, not to climb anything.
     */
    private void driveToward(Pos target, IPlayerView player) {
        actuate(false, target, player);
    }

    /** Writes the whole input set and the desired facing toward one target position. */
    private void actuate(boolean jump, Pos target, IPlayerView player) {
        // The full set, every tick, per global rule 4. Writing only what changed would leave a
        // JUMP held from a finished ascend, and would rely on a previous setInput persisting --
        // which rule 4 says a core may not do.
        actuator.setInput(Input.FORWARD, true);
        actuator.setInput(Input.JUMP, jump);
        actuator.setInput(Input.BACK, false);
        actuator.setInput(Input.LEFT, false);
        actuator.setInput(Input.RIGHT, false);
        actuator.setInput(Input.SNEAK, false);
        actuator.setInput(Input.SPRINT, false);

        // A diagonal is walked by facing its destination and holding FORWARD, which is why LEFT
        // and RIGHT are never pressed.
        actuator.setLook(Yaw.toward(player.x(), player.z(), target.x(), target.z()),
            player.pitch());
    }

    private void offPath(IPlayerView player) {
        log.info("Continuo executor: off path, stopping");
        stop();
    }

    private void releaseAll() {
        for (Input input : Input.values()) {
            actuator.setInput(input, false);
        }
    }
}
