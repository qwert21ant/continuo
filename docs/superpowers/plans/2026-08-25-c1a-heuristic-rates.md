# C1a Heuristic Rates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace A\*'s Chebyshev heuristic and its single cost multiplier with an octile heuristic and a two-rate declaration, so the search stops under-estimating diagonal travel by √2.

**Architecture:** `ActiveMovements` stops handing out a bare `double` and hands out an immutable `HeuristicRates` carrying a horizontal rate (ticks per *octile unit*) and a vertical rate (ticks per block of Y), each an independent minimum over the active movements. `IMovementType` declares both instead of one. `GoalBlock` and `GoalXZ` combine them through `HeuristicRates` itself, which is also where the zero-displacement guard lives. Admissibility stays a property of how the rates are derived rather than a numeric fact about the current cost table.

**Tech Stack:** Java 8 bytecode, JUnit 5, Gradle. Modules `:core-movement`, `:core-pathfinder`, `:movement-parkour`.

**Spec:** `docs/superpowers/specs/2026-08-25-c1a-heuristic-rates-design.md`

## Global Constraints

- **Java 8 bytecode, machine-checked.** No `var`, no records, no `List.of`, no text blocks, no switch expressions. **No lambdas in main source.** Anonymous inner classes instead.
- **Gate every check on `./gradlew build`, never `./gradlew :module:test`.** Javadoc is build-failing (`-Xdoclint:all,-missing -Xwerror`) and `:test` does not run it.
- **Never run `./gradlew clean`.** It destroys the 1.7.10 decompiled sources at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`, which take a long time to regenerate. Use `build --rerun-tasks`.
- **`GRADLE_USER_HOME` is already `C:\GradleHome`.** Never set, export or override it.
- **A `{@link}` to a type outside the module's javadoc sourcepath breaks the build.** `:core-pathfinder` can `{@link}` `:core-movement` types (it depends on it). When unsure, use `{@code}`.
- **Filtered Gradle runs corrupt XML test counts.** `--tests 'X'` leaves only that class's results in `build/test-results/`. Only count tests after a full `build --rerun-tasks`.
- **`MovementCosts` is not edited.** No cost constant changes value in this plan. The only declared number that moves is `walk.diagonal`'s, and only because its unit changed underneath it.
- **No SPI change, no adapter change, no new module dependency.** `:core-pathfinder` already depends on `:core-movement`; nothing else is added, and `settings.gradle.kts` / `allowedProjectDependencies` are untouched.
- **Do not adjust the brief.** If a step's stated expectation does not match what you observe, **stop and report the discrepancy** rather than editing the expectation to fit. Every number below is derived in the spec; a mismatch means either the derivation or the code is wrong, and both are worth knowing.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `core-movement/src/main/java/dev/continuo/movement/HeuristicRates.java` | The two rates, the octile unit, and the estimate combination including the zero-displacement guard |
| `core-movement/src/test/java/dev/continuo/movement/HeuristicRatesTest.java` | Octile arithmetic, guards, validation |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/OctileSearchTest.java` | The expansion-count regression — the test that would have caught this defect |

**Modified:**

| File | Change |
|---|---|
| `core-movement/.../IMovementType.java` | `minCostPerAxisStep()` → `minCostPerHorizontalUnit()` + `minCostPerVerticalStep()` |
| `core-movement/.../ActiveMovements.java` | `cheapestAxisStep()` → `rates()`, two independent minima |
| `core-movement/.../MovementRegistry.java` | Validate both declarations |
| `core-movement/.../IMovementRegistry.java` | Javadoc `@throws` wording |
| `core-movement/.../MovementContract.java` | Two-way audit |
| `core-movement/.../MoveSink.java` | Javadoc reference only |
| `core-pathfinder/.../Goal.java`, `GoalBlock.java`, `GoalXZ.java` | Parameter type and octile formula |
| `core-pathfinder/.../AStarPathfinder.java` | Pass `HeuristicRates` |
| `core-pathfinder/.../TraverseMove.java`, `AscendMove.java`, `DescendMove.java`, `DiagonalMove.java` | Declare two rates |
| `movement-parkour/.../ParkourMove.java` | Declare two rates |
| 6 test doubles, 7 test classes | See Tasks 2 and 3 |

---

