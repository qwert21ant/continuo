package dev.continuo.runtime;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathRenderer;
import dev.continuo.pathfinder.PathResult;
import dev.continuo.pathfinder.Pos;

/**
 * Runs A\u002A against a live world and renders the result, so a route can be looked at in a
 * running game.
 *
 * <p>Dev-only, like {@code BlockDumpWalker} beside it. Nothing calls it during normal operation.
 *
 * <p><b>Main thread only.</b> A live {@code BlockSource} inherits {@code IBlockView}'s delivery
 * window, and this reads through it synchronously. That is also why C3's {@code WorldSnapshot} is
 * not a prerequisite: a snapshot is what makes reads safe <em>off</em> the main thread, and
 * nothing here leaves it.
 *
 * <p>The mark-then-run shape is deliberate: it lets an owner walk to somewhere awkward, mark it,
 * walk back, and search across terrain they chose, without any new SPI surface for naming a
 * destination.
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
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            world, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));

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
            .append(", budget ").append(nodeBudget);

        if (bounds.clamped) {
            String notice = "the map is clamped to " + ProbeBounds.MAX_EXTENT
                + " blocks per axis, so terrain outside it is not drawn";
            summary.append("; ").append(notice);
            // Appended as a comment line rather than prepended, because the fixture parser
            // requires "origin:" on the first line and skips "//" lines. Prepending it would
            // make exactly the maps worth pasting back unparseable.
            map.append("// ").append(notice).append('\n');
        }

        return ProbeReport.of(result.outcome(), summary.toString(), map.toString());
    }
}
