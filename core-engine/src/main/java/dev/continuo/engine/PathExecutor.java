package dev.continuo.engine;

import dev.continuo.core.BlockSource;
import dev.continuo.core.RuntimeLog;
import dev.continuo.core.WorldSnapshot;
import dev.continuo.core.Yaw;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.MovementKind;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Run;
import dev.continuo.pathfinder.SegmentedResult;
import dev.continuo.pathfinder.SegmentedSearch;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a path into per-tick game inputs, and searches for the next one while doing it.
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
 *
 * <p><b>Plan-ahead and repath are different mechanisms, and only one of them overlaps with
 * movement.</b> Plan-ahead fires when the driven path runs out short of the goal: it searches from
 * the path's <em>end</em>, not from the player, and appends the continuation, so the anchor stays
 * valid and the player never breaks stride — inputs keep being written on the very ticks the search
 * runs. Repath fires on off-path or stuck: it searches from the player and replaces the path whole,
 * resetting the anchor, and the executor releases every input once and stands still while it runs,
 * because there is nothing valid left to drive toward.
 */
public final class PathExecutor {

    /**
     * How few steps may remain before the next segment is searched, while still walking.
     *
     * <p>Twenty. A search costs roughly thirteen slices, and at one slice per tick that is thirteen
     * ticks; a step of walking costs about 4.6 ticks at vanilla speed, so three remaining steps
     * would already cover a search. Twenty is margin — about 4.6 s, close to the 7.5 s Baritone
     * uses for the same purpose.
     *
     * <p><b>Extrapolated.</b> It also assumes this executor's searches cost roughly what the
     * probe's do, and they should cost less: the probe grants {@code PARKOUR} and this does not, so
     * its branching factor is lower. That makes twenty conservative in the safe direction, by an
     * unmeasured amount.
     */
    static final int PLAN_AHEAD_STEPS = 20;

    /**
     * How many consecutive driving ticks the anchor may fail to advance before repathing.
     *
     * <p>Forty — two seconds. Measuring path progress rather than position is what makes this
     * immune both to a player shuffling against a block boundary and to a legitimately slow tick,
     * and it is why the executor needs no velocity. <b>Extrapolated.</b>
     */
    static final int STUCK_TICKS = 40;

    /**
     * How many repaths may fail to advance the anchor before the executor gives up.
     *
     * <p>Three. The counter resets on any advance, so a long route that repaths repeatedly while
     * making real progress is not killed by a budget meant for thrash. <b>Extrapolated.</b>
     */
    static final int MAX_CONSECUTIVE_REPATHS = 3;

    private final BlockSource world;
    private final IActuator actuator;
    private final IPlayerView player;
    private final RuntimeLog log;

    /**
     * Built once: {@link AStarPathfinder}'s constructor runs {@code MovementRegistry.discover()},
     * an uncached {@code ServiceLoader} classpath scan measured at ~6 ms, and a search per tick
     * would pay that repeatedly and invisibly.
     */
    private final SegmentedSearch search;

    private PathFollower follower;

    /** Where {@link #walkTo} last aimed; what every search, plan-ahead or repath, searches toward. */
    private Pos goal;

    /** The search in flight, or {@code null} if none is. */
    private Run pendingRun;

    /** Whether {@link #pendingRun} is a plan-ahead (append) rather than a repath (replace). */
    private boolean pendingIsPlanAhead;

    private int stuckTicks;
    private int lastAnchor;
    private int consecutiveRepaths;

    /**
     * @param world    the world searches read; never {@code null}
     * @param actuator the adapter's actuator, wrapped in the humanizer here; never {@code null}
     * @param player   the player the humanizer turns from, and the position a search starts from;
     *                 never {@code null}
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
        this.player = player;
        this.log = log;
        this.search = new SegmentedSearch(new AStarPathfinder());
    }

    /**
     * Begins driving a path, replacing whatever was being driven or searched for.
     *
     * <p>Package-private because a caller outside this package names a destination rather than a
     * route; {@code walkTo} is the public entry.
     *
     * <p><b>A clean slate, regardless of what was in flight.</b> Any pending search is cancelled --
     * without this, a plan-ahead left running past this call would still resolve against the
     * follower this replaces, and {@link PathFollower#append} would throw straight out of
     * {@link #tick}, since its continuation would no longer begin where the new path ends. The
     * stuck and repath counters are reset too, so a path installed here starts exactly as fresh as
     * one adopted from a real search does.
     *
     * @param path  the route, start to end; never empty
     * @param steps its moves
     */
    void follow(List<Pos> path, List<Step> steps) {
        cancelPending();
        follower = new PathFollower(path, steps);
        lastAnchor = 0;
        stuckTicks = 0;
        consecutiveRepaths = 0;
    }

