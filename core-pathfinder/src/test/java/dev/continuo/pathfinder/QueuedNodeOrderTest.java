package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins each leg of the open set's order independently.
 *
 * <p>Every fixture here is built so that the leg under test disagrees with the legs below it. A
 * test that only fed the comparator entries its remaining legs already order correctly would pass
 * against a comparator with that leg deleted, which is exactly the defect this class exists to
 * close: the whole-search golden-path test in {@code AStarPathfinderTest} survived reducing the
 * comparator to {@code f} alone, reversing the {@code g} leg, and stubbing the sequence leg to
 * {@code 0}.
 */
class QueuedNodeOrderTest {

    private final QueuedNodeOrder order = QueuedNodeOrder.INSTANCE;

    private static QueuedNode entry(double f, double g, int sequence) {
        return new QueuedNode(Pos.pack(0, 64, 0), f, g, sequence);
    }

    @Test
    void lowerFComesFirst() {
        // The g and sequence legs both prefer `costlier`, so only the f leg can order these.
        QueuedNode cheaper = entry(1.0, 0.0, 7);
        QueuedNode costlier = entry(2.0, 5.0, 1);

        assertTrue(order.compare(cheaper, costlier) < 0,
            "f is A* itself; the lower-f entry must poll first however the tie-breaks fall");
        assertTrue(order.compare(costlier, cheaper) > 0, "and the reverse must be symmetric");
    }

    @Test
    void equalFBreaksTowardTheHigherG() {
        // Equal f, so the f leg abstains. The sequence leg prefers `further`, so only the g leg
        // can put `nearer` first — and with f equal, higher g means lower h means nearer the goal.
        QueuedNode further = entry(5.0, 1.0, 1);
        QueuedNode nearer = entry(5.0, 3.0, 7);

        assertTrue(order.compare(nearer, further) < 0,
            "among equal-f entries the one with more cost already paid is the one closer to the"
                + " goal, and expanding it first is what spec 5.1 asks for");
        assertTrue(order.compare(further, nearer) > 0, "and the reverse must be symmetric");
    }

    @Test
    void equalFAndGBreakTowardTheEarlierSequence() {
        // Both numeric legs abstain, so nothing but the sequence can separate these two.
        QueuedNode earlier = entry(5.0, 2.0, 1);
        QueuedNode later = entry(5.0, 2.0, 7);

        assertTrue(order.compare(earlier, later) < 0,
            "with f and g equal the discovery order of the entry is the last thing left; without"
                + " it the heap is free to return either and the search stops being reproducible");
        assertTrue(order.compare(later, earlier) > 0, "and the reverse must be symmetric");
    }

    @Test
    void noTwoDistinctEntriesCompareEqual() {
        // Every combination of two f values and two g values, each at its own sequence number.
        // A comparator missing any leg leaves at least one of these pairs comparing equal.
        List<QueuedNode> entries = new ArrayList<QueuedNode>();
        int sequence = 0;
        for (int f = 0; f < 2; f++) {
            for (int g = 0; g < 2; g++) {
                entries.add(entry(f, g, sequence++));
            }
        }

        for (int i = 0; i < entries.size(); i++) {
            for (int j = 0; j < entries.size(); j++) {
                QueuedNode a = entries.get(i);
                QueuedNode b = entries.get(j);
                if (i == j) {
                    continue;
                }
                assertTrue(order.compare(a, b) != 0,
                    "entries " + i + " and " + j + " compare equal, so the order is not total and"
                        + " the heap may return either — this is how a search stops being"
                        + " deterministic without any test asserting a wrong path");
                assertTrue(Integer.signum(order.compare(a, b))
                        == -Integer.signum(order.compare(b, a)),
                    "entries " + i + " and " + j + " do not compare antisymmetrically");
            }
        }
    }
}
