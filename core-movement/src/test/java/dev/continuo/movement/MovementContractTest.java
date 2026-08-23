package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementContractTest {

    @Test
    void anHonestDeclarationHasNoViolations() {
        assertEquals(java.util.Collections.<String>emptyList(),
            MovementContract.violations(new FakeMovement("a.honest", 3.5636, 1, 3.5636)));
    }

    @Test
    void aWideMovementDeclaringItsWholeCostRatherThanItsPerStepCostIsCaught() {
        // Offers a neighbour four blocks away for 4.8 ticks — 1.2 per axis step — while
        // declaring 4.8. This is the mistake that makes A* inadmissible with a green suite.
        List<String> violations =
            MovementContract.violations(new FakeMovement("a.liar", 4.8, 4, 4.8));

        assertEquals(1, violations.size(), "expected exactly one violation, got " + violations);
        assertTrue(violations.get(0).contains("a.liar"), violations.get(0));
        assertTrue(violations.get(0).contains("1.2"), violations.get(0));
    }

    @Test
    void theViolationNamesTheOfferThatBrokeTheDeclaration() {
        List<String> violations =
            MovementContract.violations(new FakeMovement("a.liar", 4.8, 4, 4.8));

        assertTrue(violations.get(0).contains("4.8"),
            "the message must carry the declared figure so the fix is obvious: " + violations);
    }

    @Test
    void onlyTheFirstOfTwoViolatingOffersInTheSameCallIsReported() {
        // TwoOfferMovement offers two neighbours per call, both violating. FakeMovement offers
        // only one per call and so cannot witness that the audit stops at the first offending
        // offer within a call rather than reporting every one of them.
        List<String> violations =
            MovementContract.violations(new TwoOfferMovement("a.twiceLiar", 4.8));

        assertEquals(1, violations.size(), "expected exactly one violation, got " + violations);
    }
}
