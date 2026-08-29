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
import dev.continuo.pathfinder.SegmentedResult;
import dev.continuo.pathfinder.SegmentedSearch;

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
 * <p><b>The search runs three times, and the extra two are the measurement.</b> C4 §13.2 measured
 * a search at 110–173 ms against a 50 ms tick but could not say how much of that was reading the
 * world rather than searching it, and the answer decides C5's mechanism: global rule 1 pins every
 * SPI call to the main thread, so a fill cannot move off it however the search is scheduled. After
 * the live run seals its snapshot, the same search is replayed twice against the seal. Every
 * position the live run read is covered, so a replay makes no SPI call at all and expands the same
 * nodes in the same order — its time is the search's own arithmetic, and the difference is the
 * fill. Replaying twice rather than once separates a JIT warm-up from a real cost; the second
 * figure is the one the split uses. The replays are checked against the live run field by field,
 * so a divergence is reported rather than silently timed. This triples what a keypress costs,
 * which a dev-only probe can afford and a bot could not.
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
     * <p>25,000, matching {@code AStarPathfinder.DEFAULT_NODE_BUDGET} — see its javadoc for the
     * per-route expansion evidence (design §6) that sets the figure: every route measured so far,
     * including the 111-block {@code e-long-range} route at 17,423 expansions, fits inside a
     * single search at 143% of its need or better. It runs on the client thread of a running
     * game, where the cost of an expansion against live block reads is not yet measured — the
     * in-game timing instrumentation this branch ships has not yet been run in a Minecraft
     * client, so whether 25,000 expansions fits comfortably inside a tick is confirmed by that
     * run, not by this number.
     */
    public static final int NODE_BUDGET = 25000;

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
        GoalBlock target = new GoalBlock(goal.x(), goal.y(), goal.z());
        CapabilitySet caps = CapabilitySet.of(Capability.PARKOUR);

        // Built before the clock starts, and that is a correction rather than a tidy-up. This
        // construction used to sit inside the timed region, where AStarPathfinder's constructor
        // runs MovementRegistry.discover() -- a ServiceLoader scan that re-reads META-INF/services
        // and, on a first press, loads and initialises the movement classes. Whatever that costs
        // was being counted as search time in every figure C4 section 13 reports. It is timed
        // separately below so the size of the error is on the record rather than merely removed.
        long builtAt = System.nanoTime();
        SegmentedSearch search = new SegmentedSearch(new AStarPathfinder(nodeBudget));
        double setupMs = msSince(builtAt);

        // The search reads through a snapshot; the render below does not. A render window can be
        // 64 blocks per axis and touches each cell once, so pushing it through the snapshot would
        // add a quarter of a million entries to save nothing. The repeat reads are in the search.
        WorldSnapshot snapshot = new WorldSnapshot(world);
        long startedAt = System.nanoTime();
        SegmentedResult result = search.run(snapshot, startX, startY, startZ, target, caps);
        double elapsedMs = msSince(startedAt);
        SealedSnapshot sealed = snapshot.seal();

        // The fill/search split, measured rather than instrumented. Every position the live search
        // read is covered by the sealed snapshot and A* is deterministic over an identical world,
        // so replaying the same search against the seal expands the same nodes in the same order
        // with no SPI call at all: its time is the search's own arithmetic, and the difference is
        // what reading the world cost. Twice, because the first replay may still be JIT-warming
        // and a warm-up counted as search time would bias the split toward "the fill dominates".
        Replay firstReplay = replay(search, sealed, start, target, caps, result);
        Replay secondReplay = replay(search, sealed, start, target, caps, result);

        PathResult combined = result.asPathResult();

        ProbeBounds bounds = ProbeBounds.around(world, start, goal, result.path());
        StringBuilder map = new StringBuilder(PathRenderer.render(world,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            start, goal, combined));

        StringBuilder summary = new StringBuilder();
        summary.append("Continuo path probe: ").append(result.outcome())
            .append(", ").append(result.segments())
            .append(result.segments() == 1 ? " segment" : " segments")
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(combined.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost())
            .append(", ").append(start).append(" -> ").append(goal)
            .append(", budget ").append(nodeBudget)
            .append(", ").append(fmt(elapsedMs)).append(" ms")
            .append(", split setup ").append(fmt(setupMs)).append("ms")
            .append(", live ").append(fmt(elapsedMs)).append("ms")
            .append(", sealed ").append(fmt(firstReplay.ms)).append("ms")
            .append(" then ").append(fmt(secondReplay.ms)).append("ms")
            .append(", fill ~").append(fmt(elapsedMs - secondReplay.ms)).append("ms");
        if (elapsedMs > 0.0) {
            summary.append(" (").append(Math.round(
                100.0 * (elapsedMs - secondReplay.ms) / elapsedMs)).append("% of live)");
        }
        summary.append(", snapshot ").append(sealed.size()).append(" positions / ")
            .append(sealed.reads()).append(" reads");
        if (sealed.size() > 0) {
            // Locale.ROOT because this reaches a log file that gets read on other machines, and a
            // default locale writes "3,8x" where the reader expects "3.8x".
            summary.append(" (").append(String.format(java.util.Locale.ROOT, "%.1f",
                (double) sealed.reads() / sealed.size())).append("x)");
        }

        String diverged = firstReplay.divergence != null
            ? firstReplay.divergence : secondReplay.divergence;
        if (diverged != null) {
            append(summary, map, "the sealed replay diverged from the live search (" + diverged
                + "), so the split figures above time two different searches and mean nothing."
                + " Every position the live search read is covered by the seal and A* is"
                + " deterministic over an identical world, so this should be impossible; treat it"
                + " as a defect in the snapshot or in the search, not as a measurement artefact");
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

    /** One replay of the live search against the seal: what it cost, and whether it agreed. */
    private static final class Replay {

        final double ms;

        /** What differed from the live run, or {@code null} if nothing did. */
        final String divergence;

        Replay(double ms, String divergence) {
            this.ms = ms;
            this.divergence = divergence;
        }
    }

    /**
     * Runs the live search again against the sealed snapshot, timing it and checking it agreed.
     *
     * <p>No SPI call happens here: a {@code SealedSnapshot} holds no live source and has no method
     * that would use one. That is the whole point — this is what the same search costs when every
     * world read is already paid for, which is also what an off-thread search would cost.
     */
    private static Replay replay(SegmentedSearch search, SealedSnapshot sealed, Pos start,
                                 GoalBlock target, CapabilitySet caps, SegmentedResult live) {
        long at = System.nanoTime();
        SegmentedResult again = search.run(sealed, start.x(), start.y(), start.z(), target, caps);
        return new Replay(msSince(at), divergence(live, again));
    }

    /**
     * What differs between a search and its replay, or {@code null} if nothing does.
     *
     * <p>Costs are compared bit-for-bit rather than within a tolerance, deliberately. The two runs
     * execute identical arithmetic over identical inputs in an identical order, so any difference
     * at all means the replay was not the same search — and a tolerance would hide exactly the
     * small reroute that invalidates the timing while looking like rounding.
     *
     * <p>Package-private because nothing driven through {@link #run} can force a divergence — the
     * snapshot memoises every read, so the replay cannot see a different world — and a check that
     * can never fire in a test is a check that can quietly stop working.
     */
    static String divergence(SegmentedResult live, SegmentedResult replayed) {
        if (live.outcome() != replayed.outcome()) {
            return "outcome " + live.outcome() + " became " + replayed.outcome();
        }
        if (Double.compare(live.cost(), replayed.cost()) != 0) {
            return "cost " + live.cost() + " became " + replayed.cost();
        }
        if (live.path().size() != replayed.path().size()) {
            return "cost matched but the route is " + live.path().size() + " steps against "
                + replayed.path().size();
        }
        if (live.expanded().size() != replayed.expanded().size()) {
            return "same route and cost, but " + live.expanded().size()
                + " nodes expanded against " + replayed.expanded().size();
        }
        if (live.segments() != replayed.segments()) {
            return "same route and cost, but " + live.segments() + " segments against "
                + replayed.segments();
        }
        return null;
    }

    /** Milliseconds since a {@link System#nanoTime} reading. */
    private static double msSince(long nanos) {
        return (System.nanoTime() - nanos) / 1000000.0;
    }

    /**
     * One decimal place, in {@code Locale.ROOT}.
     *
     * <p>Not the default locale: this reaches a log file that gets read on other machines, and a
     * default locale writes {@code "3,8"} where the reader expects {@code "3.8"}.
     */
    private static String fmt(double ms) {
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(ms));
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
