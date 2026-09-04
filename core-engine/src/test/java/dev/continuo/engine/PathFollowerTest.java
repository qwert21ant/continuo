package dev.continuo.engine;

import dev.continuo.pathfinder.PathResults;
import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFollowerTest {

    private final FakePlayerView player = new FakePlayerView();

    /** A straight run of {@code length} traverse steps east along z=0 at y=64, from x=0. */
    private static List<Pos> straightRunPath(int length) {
        List<Pos> path = new ArrayList<Pos>();
        for (int x = 0; x <= length; x++) {
            path.add(new Pos(x, 64, 0));
        }
        return path;
    }

    private static PathFollower straightRun(int length) {
        return follower(straightRunPath(length));
    }

    private static PathFollower follower(List<Pos> path) {
        return new PathFollower(path, PathResults.stepsOf(path));
    }

    /** Puts the player at the centre of a block, on the ground. */
    private void standOn(int x, int y, int z) {
        player.set(x + 0.5, y, z + 0.5, 0.0f, 0.0f, true);
    }

    /** Walks the anchor forward one node at a time, as the executor would while the player moves. */
    private void walkAnchorTo(PathFollower follower, List<Pos> path, int index) {
        for (int i = 0; i <= index; i++) {
            standOn(path.get(i).x(), path.get(i).y(), path.get(i).z());
            assertTrue(follower.reanchor(player),
                "walking the anchor must stay on the path at node " + i);
        }
    }

    @Test
    void anchorsToTheNodeThePlayerIsStandingOn() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);

        assertTrue(follower.reanchor(player));
        assertEquals(5, follower.anchor());
    }

    @Test
    void theCurrentStepIsTheOneLeavingTheAnchor() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertEquals(new Pos(6, 64, 0), follower.current().to(),
            "the step being driven goes to the node after the anchor");
    }

    @Test
    void aKnockbackInsideTheWindowIsAbsorbed() {
        // The player is shoved three blocks back. No repath: the anchor simply follows, which is
        // the whole of what replaced onPositionCorrection for this case.
        List<Pos> path = straightRunPath(20);
        PathFollower follower = follower(path);
        // A fresh follower's anchor starts at 0 -- a path is always searched from the player, so
        // reaching node 10 means walking there first, one node at a time.
        walkAnchorTo(follower, path, 10);

        standOn(7, 64, 0);

        assertTrue(follower.reanchor(player), "still on the path");
        assertEquals(7, follower.anchor());
    }

    @Test
    void aTeleportOutsideTheWindowIsOffPath() {
        PathFollower follower = straightRun(20);
        standOn(2, 64, 0);
        follower.reanchor(player);

        standOn(19, 64, 0);

        assertFalse(follower.reanchor(player),
            "a node 17 steps ahead is outside the window, so this is a teleport, not progress");
    }

    @Test
    void beingNearNoNodeAtAllIsOffPath() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        // Same index range, but forty blocks sideways.
        player.set(5.5, 64.0, 40.5, 0.0f, 0.0f, true);

        assertFalse(follower.reanchor(player));
    }

    @Test
    void aPlayerDriftedOffTheBlockCentreIsStillOnPath() {
        // The deciding comparison is between a SQUARED distance and a squared radius. Drifted 1.5
        // blocks sideways, the squared distance is 2.25 against a squared radius of 4.0, so this is
        // on path. Comparing the same squared distance against the unsquared 2.0 would call it off
        // path and repath a player who is walking normally. Every other test stands exactly on a
        // block centre, where 0 compares equal either way, so this is the only case that can tell.
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        assertTrue(follower.reanchor(player));

        player.set(5.5, 64.0, 0.5 + 1.5, 0.0f, 0.0f, true);

        assertTrue(follower.reanchor(player),
            "1.5 blocks off the centre line is within OFF_PATH_RADIUS of 2.0");
    }

    @Test
    void theWindowStopsASelfCrossingRouteFromSnappingBackToTheFirstPass() {
        // A route that walks out, turns, and comes back along a parallel row so that its very
        // last node lands on the same block as its second node. Every real multi-segment search
        // can produce this: each segment is searched fresh from the end of the one before it and
        // may cross ground the earlier segment already covered.
        //
        // Nodes 0-10: (x, 64, 0) for x = 0..10 -- walk east.
        // Node 11: (10, 64, 1) -- step north.
        // Nodes 12-20: (x, 64, 1) for x = 9 down to 1 -- walk west, one row over.
        // Node 21: (1, 64, 0) -- step south, landing on the same block as node 1.
        //
        // Under the shipped windowed scan, W = 6 from anchor 20 gives a window of [14, 21], which
        // contains node 21 and excludes node 1 -- the anchor lands on 21, correctly. Under an
        // unbounded scan, nodes 1 and 21 are both at distance 0 from the player and the strict
        // `distance < bestDistance` comparison keeps the first one found, so the anchor would snap
        // back to node 1 -- a jump of nineteen steps backwards that sends the executor back around
        // the loop it just finished walking, forever. That is a decisive failure, not a cosmetic
        // one, and it is exactly the case the window exists to rule out.
        List<Pos> path = new ArrayList<Pos>();
        for (int x = 0; x <= 10; x++) {
            path.add(new Pos(x, 64, 0));
        }
        path.add(new Pos(10, 64, 1));
        for (int x = 9; x >= 1; x--) {
            path.add(new Pos(x, 64, 1));
        }
        path.add(new Pos(1, 64, 0));

        PathFollower follower = follower(path);

        for (int i = 0; i <= 20; i++) {
            Pos node = path.get(i);
            standOn(node.x(), node.y(), node.z());
            assertTrue(follower.reanchor(player));
        }
        assertEquals(20, follower.anchor());

        standOn(1, 64, 0);
        assertTrue(follower.reanchor(player), "still on the path -- node 21 is the same block");
        assertEquals(21, follower.anchor(),
            "the window must pick the node ahead (21), not the identical one nineteen steps back (1)");
    }

    @Test
    void arrivalIsTheLastNodeAndNotMerelyTheLastStep() {
        PathFollower follower = straightRun(3);
        standOn(3, 64, 0);

        assertTrue(follower.reanchor(player));
        assertTrue(follower.arrived(player));
    }

    @Test
    void arrivalMeasuresYFeetToFeetWithNoBlockCentreOffset() {
        // A node's Y is the block the feet occupy, which is exactly what IPlayerView.y() returns,
        // so distanceTo must offset X and Z to the block centre but NOT Y. With the player standing
        // at the node's own Y and 0.4 off in X, the true squared distance is 0.16, inside
        // ARRIVE_RADIUS squared of 0.25. A spurious +0.5 on dy makes it 0.41 and arrival never
        // fires -- a bot that walks to its goal and refuses to stop.
        PathFollower follower = straightRun(3);
        player.set(3.5 + 0.4, 64.0, 0.5, 0.0f, 0.0f, true);

        assertTrue(follower.reanchor(player));
        assertTrue(follower.arrived(player), "0.4 off in X only is within ARRIVE_RADIUS");
    }

    @Test
    void beingShortOfTheFinalNodeIsNotArrival() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertFalse(follower.arrived(player), "fifteen steps of path remain");
    }

    @Test
    void remainingCountsTheStepsLeft() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertEquals(15, follower.remaining());
    }

    @Test
    void appendExtendsThePathAndLeavesTheAnchorAlone() {
        // Plan-ahead adoption. The player must not so much as break stride, so the anchor -- an
        // index into a list that only grew at its far end -- has to survive untouched.
        List<Pos> path = straightRunPath(20);
        PathFollower follower = follower(path);
        // A fresh follower's anchor starts at 0 -- a path is always searched from the player, so
        // reaching node 18 means walking there first, one node at a time.
        walkAnchorTo(follower, path, 18);
        int before = follower.anchor();

        List<Pos> more = new ArrayList<Pos>();
        more.add(new Pos(20, 64, 0));
        more.add(new Pos(21, 64, 0));
        more.add(new Pos(22, 64, 0));
        follower.append(more, PathResults.stepsOf(more));

        assertEquals(before, follower.anchor());
        assertEquals(4, follower.remaining());
        assertEquals(new Pos(22, 64, 0), follower.last());
    }

    @Test
    void appendRefusesAContinuationThatDoesNotBeginWhereThisPathEnds() {
        // Plan-ahead always searches from the path's end, so this cannot happen -- and if it ever
        // did, silently accepting it would grow the path by one more entry than the steps and
        // break this class's invariant with no visible symptom until the executor drove a step
        // that names the wrong node.
        final PathFollower follower = straightRun(5);
        final List<Pos> disconnected = new ArrayList<Pos>();
        disconnected.add(new Pos(40, 64, 0));
        disconnected.add(new Pos(41, 64, 0));

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                follower.append(disconnected, PathResults.stepsOf(disconnected));
            }
        });
    }

    @Test
    void appendRefusesAContinuationWhoseStepCountDoesNotMatch() {
        // The constructor already guards this invariant on construction; append is the only other
        // way into the object, so it must guard the invariant too rather than trust the caller.
        final PathFollower follower = straightRun(5);
        final List<Pos> more = new ArrayList<Pos>();
        more.add(new Pos(5, 64, 0));
        more.add(new Pos(6, 64, 0));
        more.add(new Pos(7, 64, 0));
        // Three positions need two steps; hand it only one.
        final List<Step> tooFewSteps = PathResults.stepsOf(more).subList(0, 1);

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                follower.append(more, tooFewSteps);
            }
        });
    }
}
