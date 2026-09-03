package dev.continuo.engine;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathResults;
import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Run;
import dev.continuo.pathfinder.SegmentedResult;
import dev.continuo.pathfinder.SegmentedSearch;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.Input;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The searching half of {@link PathExecutor}: plan-ahead, repath, stuck, and termination.
 *
 * <p>Two fixtures. {@link FlatCorridor} is a short, cheap, single-segment route -- its own search
 * settles in one slice -- used everywhere the exact search cost does not matter. {@link MazeRoom} is
 * engineered (not measured) to force more than {@code Run.SLICE_NODES} expansions before a route is
 * found, which is what the "sliced rather than all at once" and search/drive overlap tests need to
 * pin anything at all: a route a single slice can finish proves nothing about slicing.
 */
class PathExecutorSearchTest {

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /**
     * A flat stone floor, two clear layers above, six blocks wide so an off-path teleport has a
     * real lane to land in. Bounded by {@code UNKNOWN} outside its footprint.
     */
    private static final class FlatCorridor implements BlockSource {
        static final int FLOOR_Y = 64;
        static final int LENGTH = 60;
        static final int WIDTH = 6;

        @Override
        public BlockData at(int x, int y, int z) {
            if (x < 0 || x > LENGTH || z < 0 || z > WIDTH) {
                return BlockData.UNKNOWN;
            }
            if (y == FLOOR_Y) {
                return STONE;
            }
            if (y == FLOOR_Y + 1 || y == FLOOR_Y + 2) {
                return AIR;
            }
            return BlockData.UNKNOWN;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 256;
        }
    }

    /**
     * A long room broken by periodic full walls, each with one gap, alternating ends, so the search
     * frontier has to hunt across the room's width to find every one of them -- measured at 6,420
     * expanded nodes for its 613-node route, more than three times {@code Run.SLICE_NODES}.
     */
    private static final class MazeRoom implements BlockSource {
        static final int FLOOR_Y = 64;
        static final int LENGTH = 80;
        static final int WIDTH = 100;
        static final int NUM_WALLS = 6;

