package dev.continuo.pathfinder;

import dev.continuo.movement.MovementKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepsTest {

    @Test
    void aStepPerAdjacentPairAndOneFewerThanPositions() {
        List<Pos> path = Arrays.asList(
            new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 65, 0), new Pos(3, 62, 0));

        List<Step> steps = Steps.derive(path);

        assertEquals(3, steps.size(), "n positions describe n-1 moves");
        assertEquals(new Pos(1, 64, 0), steps.get(0).to());
        assertEquals(MovementKind.TRAVERSE, steps.get(0).kind());
        assertEquals(MovementKind.ASCEND, steps.get(1).kind());
        assertEquals(MovementKind.DESCEND, steps.get(2).kind(), "a three-block drop is a descend");
    }

    @Test
    void aPathTooShortToContainAMoveHasNoSteps() {
        assertTrue(Steps.derive(Collections.<Pos>emptyList()).isEmpty(),
            "NO_PATH and BUDGET_EXCEEDED carry an empty path");
        assertTrue(Steps.derive(Collections.singletonList(new Pos(0, 64, 0))).isEmpty(),
            "a start that is already the goal is one position and no moves");
    }

    @Test
    void anUnnameableDeltaYieldsANullKindRatherThanThrowing() {
        // Building a result must never fail: the probe renders paths this executor would refuse
        // to drive. The null propagates to the executor, which stops with a message naming it.
        List<Step> steps = Steps.derive(Arrays.asList(new Pos(0, 64, 0), new Pos(3, 68, 2)));

        assertEquals(1, steps.size());
        assertNull(steps.get(0).kind());
    }

    @Test
    void theDerivedListIsUnmodifiable() {
        List<Step> steps = Steps.derive(Arrays.asList(new Pos(0, 64, 0), new Pos(1, 64, 0)));

        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                steps.add(null);
            }
        });
    }

    @Test
    void aJoinedTwoSegmentPathDerivesEndToEnd() {
        // Run.append drops each segment's first position, so a joined route contains no repeated
        // position and every adjacent pair is one legal move. If it ever stopped doing that, the
        // duplicate would derive as a null kind and this test would catch it here rather than in
        // a client.
        List<Pos> first = Arrays.asList(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 64, 0));
        List<Pos> second = Arrays.asList(new Pos(2, 64, 0), new Pos(3, 64, 0));

        List<Pos> joined = new ArrayList<Pos>(first);
        joined.addAll(second.subList(1, second.size()));

        List<Step> steps = Steps.derive(joined);

        assertEquals(3, steps.size());
        for (Step step : steps) {
            assertEquals(MovementKind.TRAVERSE, step.kind(),
                "every step of a joined flat route is a traverse; a null would mean a duplicate");
        }
    }
}