    /**
     * Begins a walk to a destination, searching from wherever the player currently is.
     *
     * <p>Replaces whatever was being driven or searched for. Grants no capabilities — decision 7 —
     * so a search this executor runs never produces a parkour step.
     *
     * @param x target X
     * @param y target Y
     * @param z target Z
     */
    public void walkTo(int x, int y, int z) {
        goal = new Pos(x, y, z);
        consecutiveRepaths = 0;
        cancelPending();
        follower = null;
        beginSearch(floorInt(player.x()), floorInt(player.y()), floorInt(player.z()), false);
    }

    /** @return whether this executor is driving or searching, and therefore owes inputs */
    public boolean active() {
        return follower != null || pendingRun != null;
    }

    /**
     * Releases every input, once, and goes idle.
     *
     * <p>Idempotent, and writes nothing when already idle: global rule 2 has an adapter call the
     * core's {@code stop} on every client level transition, including the ordinary world load where
     * nothing was running, and releasing there would stamp on the user's keyboard.
     *
     * <p><b>Also cancels any pending search.</b> A pending {@link Run} holds a {@link WorldSnapshot}
     * wrapping a live {@link BlockSource}, so it pins a level; cancelling drops that reference. This
     * runs whether or not a path was being driven, since a search can be in flight with no follower
     * at all — the interval between {@link #walkTo} and the first path being found.
     *
     * <p><b>Also clears the goal.</b> A stopped executor is pursuing nothing, so a {@link #goal}
     * left over from before this call would be a value with no operational referent — reachable only
     * by a future {@link #follow} call made without an intervening {@link #walkTo}, which would then
     * silently arm plan-ahead against a destination this call was told to abandon. {@code goal} is
     * therefore non-{@code null} exactly when a {@link #walkTo} has happened since the last
     * {@code stop}.
     */
    public void stop() {
        cancelPending();
        goal = null;
        if (follower == null) {
            return;
        }
        follower = null;
        releaseAll();
    }

