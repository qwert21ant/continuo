package dev.continuo.pathfinder;

import dev.continuo.movement.MovementKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Derives a path's moves from the positions it joins.
 *
 * <p>The single implementation {@link PathResult} and {@link SegmentedResult} both call, so the two
 * cannot drift.
 *
 * <p><b>Walks the path only, never the expanded set.</b> On the measured route the path is 245
 * entries against 25,053 expanded, which is what makes deriving eagerly in a constructor
 * affordable.
 */
final class Steps {

    private Steps() {
    }

    /**
     * @param path the route, start to end
     * @return one step per adjacent pair, unmodifiable; empty when {@code path} has fewer than two
     *         entries
     */
    static List<Step> derive(List<Pos> path) {
        if (path.size() < 2) {
            return Collections.emptyList();
        }
        List<Step> steps = new ArrayList<Step>(path.size() - 1);
        Pos from = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            Pos to = path.get(i);
            steps.add(new Step(to, MovementKind.forDelta(
                to.x() - from.x(), to.y() - from.y(), to.z() - from.z())));
            from = to;
        }
        return Collections.unmodifiableList(steps);
    }
}
