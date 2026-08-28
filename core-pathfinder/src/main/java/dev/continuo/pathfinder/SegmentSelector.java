package dev.continuo.pathfinder;

/**
 * Picks the node a budget-exhausted search backs off to: the lowest {@code h} offered, provided it
 * beats the segment's starting {@code h} by {@code minProgress}.
 *
 * <p><b>There is no cost term, and that is a measured decision rather than an omission.</b> The
 * rule began as {@code h + g/C}, on the reasoning that penalising {@code g} would stop the search
 * committing to a node reached by an expensive wandering route. Swept across real terrain before
 * this class was written, every finite {@code C} failed to reach the goal at every budget, because
 * penalising {@code g} biases selection toward nodes near the <em>start</em> and produces segments
 * too timid to commit. Section 2.1 of the design has the table.
 *
 * <p><b>The eligibility test is the load-bearing half.</b> It is what makes a run terminate: an
 * admissible {@code h} lower-bounds the remaining cost, each segment lowers it by at least
 * {@code minProgress}, and it cannot fall below zero, so a run needs at most
 * {@code startH / minProgress} segments. Without it the same scoring livelocks.
 *
 * <p>One {@code long} and three {@code double}s. It reads no world and allocates nothing per
 * offer.
 */
final class SegmentSelector {

    private final double threshold;

    private boolean has;
    private long candidate;
    private double bestH;

    /**
     * @param startH the heuristic at the position this segment starts from
     * @param minProgress how much closer to the goal a candidate must be, in ticks; must be
     *                    positive, or a zero-length segment could qualify
     * @throws IllegalArgumentException if {@code minProgress} is not positive
     */
    SegmentSelector(double startH, double minProgress) {
        if (!(minProgress > 0.0)) {
            throw new IllegalArgumentException(
                "minProgress must be positive, got " + minProgress);
        }
        this.threshold = startH - minProgress;
        this.bestH = Double.POSITIVE_INFINITY;
    }

    /**
     * Offers an expanded node.
     *
     * @param packed the node's packed position
     * @param h its heuristic distance to the goal
     */
    void consider(long packed, double h) {
        if (h > threshold) {
            return;
        }
        if (h < bestH) {
            bestH = h;
            candidate = packed;
            has = true;
        }
    }

    /** @return whether any node has qualified */
    boolean hasCandidate() {
        return has;
    }

    /**
     * @return the packed position of the qualifying node closest to the goal
     * @throws IllegalStateException if none has qualified
     */
    long candidate() {
        if (!has) {
            throw new IllegalStateException("no candidate: no expanded node beat the start by"
                + " minProgress");
        }
        return candidate;
    }
}