    /**
     * Spends one tick: advance a pending search and adopt it if it finished, then re-anchor and
     * drive.
     *
     * <p>The order is load-bearing. A slice is spent on any pending run first, and adopted the
     * moment it finishes — in the same tick, so a plan-ahead's continuation is available to drive
     * without a tick lost to the boundary. Only then, if there is nothing to drive, does this
     * return without writing anything.
     *
     * @param player where the player is now; never {@code null}
     */
    public void tick(IPlayerView player) {
        if (pendingRun != null && pendingRun.advance(Run.SLICE_NODES)) {
            adopt();
        }
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

        int currentAnchor = follower.anchor();
        if (currentAnchor > lastAnchor) {
            stuckTicks = 0;
            consecutiveRepaths = 0;
        } else {
            stuckTicks++;
        }
        lastAnchor = currentAnchor;

        if (stuckTicks >= STUCK_TICKS) {
            log.info("Continuo executor: stuck, repathing");
            beginRepath(player);
            return;
        }

        // Plan-ahead: search from the path's END, not the player, and append rather than
        // replace, so the anchor computed above stays valid and this tick still drives below.
        // Decision 2's whole point is that this overlaps movement instead of preceding it.
        //
        // goal is null whenever the current path was installed through follow() rather than
        // walkTo() -- package-private, and used directly by tests that drive a hand-built path
        // with nothing to search toward. There is no goal to plan ahead against in that case, so
        // this simply does not trigger, rather than searching toward a destination nobody named.
        if (goal != null && pendingRun == null && follower.remaining() <= PLAN_AHEAD_STEPS
                && !follower.last().equals(goal)) {
            Pos last = follower.last();
            beginSearch(last.x(), last.y(), last.z(), true);
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
            // the gap. The stuck detector above bounds the case where it cannot.
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

    /**
     * Adopts a finished pending run: copies its path and steps out, drops the
     * {@link SegmentedResult}, and either appends (plan-ahead) or replaces (everything else).
     *
     * <p><b>The result is never retained.</b> {@code SegmentedResult.expanded()} accumulates across
     * every segment of the run, bounded by roughly the segment cap times the node budget; holding it
     * for the duration of a walk rather than of one search would be strictly worse than the code
     * this branch replaced.
     *
     * <p><b>A {@code NO_PATH} result's prefix is discarded, not walked.</b> {@link SegmentedResult}
     * carries a non-empty {@code path()} on {@code NO_PATH} whenever an earlier segment made real
     * progress before the terminal search failed. This stops without adopting that prefix at all --
     * for a plan-ahead, that discards steps the search genuinely found beyond where the follower
     * already ends, not merely the ones it failed to find. Stopping outright is still correct, since
     * {@code NO_PATH} proves the goal unreachable regardless; the prefix is real information this
     * simply does not use.
     */
    private void adopt() {
        SegmentedResult result = pendingRun.result();
        List<Pos> path = new ArrayList<Pos>(result.path());
        List<Step> steps = new ArrayList<Step>(result.steps());
        PathOutcome outcome = result.outcome();
        boolean planAhead = pendingIsPlanAhead;
        pendingRun = null;
        // `result` falls out of scope here uncopied into any field -- its expanded() list, and
        // the run that produced it, are unreachable from this object from this point on.

        if (outcome == PathOutcome.NO_PATH) {
            // I5/the design's own contract: NO_PATH is the only definitive "stop retrying" signal
            // a search produces. Retrying -- whether as another plan-ahead or another repath --
            // would re-search a goal already proven impossible from this position.
            log.info("Continuo executor: no path to the goal -- stopping rather than retrying a"
                + " goal already proven impossible");
            stop();
            return;
        }
        if (path.isEmpty()) {
            log.info("Continuo executor: search exceeded its budget with nothing to show for it"
                + " -- stopping");
            stop();
            return;
        }
        if (planAhead) {
            follower.append(path, steps);
        } else {
            follower = new PathFollower(path, steps);
            lastAnchor = 0;
            stuckTicks = 0;
        }
    }

    /**
     * Off-path handling: release every input once, cancel any pending plan-ahead -- it was
     * searching from a path end this is about to discard -- drop the follower, and repath from the
     * player.
     */
    private void offPath(IPlayerView player) {
        log.info("Continuo executor: off path, repathing");
        beginRepath(player);
    }

    /**
     * Releases every input once, cancels any pending search, drops the follower, and begins a new
     * search from the player -- unless the repath budget is already spent, in which case this gives
     * up instead of trying again.
     */
    private void beginRepath(IPlayerView player) {
        releaseAll();
        cancelPending();
        follower = null;
        consecutiveRepaths++;
        if (consecutiveRepaths > MAX_CONSECUTIVE_REPATHS) {
            log.info("Continuo executor: giving up after " + MAX_CONSECUTIVE_REPATHS
                + " repaths without progress");
            // Same invariant stop() keeps: goal is non-null only while a walk is actually being
            // pursued, and giving up ends the walk exactly as definitively as stop() does.
            goal = null;
            return;
        }
        beginSearch(floorInt(player.x()), floorInt(player.y()), floorInt(player.z()), false);
    }

    /** Starts a search toward {@link #goal}, wrapping {@link #world} in a fresh snapshot. */
    private void beginSearch(int x, int y, int z, boolean planAhead) {
        WorldSnapshot snapshot = new WorldSnapshot(world);
        pendingRun = search.begin(snapshot, x, y, z,
            new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.none());
        pendingIsPlanAhead = planAhead;
    }

    /**
     * Cancels and drops any pending run, so it releases the {@link WorldSnapshot} it reads.
     *
     * <p>No separate field holds that snapshot: {@link Run} already does, and {@link Run#cancel}
     * nulls its own copy, so a second reference here would only outlive it and pin the level a tick
     * longer than necessary.
     */
    private void cancelPending() {
        if (pendingRun != null) {
            pendingRun.cancel();
            pendingRun = null;
        }
    }

    private static int floorInt(double v) {
        return (int) Math.floor(v);
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

    private void releaseAll() {
        for (Input input : Input.values()) {
            actuator.setInput(input, false);
        }
    }

    /**
     * @return the search in flight, or {@code null} if none is; visible for testing that a search
     *         is spent one slice a tick, and that a repath cancels a pending plan-ahead by reference
     */
    Run pendingRun() {
        return pendingRun;
    }

    /**
     * @return the anchor index of the path currently being driven
     * @throws IllegalStateException if this executor is not driving
     */
    int anchor() {
        if (follower == null) {
            throw new IllegalStateException("not driving");
        }
        return follower.anchor();
    }

    /**
     * @return whether this executor has a path to drive right now, as opposed to merely searching
     *         with no follower yet (or any longer) -- visible so a test can tell the two {@code
     *         active()} states apart with a clear message instead of {@link #anchor}'s exception
     */
    boolean isDriving() {
        return follower != null;
    }
}
