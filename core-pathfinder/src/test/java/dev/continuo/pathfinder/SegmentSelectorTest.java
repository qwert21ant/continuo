package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backoff rule, tested as the pure arithmetic it is. No world, no search, no fixture.
 *
 * <p>The rule is deliberately the simple one: the lowest h offered, provided it beats the
 * segment's starting h by minProgress. Spec section 2.1 records that the richer rule this
 * replaced was measured and failed to reach the goal on every fixture.
 */
class SegmentSelectorTest {

    private static final double START_H = 100.0;
    private static final double MIN_PROGRESS = 10.0;

    private static SegmentSelector selector() {
        return new SegmentSelector(START_H, MIN_PROGRESS);
    }

    @Test
    void aFreshSelectorHasNoCandidate() {
        assertFalse(selector().hasCandidate());
    }

    @Test
    void aNodeThatDoesNotImproveEnoughIsIgnored() {
        SegmentSelector s = selector();
        s.consider(7L, 90.5);
        assertFalse(s.hasCandidate(), "90.5 misses the 90.0 threshold and must not qualify");
    }

    @Test
    void theThresholdItselfQualifies() {
        SegmentSelector s = selector();
        s.consider(7L, START_H - MIN_PROGRESS);
        assertTrue(s.hasCandidate(), "eligibility is h <= startH - minProgress, inclusive");
        assertEquals(7L, s.candidate());
    }

    @Test
    void theLowestHWins() {
        SegmentSelector s = selector();
        s.consider(1L, 80.0);
        s.consider(2L, 60.0);
        s.consider(3L, 70.0);
        assertEquals(2L, s.candidate());
    }

    @Test
    void tiesFallToTheEarlierOffer() {
        SegmentSelector s = selector();
        s.consider(1L, 60.0);
        s.consider(2L, 60.0);
        // Replacement is on strictly lower h only. Expansion order is already deterministic, so
        // this is what makes the returned segment deterministic too -- C1 section 5.1.
        assertEquals(1L, s.candidate());
    }

    @Test
    void aStartPositionCanNeverQualify() {
        SegmentSelector s = selector();
        s.consider(42L, START_H);
        assertFalse(s.hasCandidate(),
            "the start's own h equals startH, so a zero-length segment is impossible by"
                + " construction rather than by a special case");
    }

    @Test
    void candidateRefusesRatherThanReturningASentinel() {
        final SegmentSelector s = selector();
        IllegalStateException e = assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                s.candidate();
            }
        });
        assertTrue(e.getMessage().contains("no candidate"), e.getMessage());
    }

    @Test
    void minProgressMustBePositive() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new SegmentSelector(100.0, 0.0);
            }
        });
    }
}
