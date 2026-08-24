package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders a world and a search result as text art, in the same format fixtures are written in.
 *
 * <p>ASCII rather than an image, deliberately. The people and agents who debug this read test
 * output as text; a PNG written to the build directory would be invisible to every one of them.
 *
 * <p><b>The output round-trips, with one stated limit.</b> Overlay characters read back as air
 * when parsed, so a rendered failure can be pasted straight into a test as a new fixture. Terrain
 * <em>not covered by an overlay</em> survives exactly, and the annotations degrade harmlessly.
 * Put this in an assertion message rather than reasoning about a failing path from coordinates
 * alone.
 *
 * <p><b>An overlay replaces the terrain character rather than accompanying it</b>, so a passable
 * non-air block underneath one — a carpet, a snow layer — re-parses as air. Nothing is stacked or
 * escaped to avoid this, deliberately: dropping the overlay wherever the terrain is non-air would
 * lose the path marker exactly where the terrain is interesting, which is worse for the debugging
 * this class exists to serve.
 *
 * <p>The practical consequence is mild rather than absent, and is worth stating rather than
 * implying away. Every block that can sit under an overlay is passable and non-supporting by
 * construction — the search only walks where it can stand — and air is passable and
 * non-supporting too, so a pasted-back fixture still poses the same routing question and still
 * reproduces the failure. What it loses is the record of which passable block was there.
 * {@code PathRendererTest} pins both halves of this: what survives, and what does not.
 */
final class PathRenderer {

    private PathRenderer() {
    }

    /**
     * @param world the world that was searched; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    static String render(FixtureWorld world, PathResult result) {
        Map<BlockData, Character> reverse = new HashMap<BlockData, Character>();
        for (Map.Entry<Character, BlockData> entry : BlockLegend.legend().entrySet()) {
            if (!reverse.containsKey(entry.getValue())) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }

        Set<Long> path = new HashSet<Long>();
        for (Pos pos : result.path()) {
            path.add(Long.valueOf(pos.packed()));
        }
        Set<Long> expanded = new HashSet<Long>();
        for (Pos pos : result.expanded()) {
            expanded.add(Long.valueOf(pos.packed()));
        }

        // A failed search has an empty path, so fall back to the fixture's own S and G markers.
        // Without this the one render that matters most — the failure you want to paste back in
        // as a regression fixture — loses the goal entirely and shows the start as an ordinary
        // expanded node, because the start is always in `expanded` whether or not it is in `path`.
        Long start = marker(result.path().isEmpty() ? world.start() : result.path().get(0));
        Long goal = marker(result.path().isEmpty()
            ? world.goal() : result.path().get(result.path().size() - 1));

        StringBuilder out = new StringBuilder();
        out.append("origin: ").append(world.minX()).append(',')
            .append(world.minY()).append(',').append(world.minZ()).append('\n');

        for (int y = world.minY(); y < world.maxY(); y++) {
            out.append("--- y=").append(y).append('\n');
            for (int z = world.minZ(); z <= world.maxZ(); z++) {
                for (int x = world.minX(); x <= world.maxX(); x++) {
                    Long key = Long.valueOf(Pos.pack(x, y, z));
                    if (key.equals(start)) {
                        out.append(FixtureWorld.START);
                    } else if (key.equals(goal)) {
                        out.append(FixtureWorld.GOAL);
                    } else if (path.contains(key)) {
                        out.append(FixtureWorld.PATH);
                    } else if (expanded.contains(key)) {
                        out.append(FixtureWorld.EXPANDED);
                    } else {
                        Character ch = reverse.get(world.at(x, y, z));
                        out.append(ch == null ? '?' : ch.charValue());
                    }
                }
                out.append('\n');
            }
        }

        appendSummary(out, result);
        return out.toString();
    }

    /** @return the position packed for marker lookup, or {@code null} if there is none */
    private static Long marker(Pos pos) {
        return pos == null ? null : Long.valueOf(pos.packed());
    }

    private static void appendSummary(StringBuilder out, PathResult result) {
        out.append("// ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost()).append('\n');
    }
}
