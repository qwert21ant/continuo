package dev.continuo.engine;

import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.IPlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the player is on a path, recomputed every tick from the player's actual position.
 *
 * <p><b>The anchor is a hint, not an authority.</b> It is one {@code int}, and every tick it is
 * recomputed by scanning the nodes within {@link #W} of it for the one nearest the player. Nothing
 * here remembers what the player did; it only ever looks at where the player now is.
 *
 * <p>That is what replaced {@code onPositionCorrection}, which D1 §3.5 dissolved rather than
 * deferred. Knockback moves the player back a step and the anchor follows with no special case; a
 * teleport moves the player outside the window and {@link #reanchor} reports off-path, which is a
 * repath. Neither is a state this class tracks. <b>The window bound is not an optimisation</b> — an
 * unbounded nearest scan anchors to whichever node happens to be closest, and on a spiral staircase
 * or a switchback that is a node a whole revolution away, which skips the loop and drives the player
 * into a wall.
 */
final class PathFollower {

    /**
     * How many steps either side of the last anchor are scanned.
     *
     * <p>Six. A walking player covers roughly 0.22 blocks per tick, so even ten dropped ticks move
     * it under three steps — the window only has to be wider than one tick's travel plus slack. It
     * also has to be <em>narrower</em> than the shortest distance along the path at which a route
     * comes back near itself, and a 3×3 spiral staircase revolves in roughly 8–12 steps.
     *
     * <p><b>Extrapolated, and the revolution length is reasoned rather than counted.</b> If a real
     * staircase revolves in six, this is already too large. D2's in-game run raises it past a real
     * revolution deliberately, to confirm the skip it is here to prevent.
     */
    static final int W = 6;

    /**
     * How far from the nearest windowed node the player may be before it counts as off path, in
     * blocks.
     *
     * <p>Two: wider than a knockback displaces, narrower than a teleport. <b>Extrapolated.</b>
     */
    static final double OFF_PATH_RADIUS = 2.0;

    /**
     * How close to the final node counts as arrived, in blocks.
     *
     * <p>Half a block — the tolerance around a block centre. <b>Extrapolated.</b>
     */
    static final double ARRIVE_RADIUS = 0.5;

    private final List<Pos> path;
    private final List<Step> steps;

    private int anchor;

    /**
     * @param path  the route, start to end; never {@code null} and never empty
     * @param steps its moves, exactly one fewer than {@code path} has entries
     * @throws IllegalArgumentException if the two do not describe the same route
     */
    PathFollower(List<Pos> path, List<Step> steps) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        if (steps == null || steps.size() != path.size() - 1) {
            throw new IllegalArgumentException("steps must have exactly one fewer entry than path;"
                + " got " + (steps == null ? "null" : String.valueOf(steps.size()))
                + " for a path of " + path.size());
        }
        this.path = new ArrayList<Pos>(path);
        this.steps = new ArrayList<Step>(steps);
    }

    /**
     * Recomputes the anchor from the player's current position.
     *
     * @param player where the player is now; never {@code null}
     * @return {@code true} if the player is still on the path, {@code false} if it is off it and
     *         the caller should repath
     */
    boolean reanchor(IPlayerView player) {
        int from = anchor - W;
        if (from < 0) {
            from = 0;
        }
        int to = anchor + W;
        if (to > path.size() - 1) {
            to = path.size() - 1;
        }

        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            double distance = distanceTo(path.get(i), player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0 || bestDistance > OFF_PATH_RADIUS * OFF_PATH_RADIUS) {
            return false;
        }
        anchor = best;
        return true;
    }

    /**
     * @param player where the player is now; never {@code null}
     * @return whether the anchor is the final node and the player is within {@link #ARRIVE_RADIUS}
     *         of it
     */
    boolean arrived(IPlayerView player) {
        return anchor == path.size() - 1
            && distanceTo(path.get(anchor), player) <= ARRIVE_RADIUS * ARRIVE_RADIUS;
    }

    /** @return the index of the node the player is currently nearest */
    int anchor() {
        return anchor;
    }

    /** @return how many steps of path are left ahead of the anchor */
    int remaining() {
        return path.size() - 1 - anchor;
    }

    /** @return the step leaving the anchor, or {@code null} when the anchor is the last node */
    Step current() {
        return anchor < steps.size() ? steps.get(anchor) : null;
    }

    /** @return where this path ends */
    Pos last() {
        return path.get(path.size() - 1);
    }

    /**
     * Extends this path with a continuation searched from {@link #last()}.
     *
     * <p><b>The anchor is untouched</b>, which is the point: the list only grew at its far end, so
     * the index still names the same node and the player does not break stride. Plan-ahead adoption
     * is an append for exactly this reason, and a repath — which cannot preserve the anchor — builds
     * a new follower instead.
     *
     * @param more      the continuation, beginning at this path's last node
     * @param moreSteps its moves
     */
    void append(List<Pos> more, List<Step> moreSteps) {
        if (more.isEmpty()) {
            return;
        }
        // The continuation is searched from this path's end, so its first position repeats that
        // node and is dropped -- the same join Run.append makes between segments, for the same
        // reason: appending whole would repeat a position and derive as a null-kind step.
        //
        // Required rather than tolerated. If the continuation began somewhere else, dropping
        // nothing would grow the path by one more entry than the steps, breaking this class's
        // invariant silently; and joining across the discontinuity would invent a move no search
        // ever offered.
        if (!more.get(0).equals(last())) {
            throw new IllegalArgumentException("a continuation must begin at this path's last node "
                + last() + ", but began at " + more.get(0));
        }
        for (int i = 1; i < more.size(); i++) {
            path.add(more.get(i));
        }
        steps.addAll(moreSteps);
    }

    /** Squared 3D distance from a node's standing position to the player. */
    private static double distanceTo(Pos node, IPlayerView player) {
        double dx = (node.x() + 0.5) - player.x();
        // Feet to feet: a node's y is the block the feet occupy, which is what IPlayerView.y()
        // returns after D1. Comparing to a block centre here would be a half-block error on every
        // node.
        double dy = node.y() - player.y();
        double dz = (node.z() + 0.5) - player.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
