package dev.continuo.pathfinder;

/**
 * A position the search has reached.
 *
 * <p>Mutable and package-private: A* lowers {@code g} and re-points {@code parent} when it finds
 * a cheaper route to a node it has already seen. This object is reachable only through the
 * search's node map and is <b>never</b> placed in the open set — the queue holds immutable
 * {@link QueuedNode} snapshots instead, so lowering {@code g} here cannot disturb the heap.
 */
final class PathNode {

    final long packed;
    double g;
    PathNode parent;
    boolean closed;

    PathNode(long packed) {
        this.packed = packed;
        this.g = Double.POSITIVE_INFINITY;
    }
}