## Task 1: `HeuristicRates`

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/HeuristicRates.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/HeuristicRatesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HeuristicRates(double horizontal, double vertical)`; `double horizontal()`; `double vertical()`; `static double octileUnits(int dx, int dz)`; `double estimate(int dx, int dy, int dz)`; `double horizontalEstimate(int dx, int dz)`. Tasks 2, 3 and 4 all use these exact names.

- [ ] **Step 1: Write the failing test**

Create `core-movement/src/test/java/dev/continuo/movement/HeuristicRatesTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicRatesTest {

    private static final double SQRT2 = Math.sqrt(2.0);

    @Test
    void aCardinalStepIsOneUnitAndADiagonalStepIsRootTwo() {
        // The whole point of the unit. If a diagonal were one unit like a cardinal step, the
        // heuristic would credit a diagonal move at the cardinal rate, which is exactly the
        // defect this change exists to fix.
        assertEquals(1.0, HeuristicRates.octileUnits(1, 0), 1.0e-9);
        assertEquals(1.0, HeuristicRates.octileUnits(0, 1), 1.0e-9);
        assertEquals(SQRT2, HeuristicRates.octileUnits(1, 1), 1.0e-9);
    }

    @Test
    void anLShapedGapCostsItsDiagonalLegPlusItsStraightRemainder() {
        // (2,1) is one diagonal step and one cardinal step, not two of either.
        assertEquals(1.0 + SQRT2, HeuristicRates.octileUnits(2, 1), 1.0e-9);
        assertEquals(1.0 + SQRT2, HeuristicRates.octileUnits(1, 2), 1.0e-9);
    }

    @Test
    void theUnitIgnoresSign() {
        assertEquals(SQRT2, HeuristicRates.octileUnits(-1, -1), 1.0e-9);
        assertEquals(3.0, HeuristicRates.octileUnits(-3, 0), 1.0e-9);
    }

    @Test
    void aPureDiagonalIsRootTwoPerBlock() {
        // The measured case: 180 blocks diagonal is 254.5584 units, not 180.
        assertEquals(180.0 * SQRT2, HeuristicRates.octileUnits(180, 180), 1.0e-9);
    }

    @Test
    void theEstimateTakesTheLargerOfItsHorizontalAndVerticalHalves() {
        // Max rather than sum, because walk.ascend closes a horizontal axis and a vertical one
        // in a single move; summing would double-count it and overestimate.
        HeuristicRates rates = new HeuristicRates(2.0, 5.0);
        assertEquals(10.0, rates.estimate(3, 2, 0), 1.0e-9, "vertical 2*5=10 beats horizontal 3*2=6");
        assertEquals(8.0, rates.estimate(4, 1, 0), 1.0e-9, "horizontal 4*2=8 beats vertical 1*5=5");
    }

    @Test
    void anInfiniteRateContributesNothingWhenThatAxisDoesNotMove() {
        // THE TRAP THIS METHOD EXISTS FOR. A registry whose movements never travel vertically
        // yields vertical() == POSITIVE_INFINITY, and Infinity * 0 is NaN, not 0. A NaN estimate
        // poisons the priority queue's ordering and A* silently stops being A*. Guarding it here
        // means the two Goal implementations cannot each get it wrong separately.
        HeuristicRates rates = new HeuristicRates(2.0, Double.POSITIVE_INFINITY);
        double estimate = rates.estimate(3, 0, 0);
        assertFalse(Double.isNaN(estimate), "a zero vertical gap must not produce NaN");
        assertEquals(6.0, estimate, 1.0e-9);
    }

    @Test
    void anInfiniteHorizontalRateContributesNothingWhenThatAxisDoesNotMove() {
        HeuristicRates rates = new HeuristicRates(Double.POSITIVE_INFINITY, 4.0);
        double estimate = rates.estimate(0, 2, 0);
        assertFalse(Double.isNaN(estimate), "a zero horizontal gap must not produce NaN");
        assertEquals(8.0, estimate, 1.0e-9);
    }

    @Test
    void aGoalAlreadyReachedEstimatesZero() {
        HeuristicRates rates = new HeuristicRates(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertEquals(0.0, rates.estimate(0, 0, 0), 1.0e-9);
    }

    @Test
    void theHorizontalOnlyEstimateIgnoresYEntirely() {
        // GoalXZ targets a column, so it must not be charged for height at all.
        HeuristicRates rates = new HeuristicRates(2.0, 5.0);
        assertEquals(6.0, rates.horizontalEstimate(3, 0), 1.0e-9);
        assertFalse(Double.isNaN(new HeuristicRates(Double.POSITIVE_INFINITY, 5.0)
            .horizontalEstimate(0, 0)));
    }

    @Test
    void aNonPositiveOrNaNRateIsRejected() {
        // A zero rate drags the heuristic to zero and turns A* into an exhaustive search; a NaN
        // one makes every comparison false and the ordering arbitrary.
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(0.0, 1.0);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(1.0, -1.0);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(Double.NaN, 1.0);
            }
        });
    }

    @Test
    void ratesInfiniteOnBothAxesAreRejected() {
        // Such a set can reach nothing at all, so accepting it would produce a search that
        // expands the start node and reports NO_PATH for a reason no message explains.
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new HeuristicRates(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
        });
    }

    @Test
    void theUnitIsSubadditiveSoAPerEdgeBoundSumsAlongAPath() {
        // Consistency, which A* needs for its closed set to be safe: the estimate may never drop
        // by more than an edge costs. Subadditivity of the distance is what guarantees it, and
        // asserting it directly is cheaper and stronger than sampling searches for the symptom.
        for (int ax = -4; ax <= 4; ax++) {
            for (int az = -4; az <= 4; az++) {
                for (int bx = -4; bx <= 4; bx++) {
                    for (int bz = -4; bz <= 4; bz++) {
                        double whole = HeuristicRates.octileUnits(ax + bx, az + bz);
                        double parts = HeuristicRates.octileUnits(ax, az)
                            + HeuristicRates.octileUnits(bx, bz);
                        assertTrue(whole <= parts + 1.0e-9,
                            "octileUnits(" + (ax + bx) + "," + (az + bz) + ")=" + whole
                                + " exceeds the sum of its legs " + parts);
                    }
                }
            }
        }
    }

    @Test
    void oneInfiniteRateIsAccepted() {
        // The ordinary case: no built-in movement travels vertically without also travelling
        // horizontally, and walk.traverse never travels vertically at all.
        HeuristicRates rates = new HeuristicRates(3.5636, Double.POSITIVE_INFINITY);
        assertTrue(Double.isInfinite(rates.vertical()));
        assertEquals(3.5636, rates.horizontal(), 1.0e-9);
    }
}
```

Note the ordering conflict: `aGoalAlreadyReachedEstimatesZero` constructs both-infinite rates, which `ratesInfiniteOnBothAxesAreRejected` forbids. **Delete `aGoalAlreadyReachedEstimatesZero`** — it is covered by the two single-infinity tests and cannot be constructed legally. This note is deliberate: it is the kind of contradiction that otherwise surfaces as a confusing failure in step 2.

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.HeuristicRatesTest'`

Expected: **compile failure**, `cannot find symbol: class HeuristicRates`. That is an error, not yet a failing test — proceed to step 3, then re-run to see real assertion failures before implementing behaviour.

- [ ] **Step 3: Write the implementation**

Create `core-movement/src/main/java/dev/continuo/movement/HeuristicRates.java`:

