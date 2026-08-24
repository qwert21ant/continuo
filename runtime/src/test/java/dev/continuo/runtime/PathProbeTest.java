package dev.continuo.runtime;

import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathProbeTest {

    /**
     * The character the map draws at a world position, or {@code '\0'} if the position falls
     * outside the drawn window.
     *
     * <p><b>Only the terrain slices are read, and that is the point of having this at all.</b>
     * A bare {@code map().indexOf('G')} also matches the G in the trailing
     * {@code "// BUDGET_EXCEEDED, ..."} summary, so a marker assertion written that way passes on
     * a map with no goal marker in it — a trap that has already fooled one reader of this file.
     * {@code PathRendererTest.aPassableNonAirBlockUnderAnOverlayComesBackAsAir} cuts the string
     * the same way, for the same reason.
     *
     * <p>Position rather than presence matters because the renderer consults its {@code start}
     * and {@code goal} arguments only when the path is empty — exactly the {@code NO_PATH} and
     * {@code BUDGET_EXCEEDED} cases this feature exists to capture. Swapping the two at the call
     * site is invisible to any assertion that only asks whether an S and a G appear somewhere.
     */
    private static char charAt(String map, int x, int y, int z) {
        int summary = map.indexOf("// ");
        String terrain = summary < 0 ? map : map.substring(0, summary);
        String[] lines = terrain.split("\n", -1);

        String[] origin = lines[0].substring(lines[0].indexOf(':') + 1).split(",");
        int minX = Integer.parseInt(origin[0].trim());
        int minZ = Integer.parseInt(origin[2].trim());

        int layerY = Integer.MIN_VALUE;
        int rowZ = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("--- y=")) {
                layerY = Integer.parseInt(line.substring("--- y=".length()).trim());
                rowZ = minZ;
                continue;
            }
            if (line.length() == 0) {
                continue;
            }
            if (layerY == y && rowZ == z) {
                int column = x - minX;
                return column >= 0 && column < line.length() ? line.charAt(column) : '\0';
            }
            rowZ++;
        }
        return '\0';
    }

    @Test
    void markingAGoalThenRunningFindsTheRoute() {
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.ran());
        assertEquals(PathOutcome.FOUND, report.outcome());
        assertTrue(report.summary().contains("FOUND"), report.summary());
        assertTrue(report.map().indexOf(PathRenderer.START) >= 0, report.map());
        assertTrue(report.map().indexOf(PathRenderer.GOAL) >= 0, report.map());
    }

    @Test
    void runningWithNoGoalMarkedIsReportedRatherThanThrown() {
        // The caller is inside the game loop. Global rule 3 makes a throw from there an adapter
        // fault, and "I forgot to press mark" is the most likely thing to happen in practice.
        ProbeReport report = new PathProbe().run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertFalse(report.ran());
        assertTrue(report.summary().contains("no goal marked"), report.summary());
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                report.map();
            }
        });
    }

    @Test
    void aWalledOffGoalReportsNoPathAndStillRendersTheMap() {
        // The case that most needs looking at, and the one a summary line cannot explain.
        // The wall spans the floor's whole Z extent: a partial wall on a finite floor is a
        // detour, not a barrier, and this test would then witness FOUND by a longer road while
        // still looking like it had witnessed NO_PATH.
        ProbeWorld world = new ProbeWorld();
        world.wallAcross(3);
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.NO_PATH, report.outcome());
        assertTrue(report.map().indexOf(PathRenderer.START) >= 0,
            "a failed render must still say where the search began\n" + report.map());
        assertTrue(report.map().indexOf(PathRenderer.GOAL) >= 0,
            "and where it was trying to get to\n" + report.map());
    }

    @Test
    void aTinyBudgetIsReportedAsBudgetExceededRatherThanAsNoPath() {
        // Distinguishing these two matters in-game more than it does in a fixture: one means
        // the world has no route and the other means the probe gave up, and the fix differs.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(3);
        probe.markGoal(40, ProbeWorld.WALK_Y, 40);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.BUDGET_EXCEEDED, report.outcome());
        assertTrue(report.summary().contains("BUDGET_EXCEEDED"), report.summary());
    }

    @Test
    void aGoalBeyondTheRenderLimitProducesAMapThatSaysItWasClamped() {
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.map().contains("clamped"),
            "a truncated map must say so, or it reads as a search that stopped for no reason\n"
                + report.map());
        assertTrue(report.summary().contains("clamped"), report.summary());
    }

    @Test
    void aClampedMapIsAnchoredOnTheStartRatherThanBeingBlank() {
        // The clamp tests above pass on 1,600 characters of air: one asks only that the word
        // "clamped" appears and the other only that the map starts with "origin:", and both are
        // true of a window centred on the empty space between a start and a distant goal. That
        // window contained no S, no G and no part of the search, so pasting it back gave a
        // fixture with start() == null and goal() == null - and clamping happens precisely in the
        // failed and budget-exceeded cases the feature exists to capture.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.map().contains("clamped"), report.map());
        assertEquals(PathRenderer.START, charAt(report.map(), 0, ProbeWorld.WALK_Y, 0),
            "a clamped window must be anchored so the start is inside it\n" + report.map());
        assertTrue(report.map().contains("anchored on the start"),
            "and the notice must say the window is anchored, because the goal may now be outside"
                + " it and a reader who is not told will read that as a missing goal\n"
                + report.map());
    }

    @Test
    void theClampNoticeDoesNotBreakTheMapsHeader() {
        // The notice is appended as a "// " line rather than prepended, because the fixture
        // parser requires "origin:" on the first line and skips "//" lines. Prepending it would
        // make exactly the maps worth pasting back unparseable.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        String map = probe.run(world, 0, ProbeWorld.WALK_Y, 0).map();

        assertTrue(map.startsWith("origin:"), map.substring(0, Math.min(80, map.length())));
    }

    @Test
    void theParkourMovementIsOnTheClasspathTheProbeSearchesWith() {
        // Spec 5.3. The probe requests Capability.PARKOUR, and a registry with nothing to grant
        // it behaves identically to one that exercises it - the same "reads as a pass, checked
        // nothing" shape MovementContract was fixed twice to close. The adapters reach parkour
        // only through :runtime's runtimeOnly dependency, so this guards the wiring that makes
        // the capability mean anything in-game.
        List<IMovementType> active = AStarPathfinder.defaultRegistry()
            .activeFor(CapabilitySet.of(Capability.PARKOUR)).movements();

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < active.size(); i++) {
            ids.add(active.get(i).id());
        }

        assertTrue(ids.contains("walk.parkour"),
            "walk.parkour is not in the set the probe searches with, so the probe would request"
                + " a capability nothing supplies and report success either way; got " + ids);
    }
}
