package dev.continuo.runtime;

import dev.continuo.core.BlockSource;
import dev.continuo.core.SealedSnapshot;
import dev.continuo.core.WorldSnapshot;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.BlockLegend;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathRenderer;
import dev.continuo.pathfinder.PathResult;
import dev.continuo.pathfinder.Pos;

/**
 * Runs A* against a live world and renders the result, so a route can be looked at in a
 * running game.
 *
 * <p>Dev-only, like {@code BlockDumpWalker} beside it. Nothing calls it during normal operation.
 *
 * <p><b>Main thread only.</b> A live {@code BlockSource} inherits {@code IBlockView}'s delivery
 * window, and this reads through it synchronously.
 *
 * <p><b>The search reads through a {@link WorldSnapshot}; the render does not.</b> Off-thread
 * safety is not why — nothing here leaves the main thread. It is that a search reads each
 * position it touches several times over and the snapshot turns all of them into one SPI call,
 * which the summary reports so that every run measures the saving on real terrain. The render is
 * left reading live because its window touches each cell once and can be 64 blocks per axis.
 *
 * <p><b>The elapsed time is reported and never consulted.</b> C1 section 5.1 makes determinism a
 * hard requirement -- tests assert which path comes back -- and a wall-clock stopping condition
 * would make every one of those assertions flaky. The budget stays counted in nodes. This figure
 * exists to size that budget and to settle whether a search can span a tick, which is C4's
 * deferred question.
 *
 * <p>The mark-then-run shape is deliberate: it lets an owner walk to somewhere awkward, mark it,
 * walk back, and search across terrain they chose, without any new SPI surface for naming a
 * destination.
 *
 * <p><b>A marked goal does not survive a level or dimension transition</b>, provided the adapter
 * calls {@link #onLevel} — which both of them do, from the same poll that reads the keys. Marking
 * a spot in the Overworld and pressing the path key in the Nether would otherwise search one
 * world for coordinates that meant something in another, and report {@code NO_PATH} as though the
 * terrain were at fault. The trigger is deliberately the one {@code AdapterRuntime} already uses
 * to stop the core, so the goal and the {@code BlockLookup} beside it are discharged by the same
 * event.
 */
public final class PathProbe {

    /**
     * The node budget a probe uses when none is given.
     *
     * <p>Far below {@code AStarPathfinder.DEFAULT_NODE_BUDGET}, and for a different reason. That
     * figure was chosen as far below anything that would hang a <em>test</em>; this one runs on
     * the client thread of a running game, where a hundred thousand expansions against live
     * block reads is a multi-second freeze. It is a stall guard, not a search-effort policy —
     * C4 owns the policy and this must not pretend to.
     */
    public static final int NODE_BUDGET = 10000;

    private final int nodeBudget;

    private Pos goal;

    /** The client level instance last seen by {@link #onLevel}, compared by identity. */
    private Object lastLevel;

    /** Uses {@link #NODE_BUDGET}. */
    public PathProbe() {
        this(NODE_BUDGET);
    }

