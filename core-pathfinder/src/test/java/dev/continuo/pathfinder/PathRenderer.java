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
 * <p><b>The output round-trips.</b> Overlay characters read back as air when parsed, so a
 * rendered failure can be pasted straight into a test as a new fixture — the terrain survives
 * and the annotations degrade harmlessly. Put this in an assertion message rather than reasoning
 * about a failing path from coordinates alone.
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
        for (Map.Entry<Character, BlockData> entry : FixtureBlocks.legend().entrySet()) {
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

        Long start = result.path().isEmpty()
            ? null : Long.valueOf(result.path().get(0).packed());
        Long goal = result.path().isEmpty()
            ? null : Long.valueOf(result.path().get(result.path().size() - 1).packed());

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

        out.append("// ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost()).append('\n');
        return out.toString();
    }
}
