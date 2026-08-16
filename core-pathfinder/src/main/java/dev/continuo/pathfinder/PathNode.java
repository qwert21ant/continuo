package dev.continuo.pathfinder;

/**
 * A position the search has reached.
 *
 * <p>Mutable and package-private: A* updates {@code g} and {@code f} in place when it finds a
 * cheaper route to a node it has already seen.
 */
final class PathNode {

    final long packed;
    final int sequence;
    double g;
    double f;
    PathNode parent;
    boolean closed;

    PathNode(long packed, int sequence) {
        this.packed = packed;
        this.sequence = sequence;
        this.g = Double.POSITIVE_INFINITY;
        this.f = Double.POSITIVE_INFINITY;
    }
}