    /**
     * @param nodeBudget the search budget; must be positive
     */
    public PathProbe(int nodeBudget) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        this.nodeBudget = nodeBudget;
    }

    /**
     * Records where a later {@link #run} should path to. Replaces any previous mark.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     */
    public void markGoal(int x, int y, int z) {
        this.goal = new Pos(x, y, z);
    }

    /**
     * Discards any marked goal when the client level instance changes.
     *
     * <p>Call once per tick from the adapter's poll, before the keys are read, passing whatever
     * the platform calls the current client level — or {@code null} outside a world. Compared by
     * identity, exactly as {@code AdapterRuntime} compares it to decide when to stop the core,
     * and deliberately so: a dimension change replaces the level without ending the session, and
     * that is the case this exists for. Passing the same instance every tick is the normal path
     * and does nothing.
     *
     * <p>Holding the reference does not pin an unloaded world. It is overwritten with the current
     * level the moment a change is seen, so it only ever names the level loaded now, or
     * {@code null}.
     *
     * @param level the current client level, or {@code null} if none is loaded
     */
    public void onLevel(Object level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;
        goal = null;
    }

    /**
     * Searches from a position to the marked goal.
     *
     * @param world the world to read; never {@code null}
     * @param startX where the search begins
     * @param startY where the search begins
     * @param startZ where the search begins
     * @return the report; never {@code null}, and never throwing merely because no goal is
     *         marked
     */
    public ProbeReport run(BlockSource world, int startX, int startY, int startZ) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            return ProbeReport.notRun("Continuo path probe: no goal marked."
                + " Stand on the destination, press the mark key, then try again.");
        }

        Pos start = new Pos(startX, startY, startZ);

        // The search reads through a snapshot; the render below does not. A render window can be
        // 64 blocks per axis and touches each cell once, so pushing it through the snapshot would
        // add a quarter of a million entries to save nothing. The repeat reads are in the search.
        WorldSnapshot snapshot = new WorldSnapshot(world);
        long startedAt = System.nanoTime();
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            snapshot, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
        double elapsedMs = (System.nanoTime() - startedAt) / 1000000.0;
        SealedSnapshot sealed = snapshot.seal();

        ProbeBounds bounds = ProbeBounds.around(world, start, goal, result.path());
        StringBuilder map = new StringBuilder(PathRenderer.render(world,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            start, goal, result));

        StringBuilder summary = new StringBuilder();
        summary.append("Continuo path probe: ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost())
            .append(", ").append(start).append(" -> ").append(goal)
            .append(", budget ").append(nodeBudget)
            .append(", ").append(String.format(java.util.Locale.ROOT, "%.1f",
                Double.valueOf(elapsedMs))).append(" ms")
            .append(", snapshot ").append(sealed.size()).append(" positions / ")
            .append(sealed.reads()).append(" reads");
        if (sealed.size() > 0) {
            // Locale.ROOT because this reaches a log file that gets read on other machines, and a
            // default locale writes "3,8x" where the reader expects "3.8x".
            summary.append(" (").append(String.format(java.util.Locale.ROOT, "%.1f",
                (double) sealed.reads() / sealed.size())).append("x)");
        }

        if (bounds.clamped) {
            // The coordinates are in the map header's own "x,y,z" format rather than Pos's
            // "(x, y, z)", so a reader retyping them into a fixture has nothing to reformat.
            String at = goal.x() + "," + goal.y() + "," + goal.z();
            String where = bounds.contains(goal)
                ? "the goal at " + at + " does lie inside it and is drawn"
                : "the goal at " + at + " lies outside it, so no G is drawn and pasting this map"
                    + " back yields goal() == null until the goal is retyped from this line";
            append(summary, map, "the map is clamped to " + ProbeBounds.MAX_EXTENT
                + " blocks per axis and the window is anchored on the start, so terrain outside"
                + " it is not drawn; " + where);
        }

        int unnamed = countUnnamed(map);
        if (unnamed > 0) {
            // Deliberately ASCII, like the clamp notice above it: this string reaches the game's
            // log as well as the file, and a console is not guaranteed to render anything else.
            append(summary, map, unnamed + " of " + drawnCells(bounds) + " drawn cells are '"
                + BlockLegend.UNMAPPED + "', a block this legend cannot name - most often sand,"
                + " gravel, a ladder or a vine, since tags take part in BlockData equality and no"
                + " legend value carries one. Each re-parses as UNKNOWN, which is impassable, so"
                + " pasting this map back as a fixture can reproduce a different search than the"
                + " one captured here, and report the same outcome while doing it. The search"
                + " itself was unaffected; this is a limit of the drawing");
        }

        return ProbeReport.of(result.outcome(), summary.toString(), map.toString());
    }

    /**
     * Adds a notice to both halves of the report.
     *
     * <p>Appended to the map as a comment line rather than prepended, because the fixture parser
     * requires {@code origin:} on the first line and skips {@code //} lines. Prepending would make
     * exactly the maps worth pasting back unparseable.
     */
    private static void append(StringBuilder summary, StringBuilder map, String notice) {
        summary.append("; ").append(notice);
        map.append("// ").append(notice).append('\n');
    }

    /**
     * Counts the cells drawn as {@link BlockLegend#UNMAPPED} in the terrain slices.
     *
     * <p>Only the terrain is counted, and deliberately: the notices already appended sit below the
     * first {@code "// "} and one of them contains the character it is reporting on, so counting
     * the whole buffer would have this grow by one every time it ran.
     */
    private static int countUnnamed(StringBuilder map) {
        int summaryAt = map.indexOf("// ");
        int end = summaryAt < 0 ? map.length() : summaryAt;
        int count = 0;
        for (int i = 0; i < end; i++) {
            if (map.charAt(i) == BlockLegend.UNMAPPED) {
                count++;
            }
        }
        return count;
    }

    /** The number of terrain cells the window covers, which is what the count above is out of. */
    private static long drawnCells(ProbeBounds bounds) {
        return (long) (bounds.maxX - bounds.minX + 1)
            * (bounds.maxY - bounds.minY + 1)
            * (bounds.maxZ - bounds.minZ + 1);
    }
}
