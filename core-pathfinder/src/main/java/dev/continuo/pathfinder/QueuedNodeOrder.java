package dev.continuo.pathfinder;

import java.util.Comparator;

/**
 * The order the open set polls {@link QueuedNode} entries in.
 *
 * <p>Three legs, in this order:
 *
 * <ol>
 *   <li><b>Lower {@code f} first.</b> This is A* itself; the rest is tie-breaking.</li>
 *   <li><b>Then higher {@code g} first.</b> Among entries of equal {@code f} that is the one with
 *       the lower {@code h}, i.e. the one nearer the goal, which is what spec §5.1 asks for.
 *       Expanding it first reaches the goal sooner without changing which path is cheapest.</li>
 *   <li><b>Then lower sequence first.</b> The discovery order of the <em>entry</em>, not of the
 *       node: one node can hold several entries, and only a per-entry sequence makes the
 *       comparison total.</li>
 * </ol>
 *
 * <p><b>Totality is the point of the third leg.</b> Two entries created at different moments never
 * compare equal, so the heap has no freedom left and an identical search over an identical world
 * returns an identical path. That is what lets a test assert <em>which</em> path it expects rather
 * than merely that one exists.
 *
 * <p>A named type rather than an anonymous class inside {@link AStarPathfinder#findPath} so that
 * each leg can be pinned by a unit test. A whole-search golden-path test cannot do that: it pins
 * movement iteration order, and passes against a comparator with legs missing.
 */
final class QueuedNodeOrder implements Comparator<QueuedNode> {

    /** The single shared instance; the comparator has no state. */
    static final QueuedNodeOrder INSTANCE = new QueuedNodeOrder();

    private QueuedNodeOrder() {
    }

    /**
     * @param a one entry; never {@code null}
     * @param b the other entry; never {@code null}
     * @return negative if {@code a} polls first, positive if {@code b} does, zero only if the two
     *         agree on all three legs — which distinct entries never do
     */
    @Override
    public int compare(QueuedNode a, QueuedNode b) {
        int byF = Double.compare(a.f, b.f);
        if (byF != 0) {
            return byF;
        }
        int byG = Double.compare(b.g, a.g);
        if (byG != 0) {
            return byG;
        }
        return Integer.compare(a.sequence, b.sequence);
    }
}