```java
package dev.continuo.movement;

/**
 * The rates A* scales its distance estimate by: one for horizontal travel, one for vertical.
 *
 * <p><b>Two rates rather than one, and they are minimised independently.</b> A single multiplier
 * over all axes means the cheapest movement on any axis degrades the estimate on every other one —
 * a ladder, which cannot travel horizontally at all, would loosen every horizontal estimate the
 * search ever makes. Separating them is what keeps a cheap vertical movement's cost where it
 * belongs.
 *
 * <p><b>Horizontal distance is measured in octile units</b>, not Chebyshev steps: one cardinal
 * step is one unit and one diagonal step is {@code √2} units. That matters because
 * {@code walk.diagonal} costs {@code TRAVERSE × √2}, so measuring its move as one step credited it
 * at the cardinal rate and left the estimate short by {@code √2} on any diagonal. A* then degrades
 * toward Dijkstra: before this, a 180-block diagonal on flat open ground exhausted a 10,000-node
 * budget outright.
 *
 * <p>Octile distance is the true shortest-path metric on an 8-connected grid with step weights
 * {@code 1} and {@code √2}, and since {@code 1 ≤ √2 ≤ 2} it is a metric — in particular
 * subadditive, which is what lets a per-edge bound sum along a path into a whole-path bound. That
 * holds for displacements of any shape, so a movement spanning two blocks or an L needs no special
 * case.
 *
 * <p>Immutable.
 */
public final class HeuristicRates {

    private static final double SQRT2 = Math.sqrt(2.0);

    private final double horizontal;
    private final double vertical;

    /**
     * @param horizontal ticks per octile unit of horizontal travel; positive, and
     *                   {@link Double#POSITIVE_INFINITY} if no active movement travels
     *                   horizontally
     * @param vertical   ticks per block of vertical travel; positive, and
     *                   {@link Double#POSITIVE_INFINITY} if no active movement travels vertically
     * @throws IllegalArgumentException if either is not positive, either is {@code NaN}, or both
     *                                  are infinite
     */
    public HeuristicRates(double horizontal, double vertical) {
        // Written as !(x > 0.0) rather than x <= 0.0 so that NaN is caught here too: every
        // comparison against NaN is false, so "NaN <= 0" would pass and a NaN rate would make the
        // priority queue's ordering arbitrary with nothing failing anywhere else.
        if (!(horizontal > 0.0)) {
            throw new IllegalArgumentException("horizontal must be positive, got " + horizontal);
        }
        if (!(vertical > 0.0)) {
            throw new IllegalArgumentException("vertical must be positive, got " + vertical);
        }
        if (Double.isInfinite(horizontal) && Double.isInfinite(vertical)) {
            throw new IllegalArgumentException("a movement set that travels along neither axis"
                + " class can reach nothing; both rates are infinite");
        }
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    /** @return ticks per octile unit of horizontal travel */
    public double horizontal() {
        return horizontal;
    }

    /** @return ticks per block of vertical travel */
    public double vertical() {
        return vertical;
    }

    /**
     * Horizontal distance in octile units: one per cardinal step, {@code √2} per diagonal step.
     *
     * <p>Shared by the heuristic and by {@code MovementContract}'s audit of the declarations that
     * feed it. <b>One definition on purpose</b> — two that agree today would drift the first time
     * either side changed, and the drift would be silent, because a movement would simply be
     * audited against a different distance than the search charges it for.
     *
     * @param dx signed X displacement
     * @param dz signed Z displacement
     * @return the distance in units; zero only when both displacements are zero
     */
    public static double octileUnits(int dx, int dz) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        int lo = Math.min(adx, adz);
        int hi = Math.max(adx, adz);
        return (hi - lo) + SQRT2 * lo;
    }

    /**
     * The estimated remaining cost for a displacement.
     *
     * <p><b>The larger of the two halves, never their sum.</b> {@code walk.ascend} closes a
     * horizontal axis and a vertical one in one move, so summing would charge twice for a single
     * movement and overestimate — which costs admissibility, and with it A*'s shortest-path
     * guarantee.
     *
     * @param dx signed X displacement
     * @param dy signed Y displacement
     * @param dz signed Z displacement
     * @return the estimate in ticks; never {@code NaN}
     */
    public double estimate(int dx, int dy, int dz) {
        return Math.max(horizontalEstimate(dx, dz), scaled(vertical, Math.abs(dy)));
    }

    /**
     * The horizontal half alone, for a goal that does not constrain Y.
     *
     * @param dx signed X displacement
     * @param dz signed Z displacement
     * @return the estimate in ticks; never {@code NaN}
     */
    public double horizontalEstimate(int dx, int dz) {
        return scaled(horizontal, octileUnits(dx, dz));
    }

    /**
     * Multiplies a rate by a distance, treating a zero distance as costing nothing.
     *
     * <p><b>The zero case is not defensive, it is arithmetic.</b> An axis class no movement
     * travels has an infinite rate, and {@code Infinity × 0} is {@code NaN} rather than zero. A
     * {@code NaN} estimate makes every priority-queue comparison false, so the open set orders
     * arbitrarily and A* silently stops being A*. Centralised here so the two {@code Goal}
     * implementations cannot each get it wrong separately.
     */
    private static double scaled(double rate, double distance) {
        return distance == 0.0 ? 0.0 : rate * distance;
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.HeuristicRatesTest'`

Expected: PASS, 12 tests (after deleting `aGoalAlreadyReachedEstimatesZero` per step 1).

- [ ] **Step 5: Run the full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. Nothing consumes `HeuristicRates` yet, so no other module can break.

- [ ] **Step 6: Commit**

```bash
git add core-movement/src/main/java/dev/continuo/movement/HeuristicRates.java core-movement/src/test/java/dev/continuo/movement/HeuristicRatesTest.java
git commit -m "feat(c1a): add HeuristicRates and the octile unit

Nothing consumes it yet. The zero-displacement guard is centralised here
because an axis class no movement travels has an infinite rate, and
Infinity * 0 is NaN -- which would make every priority-queue comparison
false and stop A* being A* with nothing else failing."
```

---

## Task 2: Movements declare two rates

