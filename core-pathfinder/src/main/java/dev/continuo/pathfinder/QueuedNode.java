package dev.continuo.pathfinder;

/**
 * An immutable snapshot of a node's priority at the moment it entered the open set.
 *
 * <p><b>Why the queue holds these rather than {@link PathNode} itself.</b> When A* finds a cheaper
 * route to a node it has already seen, it lowers that node's {@code g}. If the queue held the node
 * object, that would be an in-place decrease-key with no sift-up: the heap invariant breaks, and
 * {@code poll()} can then return an entry that is not the minimum. A* would close a node at a
 * non-optimal cost and — because closed nodes are never reopened — return a path that is not the
 * cheapest.
 *
 * <p>Snapshots make the queue's keys immutable, so the heap stays a heap. A node may appear
 * several times; the entry with the lowest {@code f} is polled first, and later entries find the
 * node already closed and are discarded.
 */
final class QueuedNode {

    final long packed;
    final double f;
    final double g;
    final int sequence;

    QueuedNode(long packed, double f, double g, int sequence) {
        this.packed = packed;
        this.f = f;
        this.g = g;
        this.sequence = sequence;
    }
}
