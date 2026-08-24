package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Renders a world and a search result as text art, in the same format fixtures are written in.
 *
 * <p>ASCII rather than an image, deliberately. The people and agents who debug this read test
 * output as text; a PNG written to the build directory would be invisible to every one of them.
 *
 * <p><b>The output round-trips, with two stated limits.</b> Overlay characters read back as air
 * when parsed, so a rendered failure can be pasted straight into a test as a new fixture. Terrain
 * <em>not covered by an overlay</em> and <em>named by the legend</em> survives exactly.
 *
 * <p><b>An overlay replaces the terrain character rather than accompanying it</b>, so a passable
 * non-air block underneath one — a carpet, a snow layer — re-parses as air. Nothing is stacked or
 * escaped to avoid this, deliberately: dropping the overlay wherever the terrain is non-air would
 * lose the path marker exactly where the terrain is interesting, which is worse for the debugging
 * this class exists to serve.
 *
 * <p>The practical consequence is mild rather than absent. Every block that can sit under an
 * overlay is passable and non-supporting by construction — the search only walks where it can
 * stand — and air is passable and non-supporting too, so a pasted-back fixture still poses the
 * same routing question and still reproduces the failure. What it loses is the record of which
 * passable block was there.
 *
 * <p><b>The second limit belongs to live worlds and is sharper.</b> A block whose classification
 * {@link BlockLegend} does not name renders as {@link BlockLegend#UNMAPPED}, and that character
 * re-parses as {@code BlockData.UNKNOWN}, which is impassable. So a map captured from a running
 * game can be <em>stricter</em> than the world it came from: a {@code ?} that was really a
 * passable block becomes a wall, and the pasted fixture may fail to reproduce the routing
 * question it was captured for. Ordinary terrain is mostly unaffected — stone, dirt and leaves
 * all classify to the legend's full cube, and slabs, stairs, fences, water and lava are named —
 * but <b>a map with {@code ?} anywhere near the route needs checking before it is trusted as a
 * fixture.</b>
 *
 * <p><b>Tagged blocks are the common surprise.</b> Tags participate in {@code BlockData}
 * equality and every legend value carries an empty tag set (bar lava's {@code AVOID}), while
 * {@code BlockClassifier} attaches {@code FALLING} to sand and gravel and {@code CLIMBABLE} to
 * ladders and vines. Those blocks therefore match no legend entry and render as {@code ?} even
 * though they are perfectly ordinary — a desert or a beach comes back as a wall of them.
 *
 * <p>{@code PathRendererTest} pins all of this: what survives, and what does not.
 */
public final class PathRenderer {

    /** Where the search began. */
    public static final char START = 'S';

    /** What it was trying to reach. */
    public static final char GOAL = 'G';

    /** A position on the returned route. */
    public static final char PATH = '*';

    /** A position the search expanded without using. */
    public static final char EXPANDED = '+';

    private PathRenderer() {
    }

    /**
     * @param world the world that was searched; never {@code null}
     * @param minX the lowest X to draw, inclusive
     * @param minY the lowest Y to draw, inclusive
     * @param minZ the lowest Z to draw, inclusive
     * @param maxX the highest X to draw, <b>inclusive</b>
     * @param maxY the highest Y to draw, <b>inclusive</b> — note this differs from
     *             {@code BlockSource.maxY()}, which is one past the top. All six bounds here
     *             mean the same thing as each other, which is what a caller reading the
     *             signature will assume
     * @param maxZ the highest Z to draw, <b>inclusive</b>
     * @param start where the search began; never {@code null}
     * @param goal what it was trying to reach; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    public static String render(BlockSource world,
                                int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ,
                                Pos start, Pos goal, PathResult result) {
        if (world == null || start == null || goal == null || result == null) {
            throw new IllegalArgumentException("no argument may be null");
        }

        Set<Long> path = new HashSet<Long>();
        for (Pos pos : result.path()) {
            path.add(Long.valueOf(pos.packed()));
        }
        Set<Long> expanded = new HashSet<Long>();
        for (Pos pos : result.expanded()) {
            expanded.add(Long.valueOf(pos.packed()));
        }

        // A failed search has an empty path, so fall back to the caller's own start and goal.
        // Without this the one render that matters most — the failure you want to paste back in
        // as a regression fixture — loses the goal entirely and shows the start as an ordinary
        // expanded node, because the start is always in `expanded` whether or not it is in
        // `path`.
        Long startKey = Long.valueOf(
            (result.path().isEmpty() ? start : result.path().get(0)).packed());
        Long goalKey = Long.valueOf(
            (result.path().isEmpty() ? goal : result.path().get(result.path().size() - 1))
                .packed());

        StringBuilder out = new StringBuilder();
        out.append("origin: ").append(minX).append(',')
            .append(minY).append(',').append(minZ).append('\n');

        for (int y = minY; y <= maxY; y++) {
            out.append("--- y=").append(y).append('\n');
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    Long key = Long.valueOf(Pos.pack(x, y, z));
                    if (key.equals(startKey)) {
                        out.append(START);
                    } else if (key.equals(goalKey)) {
                        out.append(GOAL);
                    } else if (path.contains(key)) {
                        out.append(PATH);
                    } else if (expanded.contains(key)) {
                        out.append(EXPANDED);
                    } else {
                        out.append(BlockLegend.characterFor(world.at(x, y, z)));
                    }
                }
                out.append('\n');
            }
        }

        appendSummary(out, result);
        return out.toString();
    }

    private static void appendSummary(StringBuilder out, PathResult result) {
        out.append("// ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost()).append('\n');
    }
}