        private int wallIndex(int x) {
            for (int i = 1; i <= NUM_WALLS; i++) {
                int wx = (LENGTH * i) / (NUM_WALLS + 1);
                if (x == wx) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public BlockData at(int x, int y, int z) {
            if (x < 0 || x > LENGTH || z < 0 || z > WIDTH) {
                return BlockData.UNKNOWN;
            }
            int idx = wallIndex(x);
            if (idx >= 0) {
                int gapZ = (idx % 2 == 0) ? WIDTH : 0;
                if (z != gapZ) {
                    return BlockData.UNKNOWN;
                }
            }
            if (y == FLOOR_Y) {
                return STONE;
            }
            if (y == FLOOR_Y + 1 || y == FLOOR_Y + 2) {
                return AIR;
            }
            return BlockData.UNKNOWN;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 256;
        }
    }

    /** Bundles one executor with the fakes that drive it, so each test can build its own world. */
    private static final class Harness {
        final FakeActuator actuator = new FakeActuator();
        final FakePlayerView player = new FakePlayerView();
        final RecordingLog log = new RecordingLog();
        final PathExecutor executor;

        Harness(BlockSource world) {
            executor = new PathExecutor(world, actuator, player, log);
        }
    }

    private static void standOn(Harness h, int x, int y, int z, boolean onGround) {
        h.player.set(x + 0.5, y, z + 0.5, h.player.yaw(), 0.0f, onGround);
    }

    /** The last state each input was written to on the current actuator recording. */
    private static Map<Input, Boolean> written(Harness h) {
        Map<Input, Boolean> state = new EnumMap<Input, Boolean>(Input.class);
        for (FakeActuator.Call call : h.actuator.calls()) {
            state.put(call.input, Boolean.valueOf(call.pressed));
        }
        return state;
    }

    /** Ticks (without moving the player) until the initial search settles one way or another. */
    private static void tickUntilSearchSettles(Harness h, int maxTicks) {
        int ticks = 0;
        while (h.executor.pendingRun() != null) {
            h.executor.tick(h.player);
            ticks++;
            assertTrue(ticks <= maxTicks,
                "the search did not settle within " + maxTicks + " ticks -- fixture assumption"
                    + " broke");
        }
    }

    private static SegmentedResult fullMazeSearch(MazeRoom world) {
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        return new SegmentedSearch(new AStarPathfinder())
            .run(world, 0, y, z, new GoalBlock(MazeRoom.LENGTH, y, z), CapabilitySet.none());
    }

    /**
     * Whether an instance of {@code target} is reachable from {@code root} by walking its
     * (non-{@code java.*}) reachable field graph.
     */
    private static boolean retainsA(Object root, Class<?> target) {
        return retainsA(root, target, new IdentityHashMap<Object, Boolean>(), 0);
    }

    private static boolean retainsA(Object obj, Class<?> target,
                                    IdentityHashMap<Object, Boolean> visited, int depth) {
        if (obj == null || depth > 10) {
            return false;
        }
        if (visited.containsKey(obj)) {
            return false;
        }
        visited.put(obj, Boolean.TRUE);
        if (target.isInstance(obj)) {
            return true;
        }
        Class<?> c = obj.getClass();
        if (c.getName().startsWith("java.") || c.getName().startsWith("javax.") || c.isEnum()) {
            return false;
        }
        if (c.isArray()) {
            if (c.getComponentType().isPrimitive()) {
                return false;
            }
            Object[] array = (Object[]) obj;
            for (Object element : array) {
                if (retainsA(element, target, visited, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        Class<?> current = c;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(obj);
                } catch (IllegalAccessException e) {
                    continue;
                }
                if (retainsA(value, target, visited, depth + 1)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    @Test
    void walkToStandsStillUntilItHasAPathAndThenDrives() {
        // While searching, the executor is not driving, so rule 4's idle clause applies: it
        // writes nothing at all. It is still active(), because it owes inputs shortly.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        standOn(h, 0, y, z, true);

        h.executor.walkTo(MazeRoom.LENGTH, y, z);
        assertTrue(h.executor.active(), "a search is in flight, so inputs are owed shortly");
        Run run = h.executor.pendingRun();
        assertNotNull(run, "walkTo must start a search synchronously");

        int ticks = 0;
        while (!run.finished()) {
            h.actuator.clear();
            h.executor.tick(h.player);
            ticks++;
            if (!run.finished()) {
                assertEquals(0, h.actuator.callCount(),
                    "nothing is driven while the search is still in flight");
            }
            assertTrue(ticks < 50, "fixture assumption broke -- the search never finished");
        }
        assertTrue(ticks > 1,
            "fixture assumption: this route must need more than one tick to search");
        assertEquals(Boolean.TRUE, written(h).get(Input.FORWARD),
            "the same tick the search finishes must also drive -- callCount() alone would also"
                + " accept a release");
        assertTrue(h.executor.active());
    }

    @Test
    void aSearchIsSpentOneSliceATickRatherThanAllAtOnce() {
        // The property C5 exists for. Assert that a walkTo over a long route needs more than one
        // tick before it drives, and that no tick expands more than Run.SLICE_NODES nodes.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        standOn(h, 0, y, z, true);

        h.executor.walkTo(MazeRoom.LENGTH, y, z);
        Run run = h.executor.pendingRun();
        assertNotNull(run);

        int ticks = 0;
        int previousExpanded = run.expandedCount();
        assertEquals(0, previousExpanded, "begin() must not have expanded anything yet");
        while (!run.finished()) {
            h.executor.tick(h.player);
            ticks++;
            int expandedNow = run.expandedCount();
            int delta = expandedNow - previousExpanded;
            assertTrue(delta <= Run.SLICE_NODES,
                "tick " + ticks + " expanded " + delta + " nodes -- more than one slice");
            previousExpanded = expandedNow;
            assertTrue(ticks < 50, "fixture assumption broke -- the search never finished");
        }
        assertTrue(ticks > 1,
            "fixture assumption: this route must need more than one slice to finish");
    }

    @Test
    void planAheadStartsBeforeThePathRunsOutAndInputsKeepBeingWritten() {
        // Decision 2, and BOTH clauses matter. Drive a path whose end is not the goal until
        // remaining() drops to PLAN_AHEAD_STEPS, then assert a search is in flight AND that the
        // same ticks still wrote a full input set. Without the second assertion this test passes
        // on a sequential implementation and decision 2 goes unenforced.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        List<Pos> prefix = shortPrefixTowardMazeGoal(world, h, y, z);

        int triggerAnchor = prefix.size() - 1 - PathExecutor.PLAN_AHEAD_STEPS;
        for (int i = 0; i <= triggerAnchor; i++) {
            Pos node = prefix.get(i);
            standOn(h, node.x(), node.y(), node.z(), true);
            h.actuator.clear();
            h.executor.tick(h.player);
            if (i < triggerAnchor) {
                assertNull(h.executor.pendingRun(),
                    "plan-ahead must not start before " + PathExecutor.PLAN_AHEAD_STEPS
                        + " steps remain -- tick " + i);
            }
        }

        assertNotNull(h.executor.pendingRun(),
            "plan-ahead must have started once " + PathExecutor.PLAN_AHEAD_STEPS
                + " steps remained");
        assertEquals(Input.values().length, h.actuator.callCount(),
            "the same tick that starts a plan-ahead search must still drive -- decision 2");
        assertEquals(Boolean.TRUE, written(h).get(Input.FORWARD),
            "and it must be a real drive, not a release");
    }

    @Test
    void planAheadAdoptionAppendsAndLeavesTheAnchorAlone() {
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        List<Pos> prefix = shortPrefixTowardMazeGoal(world, h, y, z);

        // What the plan-ahead search will deterministically find, computed independently so
        // driving can continue past the prefix once the follower has actually grown to include
        // it. This need NOT retrace the original full search's own suffix node for node -- A* is
        // optimal, not unique, and a fresh search from a different start is free to break a tie
        // the other way; that is exactly what this fixture does starting at index 26 (a diagonal
        // climb in X instead of holding X while Z falls), discovered by comparing the two.
        Pos last = prefix.get(prefix.size() - 1);
        SegmentedResult tail = new SegmentedSearch(new AStarPathfinder())
            .run(world, last.x(), last.y(), last.z(), new GoalBlock(MazeRoom.LENGTH, y, z),
                CapabilitySet.none());
        assertEquals(PathOutcome.FOUND, tail.outcome(),
            "fixture assumption: the tail must be reachable");

        List<Pos> wholeRoute = new ArrayList<Pos>(prefix);
        for (int k = 1; k < tail.path().size(); k++) {
            wholeRoute.add(tail.path().get(k));
        }

        boolean sawPendingRun = false;
        int previousAnchor = -1;
        int i = 0;
        while (h.executor.active()) {
            // Bounds-checked before indexing, and the "still actually driving" check runs before
            // anchor() -- a replace-instead-of-append mutation derails the follower (an
            // unexpected off-path, or simply running out of the route this test knows about)
            // well before either guard message below would otherwise fire, and a raw
            // IndexOutOfBoundsException or anchor()'s IllegalStateException reads as a crash, not
            // as the intended finding.
            assertTrue(i < wholeRoute.size() + 20,
                "fixture assumption broke -- driving never reached the real goal, at tick " + i);
            Pos node = wholeRoute.get(i);
            standOn(h, node.x(), node.y(), node.z(), true);
            h.executor.tick(h.player);
            if (h.executor.pendingRun() != null) {
                sawPendingRun = true;
            }
            if (h.executor.active()) {
                assertTrue(h.executor.isDriving(),
                    "an append gone wrong -- a replace, or the follower dropped by an unexpected"
                        + " off-path/repath -- must not silently derail driving, at tick " + i);
                int anchorNow = h.executor.anchor();
                assertTrue(anchorNow >= previousAnchor,
                    "the anchor must never regress -- a replace instead of an append would reset"
                        + " it to 0 mid-path, at tick " + i);
                previousAnchor = anchorNow;
            }
            i++;
        }

        assertTrue(sawPendingRun, "a plan-ahead search must actually have run");
        assertTrue(h.log.messages().toString().contains("arrived"),
            "the appended continuation must reach the real goal, not stop short of it");
    }

    @Test
    void aRepathCancelsAPendingPlanAhead() {
        // A plan-ahead searches from a path end the repath is about to discard, so letting the
        // two race would append a continuation of a route that no longer exists. Assert through
        // Run.cancelled() by reference, not through behaviour.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        List<Pos> prefix = shortPrefixTowardMazeGoal(world, h, y, z);

        int triggerAnchor = prefix.size() - 1 - PathExecutor.PLAN_AHEAD_STEPS;
        for (int i = 0; i <= triggerAnchor; i++) {
            Pos node = prefix.get(i);
            standOn(h, node.x(), node.y(), node.z(), true);
            h.executor.tick(h.player);
        }
        Run planAhead = h.executor.pendingRun();
        assertNotNull(planAhead, "fixture assumption: plan-ahead should have started");
        assertFalse(planAhead.finished(),
            "fixture assumption: the tail search must still be pending, or there is no race for"
                + " a repath to preempt");
        assertFalse(planAhead.cancelled());

        // Teleport far off the route -- outside the follower's reanchor window -- so the next
        // tick detects off-path and repaths, discarding the plan-ahead in flight.
        standOn(h, MazeRoom.LENGTH / 2, y, 0, true);
        h.executor.tick(h.player);

        assertTrue(planAhead.cancelled(),
            "the repath must cancel the plan-ahead it preempted, by reference");
        assertNotSame(planAhead, h.executor.pendingRun(),
            "the repath's own run must be a different object from the cancelled plan-ahead");
    }

    @Test
    void followClearsAnyPendingSearchAndResetsCounters() {
        // Finding 1 of fix round 1: follow() must be a clean slate regardless of what was in
        // flight. Without cancelling a pending plan-ahead first, the plan-ahead later resolves and
        // adopt() reaches follower.append() against a follower this call already replaced -- its
        // continuation begins at the OLD follower's last node, not this one's -- and
        // PathFollower.append throws IllegalArgumentException straight out of tick().
        //
        // Deliberately does not reuse shortPrefixTowardMazeGoal here: that helper's own follow()
        // call would itself be the thing under test, so this drives the initial search to a real
        // adoption first (an ordinary walkTo + settle, no pending run left over), THEN installs a
        // short prefix over it, so the plan-ahead triggered below is the only search in flight
        // when the second follow() call -- the one actually being tested -- happens.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        standOn(h, 0, y, z, true);
        h.executor.walkTo(MazeRoom.LENGTH, y, z);
        tickUntilSearchSettles(h, 10);
        assertTrue(h.executor.active(), "fixture assumption: the initial search must have adopted");

        SegmentedResult full = fullMazeSearch(world);
        int cut = PathExecutor.PLAN_AHEAD_STEPS + 5;
        List<Pos> prefix = new ArrayList<Pos>(full.path().subList(0, cut + 1));
        h.executor.follow(prefix, PathResults.stepsOf(prefix));

        int triggerAnchor = prefix.size() - 1 - PathExecutor.PLAN_AHEAD_STEPS;
        for (int i = 0; i <= triggerAnchor; i++) {
            Pos node = prefix.get(i);
            standOn(h, node.x(), node.y(), node.z(), true);
            h.executor.tick(h.player);
        }
        assertNotNull(h.executor.pendingRun(), "fixture assumption: plan-ahead should be pending");

        // An unrelated path, nowhere near the maze or the pending search's target -- follow()
        // never reads the world, so it does not need to be walkable.
        List<Pos> unrelated = new ArrayList<Pos>();
        unrelated.add(new Pos(500, 65, 500));
        unrelated.add(new Pos(501, 65, 500));
        unrelated.add(new Pos(502, 65, 500));
        h.executor.follow(unrelated, PathResults.stepsOf(unrelated));

        assertNull(h.executor.pendingRun(),
            "follow() must cancel a pending search rather than leave it to resolve against an"
                + " unrelated follower");

        // If the cancelled search were still pending, this tick would throw
        // IllegalArgumentException out of adopt() -> PathFollower.append instead of driving.
        standOn(h, 500, 65, 500, true);
        h.executor.tick(h.player);

        assertTrue(h.executor.active());
        assertEquals(0, h.executor.anchor(), "driving the newly installed path, from its own start");
        assertEquals(Boolean.TRUE, written(h).get(Input.FORWARD), "and it actually drives");
    }

    /**
     * Sets up a driving executor whose follower is a short, real prefix of the maze's true route,
     * ending well before the goal -- via {@code walkTo} (to set the goal, and start a real search
     * this never lets run) then {@code follow} (to install the prefix in its place instead, bypassing
     * the real multi-tick search for speed).
     *
     * <p>Deliberately does NOT call {@code stop()} between the two: {@code stop()} clears
     * {@link PathExecutor#goal}, and this needs the goal {@code walkTo} set to survive so the
     * installed prefix still has something to plan-ahead toward. {@code follow()} cancelling
     * {@code walkTo}'s own pending search itself (Finding 1's fix) is what makes this safe without
     * an intervening {@code stop()} at all.
     */
    private static List<Pos> shortPrefixTowardMazeGoal(MazeRoom world, Harness h, int y, int z) {
        SegmentedResult full = fullMazeSearch(world);
        int cut = PathExecutor.PLAN_AHEAD_STEPS + 5;
        List<Pos> prefix = new ArrayList<Pos>(full.path().subList(0, cut + 1));

        standOn(h, 0, y, z, true);
        h.executor.walkTo(MazeRoom.LENGTH, y, z);
        h.executor.follow(prefix, PathResults.stepsOf(prefix));
        return prefix;
    }

    @Test
    void goingOffPathReleasesEveryInputOnceThenRepathsFromThePlayer() {
        // Teleport the player far off the route. Assert: a full release on that tick, no drive
        // while the repath runs, then driving resumes on a path whose first node is near where
        // the player now is.
        //
        // MazeRoom, not FlatCorridor: a repath there settles in a single slice, so the "no drive
        // while the repath runs" loop below would never execute its guarded body at all -- a
        // guarded assertion that never runs reads as coverage it is not. The teleport target
        // (5, 0) was measured (a throwaway search from that exact position) at 6,420 expanded
        // nodes toward the goal, the same cost as the original route, so the repath genuinely
        // spans several ticks.
        MazeRoom world = new MazeRoom();
        Harness h = new Harness(world);
        int y = MazeRoom.FLOOR_Y + 1;
        int z = MazeRoom.WIDTH / 2;
        standOn(h, 0, y, z, true);
        h.executor.walkTo(MazeRoom.LENGTH, y, z);
        tickUntilSearchSettles(h, 10);

        standOn(h, 1, y, z, true);
        h.executor.tick(h.player);
        standOn(h, 2, y, z, true);
        h.executor.tick(h.player);
        assertTrue(h.actuator.callCount() > 0, "fixture assumption: it was driving normally");

        int offX = 5;
        int offZ = 0;
        standOn(h, offX, y, offZ, true);
        h.actuator.clear();
        h.executor.tick(h.player);

        assertEquals(Input.values().length, h.actuator.callCount(),
            "off path must release every input exactly once");
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, written(h).get(input), input + " must be released");
        }
        assertTrue(h.executor.active(), "it is repathing, not idle");
        Run repathRun = h.executor.pendingRun();
        assertNotNull(repathRun);

        int guard = 0;
        int noDriveTicksObserved = 0;
        while (!repathRun.finished()) {
            h.actuator.clear();
            h.executor.tick(h.player);
            guard++;
            if (!repathRun.finished()) {
                assertEquals(0, h.actuator.callCount(), "nothing is driven while the repath runs");
                noDriveTicksObserved++;
            }
            assertTrue(guard < 20, "fixture assumption broke -- the repath never finished");
        }
        assertTrue(noDriveTicksObserved > 0,
            "fixture assumption: the repath must span more than one tick, or the no-drive"
                + " assertion above never actually ran");

        assertEquals(Boolean.TRUE, written(h).get(Input.FORWARD),
            "driving resumes the same tick the repath is found");
        assertEquals(0, h.executor.anchor(), "the new path starts where the player now is");
    }

    @Test
    void stuckFiresAtStuckTicksAndNotAtOneFewer() {
        // Pin the player's position while driving. The negative half is what pins the boundary
        // rather than the behaviour.
        FlatCorridor world = new FlatCorridor();
        Harness h = new Harness(world);
        int y = FlatCorridor.FLOOR_Y + 1;
        int z = FlatCorridor.WIDTH / 2;
        standOn(h, 0, y, z, true);
        h.executor.walkTo(FlatCorridor.LENGTH, y, z);
        tickUntilSearchSettles(h, 10);

        // A real advance, cleanly resetting the stuck counter, so the boundary below is exact.
        standOn(h, 1, y, z, true);
        h.executor.tick(h.player);
        assertTrue(h.executor.active());

        for (int i = 0; i < PathExecutor.STUCK_TICKS - 1; i++) {
            h.actuator.clear();
            h.executor.tick(h.player);
            assertEquals(Boolean.TRUE, written(h).get(Input.FORWARD),
                "tick " + i + ": still driving normally, not yet stuck");
        }
        assertNull(h.executor.pendingRun(), "must not have repathed yet");

        h.actuator.clear();
        h.executor.tick(h.player);
        assertEquals(Input.values().length, h.actuator.callCount(),
            "the stuck tick releases every input once");
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, written(h).get(input), input + " must be released");
        }
        assertNotNull(h.executor.pendingRun(), "stuck must have triggered a repath");
    }

    @Test
    void noPathStopsWithoutRetrying() {
        // Against EmptyWorld, which answers UNKNOWN everywhere. NO_PATH is the only definitive
        // "stop retrying" signal the search produces (SegmentedResult.outcome():34-42), so a
        // retry here would re-search a goal already proven impossible.
        EmptyWorld world = new EmptyWorld();
        Harness h = new Harness(world);
        standOn(h, 0, 64, 0, true);

        h.executor.walkTo(10, 64, 0);
        tickUntilSearchSettles(h, 10);

        assertFalse(h.executor.active(), "NO_PATH must leave the executor idle");
        assertTrue(h.log.messages().toString().contains("no path"),
            "the log must explain why it stopped");

        h.actuator.clear();
        h.executor.tick(h.player);
        assertEquals(0, h.actuator.callCount(), "no retry -- still idle, writes nothing");
        assertNull(h.executor.pendingRun(), "no retry -- no new search was started");
        assertFalse(h.executor.active());
    }

    @Test
    void theRepathBudgetTerminates() {
        FlatCorridor world = new FlatCorridor();
        Harness h = new Harness(world);
        int y = FlatCorridor.FLOOR_Y + 1;
        int z = FlatCorridor.WIDTH / 2;
        standOn(h, 0, y, z, true);
        h.executor.walkTo(FlatCorridor.LENGTH, y, z);

        // The player is never moved past its pinned start, so every repath the executor attempts
        // is pure thrash: nothing after the very first tick ever advances the anchor.
        int repaths = 0;
        int ticks = 0;
        while (h.executor.active()) {
            boolean wasWaitingToRepath = h.executor.pendingRun() == null;
            h.executor.tick(h.player);
            ticks++;
            if (wasWaitingToRepath && h.executor.pendingRun() != null) {
                repaths++;
            }
            assertTrue(ticks < 2000, "fixture assumption broke -- it never gave up");
        }

        assertEquals(PathExecutor.MAX_CONSECUTIVE_REPATHS, repaths,
            "the executor must give up after exactly the repath budget, not loop forever");
        assertFalse(h.executor.active());
        assertTrue(h.log.messages().toString().contains("giving up"));
    }

    @Test
    void theRepathCounterResetsWhenTheAnchorAdvances() {
        // Otherwise a long route that repaths five times while making real progress is killed by
        // a budget meant for thrash.
        FlatCorridor world = new FlatCorridor();
        Harness h = new Harness(world);
        int y = FlatCorridor.FLOOR_Y + 1;
        int z = FlatCorridor.WIDTH / 2;
        standOn(h, 0, y, z, true);
        h.executor.walkTo(FlatCorridor.LENGTH, y, z);
        tickUntilSearchSettles(h, 10);

        int position = 0;
        int cycles = PathExecutor.MAX_CONSECUTIVE_REPATHS + 1;
        for (int cycle = 0; cycle < cycles; cycle++) {
            int guard = 0;
            while (h.executor.pendingRun() == null) {
                standOn(h, position, y, z, true);
                h.executor.tick(h.player);
                guard++;
                assertTrue(h.executor.active(), "must not give up during cycle " + cycle);
                assertTrue(guard < PathExecutor.STUCK_TICKS + 5,
                    "fixture assumption broke -- never got stuck in cycle " + cycle);
            }

            Run repath = h.executor.pendingRun();
            int settleGuard = 0;
            while (!repath.finished()) {
                h.executor.tick(h.player);
                settleGuard++;
                assertTrue(settleGuard < 20,
                    "fixture assumption broke -- repath never finished in cycle " + cycle);
            }
            assertTrue(h.executor.active(), "must still be driving after cycle " + cycle);

            // Real progress: advance a few real steps, which must reset the repath counter.
            for (int step = 0; step < 5; step++) {
                position++;
                standOn(h, position, y, z, true);
                h.executor.tick(h.player);
            }
        }

        // Every one of MAX_CONSECUTIVE_REPATHS + 1 stuck cycles was preceded by real progress
        // resetting the counter, so the executor must still be trying, not given up.
        assertTrue(h.executor.active(), "progress between repaths must have reset the budget");
        assertFalse(h.log.messages().toString().contains("giving up"));
    }

    @Test
    void stopCancelsAPendingRunByReference() {
        // A pending Run holds a WorldSnapshot wrapping a live BlockSource and so pins a level.
        // Asserted through Run.cancelled() rather than through behaviour, per C5's mutation 7.
        FlatCorridor world = new FlatCorridor();
        Harness h = new Harness(world);
        int y = FlatCorridor.FLOOR_Y + 1;
        int z = FlatCorridor.WIDTH / 2;
        standOn(h, 0, y, z, true);

        h.executor.walkTo(FlatCorridor.LENGTH, y, z);
        Run run = h.executor.pendingRun();
        assertNotNull(run);
        assertFalse(run.cancelled());

        h.executor.stop();

        assertTrue(run.cancelled(), "stop() must cancel the pending run, by reference");
        assertNull(h.executor.pendingRun());
        assertFalse(h.executor.active());
    }

    @Test
    void theSegmentedResultIsNotRetainedAfterAdoption() {
        // expanded() accumulates across every segment -- roughly cap x nodeBudget entries. Held
        // for one search that is a C5 residual; held for the duration of a walk it would be
        // strictly worse than today. Assert the executor holds no reference to the result.
        FlatCorridor world = new FlatCorridor();
        Harness h = new Harness(world);
        int y = FlatCorridor.FLOOR_Y + 1;
        int z = FlatCorridor.WIDTH / 2;
        standOn(h, 0, y, z, true);

        h.executor.walkTo(FlatCorridor.LENGTH, y, z);
        tickUntilSearchSettles(h, 10);
        assertTrue(h.executor.active());

        assertFalse(retainsA(h.executor, SegmentedResult.class),
            "the executor must not hold a SegmentedResult anywhere in its reachable state after"
                + " adopting a search");
    }
}