**Files:**
- Modify: `core-movement/src/main/java/dev/continuo/movement/IMovementType.java`
- Modify: `core-movement/src/main/java/dev/continuo/movement/ActiveMovements.java`
- Modify: `core-movement/src/main/java/dev/continuo/movement/MovementRegistry.java`
- Modify: `core-movement/src/main/java/dev/continuo/movement/IMovementRegistry.java` (javadoc)
- Modify: `core-movement/src/main/java/dev/continuo/movement/MoveSink.java` (javadoc)
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/{TraverseMove,AscendMove,DescendMove,DiagonalMove}.java`
- Modify: `movement-parkour/src/main/java/dev/continuo/movement/parkour/ParkourMove.java`
- Modify: `core-movement/src/test/java/dev/continuo/movement/{FakeMovement,DiscoverableMovement,PreconditionGatedMovement,TwoOfferMovement}.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FakeMovement.java`
- Modify: `core-movement/src/test/java/dev/continuo/movement/MovementRegistryTest.java`
- Modify: `core-movement/src/main/java/dev/continuo/movement/MovementContract.java:95-168`
- Modify: `core-movement/src/test/java/dev/continuo/movement/MovementContractTest.java`
- Create: `core-movement/src/test/java/dev/continuo/movement/{TwoRateMovement,DiagonalOfferMovement,DropOfferMovement}.java`

**Interfaces:**
- Consumes: `HeuristicRates` from Task 1.
- Produces: `IMovementType.minCostPerHorizontalUnit()`, `IMovementType.minCostPerVerticalStep()`, `ActiveMovements.rates()` returning `HeuristicRates`. `ActiveMovements.cheapestAxisStep()` and `IMovementType.minCostPerAxisStep()` no longer exist.

**This task cannot be split, and it includes `MovementContract`'s audit.** Changing a Java interface breaks every implementor at compile time, so the interface, all five production movements, all six test doubles and the registry must move together or nothing compiles.

The audit has to move in the same commit, and this was verified by arithmetic rather than assumed. `FALL_TICKS = {4.6147, 6.7881, 8.4687}` and `TRAVERSE = 3.5636`, so `DescendMove`'s new horizontal rate is its shallowest whole cost, `3.5636 + 4.6147 = 8.1783`. If the audit kept dividing by a Chebyshev span, a 3-block drop would measure `12.0323 / 3 = 4.0108` against a declared `8.1783` and `BuiltInMovementContractTest` would fail inside this task. **The declarations and the audit that polices them change meaning together.**

For reference, every built-in movement meets its new declaration under the new audit, at equality or above: traverse `3.5636/1`, diagonal `5.0397/√2 = 3.5636`, ascend `6.5582/1` on both axes, descend `12.0323/1` horizontal and `12.0323/3 = 4.0108` vertical, parkour `10.1218/2 = 5.0609`. **If any of them reports a violation, stop and report** — a declared rate is too high and the heuristic would overestimate.

- [ ] **Step 1: Write the failing test**

Add to `core-movement/src/test/java/dev/continuo/movement/MovementRegistryTest.java`:

```java
    @Test
    void theTwoRatesAreMinimisedIndependently() {
        // D2's entire justification. A movement that is cheap vertically must not lower the
        // horizontal rate, because it may not travel horizontally at all -- a ladder is exactly
        // that. Under one shared multiplier its number would degrade every horizontal estimate
        // the search ever makes, and no other test in this suite would notice.
        MovementRegistry registry = new MovementRegistry();
        registry.register(new TwoRateMovement("a.walk", 3.5636, Double.POSITIVE_INFINITY));
        registry.register(new TwoRateMovement("b.ladder", Double.POSITIVE_INFINITY, 0.5));

        HeuristicRates rates = registry.activeFor(CapabilitySet.none()).rates();

        assertEquals(3.5636, rates.horizontal(), 1.0e-9,
            "a movement that cannot travel horizontally must not set the horizontal rate");
        assertEquals(0.5, rates.vertical(), 1.0e-9);
    }

    @Test
    void aMovementDeclaringNeitherAxisIsRejected() {
        final MovementRegistry registry = new MovementRegistry();
        assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    registry.register(new TwoRateMovement("a.nowhere",
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
                }
            });
    }

    @Test
    void aNonPositiveDeclarationIsRejectedOnEitherAxis() {
        final MovementRegistry registry = new MovementRegistry();
        assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    registry.register(new TwoRateMovement("a.zero", 0.0, 1.0));
                }
            });
        assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    registry.register(new TwoRateMovement("b.zero", 1.0, 0.0));
                }
            });
    }
```

Create `core-movement/src/test/java/dev/continuo/movement/TwoRateMovement.java`:

```java
package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement that declares both rates explicitly and offers nothing.
 *
 * <p>{@link FakeMovement} always declares an infinite vertical rate, so no test built on it can
 * show that the two rates are minimised independently — which is the whole reason
 * {@code HeuristicRates} carries two.
 */
final class TwoRateMovement implements IMovementType {

    private final String id;
    private final double horizontal;
    private final double vertical;

    TwoRateMovement(String id, double horizontal, double vertical) {
        this.id = id;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return horizontal;
    }

    @Override
    public double minCostPerVerticalStep() {
        return vertical;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        // Declarations only; nothing here is audited by these tests.
    }
}
```

Also add to `core-movement/src/test/java/dev/continuo/movement/MovementContractTest.java`:

```java
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
```

Create `core-movement/src/test/java/dev/continuo/movement/DiagonalOfferMovement.java`:

```java
package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** Offers exactly one diagonal neighbour, at a cost the test chooses. */
final class DiagonalOfferMovement implements IMovementType {

    private final String id;
    private final double horizontal;
    private final double cost;

    DiagonalOfferMovement(String id, double horizontal, double cost) {
        this.id = id;
        this.horizontal = horizontal;
        this.cost = cost;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return horizontal;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + 1, ctx.y(), ctx.z() + 1, cost);
    }
}
```

Create `core-movement/src/test/java/dev/continuo/movement/DropOfferMovement.java`:

```java
package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** Offers exactly one three-block drop, at a cost the test chooses. */
final class DropOfferMovement implements IMovementType {

    private final String id;
    private final double vertical;
    private final double cost;

