package dev.continuo.pathfinder;

import java.util.List;

/**
 * Factory helpers for result types whose constructors are package-private.
 *
 * <p>Exists because {@link Steps} is an implementation detail of this package while
 * {@link Step} is part of its public surface: a caller outside the package that has a list of
 * positions — an executor adopting one, or a test building one by hand — needs the same derivation
 * without the package being opened up.
 */
public final class PathResults {

    private PathResults() {
    }

    /**
     * The moves joining a list of positions, derived exactly as a search result derives its own.
     *
     * @param path the route, start to end; never {@code null}
     * @return one step per adjacent pair, unmodifiable; empty when {@code path} has fewer than two
     *         entries
     * @throws IllegalArgumentException if {@code path} is null
     */
    public static List<Step> stepsOf(List<Pos> path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        return Steps.derive(path);
    }
}
