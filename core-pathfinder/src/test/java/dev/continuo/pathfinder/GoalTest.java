package dev.continuo.pathfinder;

import dev.continuo.movement.MovementCosts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalTest {

    @Test
    void goalBlockIsReachedOnlyAtTheExactPosition() {
        Goal goal = new GoalBlock(10, 64, -3);

        assertTrue(goal.isReached(10, 64, -3));
        assertFalse(goal.isReached(10, 65, -3));
        assertFalse(goal.isReached(11, 64, -3));
        assertFalse(goal.isReached(10, 64, -2));
    }

    @Test
    void goalBlockHasNoDistanceToItself() {
        assertEquals(0.0, new GoalBlock(10, 64, -3).heuristic(10, 64, -3), 1.0e-9);
    }

    @Test
    void goalXzIgnoresHeight() {
        Goal goal = new GoalXZ(10, -3);

        assertTrue(goal.isReached(10, 64, -3));
        assertTrue(goal.isReached(10, 200, -3));
        assertFalse(goal.isReached(11, 64, -3));
    }

    @Test
    void goalXzHeuristicIgnoresHeight() {
        Goal goal = new GoalXZ(10, -3);

        assertEquals(goal.heuristic(0, 64, 0), goal.heuristic(0, 200, 0), 1.0e-9);
    }

    @Test
    void theHeuristicCountsTheFewestPossibleMovesNotTheDistanceWalked() {
        Goal goal = new GoalBlock(3, 64, 3);

        assertEquals(3 * MovementCosts.cheapestMove(), goal.heuristic(0, 64, 0), 1.0e-9,
            "a diagonal covers X and Z at once, so three moves suffice, not six");
    }

    @Test
    void verticalDistanceCountsWhenItExceedsHorizontal() {
        Goal goal = new GoalBlock(0, 74, 0);

        assertEquals(10 * MovementCosts.cheapestMove(), goal.heuristic(0, 64, 0), 1.0e-9,
            "every move changes Y by at most one, so ten levels need ten moves");
    }

    @Test
    void theHeuristicIsNeverNegative() {
        Goal goal = new GoalBlock(-5, 64, -5);

        assertTrue(goal.heuristic(5, 100, 5) >= 0);
        assertTrue(goal.heuristic(-5, 64, -5) >= 0);
    }

    @Test
    void aReachedGoalHasZeroHeuristicSoTheSearchCanTerminate() {
        Goal block = new GoalBlock(7, 64, 7);
        assertEquals(0.0, block.heuristic(7, 64, 7), 1.0e-9);

        Goal column = new GoalXZ(7, 7);
        assertEquals(0.0, column.heuristic(7, 64, 7), 1.0e-9);
    }
}