    DropOfferMovement(String id, double vertical, double cost) {
        this.id = id;
        this.vertical = vertical;
        this.cost = cost;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double minCostPerVerticalStep() {
        return vertical;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x(), ctx.y() - 3, ctx.z(), cost);
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementRegistryTest' --tests 'dev.continuo.movement.MovementContractTest'`

Expected: compile failure — `minCostPerHorizontalUnit()` is not a member of `IMovementType`, and `ActiveMovements.rates()` does not exist.

- [ ] **Step 3: Change the interface**

In `IMovementType.java`, replace the whole `minCostPerAxisStep()` method and its javadoc with:

```java
    /**
     * A lower bound on what one <em>octile unit</em> of horizontal travel costs this movement.
     *
     * <p><b>Get this wrong and the search silently stops returning shortest paths.</b> The
     * heuristic is an octile distance times the smallest value any active movement declares here.
     * Declaring too high a figure makes the heuristic overestimate, which costs admissibility with
     * no test failing anywhere else.
     *
     * <p>Concretely, the contract is: <b>the smallest
     * {@code cost / HeuristicRates.octileUnits(dx, dz)} of any neighbour this movement can ever
     * offer.</b> A movement stepping one block along one axis declares its cheapest cost. One
     * stepping diagonally divides by {@code √2}. One jumping two blocks along an axis divides
     * by two.
     *
     * <p>Return {@link Double#POSITIVE_INFINITY} if this movement never displaces horizontally.
     *
     * <p>It is a declaration, so it is checked rather than trusted:
     * {@code MovementContract#violations(IMovementType)} audits it against real expansions.
     *
     * @return the lower bound, in ticks; must be positive, and may be infinite
     */
    double minCostPerHorizontalUnit();

    /**
     * A lower bound on what one block of vertical travel costs this movement.
     *
     * <p>The smallest {@code cost / |dy|} of any neighbour this movement can ever offer.
     * Minimised separately from {@link #minCostPerHorizontalUnit()} so that a movement which is
     * cheap per block of height — a fall, or a ladder later — cannot degrade the estimate for
     * horizontal travel it may not be capable of at all.
     *
     * <p>Return {@link Double#POSITIVE_INFINITY} if this movement never displaces vertically.
     * A movement declaring both rates infinite reaches nothing and is rejected at registration.
     *
     * @return the lower bound, in ticks; must be positive, and may be infinite
     */
    double minCostPerVerticalStep();
```

- [ ] **Step 4: Change `ActiveMovements`**

Replace the field, the constructor's computation and the accessor:

```java
    private final List<IMovementType> movements;
    private final HeuristicRates rates;
```

In the constructor, replace the `cheapest` loop with:

```java
        double horizontal = Double.POSITIVE_INFINITY;
        double vertical = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.movements.size(); i++) {
            IMovementType type = this.movements.get(i);
            double declaredHorizontal = type.minCostPerHorizontalUnit();
            if (declaredHorizontal < horizontal) {
                horizontal = declaredHorizontal;
            }
            double declaredVertical = type.minCostPerVerticalStep();
            if (declaredVertical < vertical) {
                vertical = declaredVertical;
            }
        }
        this.rates = new HeuristicRates(horizontal, vertical);
```

Replace `cheapestAxisStep()` with:

```java
    /**
     * The rates the heuristic scales its distance estimate by.
     *
     * <p><b>This is what makes A* admissible, and it is structural rather than numeric.</b> Each
     * rate is a minimum over exactly the movements the search will use, so every movement
     * satisfies {@code cost >= horizontal × octileUnits(dx, dz)} and
     * {@code cost >= vertical × |dy|} by definition, and therefore bounds the estimate's decrease
     * across any edge it offers. Adding a cheap wide movement merely loosens the heuristic rather
     * than breaking it.
     *
     * @return the rates; never {@code null}
     */
    public HeuristicRates rates() {
        return rates;
    }
```

- [ ] **Step 5: Change `MovementRegistry.register`**

Replace the `declared` block (currently lines 38–43) with:

```java
        double horizontal = type.minCostPerHorizontalUnit();
        double vertical = type.minCostPerVerticalStep();
        // !(x > 0.0) rather than x <= 0.0 so NaN is rejected here too; every comparison against
        // NaN is false, so "NaN <= 0" would pass and the heuristic's ordering would go arbitrary.
        if (!(horizontal > 0.0)) {
            throw new IllegalArgumentException("movement " + id + " declares a"
                + " minCostPerHorizontalUnit of " + horizontal + "; it must be positive, or it"
                + " would drag the heuristic's horizontal rate to zero and turn A* into an"
                + " exhaustive search");
        }
        if (!(vertical > 0.0)) {
            throw new IllegalArgumentException("movement " + id + " declares a"
                + " minCostPerVerticalStep of " + vertical + "; it must be positive, or it would"
                + " drag the heuristic's vertical rate to zero and turn A* into an exhaustive"
                + " search");
        }
        if (Double.isInfinite(horizontal) && Double.isInfinite(vertical)) {
            throw new IllegalArgumentException("movement " + id + " declares both rates infinite,"
                + " which says it never displaces along either axis class and so can reach"
                + " nothing; a movement that offers no travel has no place in a registry");
        }
```

In `IMovementRegistry.java`, update the `@throws` javadoc that names `minCostPerAxisStep` to name both new methods. In `MoveSink.java`, update the `{@link IMovementType#minCostPerAxisStep()}` reference to `{@link IMovementType#minCostPerHorizontalUnit()}`.

- [ ] **Step 6: Change the five production movements**

`TraverseMove` — replace `minCostPerAxisStep()`:

```java
    @Override
    public double minCostPerHorizontalUnit() {
        return MovementCosts.TRAVERSE;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }
```

`DiagonalMove` — **this is the only movement whose number changes**:

```java
    /**
     * One diagonal step is {@code √2} octile units, so the per-unit rate is the diagonal cost
     * divided by that — which comes out at exactly {@code MovementCosts.TRAVERSE}, since
     * {@code DIAGONAL} is defined as {@code TRAVERSE × √2}. Declaring the whole diagonal cost
     * here, as this did before C1a, credited a diagonal move at one unit and left the heuristic
     * short by {@code √2} on any diagonal.
     */
    private static final double MIN_COST_PER_HORIZONTAL_UNIT =
        MovementCosts.DIAGONAL / HeuristicRates.octileUnits(1, 1);

    @Override
    public double minCostPerHorizontalUnit() {
        return MIN_COST_PER_HORIZONTAL_UNIT;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }
```

Add `import dev.continuo.movement.HeuristicRates;` to `DiagonalMove`.

`AscendMove`:

```java
    @Override
    public double minCostPerHorizontalUnit() {
        return MovementCosts.ASCEND;
    }

    @Override
    public double minCostPerVerticalStep() {
        return MovementCosts.ASCEND;
    }
```

`DescendMove` — rename the existing constant and add the horizontal one:

```java
    /**
     * Every descend offer displaces exactly one horizontal unit whatever the drop, so the
     * horizontal rate is its cheapest whole cost — the <em>shallowest</em> drop.
     */
    private static final double MIN_COST_PER_HORIZONTAL_UNIT = cheapestOffer();

    /**
     * The deepest fall gives the worst cost per block of height, because a fall accelerates: its
     * marginal cost per block falls away while the heuristic's credit per block does not.
     * Computed rather than written as a literal, so that re-deriving {@code MAX_SAFE_FALL} or
     * {@code fallTicks} cannot leave a stale figure behind.
     */
    private static final double MIN_COST_PER_VERTICAL_STEP = worstRatio();

    private static double cheapestOffer() {
        double cheapest = Double.POSITIVE_INFINITY;
        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            double cost = MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop);
            if (cost < cheapest) {
                cheapest = cost;
            }
        }
        return cheapest;
    }

    @Override
    public double minCostPerHorizontalUnit() {
        return MIN_COST_PER_HORIZONTAL_UNIT;
    }

    @Override
    public double minCostPerVerticalStep() {
        return MIN_COST_PER_VERTICAL_STEP;
    }
```

Keep `worstRatio()` exactly as it is.

`ParkourMove`:

```java
    /** Two blocks along one axis, which is two octile units, so half the cost. */
    private static final double MIN_COST_PER_HORIZONTAL_UNIT =
        COST / HeuristicRates.octileUnits(2, 0);

    @Override
    public double minCostPerHorizontalUnit() {
        return MIN_COST_PER_HORIZONTAL_UNIT;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }
```

Add `import dev.continuo.movement.HeuristicRates;` to `ParkourMove`.

- [ ] **Step 7: Change the six test doubles**

Every one of them offers along a single horizontal axis and never displaces vertically, so each keeps its existing declared number as the horizontal rate and returns infinity for vertical. **No constructor signature changes**, so no call site in any test needs editing.

In each of `core-movement/src/test/.../FakeMovement.java`, `DiscoverableMovement.java`, `PreconditionGatedMovement.java`, `TwoOfferMovement.java`, and `core-pathfinder/src/test/.../FakeMovement.java`, replace the `minCostPerAxisStep()` override with the same two methods, returning the same expression it returned before for horizontal and `Double.POSITIVE_INFINITY` for vertical. For example, in `core-movement`'s `FakeMovement`:

```java
    @Override
    public double minCostPerHorizontalUnit() {
        return minCostPerAxisStep;
    }

    @Override
    public double minCostPerVerticalStep() {
        return Double.POSITIVE_INFINITY;
    }
```

Leave the private field name `minCostPerAxisStep` alone in these doubles if renaming it widens the diff; it is a local detail. `DiscoverableMovement` returns the literal `7.0`; `PreconditionGatedMovement` returns `DECLARED`; `TwoOfferMovement` returns its `minCostPerAxisStep` field.

Also update `HeuristicMultiplierAdmissibilityTest`'s inner movement classes in `core-pathfinder/src/test/.../HeuristicMultiplierAdmissibilityTest.java` if they override `minCostPerAxisStep()` directly rather than extending `FakeMovement` — they offer along X and Z only, so the same substitution applies.

- [ ] **Step 8: Change the audit in `MovementContract`**

In `MovementContract.violations`, replace `final double declared = type.minCostPerAxisStep();` with:

```java
        final double declaredHorizontal = type.minCostPerHorizontalUnit();
        final double declaredVertical = type.minCostPerVerticalStep();
```

Inside the `offer` method, replace the span computation, the self-offer guard and the comparison with:

```java
                        // The context always sits at x = 0, z = 0, so nx and nz are already
                        // offsets from the origin; only Y needs subtracting.
                        int ady = Math.abs(ny - originY);
                        double units = HeuristicRates.octileUnits(nx, nz);
                        // A movement offering the position it was asked to expand from. This
                        // guard is NOT redundant with the comparisons below, which is why it was
                        // deleted once and restored: dividing by zero gives Infinity (or NaN at
                        // cost 0), and neither is less than a declared figure, so the comparison
                        // passes a degenerate movement in silence. The no-offer branch does not
                        // catch it either -- the counter above has already incremented, so as far
                        // as that branch can tell the audit was exercised.
                        if (units == 0.0 && ady == 0) {
                            violations.add(type.id() + " offered its own position (" + nx + ", "
                                + ny + ", " + nz + "), which is not a move: an edge spanning no"
                                + " distance has no per-unit cost to check its declarations"
                                + " against. A* would also expand it as a zero-length neighbour of"
                                + " itself. Fix expand() so it never offers the position it was"
                                + " given.");
                            return;
                        }
                        // Each half is checked only where the offer is evidence about it. An
                        // offer that does not move horizontally says nothing about the horizontal
                        // declaration, and must be skipped rather than treated as a violation.
                        if (units > 0.0 && cost / units < declaredHorizontal - 1.0e-9) {
                            violations.add(type.id() + " declares minCostPerHorizontalUnit "
                                + declaredHorizontal + " but offered (" + nx + ", " + ny + ", "
                                + nz + ") from (0, " + originY + ", 0) for " + cost + " across "
                                + units + " octile units, which is " + (cost / units)
                                + " per unit; the heuristic would overestimate and A* would stop"
                                + " returning shortest paths");
                            return;
                        }
                        if (ady > 0 && cost / ady < declaredVertical - 1.0e-9) {
                            violations.add(type.id() + " declares minCostPerVerticalStep "
                                + declaredVertical + " but offered (" + nx + ", " + ny + ", " + nz
                                + ") from (0, " + originY + ", 0) for " + cost + " across " + ady
                                + " blocks of height, which is " + (cost / ady) + " per block; the"
                                + " heuristic would overestimate and A* would stop returning"
                                + " shortest paths");
                        }
```

`HeuristicRates` is in the same package, so no import is needed.

In the never-offered message at the end of the method, replace `its declared minCostPerAxisStep of " + declared + "` with `its declarations were never`, keeping the rest of that long message verbatim — it explains the seeded-world palette and is the reason the guard is useful.

- [ ] **Step 9: Update the assertions that read the multiplier**

Mechanical rename, **same expected values**, because every movement involved offers along a single axis where octile and Chebyshev agree:

| File | Change |
|---|---|
| `core-movement/.../MovementRegistryTest.java` (3 sites) | `.cheapestAxisStep()` → `.rates().horizontal()` |
| `core-pathfinder/.../DefaultRegistryTest.java` (1 site) | same |
| `core-pathfinder/.../HeuristicMultiplierAdmissibilityTest.java` (2 sites) | `.cheapestAxisStep()` → `.rates().horizontal()`, and `type.minCostPerAxisStep()` → `type.minCostPerHorizontalUnit()` |
| `movement-parkour/.../ParkourPathfindingTest.java` (2 sites) | `.cheapestAxisStep()` → `.rates().horizontal()` |
| `movement-parkour/.../ParkourMoveTest.java` | `minCostPerAxisStep()` → `minCostPerHorizontalUnit()`; expected value stays `COST / 2` |
| `core-pathfinder/.../BuiltInMovementContractTest.java` | `minCostPerAxisStep()` → `minCostPerHorizontalUnit()` |
| `core-movement/.../MovementCostsTest.java` | javadoc reference only |

**`DefaultRegistryTest` is the exception to "same expected values".** It asserts the default registry's multiplier. `walk.diagonal` now declares `3.5636` instead of `5.0397`, but `walk.traverse` already declared `3.5636` and was already the minimum, so the multiplier is **unchanged at 3.5636**. If it comes out different, stop and report — do not adjust the expectation.

- [ ] **Step 10: Run the full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. `:core-pathfinder`'s search still uses the Chebyshev formula at this point (Task 3 changes that), so no search behaviour has changed yet and every existing path test must still pass. **If any path or cost test fails here, stop and report** — this task is meant to be behaviour-preserving.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor(c1a)!: movements declare a horizontal and a vertical rate

IMovementType.minCostPerAxisStep() is replaced by minCostPerHorizontalUnit()
and minCostPerVerticalStep(), and ActiveMovements.cheapestAxisStep() by
rates(). The two are minimised independently so that a movement which is
cheap per block of height cannot degrade the estimate for horizontal travel
it may not be capable of at all -- the seam a ladder lands on later.

MovementContract moves in the same commit and not by preference: it measured
a Chebyshev span, so a 3-block descend measured 12.0323/3 = 4.0108 against a
declaration that is now the shallowest drop's whole cost, 8.1783. Splitting
the two would leave BuiltInMovementContractTest red at a task boundary. The
audit now measures octile units horizontally and blocks of height vertically,
and checks each only where an offer is evidence about it.

Behaviour-preserving for the search. Only walk.diagonal's declared number
moves, from DIAGONAL to DIAGONAL/sqrt(2), and only because its unit changed
underneath it; walk.traverse was already the minimum at the same figure, so
the default registry's rate is unchanged. The heuristic still computes a
Chebyshev distance until the next commit."
```

---

## Task 3: The octile heuristic

**Files:**
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Goal.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalBlock.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalXZ.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java:139,154,202`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/OctileSearchTest.java`

**Interfaces:**
- Consumes: `HeuristicRates` (Task 1), `ActiveMovements.rates()` (Task 2).
- Produces: `Goal.heuristic(int x, int y, int z, HeuristicRates rates)`. Task 4 depends on nothing here.

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/OctileSearchTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression that would have caught C1a's defect.
 *
 * <p>A* returned correct paths throughout; what it did wrong was explore far too much of the world
 * to find them, because the heuristic credited a diagonal step at the cardinal rate. No test
 * asserted anything about how much was explored, so the defect survived C1's review, C2's, and a
 * whole-branch review, and only surfaced when the region a search touches had to be measured for
 * C3.
 */
class OctileSearchTest {

    /** Unbounded flat ground: stone at {@link #FLOOR_Y}, air everywhere above. */
    private static final class FlatWorld implements BlockSource {
        static final int FLOOR_Y = 63;

        @Override
        public BlockData at(int x, int y, int z) {
            return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    private static PathResult diagonalRun(int distance, int budget) {
        return new AStarPathfinder(budget).findPath(
            new FlatWorld(), 0, FlatWorld.FLOOR_Y + 1, 0,
            new GoalBlock(distance, FlatWorld.FLOOR_Y + 1, distance),
            CapabilitySet.none());
    }

    @Test
    void aDiagonalRunOnOpenGroundDoesNotFanOut() {
        // Before C1a this expanded 4,506 nodes for a 91-step path. The bound is deliberately
        // generous -- the exact figure is not the contract and an unrelated tie-break change
        // would move it -- but 10x the path length still fails by two orders of magnitude
        // against the old behaviour.
        PathResult result = diagonalRun(90, 10000);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(91, result.path().size());
        assertTrue(result.nodesExpanded() <= 10 * result.path().size(),
            "a diagonal run across open flat ground must not explore the plane around it;"
                + " expanded " + result.nodesExpanded() + " nodes for a "
                + result.path().size() + "-step path");
    }

    @Test
    void aLongDiagonalRunCompletesInsideTheProbesBudget() {
        // Before C1a this returned BUDGET_EXCEEDED on empty flat ground, which is the symptom
        // an owner would meet in game the first time they marked a goal 180 blocks out on a
        // diagonal. No terrain is involved; the search simply gave up.
        PathResult result = diagonalRun(180, 10000);

        assertEquals(PathOutcome.FOUND, result.outcome(),
            "a straight diagonal over empty ground is the easiest long path there is");
        assertEquals(181, result.path().size());
    }

    @Test
    void theDiagonalMovementDeclaresTheSameRateAsTheStraightOne() {
        // Spec 5.1. DIAGONAL is defined as TRAVERSE * sqrt(2), so once the unit is octile the two
        // must declare an identical per-unit figure. This is the test that fails if someone later
        // "corrects" DiagonalMove back to declaring the whole diagonal cost, and it is the
        // cheapest possible statement of what the unit means.
        double straight = Double.NaN;
        double diagonal = Double.NaN;
        java.util.List<dev.continuo.movement.IMovementType> ms =
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).movements();
        for (int i = 0; i < ms.size(); i++) {
            if ("walk.traverse".equals(ms.get(i).id())) {
                straight = ms.get(i).minCostPerHorizontalUnit();
            }
            if ("walk.diagonal".equals(ms.get(i).id())) {
                diagonal = ms.get(i).minCostPerHorizontalUnit();
            }
        }
        assertEquals(straight, diagonal, 1.0e-9,
            "a diagonal step is sqrt(2) units costing sqrt(2) times as much, so its per-unit rate"
                + " is the same as a cardinal step's; if these differ the unit is wrong");
    }

    @Test
    void anAxisAlignedRunIsUnaffected() {
        // Octile and Chebyshev agree on an axis-aligned gap, so this pins that the change is
        // confined to diagonals rather than being a general retune.
        PathResult result = new AStarPathfinder(10000).findPath(
            new FlatWorld(), 0, FlatWorld.FLOOR_Y + 1, 0,
            new GoalBlock(256, FlatWorld.FLOOR_Y + 1, 0),
            CapabilitySet.none());

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(257, result.path().size());
        assertEquals(257, result.nodesExpanded(),
            "an axis-aligned run was already optimal and must stay exactly so");
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.OctileSearchTest'`

Expected: `aDiagonalRunOnOpenGroundDoesNotFanOut` FAILS with roughly `expanded 4506 nodes for a 91-step path`, and `aLongDiagonalRunCompletesInsideTheProbesBudget` FAILS with `expected FOUND but was BUDGET_EXCEEDED`. `anAxisAlignedRunIsUnaffected` PASSES already — that is correct and is what makes it a guard rather than a restatement.

**If `aDiagonalRunOnOpenGroundDoesNotFanOut` passes here, stop and report.** It would mean the search is not behaving as measured and the rest of this plan rests on a wrong premise.

- [ ] **Step 3: Change `Goal` and both implementations**

`Goal.java` — replace the class javadoc's second paragraph and the method:

```java
/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here delegate the arithmetic to
 * {@link dev.continuo.movement.HeuristicRates}, whose rates are minima over exactly the movements
 * the search may use. That is what makes the guarantee structural: every movement satisfies
 * {@code cost >= horizontal × octileUnits(dx, dz)} and {@code cost >= vertical × |dy|} by the
 * definition of a minimum.
 */
public interface Goal {

    /**
     * @param x candidate X
     * @param y candidate Y
     * @param z candidate Z
     * @return whether standing here satisfies the goal
     */
    boolean isReached(int x, int y, int z);

    /**
     * @param x candidate X
     * @param y candidate Y
     * @param z candidate Z
     * @param rates the rates this search may scale a distance by, from
     *              {@link dev.continuo.movement.ActiveMovements#rates()}; never {@code null}
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z, HeuristicRates rates);
}
```

Add `import dev.continuo.movement.HeuristicRates;`.

`GoalBlock.java` — replace the class javadoc's heuristic paragraph and the method:

```java
/**
 * A single block position.
 *
 * <p>The heuristic is the larger of an octile horizontal estimate and a vertical one — never their
 * sum, because {@code walk.ascend} closes a horizontal axis and a vertical one in one move and
 * summing would charge twice for it.
 */
```

```java
    @Override
    public double heuristic(int px, int py, int pz, HeuristicRates rates) {
        return rates.estimate(x - px, y - py, z - pz);
    }
```

`GoalXZ.java`:

```java
    @Override
    public double heuristic(int px, int py, int pz, HeuristicRates rates) {
        return rates.horizontalEstimate(x - px, z - pz);
    }
```

Its existing class javadoc about ignoring Y stays exactly as it is; `horizontalEstimate` is the method that honours it.

Add `import dev.continuo.movement.HeuristicRates;` to both.

- [ ] **Step 4: Change `AStarPathfinder`**

At line 139, replace:

```java
        final double cheapestAxisStep = active.cheapestAxisStep();
```

with:

```java
        final HeuristicRates rates = active.rates();
```

At lines 154 and 202, replace `cheapestAxisStep` with `rates` in the two `goal.heuristic(...)` calls. Add `import dev.continuo.movement.HeuristicRates;`.

- [ ] **Step 5: Run the test and verify it passes**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.OctileSearchTest'`

Expected: PASS, 4 tests. `aDiagonalRunOnOpenGroundDoesNotFanOut` should now report about 91 expanded nodes and `aLongDiagonalRunCompletesInsideTheProbesBudget` about 181.

- [ ] **Step 6: Run the full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL.

**Existing exact-path tests are the ones to watch.** C1 asserts specific paths, and a tighter heuristic can change which of several equal-cost paths is found first — ties are broken by discovery order, and the order nodes are discovered in has changed. **If an exact-path test fails, stop and report it with the old and new paths and their costs.** If the two costs are equal the test needs its expectation re-derived with that stated; if the new cost is *higher*, the heuristic is inadmissible and the change is wrong.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(c1a): estimate horizontal distance in octile units

walk.diagonal costs TRAVERSE * sqrt(2), but the Chebyshev estimate counted
a diagonal move as one step and credited it at the cardinal rate, leaving
the heuristic short by sqrt(2) on any diagonal. A* degraded toward Dijkstra:
a 90-block diagonal on flat open ground expanded 4,506 nodes for a 91-step
path, and a 180-block one exhausted the probe's 10,000-node budget outright.

Both now expand about one node per step. Path costs are unchanged -- this is
search efficiency, not a different answer -- and axis-aligned runs are
untouched, since octile and Chebyshev agree there."
```

---

## Task 4: Prove the new tests are not vacuous, and sweep

**Files:**
- Temporarily modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java`
- Modify: any remaining `minCostPerAxisStep` references

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Mutate `DiagonalMove` back to its old declaration**

In `DiagonalMove.java`, temporarily change:

```java
    private static final double MIN_COST_PER_HORIZONTAL_UNIT =
        MovementCosts.DIAGONAL / HeuristicRates.octileUnits(1, 1);
```

to:

```java
    private static final double MIN_COST_PER_HORIZONTAL_UNIT = MovementCosts.DIAGONAL;
```

- [ ] **Step 2: Run the two tests that must now fail**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.OctileSearchTest'`

Expected: **FAIL.** Record the exact failure output — it is required by the spec's done criterion 3 and belongs in the final commit message.

Note *which* tests fail and how. A too-high declaration makes the heuristic overestimate, so the likely symptom is a wrong path or a changed cost rather than fan-out. Whatever it is, it must not be silence.

**If everything passes, stop and report.** A test that cannot tell the fixed code from the broken code is the exact failure shape this repo has hit repeatedly, and shipping it would be worse than shipping no test.

- [ ] **Step 3: Revert the mutation**

```bash
git checkout core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java
```

- [ ] **Step 4: Sweep for the old names**

Run:

```bash
git grep -n "minCostPerAxisStep\|cheapestAxisStep" -- '*.java'
```

Expected: **no matches.** Spec done criterion 4 makes this a stated success condition. Private field names inside test doubles are the one allowed exception if step 7 of Task 2 left them; if the grep finds those, either rename them or note them explicitly in the commit.

Also run:

```bash
git grep -n "Chebyshev" -- '*.java'
```

Any surviving mention is now wrong and must be corrected — javadoc that describes the old formula is worse than none.

- [ ] **Step 5: Full build and test count**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL, 81 tasks.

Then count:

```bash
python -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for p in glob.glob('**/build/test-results/**/*.xml', recursive=True):
    r=ET.parse(p).getroot()
    if r.tag!='testsuite': continue
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0))
print('tests=%d failures=%d errors=%d' % (t,f,e))
"
```

Expected: `failures=0 errors=0`, and a total of **390 plus the tests added by this plan** (12 in Task 1, 6 in Task 2, 4 in Task 3 = **412**). A different total is not necessarily wrong, but reconcile it before claiming done.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test(c1a): record the mutation that proves the octile tests bite

Reverting DiagonalMove to declare MovementCosts.DIAGONAL was run against the
suite and OctileSearchTest fails; output recorded below. A test whose subject
is 'the search does not fan out' is the shape that reads as a pass while
checking nothing, and on this repo the whole-branch review has three times
found by executing mutations what reading a diff did not.

<paste the recorded failure output from step 2 here>"
```

---

## Self-Review

**Spec coverage.** §4.1 `HeuristicRates`/`octileUnits` → Task 1. §4.2 the two declarations and their minima → Task 2. §4.3 the heuristic and both `Goal` implementations → Task 3. §4.4 the contract audit → Task 2 (see the ruling in its header). §4.5 blast radius → enumerated across Tasks 2 and 3. §7 done criteria → Task 4.

§5's verification list initially had two gaps, both now closed **inside the tasks** rather than noted here:

- **Test 1, the octile identity.** The first draft leaned on `DefaultRegistryTest`'s unchanged
  `3.5636`, which passes whether or not `walk.diagonal` declares the right figure — traverse is
  the minimum either way, so the assertion is blind to exactly the number this change moves.
  `theDiagonalMovementDeclaresTheSameRateAsTheStraightOne` is now a test in Task 3.
- **Test 4, consistency.** Octile's subadditivity was argued in the spec and asserted nowhere.
  `theUnitIsSubadditiveSoAPerEdgeBoundSumsAlongAPath` is now a test in Task 1.

Test 3 (admissibility as a property) and test 5 (rates are independent) → Task 2 step 1. Test 6 (`POSITIVE_INFINITY`) → Task 1.

Update the expected counts in Task 4 step 5 accordingly: 12 in Task 1, 6 in Task 2, 4 in Task 3 = **412** total.

**Placeholder scan.** One deliberate placeholder remains: `<paste the recorded failure output from step 2 here>` in Task 4's commit message, which cannot be written in advance because it is the observed output. Everything else contains real code.

**Type consistency.** `minCostPerHorizontalUnit()` / `minCostPerVerticalStep()` / `rates()` / `horizontal()` / `vertical()` / `octileUnits(int,int)` / `estimate(int,int,int)` / `horizontalEstimate(int,int)` are used identically in Tasks 1–5. `HeuristicRates` lives in `dev.continuo.movement`, so `MovementContract` needs no import and the `:core-pathfinder` classes do.
