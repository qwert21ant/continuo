package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aMovementTheAuditCannotExerciseAtAllIsReportedRatherThanPassed() {
        // The failure this closes: violations() returned an empty list both when a declaration
        // was honest and when the audit never elicited a single offer, and every caller reads
        // empty as "passed". A movement gated on a precondition the palette cannot produce — a
        // ladder on BlockTag.CLIMBABLE, which is what this gate stands in for and which the
        // palette still deliberately does not generate — therefore got a silent pass however
        // wrong its declaration was. This double's declaration is wrong by four.
        List<String> violations = MovementContract.violations(
            new PreconditionGatedMovement("a.ladder", PreconditionGatedMovement.Gate.CLIMBABLE));

        assertEquals(1, violations.size(), "expected exactly one violation, got " + violations);
        assertTrue(violations.get(0).contains("a.ladder"), violations.get(0));
        assertTrue(violations.get(0).contains("offered nothing"), violations.get(0));
        assertTrue(violations.get(0).contains("NOT A PASS"),
            "a plugin author must not be able to read this as a clean audit: " + violations.get(0));
    }

    @Test
    void thePaletteReachesEveryPreconditionItClaimsToGenerate() {
        // The other half of the same defect. Widening the palette is only worth anything if the
        // audit really produces those blocks, and a palette entry that never appeared would look
        // exactly like one that did — every gate below would report "offered nothing" instead of
        // the cost lie. The reviewer's demonstration was a movement gated on BlockShape.FENCE
        // returning zero violations while wrong by four; each of these is that demonstration at
        // one of the four preconditions the palette gained.
        PreconditionGatedMovement.Gate[] reachable = {
            PreconditionGatedMovement.Gate.FENCE,
            PreconditionGatedMovement.Gate.UNKNOWN,
            PreconditionGatedMovement.Gate.HARMFUL,
            PreconditionGatedMovement.Gate.WATER
        };

        for (int i = 0; i < reachable.length; i++) {
            String id = "a.gatedOn" + reachable[i];
            List<String> violations = MovementContract.violations(
                new PreconditionGatedMovement(id, reachable[i]));

            assertEquals(1, violations.size(), id + ": expected one violation, got " + violations);
            assertTrue(violations.get(0).contains("1.2"),
                id + " must be caught on its cost, not reported as unreachable: "
                    + violations.get(0));
        }
    }

    @Test
    void aMovementWhoseOnlyEdgeIsItsOwnPositionIsReportedRatherThanPassed() {
        // A span of zero, so the offer is the position expand() was handed. Neither of the other
        // two branches sees it: cost / 0 is Infinity, which is not below any declared figure, and
        // the no-offer branch cannot fire because an offer really was made. This is the one shape
        // that reads as a clean audit while nothing was checked, and it is why the guard exists —
        // it was once deleted as inert on the strength of the Infinity half of that alone.
        List<String> violations =
            MovementContract.violations(new FakeMovement("a.stayer", 3.5636, 0, 3.5636));

        assertEquals(1, violations.size(), "expected exactly one violation, got " + violations);
        assertTrue(violations.get(0).contains("a.stayer"), violations.get(0));
        assertTrue(violations.get(0).contains("its own position"), violations.get(0));
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

    @Test
    void aDiagonalOfferUnderstatingItsCostIsCaught() {
        // A movement offering a diagonal for the cost of a single cardinal step. Under the old
        // Chebyshev span that offer measured one axis step and passed; a diagonal is worth sqrt(2)
        // units, and conflating the two is the same mistake that made the heuristic loose.
        List<String> violations = MovementContract.violations(
            new DiagonalOfferMovement("bad.diagonal", 3.0, 3.0));

        assertFalse(violations.isEmpty(),
            "a diagonal offered at the cost of a single cardinal step understates its rate");
        assertTrue(violations.get(0).contains("bad.diagonal"), violations.get(0));
    }

    @Test
    void anHonestDiagonalOfferPasses() {
        // The same movement paying the full sqrt(2) units. Without this the test above passes on
        // an audit that rejects every diagonal, which checks nothing.
        List<String> violations = MovementContract.violations(
            new DiagonalOfferMovement("good.diagonal", 3.0, 3.0 * Math.sqrt(2.0)));

        assertTrue(violations.isEmpty(), String.valueOf(violations));
    }

    @Test
    void aVerticalOfferUnderstatingItsCostIsCaught() {
        // The vertical half of the audit, which has no coverage otherwise: every other double in
        // this suite declares an infinite vertical rate and never moves in Y.
        List<String> violations = MovementContract.violations(
            new DropOfferMovement("bad.drop", 5.0, 3.0));

        assertFalse(violations.isEmpty(),
            "a three-block drop offered for less than three times the declared vertical rate"
                + " understates it");
        assertTrue(violations.get(0).contains("bad.drop"), violations.get(0));
    }
}
