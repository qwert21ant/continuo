package dev.continuo.runtime;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.BlockLegend;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathRenderer;
import dev.continuo.pathfinder.SegmentedResult;
import dev.continuo.pathfinder.SegmentedSearch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathProbeTest {

    /**
     * Sand, as {@code BlockClassifier} actually produces it: a full cube carrying {@code FALLING}.
     *
     * <p>No {@code BlockLegend} value carries a tag bar lava's {@code AVOID}, and tags participate
     * in {@code BlockData} equality, so this matches no legend entry and renders as {@code ?} —
     * which is why an ordinary beach comes back as a wall of them.
     */
    private static final BlockData SAND = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.of(BlockTag.FALLING));

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

    /**
     * The integer that precedes a word in the probe's summary line.
     *
     * <p>Parsing rather than substring-matching is the point: {@code "snapshot 42 positions /
     * 137 reads"} and the same line with the two numbers transposed both contain every substring
     * the other does, so only reading them as numbers can tell the two apart.
     */
    /**
     * The path cost the probe reported.
     *
     * <p>{@code StringBuilder.append(double)} goes through {@code Double.toString}, which always
     * writes a {@code '.'} whatever the default locale is, so this needs none of the care the
     * ratio's {@code Locale.ROOT} formatting does.
     */
    private static double costFrom(String summary) {
        int start = summary.indexOf("cost ") + "cost ".length();
        int end = summary.indexOf(',', start);
        return Double.parseDouble(summary.substring(start, end));
    }

    private static int figureBefore(String summary, String word) {
        int end = summary.indexOf(" " + word);
        if (end < 0) {
            throw new AssertionError("no '" + word + "' in summary: " + summary);
        }
        int start = summary.lastIndexOf(' ', end - 1) + 1;
        return Integer.parseInt(summary.substring(start, end));
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
        // FOUND alone is a weak guard, and the probe wave learned why the hard way: a map pasted
        // back as a fixture reported FOUND by a completely different route, at a different cost,
        // and an assertion on the verdict could not tell the two apart. ProbeWorld is a fixture
        // and A* is deterministic, so the route is exactly pinnable - and a change to movement
        // costs or tie-breaking that silently rerouted this search would otherwise pass here.
        assertEquals(7, figureBefore(report.summary(), "steps"),
            "six blocks of flat floor, start inclusive\n" + report.summary());
        assertEquals(6 * 3.5636, costFrom(report.summary()), 1.0e-9,
            "six walk.traverse steps and nothing else\n" + report.summary());
        assertEquals(PathRenderer.START, charAt(report.map(), 0, ProbeWorld.WALK_Y, 0),
            "the start marker must sit on the start\n" + report.map());
        assertEquals(PathRenderer.GOAL, charAt(report.map(), 6, ProbeWorld.WALK_Y, 0),
            "and the goal marker on the goal\n" + report.map());
    }

    @Test
    void aRouteOnlyParkourCanTakeIsFoundThroughTheProbeItself() {
        // Spec 5.3, behaviourally. The registry test below proves walk.parkour is on the
        // classpath; this one proves the probe actually asks for it. A one-block bottomless
        // trench spanning the floor's whole Z extent is crossable by walk.parkour and by nothing
        // else in the registry - walk.traverse cannot enter a non-standable block, and neither
        // walk.descend nor walk.ascend has anything to land on or climb in a void column - so
        // FOUND here can only happen if the movement is both present and requested. Dropping
        // either the runtimeOnly dependency or PathProbe's CapabilitySet.of(Capability.PARKOUR)
        // turns this into NO_PATH; both mutations were run.
        ProbeWorld world = new ProbeWorld();
        world.trenchAcross(3);
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.FOUND, report.outcome(),
            "only walk.parkour crosses a bottomless one-block trench, so anything but FOUND means"
                + " the probe searched without the capability it claims to request\n"
                + report.map());
    }

    @Test
    void aMarkedGoalDoesNotSurviveADimensionChange() {
        // A change of client level instance is the same trigger AdapterRuntime already uses to
        // stop the core, and it is the right one here for the same reason: a dimension change
        // replaces the level without ending the session, and coordinates that meant something in
        // the Overworld mean somewhere else entirely in the Nether. Without this the probe
        // searches the new world for the old world's goal and blames the terrain for the miss.
        Object overworld = new Object();
        Object nether = new Object();
        PathProbe probe = new PathProbe();
        probe.onLevel(overworld);
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);
        probe.onLevel(nether);

        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertFalse(report.ran(), report.summary());
        assertTrue(report.summary().contains("no goal marked"), report.summary());
    }

    @Test
    void aMarkedGoalSurvivesTheTicksThatFollowItOnTheSameLevel() {
        // The guard that stops the fix from being "clear the goal always". The adapters call
        // onLevel from their per-tick poll, so an implementation that does not compare by identity
        // would discard every mark on the tick after it was made and the probe would never path
        // anywhere again — a regression no other test in this file would notice, since none of
        // them calls onLevel at all.
        Object level = new Object();
        PathProbe probe = new PathProbe();
        probe.onLevel(level);
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);
        probe.onLevel(level);
        probe.onLevel(level);

        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.FOUND, report.outcome(), report.summary());
    }

    @Test
    void aMarkedGoalDoesNotSurviveLeavingTheWorld() {
        // Disconnecting to the title screen nulls the level, and rejoining builds a fresh
        // instance. A goal kept across that gap is aimed at a world object that no longer exists,
        // even when the save behind it is the same one.
        PathProbe probe = new PathProbe();
        probe.onLevel(new Object());
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);
        probe.onLevel(null);

        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertFalse(report.ran(), report.summary());
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
        // outcome() carries the same contract as map() and had none of the same cover: returning
        // null instead of refusing went unnoticed, and a null outcome reads in a caller's log as
        // a search that produced nothing rather than one that never ran.
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                report.outcome();
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
        // Position, not presence. A failed search has an empty path, which is the only case in
        // which the renderer consults its start and goal arguments at all - so this is also the
        // only test that can catch those two being swapped at PathProbe's call site. Asserting
        // the markers merely exist passes with them the wrong way round, and passes on the
        // summary line's own G besides.
        assertEquals(PathRenderer.START, charAt(report.map(), 0, ProbeWorld.WALK_Y, 0),
            "a failed render must say where the search began, and say it in the right place\n"
                + report.map());
        assertEquals(PathRenderer.GOAL, charAt(report.map(), 6, ProbeWorld.WALK_Y, 0),
            "and where it was trying to get to, likewise\n" + report.map());
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
    void theSummaryReportsWhatTheSnapshotSavedOverReadingLive() {
        // C3's central claim, put where every future probe run measures it on real terrain
        // instead of on a fixture. The search reads each position it touches several times and
        // the snapshot turns all of them into one, so reads must exceed positions.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.summary().contains("snapshot "),
            "the summary must carry the snapshot's figures\n" + report.summary());
        assertTrue(report.summary().contains(" positions"), report.summary());
        assertTrue(report.summary().contains(" reads"), report.summary());
        assertFalse(report.summary().contains("snapshot 0 positions"),
            "a search that read nothing means the probe is not reading through the snapshot\n"
                + report.summary());
        assertTrue(report.summary().contains("x)"),
            "and the ratio, which is the number worth looking at\n" + report.summary());

        int positions = figureBefore(report.summary(), "positions");
        int reads = figureBefore(report.summary(), "reads");
        assertTrue(reads > positions,
            "a search re-reads the positions it touches, so reads must exceed positions."
                + " If these two are ever transposed at the call site, every future in-game"
                + " measurement inverts and nothing else in this file would notice\n"
                + report.summary());
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
    void aClampedMapNamesTheGoalItCouldNotDraw() {
        // A clamped window is anchored on the start, so the goal usually falls outside it and no
        // G is drawn. FixtureWorld.parse then yields goal() == null and the map cannot be
        // replayed as a search fixture without inventing a goal - which is exactly what happened
        // to three of the four dumps captured in game on 2026-08-25, costing two of the three
        // real-terrain samples C3's spec wanted. Nothing else in the file records the goal, so
        // the notice has to.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        String coordinates = "400," + ProbeWorld.WALK_Y + ",0";
        assertTrue(report.map().contains(coordinates),
            "the notice must carry the goal in the map's own origin format, so a reader can"
                + " retype it\n" + report.map());
        // Not contains("outside"): the notice's first clause already says "terrain outside it is
        // not drawn", so that assertion passes on a notice claiming the goal IS drawn. Forcing
        // ProbeBounds.contains to return true proved it - the assertion was there and vacuous.
        assertTrue(report.map().contains("lies outside it"),
            "and must say the goal fell outside the window rather than leave it to be guessed\n"
                + report.map());
        assertFalse(report.map().contains("does lie inside it"),
            "and must not claim the opposite\n" + report.map());
        assertTrue(report.summary().contains(coordinates), report.summary());
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
    void aMapDrawingBlocksTheLegendCannotNameSaysSoRatherThanLookingPasteable() {
        // Verified against a real capture on 2026-08-25: the owner's sand map reported
        // "FOUND, 5 steps, cost 18.8691" in game, and the same text pasted back as a FixtureWorld
        // reported "FOUND, 7 steps, cost 25.9963" - the ridge the route walked over had become a
        // wall, and the search went around it one Y lower. Both said FOUND. That is the whole
        // reason this notice exists: the failure is not a parse error or a NO_PATH, it is a
        // different search wearing the same verdict, and nothing in the map admitted it.
        ProbeWorld world = new ProbeWorld();
        world.put(3, ProbeWorld.FLOOR_Y, 0, SAND);
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.map().contains("1 of"),
            "the notice must count the cells, because one ? beside the route and a hundred far"
                + " from it are different problems\n" + report.map());
        assertTrue(report.map().contains("impassable"),
            "and must say what the character re-parses as, which is the part that changes the"
                + " search\n" + report.map());
        assertTrue(report.summary().contains("impassable"), report.summary());
    }

    @Test
    void aMapOfTerrainTheLegendNamesCarriesNoSuchNotice() {
        // The guard that stops the notice from being unconditional. Every other test in this file
        // renders legend-named terrain, and none of them would notice a warning bolted onto every
        // map - which would train a reader to ignore the one map that means it.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertFalse(report.map().contains("impassable"), report.map());
        assertFalse(report.summary().contains("impassable"), report.summary());
    }

    @Test
    void theUnnamedBlockNoticeDoesNotBreakTheMapsHeader() {
        // Same hazard the clamp notice has: the fixture parser requires "origin:" on the first
        // line and skips "//" lines, so a prepended notice would make exactly the maps worth
        // pasting back unparseable.
        ProbeWorld world = new ProbeWorld();
        world.put(3, ProbeWorld.FLOOR_Y, 0, SAND);
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

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

    @Test
    void theSummaryReportsHowLongTheSearchTook() {
        PathProbe probe = new PathProbe(1000);
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);
        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        String summary = report.summary();
        Matcher m = Pattern.compile(", ([0-9]+\\.[0-9]) ms").matcher(summary);
        assertTrue(m.find(), "no millisecond figure in: " + summary);
        // Parsed as a number rather than matched as a substring. C3's review found that the
        // probe's two integers could be transposed with zero test failures because every
        // assertion matched substrings; this is the same class of defect, pre-empted.
        double ms = Double.parseDouble(m.group(1));
        assertTrue(ms >= 0.0, "negative elapsed time: " + ms);
    }

    @Test
    void theSummaryReportsHowManySegmentsTheRunTook() {
        PathProbe probe = new PathProbe(1000);
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);
        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        Matcher m = Pattern.compile(", ([0-9]+) segments?").matcher(report.summary());
        assertTrue(m.find(), "no segment count in: " + report.summary());
        assertEquals(1, Integer.parseInt(m.group(1)),
            "a goal this close is one search; segmenting it would mean the budget is being"
                + " exhausted where it should not be");
    }

    @Test
    void theSummaryReportsMoreThanOneSegmentWhenTheBudgetForcesIt() {
        // I5: "1 segment" hardcoded into the summary passes every other test in this file, because
        // none of them drives a multi-segment run. A budget of 10 against the far corner of
        // ProbeWorld's floor does: measured by direct execution, it takes exactly two segments to
        // reach (12, WALK_Y, 12) from the origin, so this is the case a hardcoded count cannot
        // survive.
        PathProbe probe = new PathProbe(10);
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.FOUND, report.outcome(), report.summary());
        Matcher m = Pattern.compile(", ([0-9]+) segments?").matcher(report.summary());
        assertTrue(m.find(), "no segment count in: " + report.summary());
        assertEquals(2, Integer.parseInt(m.group(1)),
            "budget 10 against ProbeWorld's far corner is measured to take two segments; a"
                + " hardcoded '1 segment' would fail this rather than merely fail to be exercised\n"
                + report.summary());
    }

    @Test
    void theSummaryReportsExpandedNodesNotStepsOnAMultiSegmentRun() {
        // I3: the summary's "N expanded" field is combined.nodesExpanded(), not result.path().size()
        // -- a substitution three independent mutations proved unpinned. Parsed as numbers, not
        // substrings, for the reason figureBefore exists: the two counts are close enough on a
        // short route that a wrong-field regression would otherwise slip through.
        PathProbe probe = new PathProbe(10);
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        ProbeReport report = probe.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        int steps = figureBefore(report.summary(), "steps");
        int expanded = figureBefore(report.summary(), "expanded");
        assertTrue(expanded > steps,
            "a multi-segment run expands more nodes than the route it returns; reporting the step"
                + " count in the expanded field would pass this only by coincidence\n"
                + report.summary());
    }

    /** A very large, obstacle-free flat floor -- big enough that a full search over it takes
     * measurable wall-clock time, unlike anything {@link ProbeWorld} (radius 12) can produce. */
    private static final class HugeFlatWorld implements BlockSource {
        static final int FLOOR_Y = 63;
        static final int WALK_Y = 64;

        @Override
        public BlockData at(int x, int y, int z) {
            return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 128;
        }
    }

    private static double msFrom(String summary) {
        Matcher m = Pattern.compile(", ([0-9]+\\.[0-9]) ms").matcher(summary);
        if (!m.find()) {
            throw new AssertionError("no millisecond figure in: " + summary);
        }
        return Double.parseDouble(m.group(1));
    }

    /**
     * The value of the {@code N.Nms} field introduced by a label in the split clause.
     *
     * <p>The label is quoted rather than interpolated raw, because {@code "fill ~"} is not a
     * regular expression and the tilde is only harmless by accident.
     */
    private static double splitField(String summary, String label) {
        Matcher m = Pattern.compile(Pattern.quote(label) + "([0-9]+\\.[0-9])ms").matcher(summary);
        if (!m.find()) {
            throw new AssertionError("no '" + label + "' figure in: " + summary);
        }
        return Double.parseDouble(m.group(1));
    }

    @Test
    void theSummaryReportsWhatTheFillCostAgainstWhatTheSearchCost() {
        // The number C5's whole mechanism turns on. C4 section 13.3 established that search cost is
        // dominated by first-touch world reads rather than by expansions, but nothing separated the
        // two: the three in-game runs cannot be fitted to a cost model, because run 3 took 164.0 ms
        // with 44% of run 2's fills and a third of its expansions. Global rule 1 pins every SPI
        // call to the main thread, so a fill cannot move off it whatever C5 decides -- which makes
        // "how much of the elapsed time is fill" the question that chooses between a time-sliced
        // main-thread search and an off-thread one.
        PathProbe probe = new PathProbe(25000);
        probe.markGoal(3000, HugeFlatWorld.WALK_Y, 3000);
        ProbeReport report = probe.run(new HugeFlatWorld(), 0, HugeFlatWorld.WALK_Y, 0);

        String summary = report.summary();
        double setup = splitField(summary, "setup ");
        double live = splitField(summary, "live ");
        double first = splitField(summary, "sealed ");
        double second = splitField(summary, "then ");
        double fill = splitField(summary, "fill ~");

        assertTrue(setup >= 0.0 && live >= 0.0 && first >= 0.0 && second >= 0.0, summary);
        // The label pin, and the reason it is worth having: transposing setup and live is the one
        // mutation that leaves every figure in range and every other assertion here passing, and it
        // would invert the finding this measurement exists to produce. The split's "live" and the
        // standalone millisecond figure are the same quantity formatted the same way, so they must
        // agree to the digit.
        assertEquals(msFrom(summary), live, 0.0,
            "the split's live figure must be the same number the summary already reports as the"
                + " elapsed time; if these differ the fields are mislabelled\n" + summary);
        // Pins the arithmetic rather than the presence of a number. A hardcoded fill, or one
        // computed from the first replay instead of the JIT-warm second, fails here.
        // 0.16, not 0.05. live and second are each parsed back from a string formatted to one
        // decimal, so each carries up to +/-0.05 of rounding, and fill was formatted from the
        // unrounded difference and carries +/-0.05 of its own. The legitimate disagreement is
        // therefore up to 0.15, and a tighter tolerance makes this test fail at random rather
        // than when the arithmetic is actually wrong. Observed as 12.199999999999996 vs 12.1.
        assertEquals(live - second, fill, 0.16,
            "the fill estimate must be the live search minus the second sealed replay\n" + summary);
    }

    @Test
    void aSealedReplayOfAnOrdinarySearchDoesNotDiverge() {
        // The guard that makes the split trustworthy. Every position the live search read is
        // covered by the sealed snapshot, and A* is deterministic by construction (C1 section 5.1),
        // so the replay must expand the same nodes in the same order and return the same route. If
        // it ever does not, the two timings are of different searches and the split is meaningless
        // -- so the probe says so rather than reporting a number that looks fine.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.FOUND, report.outcome(), report.summary());
        assertFalse(report.summary().contains("diverged"), report.summary());
        assertFalse(report.map().contains("diverged"), report.map());
    }

    @Test
    void aReplayThatDoesNotReproduceTheLiveSearchIsDetected() {
        // The other half: the check above passes on a divergence() that always returns null, and
        // nothing driven through run() can force a divergence, because the snapshot memoises every
        // read and the replay therefore cannot see a different world. So the comparison is pinned
        // directly, against two runs that genuinely differ.
        SegmentedSearch search = new SegmentedSearch(new AStarPathfinder(25000));
        CapabilitySet caps = CapabilitySet.of(Capability.PARKOUR);
        ProbeWorld world = new ProbeWorld();
        SegmentedResult six = search.run(world, 0, ProbeWorld.WALK_Y, 0,
            new GoalBlock(6, ProbeWorld.WALK_Y, 0), caps);
        SegmentedResult five = search.run(world, 0, ProbeWorld.WALK_Y, 0,
            new GoalBlock(5, ProbeWorld.WALK_Y, 0), caps);

        assertNull(PathProbe.divergence(six, six),
            "a result compared with itself cannot have diverged");
        String found = PathProbe.divergence(six, five);
        assertNotNull(found, "two searches to different goals must be reported as divergent");
        assertTrue(found.contains("cost"),
            "the notice must name what differed, or a reader cannot tell a retimed search from a"
                + " rerouted one; got: " + found);
    }

    @Test
    void theMillisecondFigureIsMonotonicInSearchSize() {
        // I4: elapsedMs = 0.0 (a hardcoded constant) satisfies "ms >= 0.0", the suite's only
        // existing assertion on this figure. D6 rules out a strict ">0" on a tiny search as flaky,
        // so this pins the figure by comparing two searches of deliberately mismatched size instead:
        // a one-node search on a tiny world against a ~3,000-expansion search over open ground that
        // genuinely takes tens of milliseconds. A hardcoded constant cannot be larger for the big
        // search and cannot be positive for it either, so both of the assertions below catch it.
        PathProbe tiny = new PathProbe(1);
        tiny.markGoal(1, ProbeWorld.WALK_Y, 0);
        ProbeReport tinyReport = tiny.run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        PathProbe large = new PathProbe(25000);
        large.markGoal(3000, HugeFlatWorld.WALK_Y, 3000);
        ProbeReport largeReport = large.run(new HugeFlatWorld(), 0, HugeFlatWorld.WALK_Y, 0);

        double tinyMs = msFrom(tinyReport.summary());
        double largeMs = msFrom(largeReport.summary());

        assertTrue(Double.isFinite(tinyMs) && tinyMs >= 0.0, "tiny: " + tinyReport.summary());
        assertTrue(Double.isFinite(largeMs) && largeMs >= 0.0, "large: " + largeReport.summary());
        assertTrue(largeMs > 0.0,
            "a search expanding thousands of nodes against live block reads must take measurable"
                + " wall-clock time; 0.0 here means the figure is not really being measured\n"
                + largeReport.summary());
        assertTrue(largeMs >= tinyMs,
            "a much larger search must not report less elapsed time than a trivially small one\n"
                + "tiny: " + tinyReport.summary() + "\nlarge: " + largeReport.summary());
    }

    @Test
    void aSlicedRunReachesTheSameRouteAsAnUnslicedOne() {
        // D7 through the probe, which is the path the adapters take. run() drives the same Run
        // with an unbounded slice, so this compares the two ways of spending one search.
        ProbeWorld world = new ProbeWorld();
        PathProbe unsliced = new PathProbe();
        unsliced.markGoal(6, ProbeWorld.WALK_Y, 0);
        ProbeReport whole = unsliced.run(world, 0, ProbeWorld.WALK_Y, 0);

        PathProbe sliced = new PathProbe();
        sliced.markGoal(6, ProbeWorld.WALK_Y, 0);
        assertNull(sliced.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0),
            "a run that starts returns no report yet");
        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = sliced.advance();
        }

        assertNotNull(done, "the sliced run never finished");
        assertEquals(whole.outcome(), done.outcome());
        assertEquals(costFrom(whole.summary()), costFrom(done.summary()), 0.0,
            "a sliced run must reach the identical route\n" + whole.summary() + "\n"
                + done.summary());
    }

    @Test
    void aSlicedRunReportsHowManySlicesItTook() {
        PathProbe probe = new PathProbe();
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);
        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = probe.advance();
        }

        assertNotNull(done);
        assertTrue(done.summary().contains("slices"), done.summary());
        assertTrue(figureBefore(done.summary(), "slices") >= 1, done.summary());
    }

    @Test
    void advancingWithNothingStartedDoesNothing() {
        // The adapter calls advance() every tick whether or not the key was pressed.
        assertNull(new PathProbe().advance());
    }

    @Test
    void aLevelChangeCancelsAnInFlightRun() {
        // The hazard C3 §9 left behind: a run holds the source it reads, and under slicing that
        // run lives across ticks. A dimension change must end it rather than leave it searching a
        // level that no longer exists.
        PathProbe probe = new PathProbe();
        Object overworld = new Object();
        probe.onLevel(overworld);
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        probe.onLevel(new Object());

        assertNull(probe.advance(),
            "a run cancelled by a level change must not go on producing a report");
    }

    @Test
    void startingASecondRunWhileOneIsInFlightReplacesIt() {
        // Pressing the key twice is the likeliest thing an owner does. Two live runs sharing one
        // probe would interleave slices and report a route neither of them took.
        PathProbe probe = new PathProbe();
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);
        probe.advance();
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = probe.advance();
        }
        assertNotNull(done, "the replacing run must still finish");
        assertEquals(PathOutcome.FOUND, done.outcome(), done.summary());
    }

    @Test
    void remarkingTheGoalMidRunDoesNotRetargetTheRunInFlight() {
        // markGoal is public and unguarded, and a sliced run spans many ticks, so an owner can
        // mark a new goal while one is in flight. The Run captured its goal at start(); if the
        // report rebuilds the target from the current field instead, the two replays search for a
        // different goal than the live run did and the probe cries "diverged" about a determinism
        // bug that does not exist.
        //
        // ProbeWorld tops out at RADIUS 12, and even its far corner finishes inside a single
        // 4,000-node slice - too fast for a second advance() to land mid-run. HugeFlatWorld and a
        // goal far enough away forces several slices, which this test needs to prove anything.
        PathProbe probe = new PathProbe();
        probe.markGoal(16000, HugeFlatWorld.WALK_Y, 0);
        assertNull(probe.start(new HugeFlatWorld(), 0, HugeFlatWorld.WALK_Y, 0));
        assertNull(probe.advance(), "one slice must not already finish a 16,000-block route");
        probe.markGoal(3, ProbeWorld.WALK_Y, 3);

        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = probe.advance();
        }

        assertNotNull(done, "the run must still finish");
        assertFalse(done.summary().contains("diverged"),
            "the replays must target the goal the run was started with\n" + done.summary());
        assertTrue(done.summary().contains("(16000, " + HugeFlatWorld.WALK_Y + ", 0)"),
            "the summary must name the goal the run actually used, not the one marked since\n"
                + done.summary());
    }
}
