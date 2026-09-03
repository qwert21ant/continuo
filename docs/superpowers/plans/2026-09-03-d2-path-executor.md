# D2 Path Executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a computed path into per-tick game inputs, so the bot walks to a marked goal on both
Minecraft versions.

**Architecture:** A new pure module `core-engine` holds `PathExecutor`, which each tick re-anchors
itself to the nearest path node inside a bounded window and writes its full desired input set — a
function of the path and the player's current state, with no state machine. It spends one search
slice per tick on a pending `Run` while continuing to walk, so plan-ahead latency is hidden behind
movement. `HumanizedActuator` decorates the adapter's `IActuator` and bounds the turn rate.

**Tech Stack:** Java 8 (no lambdas in main source), JUnit 5, Gradle Kotlin DSL, `platform-testkit`'s
`FakePlayerView`/`FakeActuator`.

**Spec:** `docs/superpowers/specs/2026-09-03-d2-path-executor-design.md`

## Global Constraints

- **Java 8 in all main source. No lambdas, no method references, no `var`.** Only the Fabric adapter
  is Java 21. Test source follows the same style as its module's existing tests.
- **Gate on `./gradlew build`, never `./gradlew :test`.** Javadoc is build-failing and `:test` never
  runs it, so a green `:test` can hide a broken build.
- **Never run `./gradlew clean`.** It destroys the 1.7.10 decompiled sources, which take a very long
  time to regenerate.
- **Never set `GRADLE_USER_HOME`.** The machine default is already correct. Setting it unquoted in
  Git Bash silently builds a second ~1.7 GB Gradle home inside the repo.
- **A new module is registered in two places**: `settings.gradle.kts` and its own
  `build.gradle.kts`. This plan's new module additionally needs both adapter build files.
- **Adapters have no tests and cannot get any.** Review is their only gate.
- **Filtered Gradle runs corrupt the XML test counts.** Count tests only from a full unfiltered run,
  summed across every `TEST-*.xml`.
- **`ServiceLoader` iteration order is unspecified**; `MovementRegistry.discover()` sorts to
  compensate. Never assert on discovery order.
- **`Locale.ROOT` for any formatted number that reaches a log**, so a machine with a comma decimal
  separator does not write `3,8` where a reader expects `3.8`.
- **Every Minecraft API claim must be cited to file and line** in the decompiled sources. §3 and §3.9
  of the spec already carry the ones this plan needs; do not add uncited ones.
- Constants introduced by this plan are **extrapolated**. Their javadoc must say so until the in-game
  run replaces the figure. See spec §9.

---

### Task 1: Hoist `PathProbe`'s reusable pieces

`core-engine` will be `runtime`'s sibling, not its dependent, so anything `PathProbe` owns that the
executor also needs must move down to a module both can see. Two things qualify: the yaw arithmetic
and the slice budget. This task is a pure extraction — no behaviour changes anywhere.

**Files:**
- Create: `core/src/main/java/dev/continuo/core/Yaw.java`
- Create: `core/src/test/java/dev/continuo/core/YawTest.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java` (add `SLICE_NODES`)
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` (delete `yawToward`,
  `wrapDegrees`, `SLICE_NODES`; delegate)
- Modify: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java:896-906` (remove the moved
  test)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `dev.continuo.core.Yaw.toward(double fromX, double fromZ, int toX, int toZ)` → `float`
  - `dev.continuo.core.Yaw.wrap(float degrees)` → `float`
  - `dev.continuo.pathfinder.Run.SLICE_NODES` → `int`, value `2000`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/YawTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YawTest {

    @Test
    void towardUsesTheConventionBothVersionsShare() {
        // Both target versions derive forward motion as (-sin yaw, cos yaw), which inverts to
        // yaw = -atan2(dx, dz). Verified against 1.7.10's moveFlying and 1.21.11's getInputVector
        // in D1 design section 3.4.
        assertEquals(0.0f, Yaw.toward(0.5, 0.5, 0, 10), 0.001f, "+Z is yaw 0");
        assertEquals(90.0f, Yaw.toward(0.5, 0.5, -10, 0), 0.001f, "-X is yaw 90");
        assertEquals(-90.0f, Yaw.toward(0.5, 0.5, 10, 0), 0.001f, "+X is yaw -90");
        // Due north comes back as -180 rather than +180: atan2(+0.0, -z) is +pi, and toward
        // negates it. The two name the same direction; this pins which representation is used.
        assertEquals(-180.0f, Yaw.toward(0.5, 0.5, 0, -10), 0.001f, "-Z is yaw -180");
    }

    @Test
    void towardAimsAtTheBlockCentreNotItsCorner() {
        // From the centre of block 0, block 1 due +X is 1.0 away in X and 0 in Z. If toward
        // aimed at the corner instead, dz would be -0.5 and the answer would not be -90.
        assertEquals(-90.0f, Yaw.toward(0.5, 0.5, 1, 0), 0.001f);
    }

    @Test
    void wrapFoldsIntoTheHalfOpenRange() {
        assertEquals(0.0f, Yaw.wrap(0.0f), 0.001f);
        assertEquals(-1.0f, Yaw.wrap(359.0f), 0.001f, "359 and -1 are one degree apart");
        assertEquals(-170.0f, Yaw.wrap(190.0f), 0.001f);
        assertEquals(170.0f, Yaw.wrap(-190.0f), 0.001f);
        assertEquals(-180.0f, Yaw.wrap(180.0f), 0.001f, "the range is half open at +180");
        assertEquals(-180.0f, Yaw.wrap(-180.0f), 0.001f, "and closed at -180");
    }

    @Test
    void wrapMakesTheShortestArcAcrossTheDiscontinuity() {
        // The property HumanizedActuator depends on: turning from -179 to 179 is 2 degrees, not
        // 358. A wrap that returned the raw difference would give 358 here.
        assertEquals(-2.0f, Yaw.wrap(-179.0f - 179.0f), 0.001f);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.YawTest'`
Expected: FAIL, compilation error — `Yaw` does not exist.

- [ ] **Step 3: Create `Yaw`**

Create `core/src/main/java/dev/continuo/core/Yaw.java`:

```java
package dev.continuo.core;

/**
 * Yaw arithmetic in the convention both target versions share.
 *
 * <p><b>The convention is a fact about both games, not an invention of this project.</b> Both derive
 * forward motion as {@code (-sin yaw, cos yaw)} — 1.7.10's {@code moveFlying} and 1.21.11's
 * {@code getInputVector} reduce to the identical delta — so on both, yaw {@code 0} faces {@code +Z}
 * and {@code 90} faces {@code -X}. See {@code IPlayerView.yaw()} for the full table.
 *
 * <p>This lives in {@code core} rather than beside either of its callers because
 * {@code dev.continuo.runtime}'s probe and {@code dev.continuo.engine}'s executor are sibling
 * modules, and {@code core} is the only one both can see. Two copies of this arithmetic is exactly
 * the divergence the SPI exists to prevent.
 */
public final class Yaw {

    private Yaw() {
    }

    /**
     * The yaw that points from a position toward a block's centre.
     *
     * <p>{@code yaw = -atan2(dx, dz)}, which is the inverse of the forward-motion delta above.
     *
     * @param fromX where the looker is
     * @param fromZ where the looker is
     * @param toX   the target block's X
     * @param toZ   the target block's Z
     * @return the yaw in degrees, unnormalised
     */
    public static float toward(double fromX, double fromZ, int toX, int toZ) {
        double dx = (toX + 0.5) - fromX;
        double dz = (toZ + 0.5) - fromZ;
        return (float) Math.toDegrees(-Math.atan2(dx, dz));
    }

    /**
     * Degrees folded into {@code [-180, 180)}, so 359 and -1 are one degree apart.
     *
     * <p>Applied to a difference between two angles, this is what makes a turn take the shortest
     * arc: {@code wrap(179 - (-179))} is {@code -2} rather than {@code 358}.
     *
     * @param degrees any finite angle
     * @return the same direction, in {@code [-180, 180)}
     */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :core:test --tests 'dev.continuo.core.YawTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Move `SLICE_NODES` to `Run`**

In `core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java`, add this constant immediately
after the class declaration (before the fields). Copy the javadoc from
`PathProbe.SLICE_NODES` verbatim — it carries the measured figures from C5 §13 and D1 §15.3 and must
not be paraphrased — then add this paragraph at its end:

```java
    /**
     * ... (PathProbe.SLICE_NODES's javadoc, copied verbatim) ...
     *
     * <p>It lives here rather than on a caller because {@code PathProbe} and D2's {@code
     * PathExecutor} are in sibling modules and both spend slices against this class. A second copy
     * of a measured constant is a copy that drifts.
     */
    public static final int SLICE_NODES = 2000;
```

- [ ] **Step 6: Delete the three moved members from `PathProbe` and delegate**

In `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`:

1. Delete `SLICE_NODES` (`:83-111`), `yawToward` (`:438-450`) and `wrapDegrees` (`:475-485`).
2. Add `import dev.continuo.core.Yaw;`.
3. `:389` becomes `float yaw = Yaw.toward(player.x(), player.z(), target.x(), target.z());`
4. `:465` becomes `float difference = Math.abs(Yaw.wrap(asked - got));`
5. `:516` becomes `boolean done = active.advance(Run.SLICE_NODES);` (`Run` is already imported).

- [ ] **Step 7: Remove the test that moved**

Delete `yawTowardUsesTheConventionBothVersionsShare` from
`runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java:896-906`. `YawTest` now covers it and
covers more.

- [ ] **Step 8: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Every other `PathProbeTest` case must still pass — they are the
regression gate proving the extraction changed no behaviour.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/dev/continuo/core/Yaw.java core/src/test/java/dev/continuo/core/YawTest.java core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java runtime/src/main/java/dev/continuo/runtime/PathProbe.java runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "refactor(d2): hoist the probe's yaw arithmetic and slice budget

core-engine will be runtime's sibling rather than its dependent, so anything
PathProbe owns that the executor also needs has to move to a module both can
see. Yaw goes to core, which is the only such module; SLICE_NODES goes to Run,
which is what a slice is actually spent against.

Pure extraction. PathProbe's surviving tests are the gate."
```

---

### Task 2: `MovementKind`

The typed name for what a step of a path is. Lives in `core-movement` beside the movements it names.

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/MovementKind.java`
- Create: `core-movement/src/test/java/dev/continuo/movement/MovementKindTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `dev.continuo.movement.MovementKind` — enum `TRAVERSE`, `DIAGONAL`, `ASCEND`, `DESCEND`,
    `PARKOUR`
  - `MovementKind.movementId()` → `String`
  - `MovementKind.forDelta(int dx, int dy, int dz)` → `MovementKind` or `null`

- [ ] **Step 1: Write the failing test**

Create `core-movement/src/test/java/dev/continuo/movement/MovementKindTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MovementKindTest {

    @Test
    void everyDeltaTheFourBuiltInsCanEmitIsNamed() {
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(1, 0, 0));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(-1, 0, 0));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(0, 0, 1));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(0, 0, -1));

        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(1, 0, 1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(-1, 0, 1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(1, 0, -1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(-1, 0, -1));

        assertEquals(MovementKind.ASCEND, MovementKind.forDelta(1, 1, 0));
        assertEquals(MovementKind.ASCEND, MovementKind.forDelta(0, 1, -1));
    }

    @Test
    void descendNamesEveryDropDepthNotJustOne() {
        // DescendMove offers the landing rather than each level passed through, so a single step
        // can drop by up to MAX_SAFE_FALL. A table that only handled dy == -1 would return null
        // for a real four-block drop and stop the executor on legal terrain.
        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            assertEquals(MovementKind.DESCEND, MovementKind.forDelta(1, -drop, 0),
                "a " + drop + "-block drop is a descend");
            assertEquals(MovementKind.DESCEND, MovementKind.forDelta(0, -drop, 1),
                "a " + drop + "-block drop is a descend");
        }
    }

    @Test
    void parkourIsTwoAlongOneAxisOnly() {
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(2, 0, 0));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(-2, 0, 0));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(0, 0, 2));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(0, 0, -2));
    }

    @Test
    void nothingElseIsNamed() {
        // Each of these is a movement the registry cannot currently produce. Returning a kind for
        // one would mean the executor silently drove something the search never planned.
        assertNull(MovementKind.forDelta(0, 0, 0), "a repeated position is not a move");
        assertNull(MovementKind.forDelta(1, 1, 1), "no diagonal ascend exists");
        assertNull(MovementKind.forDelta(1, -1, 1), "no diagonal descend exists");
        assertNull(MovementKind.forDelta(3, 0, 0), "no three-block parkour exists");
        assertNull(MovementKind.forDelta(2, 0, 2), "no diagonal parkour exists");
        assertNull(MovementKind.forDelta(0, 2, 1), "nothing climbs two blocks in one step");
        assertNull(MovementKind.forDelta(2, 1, 0), "no parkour ascend exists");
    }

    @Test
    void everyConstantNamesAMovementId() {
        // The association section 5.3's guard checks the derivation against. A constant with a
        // typo'd id would make the guard compare the derivation to nothing.
        assertEquals("walk.traverse", MovementKind.TRAVERSE.movementId());
        assertEquals("walk.diagonal", MovementKind.DIAGONAL.movementId());
        assertEquals("walk.ascend", MovementKind.ASCEND.movementId());
        assertEquals("walk.descend", MovementKind.DESCEND.movementId());
        assertEquals("walk.parkour", MovementKind.PARKOUR.movementId());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementKindTest'`
Expected: FAIL, compilation error — `MovementKind` does not exist.

- [ ] **Step 3: Create `MovementKind`**

Create `core-movement/src/main/java/dev/continuo/movement/MovementKind.java`:

```java
package dev.continuo.movement;

/**
 * What one step of a path is, derived from the positions it joins.
 *
 * <p><b>A path carries no movement identity of its own.</b> {@link MoveSink#offer} takes no movement
 * argument and a search result is a bare list of positions, so an executor either re-derives what
 * each step was or the search threads an edge identity through its hottest interface. D2 chose
 * derivation, on the evidence that the five registered movements emit <b>pairwise disjoint</b> delta
 * sets that <b>jointly cover</b> everything the registry can produce — so the derivation is lossless
 * rather than a guess.
 *
 * <p><b>That property is not permanent, and it is guarded rather than assumed.</b> A diagonal
 * ascend, a three-block parkour, a ladder climb or a water move would break it. The guard is a test
 * that expands every registered {@link IMovementType} and asserts totality, disjointness, and
 * agreement between each offer's derived kind and the producing type's own {@link
 * IMovementType#id()} — which is what {@link #movementId()} exists for. Adding such a movement fails
 * the build at the point of addition rather than mis-executing silently in a client.
 */
public enum MovementKind {

    /** Walking one block to a cardinal neighbour at the same height. */
    TRAVERSE("walk.traverse"),

    /** Walking one block diagonally on the level. */
    DIAGONAL("walk.diagonal"),

    /** Jumping up one block onto a cardinal neighbour. */
    ASCEND("walk.ascend"),

    /** Walking off a ledge to a cardinal neighbour and falling to the first floor below. */
    DESCEND("walk.descend"),

    /** Sprint-jumping a one-block gap to the block beyond, on the level. */
    PARKOUR("walk.parkour");

    private final String movementId;

    MovementKind(String movementId) {
        this.movementId = movementId;
    }

    /**
     * The {@link IMovementType#id()} of the movement this names.
     *
     * @return the id; never {@code null}
     */
    public String movementId() {
        return movementId;
    }

    /**
     * The kind of movement that joins two positions, or {@code null} if none does.
     *
     * <p><b>{@code null} is returned rather than thrown</b>, because this is called while building
     * every search result including the probe's, and a result that cannot be constructed is worse
     * than a step an executor refuses to drive. A caller that meets {@code null} has found a defect
     * in this table, not in its own input.
     *
     * @param dx the destination's X minus the origin's
     * @param dy the destination's Y minus the origin's
     * @param dz the destination's Z minus the origin's
     * @return the kind, or {@code null} if no registered movement can emit that delta
     */
    public static MovementKind forDelta(int dx, int dy, int dz) {
        int ax = dx < 0 ? -dx : dx;
        int az = dz < 0 ? -dz : dz;
        if (dy == 0) {
            if (ax + az == 1) {
                return TRAVERSE;
            }
            if (ax == 1 && az == 1) {
                return DIAGONAL;
            }
            if ((ax == 2 && az == 0) || (ax == 0 && az == 2)) {
                return PARKOUR;
            }
            return null;
        }
        if (ax + az != 1) {
            return null;
        }
        if (dy == 1) {
            return ASCEND;
        }
        return dy < 0 ? DESCEND : null;
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementKindTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core-movement/src/main/java/dev/continuo/movement/MovementKind.java core-movement/src/test/java/dev/continuo/movement/MovementKindTest.java
git commit -m "feat(d2): name what a step of a path is

The five registered movements emit pairwise disjoint delta sets that jointly
cover everything the registry can produce, so deriving a step's identity from
the positions it joins is lossless rather than a guess. Each constant carries
the IMovementType id it names, which is what lets the next task's guard check
the derivation against the movements themselves rather than against a second
hand-written table."
```

---

### Task 3: `Step`, `steps()`, and the totality guard

The guard is the entire justification for deriving movement identity rather than threading it
through the search, so it ships with the derivation and not later.

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Step.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Steps.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResult.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedResult.java`
- Modify: `core-pathfinder/build.gradle.kts` (add `testRuntimeOnly(project(":movement-parkour"))`)
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/StepsTest.java`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementKindCoverageTest.java`

**Interfaces:**
- Consumes: `MovementKind.forDelta`, `MovementKind.movementId` (Task 2).
- Produces:
  - `dev.continuo.pathfinder.Step` with `Pos to()` and `MovementKind kind()`
  - `PathResult.steps()` → `List<Step>`, unmodifiable
  - `SegmentedResult.steps()` → `List<Step>`, unmodifiable

- [ ] **Step 1: Write the failing derivation test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/StepsTest.java`:

```java
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
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.StepsTest'`
Expected: FAIL, compilation error — `Step` and `Steps` do not exist.

- [ ] **Step 3: Create `Step`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Step.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.MovementKind;

/**
 * One move of a path: where it goes, and what kind of movement gets there.
 *
 * <p>Derived when the result is built rather than re-derived by whoever executes it, so the mapping
 * from a position delta to a movement has one home. See {@link MovementKind}.
 */
public final class Step {

    private final Pos to;
    private final MovementKind kind;

    Step(Pos to, MovementKind kind) {
        this.to = to;
        this.kind = kind;
    }

    /** @return where this step ends; never {@code null} */
    public Pos to() {
        return to;
    }

    /**
     * What kind of movement this step is.
     *
     * <p><b>May be {@code null}</b>, meaning no registered movement can emit this step's delta.
     * That is a defect in {@link MovementKind}'s table rather than in the path, and
     * {@code MovementKind}'s own documentation explains why it is reported this way instead of
     * thrown.
     *
     * @return the kind, or {@code null} if the delta cannot be named
     */
    public MovementKind kind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Step)) {
            return false;
        }
        Step that = (Step) other;
        return to.equals(that.to) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return 31 * to.hashCode() + (kind == null ? 0 : kind.hashCode());
    }

    @Override
    public String toString() {
        return kind + " -> " + to;
    }
}
```

- [ ] **Step 4: Create `Steps`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Steps.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.MovementKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Derives a path's moves from the positions it joins.
 *
 * <p>The single implementation {@link PathResult} and {@link SegmentedResult} both call, so the two
 * cannot drift.
 *
 * <p><b>Walks the path only, never the expanded set.</b> On the measured route the path is 245
 * entries against 25,053 expanded, which is what makes deriving eagerly in a constructor
 * affordable.
 */
final class Steps {

    private Steps() {
    }

    /**
     * @param path the route, start to end
     * @return one step per adjacent pair, unmodifiable; empty when {@code path} has fewer than two
     *         entries
     */
    static List<Step> derive(List<Pos> path) {
        if (path.size() < 2) {
            return Collections.emptyList();
        }
        List<Step> steps = new ArrayList<Step>(path.size() - 1);
        Pos from = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            Pos to = path.get(i);
            steps.add(new Step(to, MovementKind.forDelta(
                to.x() - from.x(), to.y() - from.y(), to.z() - from.z())));
            from = to;
        }
        return Collections.unmodifiableList(steps);
    }
}
```

- [ ] **Step 5: Add `steps()` to both result types**

In `PathResult.java`, add the field, initialise it in the constructor **after** `this.path` is
assigned, and add the accessor:

```java
    private final List<Step> steps;
```

```java
        this.steps = Steps.derive(this.path);
```

```java
    /**
     * The moves this path is made of: one per adjacent pair of {@link #path()} entries, so
     * {@code steps().size()} is {@code path().size() - 1}, and empty whenever the path is.
     *
     * @return the steps, unmodifiable
     */
    public List<Step> steps() {
        return steps;
    }
```

Make the identical addition to `SegmentedResult.java`. **Derive from its own `this.path`, not by
delegating to `asPathResult()`** — that method copies `expanded()` a second time, which on a real
run is 25,053 entries.

- [ ] **Step 6: Run the derivation test and confirm it passes**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.StepsTest'`
Expected: PASS, 5 tests.

- [ ] **Step 7: Put all five movements on the test runtime classpath**

In `core-pathfinder/build.gradle.kts`, add to the `dependencies` block:

```kotlin
    // Discovered by ServiceLoader at test runtime only, never compiled against. The
    // MovementKind coverage guard has to see every movement that exists, and parkour lives in a
    // module core-pathfinder does not and must not depend on. Verified safe against the existing
    // suite: the only tests that assert on a movement list read activeFor(CapabilitySet.none()),
    // which filters parkour out because it requires Capability.PARKOUR.
    testRuntimeOnly(project(":movement-parkour"))
```

- [ ] **Step 8: Confirm the existing suite is undisturbed by that classpath change**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS, with the same test count as before the change. **If `DefaultRegistryTest` or any
search test now fails, stop and report** — it means a test grants `PARKOUR` against a discovered
registry, and the guard needs a different home rather than the suite needing a change.

- [ ] **Step 9: Write the failing coverage guard**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementKindCoverageTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementKind;
import dev.continuo.movement.MovementRegistry;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that makes deriving a step's movement from its delta legitimate.
 *
 * <p>D2 chose to derive movement identity rather than thread it through {@code MoveSink.offer},
 * on the evidence that the registered movements emit pairwise disjoint delta sets that jointly
 * cover everything the registry can produce. That is a property of today's registry, not a law.
 * This test is what converts it from an assumption into something a build can enforce: adding a
 * diagonal ascend, a three-block parkour, a ladder or a water move fails here, at the point of
 * addition, rather than mis-executing silently in a client.
 */
class MovementKindCoverageTest {

    /** Every offer one movement made, as a delta. */
    private static final class Offer {

        final int dx;
        final int dy;
        final int dz;
        final String movementId;

        Offer(int dx, int dy, int dz, String movementId) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.movementId = movementId;
        }

        String key() {
            return dx + "," + dy + "," + dz;
        }

        @Override
        public String toString() {
            return movementId + " offers (" + key() + ")";
        }
    }

    @Test
    void everyOfferEveryRegisteredMovementCanMakeIsNamedByExactlyOneKind() {
        List<Offer> offers = collectOffers();

        // The assertion that stops a broken fixture from making this test vacuous. Without it, a
        // world where nothing can move passes every check below while proving nothing.
        Set<MovementKind> observed = EnumSet.noneOf(MovementKind.class);
        for (Offer offer : offers) {
            MovementKind kind = MovementKind.forDelta(offer.dx, offer.dy, offer.dz);
            assertNotNull(kind, "no MovementKind names " + offer
                + "; extend MovementKind.forDelta rather than deleting this assertion");
            observed.add(kind);
        }
        assertEquals(EnumSet.allOf(MovementKind.class), observed,
            "the fixture must exercise every kind, or this test proves nothing about the ones it "
                + "misses; fix the fixture, not this assertion");

        // Disjointness. Two movements offering the same delta would make the derivation ambiguous
        // and the executor's choice arbitrary.
        Map<String, String> owner = new HashMap<String, String>();
        for (Offer offer : offers) {
            String previous = owner.put(offer.key(), offer.movementId);
            assertTrue(previous == null || previous.equals(offer.movementId),
                "delta (" + offer.key() + ") is offered by both " + previous + " and "
                    + offer.movementId + ", so it cannot be derived unambiguously");
        }

        // Agreement. A kind that names the wrong movement would drive the wrong inputs.
        for (Offer offer : offers) {
            MovementKind kind = MovementKind.forDelta(offer.dx, offer.dy, offer.dz);
            assertEquals(offer.movementId, kind.movementId(),
                offer + " derived as " + kind + ", which names " + kind.movementId());
        }
    }

    /**
     * Expands every registered movement from every position of a fixture built to give all five
     * of them something to offer, and returns the deltas.
     */
    private static List<Offer> collectOffers() {
        MovementRegistry registry = AStarPathfinder.defaultRegistry();
        List<IMovementType> types =
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).movements();

        FixtureWorld world = FixtureWorld.parse(COVERAGE_FIXTURE);
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        List<Offer> offers = new ArrayList<Offer>();

        for (IMovementType type : types) {
            for (int y = FIXTURE_MIN_Y; y <= FIXTURE_MAX_Y; y++) {
                for (int x = FIXTURE_MIN_X; x <= FIXTURE_MAX_X; x++) {
                    for (int z = FIXTURE_MIN_Z; z <= FIXTURE_MAX_Z; z++) {
                        ctx.moveTo(x, y, z);
                        offers.addAll(offersOf(type, ctx, x, y, z));
                    }
                }
            }
        }
        return offers;
    }

    private static List<Offer> offersOf(final IMovementType type, MutableExpansionContext ctx,
                                        final int x, final int y, final int z) {
        final List<Offer> found = new ArrayList<Offer>();
        RecordingSink sink = new RecordingSink();
        type.expand(ctx, sink);
        for (Pos offered : sink.positions()) {
            found.add(new Offer(offered.x() - x, offered.y() - y, offered.z() - z, type.id()));
        }
        return found;
    }
}
```

- [ ] **Step 10: Build the coverage fixture until every kind appears**

Add the fixture constant and its bounds to `MovementKindCoverageTest`. Start from this and
**iterate until the `observed` assertion passes** — that assertion is the specification of what the
fixture must contain, and a fixture that does not satisfy it is the bug.

```java
    private static final int FIXTURE_MIN_X = 0;
    private static final int FIXTURE_MAX_X = 8;
    private static final int FIXTURE_MIN_Y = 64;
    private static final int FIXTURE_MAX_Y = 68;
    private static final int FIXTURE_MIN_Z = 0;
    private static final int FIXTURE_MAX_Z = 4;

    /**
     * A fixture giving all five movements something to offer.
     *
     * <p>Reading west to east: a flat shelf for traverse and diagonal; a step up for ascend; a
     * hole with a floor for descend; and a floorless gap with a landing beyond it for parkour —
     * floorless because ParkourMove refuses a gap that is standable, since traverse already
     * reaches the far side in two cheaper steps.
     */
    private static final String COVERAGE_FIXTURE = ...;
```

The fixture must satisfy, simultaneously:

| kind | what the fixture needs |
|---|---|
| `TRAVERSE` | a standable cardinal neighbour at the same Y, with head clearance |
| `DIAGONAL` | the same diagonally, **with both orthogonal corners clear at feet and head height** — `DiagonalMove` refuses a squeeze |
| `ASCEND` | a standable neighbour one Y higher, **and clearance two blocks above the origin** |
| `DESCEND` | a cardinal neighbour that is not standable, with a real floor somewhere below it |
| `PARKOUR` | a cardinal neighbour that is **not standable and has no floor below it**, with a standable block two out at the same Y |

Remember `FixtureWorld`'s rules: the header declares the lowest slice's origin, slices run lowest
first and contiguous, columns run `+X` and rows run `+Z`, and **reads outside the declared extent
are `UNKNOWN`, not air**. Give the world at least two clear layers above every standable surface —
a walk layer in the top slice makes nothing standable and produces a fixture where no movement
offers anything.

- [ ] **Step 11: Run the guard and confirm it passes**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.MovementKindCoverageTest'`
Expected: PASS, 1 test.

- [ ] **Step 12: Prove the guard can fail**

Temporarily change `MovementKind.forDelta` so that `dy == 1` returns `TRAVERSE` instead of `ASCEND`.

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.MovementKindCoverageTest'`
Expected: **FAIL** on the agreement assertion, naming `walk.ascend`.

Then temporarily change it so `(2, 0, 0)` returns `null`.
Expected: **FAIL** on the totality assertion.

**Revert both changes and re-run to confirm green.** A guard that cannot be shown to fail is not a
guard. Record both observed failure messages in the commit body.

- [ ] **Step 13: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 14: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/Step.java core-pathfinder/src/main/java/dev/continuo/pathfinder/Steps.java core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResult.java core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedResult.java core-pathfinder/build.gradle.kts core-pathfinder/src/test/java/dev/continuo/pathfinder/StepsTest.java core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementKindCoverageTest.java
git commit -m "feat(d2): a path result carries its moves, and a guard keeps that honest

Both result types derive one Step per adjacent pair of positions when they are
built. Eager is affordable because the derivation walks the path only -- 245
entries on the measured route against 25,053 expanded -- and SegmentedResult
derives its own rather than going through asPathResult(), which would copy the
expanded set a second time.

An unnameable delta yields a null kind rather than throwing, because building a
result must never fail: the probe renders paths the executor would refuse to
drive.

The coverage guard is what makes deriving legitimate rather than lucky. It
expands every registered movement over a fixture built to exercise all five and
asserts totality, disjointness, and agreement with each movement's own id. It
also asserts that all five kinds were actually observed, so a broken fixture
fails loudly instead of passing empty. Demonstrated to fail in both directions
before being committed."
```

---

### Task 4: The `core-engine` module

Two mechanical moves and no behaviour change. `ContinuoCore` goes up so it can name a path;
`RuntimeLog` goes down so the executor can log without depending on `runtime`, which is its sibling.

**Files:**
- Create: `core-engine/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Move: `core/src/main/java/dev/continuo/core/ContinuoCore.java` →
  `core-engine/src/main/java/dev/continuo/engine/ContinuoCore.java`
- Move: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java` →
  `core-engine/src/test/java/dev/continuo/engine/ContinuoCoreTest.java`
- Move: `runtime/src/main/java/dev/continuo/runtime/RuntimeLog.java` →
  `core/src/main/java/dev/continuo/core/RuntimeLog.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java` (import)
- Modify: `runtime/src/test/java/dev/continuo/runtime/AdapterRuntimeConformanceTest.java` (import)
- Modify: `adapters/adapter-fabric-1.21.11/build.gradle.kts`
- Modify: `adapters/adapter-forge-1.7.10/build.gradle.kts`
- Modify: all four adapter sources naming `ContinuoCore` or `RuntimeLog`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - module `:core-engine`, package `dev.continuo.engine`
  - `dev.continuo.engine.ContinuoCore` — unchanged behaviour, `implements CoreApi`
  - `dev.continuo.core.RuntimeLog` with `void info(String)` and `void error(String, Throwable)`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add immediately after `include("core-pathfinder")`:

```kotlin
include("core-engine")
```

- [ ] **Step 2: Create the module's build file**

Create `core-engine/build.gradle.kts`:

```kotlin
plugins {
    id("continuo-pure-module")
}

// :core-pathfinder is `api`, not `implementation`: PathExecutor's public surface names Pos, and
// ContinuoCore's consumers reach BlockLookup through it, so both need those types on their own
// compile classpath.
dependencies {
    api(project(":core-pathfinder"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":platform-testkit"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 3: Move `ContinuoCore` and its test**

```bash
mkdir -p core-engine/src/main/java/dev/continuo/engine core-engine/src/test/java/dev/continuo/engine
git mv core/src/main/java/dev/continuo/core/ContinuoCore.java core-engine/src/main/java/dev/continuo/engine/ContinuoCore.java
git mv core/src/test/java/dev/continuo/core/ContinuoCoreTest.java core-engine/src/test/java/dev/continuo/engine/ContinuoCoreTest.java
```

In both files change `package dev.continuo.core;` to `package dev.continuo.engine;` and add imports
for the `dev.continuo.core` types they now reference from outside that package — at minimum
`BlockClassifier`, `BlockLookup`, `BlockTableLoader` and `CoreApi` in the main file. Let the compiler
enumerate the rest.

**`BlockLookup`, `BlockClassifier` and `BlockTableLoader` must be public for this to compile.** If
any is package-private, widen it and say so in the commit body rather than working around it.

- [ ] **Step 4: Move `RuntimeLog`**

```bash
git mv runtime/src/main/java/dev/continuo/runtime/RuntimeLog.java core/src/main/java/dev/continuo/core/RuntimeLog.java
```

Change its package to `dev.continuo.core` and reword the first javadoc line, which currently says
"The runtime's log":

```java
/**
 * Where the core and the adapter runtime log, abstracted because 1.7.10 predates SLF4J in
 * Minecraft and logs through log4j2.
 *
 * <p>Global rules 2 and 3 log through this, so both versions emit byte-identical text. The smoke
 * checklists assert on those strings, so this strengthens them.
 *
 * <p>It lives in {@code core} rather than in {@code runtime} because {@code dev.continuo.engine} is
 * {@code runtime}'s sibling and needs it too. The alternative was a second logging interface.
 */
```

Add `import dev.continuo.core.RuntimeLog;` to `AdapterRuntime.java` and
`AdapterRuntimeConformanceTest.java`.

- [ ] **Step 5: Rewire both adapters**

In both `adapters/adapter-fabric-1.21.11/build.gradle.kts` and
`adapters/adapter-forge-1.7.10/build.gradle.kts`, add after `implementation(project(":core"))`:

```kotlin
    implementation(project(":core-engine"))
```

Then update imports in all four adapter sources: `ContinuoFabricMod`, `Slf4jRuntimeLog`,
`ContinuoForgeMod`, `Log4jRuntimeLog`. `ContinuoCore` becomes `dev.continuo.engine.ContinuoCore`;
`RuntimeLog` becomes `dev.continuo.core.RuntimeLog`.

- [ ] **Step 6: Verify nothing behaves differently**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, with the **same total test count** as before this task. Two moves and no
new tests: if the count changed, something was lost.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(d2): the bot moves up a layer so it can name a path

core sits below core-pathfinder, so ContinuoCore could not reference a
PathResult where it stood. That is a fact about the build rather than a
preference, and it says out loud what the layering already implied: core is a
world model everything depends on, plus a bot that drives it, and only the
second needs the pathfinder.

core-engine takes the bot. RuntimeLog goes the other way, into core, because
dev.continuo.engine is runtime's sibling rather than its dependent and the
alternative was a second logging interface.

Two mechanical moves, no behaviour change, same test count."
```

---

### Task 5: `HumanizedActuator`

**Files:**
- Create: `core-engine/src/main/java/dev/continuo/engine/HumanizedActuator.java`
- Create: `core-engine/src/test/java/dev/continuo/engine/HumanizedActuatorTest.java`

**Interfaces:**
- Consumes: `Yaw.wrap` (Task 1).
- Produces:
  - `dev.continuo.engine.HumanizedActuator implements IActuator`
  - constructor `HumanizedActuator(IActuator delegate, IPlayerView player)`
  - `HumanizedActuator.MAX_DEG_PER_TICK` → `float`, value `30.0f`

- [ ] **Step 1: Write the failing test**

Create `core-engine/src/test/java/dev/continuo/engine/HumanizedActuatorTest.java`:

```java
package dev.continuo.engine;

import dev.continuo.platform.Input;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanizedActuatorTest {

    private final FakeActuator delegate = new FakeActuator();
    private final FakePlayerView player = new FakePlayerView();
    private final HumanizedActuator actuator = new HumanizedActuator(delegate, player);

    @Test
    void aSmallTurnIsPassedThroughWhole() {
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        actuator.setLook(10.0f, 0.0f);

        assertEquals(1, delegate.lookCalls().size());
        assertEquals(10.0f, delegate.lookCalls().get(0).yaw, 0.001f);
    }

    @Test
    void aLargeTurnIsClampedToTheRate() {
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        actuator.setLook(180.0f, 0.0f);

        assertEquals(HumanizedActuator.MAX_DEG_PER_TICK, delegate.lookCalls().get(0).yaw, 0.001f,
            "a 180 degree request must not arrive in one frame");
    }

    @Test
    void aReversalTakesSixTicksAndNoFewer() {
        // The behavioural claim in one assertion: 180 degrees at 30 per tick is six ticks. The
        // executor re-states its target every tick, so this loop is what actually happens.
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        for (int tick = 0; tick < 6; tick++) {
            actuator.setLook(180.0f, 0.0f);
            // The executor does not move the player here; the adapter would. Feed the written
            // yaw back as the player's actual yaw, which is what a real client does.
            player.set(0.0, 64.0, 0.0, delegate.lookCalls().get(tick).yaw, 0.0f, true);
        }

        assertEquals(180.0f, delegate.lookCalls().get(5).yaw, 0.001f, "six ticks reaches it");
        assertTrue(Math.abs(delegate.lookCalls().get(4).yaw) < 180.0f, "five ticks does not");
    }

    @Test
    void theTurnTakesTheShortestArcAcrossTheDiscontinuity() {
        // From -179 toward 179 is 2 degrees the short way and 358 the long way. An implementation
        // that subtracted without wrapping would step +30 -- the wrong direction entirely.
        player.set(0.0, 64.0, 0.0, -179.0f, 0.0f, true);

        actuator.setLook(179.0f, 0.0f);

        assertEquals(-181.0f, delegate.lookCalls().get(0).yaw, 0.001f,
            "two degrees anticlockwise from -179, not thirty degrees clockwise");
    }

    @Test
    void theStepIsComputedFromTheLivePlayerYawNotThePreviousRequest() {
        // The assertion that pins statelessness. A stateful implementation would remember it had
        // asked for 90 and step on from there; this one must start from where the player now
        // actually is, which is how a server rotation correction gets absorbed.
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);
        actuator.setLook(90.0f, 0.0f);
        assertEquals(30.0f, delegate.lookCalls().get(0).yaw, 0.001f);

        // The server yanks the player somewhere else entirely.
        player.set(0.0, 64.0, 0.0, -90.0f, 0.0f, true);
        actuator.setLook(90.0f, 0.0f);

        assertEquals(-60.0f, delegate.lookCalls().get(1).yaw, 0.001f,
            "thirty degrees on from -90, not from the 30 it last wrote");
    }

    @Test
    void pitchIsPassedThroughUnchanged() {
        player.set(0.0, 64.0, 0.0, 0.0f, 42.0f, true);

        actuator.setLook(0.0f, 42.0f);

        assertEquals(42.0f, delegate.lookCalls().get(0).pitch, 0.001f);
    }

    @Test
    void inputIsPassedThroughUntouched() {
        actuator.setInput(Input.FORWARD, true);
        actuator.setInput(Input.JUMP, false);

        assertEquals(2, delegate.calls().size());
        assertEquals(Input.FORWARD, delegate.calls().get(0).input);
        assertTrue(delegate.calls().get(0).pressed);
        assertEquals(Input.JUMP, delegate.calls().get(1).input);
        assertEquals(false, delegate.calls().get(1).pressed);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.HumanizedActuatorTest'`
Expected: FAIL, compilation error — `HumanizedActuator` does not exist.

- [ ] **Step 3: Implement it**

Create `core-engine/src/main/java/dev/continuo/engine/HumanizedActuator.java`:

```java
package dev.continuo.engine;

import dev.continuo.core.Yaw;
import dev.continuo.platform.IActuator;
import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Input;

/**
 * The humanizer seam: an {@link IActuator} that bounds how fast the player turns.
 *
 * <p><b>Core-side, and a decorator.</b> In an adapter this would be per-version, duplicated,
 * untestable, and would fail M5's SPI audit on sight — an adapter is translation, not behaviour.
 * Here it is a pure class with a single seam, which is what makes it the place anticheat
 * plausibility gets added later without touching the core or the movements.
 *
 * <p><b>Stateless, and that is the design rather than an economy.</b> It stores no target and
 * accumulates no rotation: each call steps from the player's <em>actual</em> yaw toward the
 * requested one. So a server rotation correction or a nudge of the user's mouse is absorbed on the
 * next tick rather than detected, and there is no remembered value that can go stale.
 *
 * <p><b>This only works because actuation is level-triggered.</b> Global rule 4 obliges a driving
 * core to re-state its desired facing every tick, so this never needs to remember what it was aiming
 * at. A decorator like this under an edge-triggered core would have to keep the target and tick
 * itself.
 *
 * <p><b>Call window — stricter than {@link IActuator}'s.</b> {@link IActuator#setLook} MUST NOT
 * throw outside {@link IGameEvents#onClientTick}'s delivery window, but {@link IPlayerView#yaw()},
 * which this reads, is unspecified there. <b>Call this only inside that window.</b> That is
 * legitimate because it is a core-side type with one caller, the executor, which runs only in
 * {@code PRE} — but it is stated here rather than left as a trap for whoever wraps it next.
 *
 * <p><b>{@link #setInput} is passthrough, deliberately.</b> Jittering presses would contradict rule
 * 4's "state the full desired input set every tick", and the two cannot both be true. Whatever input
 * humanization eventually looks like, it is not a decorator that drops presses.
 */
public final class HumanizedActuator implements IActuator {

    /**
     * The most the player's yaw may change in one tick, in degrees.
     *
     * <p>Thirty, so a 180° reversal takes six ticks — about 0.3 s, slow enough not to be a single
     * frame's teleport and fast enough not to be felt as lag while path-following.
     *
     * <p><b>Extrapolated, not measured.</b> D2's in-game run observes it and this javadoc is
     * rewritten with what was seen, exactly as {@code Run.SLICE_NODES} was after D1.
     */
    public static final float MAX_DEG_PER_TICK = 30.0f;

    private final IActuator delegate;
    private final IPlayerView player;

    /**
     * @param delegate the adapter's actuator, which this wraps; never {@code null}
     * @param player   where the turn starts from, read on every call; never {@code null}
     * @throws IllegalArgumentException if either is null
     */
    public HumanizedActuator(IActuator delegate, IPlayerView player) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        this.delegate = delegate;
        this.player = player;
    }

    /** Passed through untouched. See the class documentation for why. */
    @Override
    public void setInput(Input input, boolean pressed) {
        delegate.setInput(input, pressed);
    }

    /**
     * Turns toward {@code yaw} by at most {@link #MAX_DEG_PER_TICK}, taking the shortest arc.
     *
     * @param yaw   the heading wanted; any finite value
     * @param pitch passed through unchanged, so it MUST already be in {@code [-90, 90]}
     */
    @Override
    public void setLook(float yaw, float pitch) {
        float current = player.yaw();
        float delta = Yaw.wrap(yaw - current);
        if (delta > MAX_DEG_PER_TICK) {
            delta = MAX_DEG_PER_TICK;
        } else if (delta < -MAX_DEG_PER_TICK) {
            delta = -MAX_DEG_PER_TICK;
        }
        delegate.setLook(current + delta, pitch);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.HumanizedActuatorTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Prove the statelessness test can fail**

Temporarily change `setLook` to remember the last yaw it wrote and use that as `current` when it is
not null.

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.HumanizedActuatorTest'`
Expected: **FAIL** on `theStepIsComputedFromTheLivePlayerYawNotThePreviousRequest`, and **only** that
one — if `theTurnTakesTheShortestArc...` also fails, the mutation was not the intended one.

**Revert and re-run to confirm green.** Record the observed failure in the commit body.

- [ ] **Step 6: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core-engine/src/main/java/dev/continuo/engine/HumanizedActuator.java core-engine/src/test/java/dev/continuo/engine/HumanizedActuatorTest.java
git commit -m "feat(d2): the humanizer seam, stateless because actuation is level-triggered

It stores no target and accumulates no rotation: each call steps from the
player's actual yaw toward the requested one, so a server rotation correction or
a mouse nudge is absorbed on the next tick rather than detected. That shape only
works because rule 4 obliges a driving core to re-state its desired facing every
tick -- D1 section 6.3 argued this in the abstract and this is the concrete form.

It carries a stricter call window than IActuator does, because it reads
IPlayerView, and says so on the class rather than leaving it latent.

setInput is passthrough. Jittering presses would contradict rule 4's 'state the
full desired input set every tick', and the two cannot both be true."
```

---

### Task 6: `PathFollower`

Pure geometry: where the player is on a path, and whether it is still on it. No SPI writes, so every
case is testable without an actuator.

**Files:**
- Create: `core-engine/src/main/java/dev/continuo/engine/PathFollower.java`
- Create: `core-engine/src/test/java/dev/continuo/engine/PathFollowerTest.java`

**Interfaces:**
- Consumes: `Step`, `Steps` output via `PathResult.steps()` (Task 3).
- Produces:
  - `dev.continuo.engine.PathFollower`, package-private
  - `PathFollower(List<Pos> path, List<Step> steps)`
  - `boolean reanchor(IPlayerView player)` — `false` means off path
  - `boolean arrived(IPlayerView player)`
  - `int anchor()`, `int remaining()`, `Step current()`, `Pos last()`
  - `void append(List<Pos> more, List<Step> moreSteps)`
  - constants `W`, `OFF_PATH_RADIUS`, `ARRIVE_RADIUS`

- [ ] **Step 1: Write the failing test**

Create `core-engine/src/test/java/dev/continuo/engine/PathFollowerTest.java`:

```java
package dev.continuo.engine;

import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFollowerTest {

    private final FakePlayerView player = new FakePlayerView();

    /** A straight run of {@code length} traverse steps east along z=0 at y=64, from x=0. */
    private static PathFollower straightRun(int length) {
        List<Pos> path = new ArrayList<Pos>();
        for (int x = 0; x <= length; x++) {
            path.add(new Pos(x, 64, 0));
        }
        return follower(path);
    }

    private static PathFollower follower(List<Pos> path) {
        return new PathFollower(path, dev.continuo.pathfinder.PathResults.stepsOf(path));
    }

    /** Puts the player at the centre of a block, on the ground. */
    private void standOn(int x, int y, int z) {
        player.set(x + 0.5, y, z + 0.5, 0.0f, 0.0f, true);
    }

    @Test
    void anchorsToTheNodeThePlayerIsStandingOn() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);

        assertTrue(follower.reanchor(player));
        assertEquals(5, follower.anchor());
    }

    @Test
    void theCurrentStepIsTheOneLeavingTheAnchor() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertEquals(new Pos(6, 64, 0), follower.current().to(),
            "the step being driven goes to the node after the anchor");
    }

    @Test
    void aKnockbackInsideTheWindowIsAbsorbed() {
        // The player is shoved three blocks back. No repath: the anchor simply follows, which is
        // the whole of what replaced onPositionCorrection for this case.
        PathFollower follower = straightRun(20);
        standOn(10, 64, 0);
        follower.reanchor(player);

        standOn(7, 64, 0);

        assertTrue(follower.reanchor(player), "still on the path");
        assertEquals(7, follower.anchor());
    }

    @Test
    void aTeleportOutsideTheWindowIsOffPath() {
        PathFollower follower = straightRun(20);
        standOn(2, 64, 0);
        follower.reanchor(player);

        standOn(19, 64, 0);

        assertFalse(follower.reanchor(player),
            "a node 17 steps ahead is outside the window, so this is a teleport, not progress");
    }

    @Test
    void beingNearNoNodeAtAllIsOffPath() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        // Same index range, but forty blocks sideways.
        player.set(5.5, 64.0, 40.5, 0.0f, 0.0f, true);

        assertFalse(follower.reanchor(player));
    }

    @Test
    void theWindowStopsASelfApproachingRouteFromSkippingALoop() {
        // A spiral: the route climbs a 3x3 column and passes directly over its own earlier nodes.
        // Node 0 is (0,64,0); node 12 is (0,67,0), three blocks straight up. An unbounded nearest
        // scan from the very start of the route can anchor on the node above; the window cannot.
        List<Pos> path = new ArrayList<Pos>();
        int[][] ring = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};
        for (int turn = 0; turn < 3; turn++) {
            for (int i = 0; i < ring.length; i++) {
                path.add(new Pos(ring[i][0], 64 + turn, ring[i][1]));
            }
        }
        PathFollower follower = follower(path);

        standOn(0, 64, 0);
        assertTrue(follower.reanchor(player));
        assertEquals(0, follower.anchor(),
            "the identical node one revolution up must not win over the one underfoot");

        // And having gone round once, the anchor must be on the second revolution, not the first.
        standOn(0, 65, 0);
        assertTrue(follower.reanchor(player));
        assertEquals(8, follower.anchor());
    }

    @Test
    void arrivalIsTheLastNodeAndNotMerelyTheLastStep() {
        PathFollower follower = straightRun(3);
        standOn(3, 64, 0);

        assertTrue(follower.reanchor(player));
        assertTrue(follower.arrived(player));
    }

    @Test
    void standingOnTheLastNodeOfAnEarlierRevolutionIsNotArrival() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertFalse(follower.arrived(player), "fifteen steps of path remain");
    }

    @Test
    void remainingCountsTheStepsLeft() {
        PathFollower follower = straightRun(20);
        standOn(5, 64, 0);
        follower.reanchor(player);

        assertEquals(15, follower.remaining());
    }

    @Test
    void appendExtendsThePathAndLeavesTheAnchorAlone() {
        // Plan-ahead adoption. The player must not so much as break stride, so the anchor -- an
        // index into a list that only grew at its far end -- has to survive untouched.
        PathFollower follower = straightRun(20);
        standOn(18, 64, 0);
        follower.reanchor(player);
        int before = follower.anchor();

        List<Pos> more = new ArrayList<Pos>();
        more.add(new Pos(20, 64, 0));
        more.add(new Pos(21, 64, 0));
        more.add(new Pos(22, 64, 0));
        follower.append(more, dev.continuo.pathfinder.PathResults.stepsOf(more));

        assertEquals(before, follower.anchor());
        assertEquals(4, follower.remaining());
        assertEquals(new Pos(22, 64, 0), follower.last());
    }

    @Test
    void appendRefusesAContinuationThatDoesNotBeginWhereThisPathEnds() {
        // Plan-ahead always searches from the path's end, so this cannot happen -- and if it ever
        // did, silently accepting it would grow the path by one more entry than the steps and
        // break this class's invariant with no visible symptom until the executor drove a step
        // that names the wrong node.
        final PathFollower follower = straightRun(5);
        final List<Pos> disconnected = new ArrayList<Pos>();
        disconnected.add(new Pos(40, 64, 0));
        disconnected.add(new Pos(41, 64, 0));

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                follower.append(disconnected, PathResults.stepsOf(disconnected));
            }
        });
    }
}
```

The imports this file needs beyond those already listed: `dev.continuo.pathfinder.PathResults` and
`static org.junit.jupiter.api.Assertions.assertThrows`. Note `FixtureWorld.parse(String)` is the
factory Task 3 uses; it is package-private, which is why the coverage guard lives in
`core-pathfinder`'s test package and not in `core-engine`'s.

- [ ] **Step 2: Add the test-facing step derivation helper**

`Steps` is package-private in `core-pathfinder` and `PathFollower` lives in `core-engine`, so tests
need a public way to derive steps for a hand-built path. Add to
`core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResults.java` (create it):

```java
package dev.continuo.pathfinder;

import java.util.List;

/**
 * Factory helpers for result types whose constructors are package-private.
 *
 * <p>Exists because {@link Steps} is an implementation detail of this package while
 * {@link Step} is part of its public surface: a caller outside the package that has a list of
 * positions — an executor adopting one, or a test building one by hand — needs the same derivation
 * without the package being opened up.
 */
public final class PathResults {

    private PathResults() {
    }

    /**
     * The moves joining a list of positions, derived exactly as a search result derives its own.
     *
     * @param path the route, start to end; never {@code null}
     * @return one step per adjacent pair, unmodifiable; empty when {@code path} has fewer than two
     *         entries
     * @throws IllegalArgumentException if {@code path} is null
     */
    public static List<Step> stepsOf(List<Pos> path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        return Steps.derive(path);
    }
}
```

- [ ] **Step 3: Run the test and confirm it fails**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathFollowerTest'`
Expected: FAIL, compilation error — `PathFollower` does not exist.

- [ ] **Step 4: Implement `PathFollower`**

Create `core-engine/src/main/java/dev/continuo/engine/PathFollower.java`:

```java
package dev.continuo.engine;

import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.IPlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the player is on a path, recomputed every tick from the player's actual position.
 *
 * <p><b>The anchor is a hint, not an authority.</b> It is one {@code int}, and every tick it is
 * recomputed by scanning the nodes within {@link #W} of it for the one nearest the player. Nothing
 * here remembers what the player did; it only ever looks at where the player now is.
 *
 * <p>That is what replaced {@code onPositionCorrection}, which D1 §3.5 dissolved rather than
 * deferred. Knockback moves the player back a step and the anchor follows with no special case; a
 * teleport moves the player outside the window and {@link #reanchor} reports off-path, which is a
 * repath. Neither is a state this class tracks. <b>The window bound is not an optimisation</b> — an
 * unbounded nearest scan anchors to whichever node happens to be closest, and on a spiral staircase
 * or a switchback that is a node a whole revolution away, which skips the loop and drives the player
 * into a wall.
 */
final class PathFollower {

    /**
     * How many steps either side of the last anchor are scanned.
     *
     * <p>Six. A walking player covers roughly 0.22 blocks per tick, so even ten dropped ticks move
     * it under three steps — the window only has to be wider than one tick's travel plus slack. It
     * also has to be <em>narrower</em> than the shortest distance along the path at which a route
     * comes back near itself, and a 3×3 spiral staircase revolves in roughly 8–12 steps.
     *
     * <p><b>Extrapolated, and the revolution length is reasoned rather than counted.</b> If a real
     * staircase revolves in six, this is already too large. D2's in-game run raises it past a real
     * revolution deliberately, to confirm the skip it is here to prevent.
     */
    static final int W = 6;

    /**
     * How far from the nearest windowed node the player may be before it counts as off path, in
     * blocks.
     *
     * <p>Two: wider than a knockback displaces, narrower than a teleport. <b>Extrapolated.</b>
     */
    static final double OFF_PATH_RADIUS = 2.0;

    /**
     * How close to the final node counts as arrived, in blocks.
     *
     * <p>Half a block — the tolerance around a block centre. <b>Extrapolated.</b>
     */
    static final double ARRIVE_RADIUS = 0.5;

    private final List<Pos> path;
    private final List<Step> steps;

    private int anchor;

    /**
     * @param path  the route, start to end; never {@code null} and never empty
     * @param steps its moves, exactly one fewer than {@code path} has entries
     * @throws IllegalArgumentException if the two do not describe the same route
     */
    PathFollower(List<Pos> path, List<Step> steps) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        if (steps == null || steps.size() != path.size() - 1) {
            throw new IllegalArgumentException("steps must have exactly one fewer entry than path;"
                + " got " + (steps == null ? "null" : String.valueOf(steps.size()))
                + " for a path of " + path.size());
        }
        this.path = new ArrayList<Pos>(path);
        this.steps = new ArrayList<Step>(steps);
    }

    /**
     * Recomputes the anchor from the player's current position.
     *
     * @param player where the player is now; never {@code null}
     * @return {@code true} if the player is still on the path, {@code false} if it is off it and
     *         the caller should repath
     */
    boolean reanchor(IPlayerView player) {
        int from = anchor - W;
        if (from < 0) {
            from = 0;
        }
        int to = anchor + W;
        if (to > path.size() - 1) {
            to = path.size() - 1;
        }

        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            double distance = distanceTo(path.get(i), player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0 || bestDistance > OFF_PATH_RADIUS * OFF_PATH_RADIUS) {
            return false;
        }
        anchor = best;
        return true;
    }

    /**
     * @param player where the player is now; never {@code null}
     * @return whether the anchor is the final node and the player is within {@link #ARRIVE_RADIUS}
     *         of it
     */
    boolean arrived(IPlayerView player) {
        return anchor == path.size() - 1
            && distanceTo(path.get(anchor), player) <= ARRIVE_RADIUS * ARRIVE_RADIUS;
    }

    /** @return the index of the node the player is currently nearest */
    int anchor() {
        return anchor;
    }

    /** @return how many steps of path are left ahead of the anchor */
    int remaining() {
        return path.size() - 1 - anchor;
    }

    /** @return the step leaving the anchor, or {@code null} when the anchor is the last node */
    Step current() {
        return anchor < steps.size() ? steps.get(anchor) : null;
    }

    /** @return where this path ends */
    Pos last() {
        return path.get(path.size() - 1);
    }

    /**
     * Extends this path with a continuation searched from {@link #last()}.
     *
     * <p><b>The anchor is untouched</b>, which is the point: the list only grew at its far end, so
     * the index still names the same node and the player does not break stride. Plan-ahead adoption
     * is an append for exactly this reason, and a repath — which cannot preserve the anchor — builds
     * a new follower instead.
     *
     * @param more      the continuation, beginning at this path's last node
     * @param moreSteps its moves
     */
    void append(List<Pos> more, List<Step> moreSteps) {
        if (more.isEmpty()) {
            return;
        }
        // The continuation is searched from this path's end, so its first position repeats that
        // node and is dropped -- the same join Run.append makes between segments, for the same
        // reason: appending whole would repeat a position and derive as a null-kind step.
        //
        // Required rather than tolerated. If the continuation began somewhere else, dropping
        // nothing would grow the path by one more entry than the steps, breaking this class's
        // invariant silently; and joining across the discontinuity would invent a move no search
        // ever offered.
        if (!more.get(0).equals(last())) {
            throw new IllegalArgumentException("a continuation must begin at this path's last node "
                + last() + ", but began at " + more.get(0));
        }
        for (int i = 1; i < more.size(); i++) {
            path.add(more.get(i));
        }
        steps.addAll(moreSteps);
    }

    /** Squared 3D distance from a node's standing position to the player. */
    private static double distanceTo(Pos node, IPlayerView player) {
        double dx = (node.x() + 0.5) - player.x();
        // Feet to feet: a node's y is the block the feet occupy, which is what IPlayerView.y()
        // returns after D1. Comparing to a block centre here would be a half-block error on every
        // node.
        double dy = node.y() - player.y();
        double dz = (node.z() + 0.5) - player.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathFollowerTest'`
Expected: PASS, 10 tests.

- [ ] **Step 6: Prove the spiral test can fail — this is the one that justifies `W`**

Temporarily change `reanchor` to scan the whole path (`from = 0; to = path.size() - 1`).

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathFollowerTest'`
Expected: **FAIL** on `theWindowStopsASelfApproachingRouteFromSkippingALoop`.

**If it passes with an unbounded window, the spiral fixture is wrong and proves nothing.** In that
case make the spiral tighter or taller until the unbounded scan genuinely picks the wrong node, then
restore the window. Do not proceed with a spiral test that passes both ways.

**Revert and re-run to confirm green.** Record the observed failure in the commit body.

- [ ] **Step 7: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core-engine/src/main/java/dev/continuo/engine/PathFollower.java core-engine/src/test/java/dev/continuo/engine/PathFollowerTest.java core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResults.java
git commit -m "feat(d2): where the player is on the path, recomputed every tick

The anchor is one int and it is a hint rather than an authority: every tick it
is recomputed by scanning the nodes within six of it for the one nearest the
player. Knockback is absorbed with no special case and a teleport reports
off-path, which is a repath -- neither is a state this class tracks. That is
what D1 dissolved onPositionCorrection in favour of.

The window bound is not an optimisation. An unbounded nearest scan anchors to
whichever node is closest, and on a spiral staircase that is a node a whole
revolution away, which skips the loop and drives into a wall. The spiral test is
demonstrated to fail with the window removed."
```

---

### Task 7: `PathExecutor` — driving

The tick loop, level-triggering, and the drive table. No search yet: a path is handed in directly,
through the same package-private entry the search will use in Task 8.

**Files:**
- Create: `core-engine/src/main/java/dev/continuo/engine/PathExecutor.java`
- Create: `core-engine/src/test/java/dev/continuo/engine/PathExecutorDriveTest.java`

**Interfaces:**
- Consumes: `PathFollower` (Task 6), `HumanizedActuator` (Task 5), `Yaw.toward` (Task 1),
  `MovementKind` (Task 2), `PathResults.stepsOf` (Task 6).
- Produces:
  - `dev.continuo.engine.PathExecutor`
  - `PathExecutor(BlockSource world, IActuator actuator, IPlayerView player, RuntimeLog log)`
  - `void follow(List<Pos> path, List<Step> steps)` — package-private
  - `void tick(IPlayerView player)`
  - `boolean active()`
  - `void stop()`

- [ ] **Step 1: Write the failing test**

Create `core-engine/src/test/java/dev/continuo/engine/PathExecutorDriveTest.java`:

```java
package dev.continuo.engine;

import dev.continuo.core.Yaw;
import dev.continuo.pathfinder.PathResults;
import dev.continuo.pathfinder.Pos;
import dev.continuo.platform.Input;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorDriveTest {

    private final FakeActuator actuator = new FakeActuator();
    private final FakePlayerView player = new FakePlayerView();
    private final RecordingLog log = new RecordingLog();
    private final PathExecutor executor =
        new PathExecutor(new EmptyWorld(), actuator, player, log);

    private void standOn(int x, int y, int z, boolean onGround) {
        player.set(x + 0.5, y, z + 0.5, player.yaw(), 0.0f, onGround);
    }

    /** The last state each input was written to on this tick. */
    private Map<Input, Boolean> written() {
        Map<Input, Boolean> state = new EnumMap<Input, Boolean>(Input.class);
        for (FakeActuator.Call call : actuator.calls()) {
            state.put(call.input, Boolean.valueOf(call.pressed));
        }
        return state;
    }

    private void followFlat(int length) {
        List<Pos> path = new ArrayList<Pos>();
        for (int x = 0; x <= length; x++) {
            path.add(new Pos(x, 64, 0));
        }
        executor.follow(path, PathResults.stepsOf(path));
    }

    @Test
    void anIdleExecutorWritesNothingAtAll() {
        // Rule 4's second clause, and the one an implementation is most likely to get wrong: a
        // core that held every input false each tick would fight the user's own keyboard whenever
        // the bot is not running.
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(0, actuator.callCount(), "an idle core writes nothing, not even false");
        assertEquals(0, actuator.lookCalls().size());
        assertFalse(executor.active());
    }

    @Test
    void aDrivingTickStatesEverySevenInputs() {
        // Rule 4's first clause. Stating the full set is what makes a screen opening cost one lost
        // tick instead of truncating the walk silently.
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(Input.values().length, written().size(),
            "every Input constant must be written every driving tick");
    }

    @Test
    void traverseHoldsForwardAndNothingElse() {
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Boolean.TRUE, state.get(Input.FORWARD));
        assertEquals(Boolean.FALSE, state.get(Input.JUMP));
        assertEquals(Boolean.FALSE, state.get(Input.SPRINT));
        assertEquals(Boolean.FALSE, state.get(Input.SNEAK));
        assertEquals(Boolean.FALSE, state.get(Input.LEFT));
        assertEquals(Boolean.FALSE, state.get(Input.RIGHT));
        assertEquals(Boolean.FALSE, state.get(Input.BACK));
    }

    @Test
    void ascendPressesJumpOnlyWhileOnTheGround() {
        // Spec section 3.9: both versions gate the jump on their own ground flag AND arm a
        // ten-tick cooldown that is cleared the moment the key is released. Holding JUMP through
        // an ascend therefore throttles a staircase to one block per ten ticks. Conditioning on
        // onGround() produces the release without any memory.
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 65, 0));
        path.add(new Pos(2, 66, 0));
        executor.follow(path, PathResults.stepsOf(path));

        standOn(0, 64, 0, true);
        executor.tick(player);
        assertEquals(Boolean.TRUE, written().get(Input.JUMP), "on the ground, jump");

        actuator.clear();
        player.set(0.5, 64.6, 0.5, player.yaw(), 0.0f, false);
        executor.tick(player);
        assertEquals(Boolean.FALSE, written().get(Input.JUMP),
            "airborne, release -- which is what clears the ten-tick cooldown");
    }

    @Test
    void descendNeverPressesJump() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 61, 0));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(Boolean.FALSE, written().get(Input.JUMP),
            "jumping off a ledge overshoots the landing the search chose");
    }

    @Test
    void itFacesTheDestinationOfTheStepItIsDriving() {
        followFlat(10);
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertEquals(1, actuator.lookCalls().size());
        float expected = Yaw.toward(player.x(), player.z(), 1, 0);
        // Through the humanizer, so the written yaw is a bounded step toward the target rather
        // than the target itself. From yaw 0 toward -90 that is -30.
        assertEquals(-HumanizedActuator.MAX_DEG_PER_TICK, actuator.lookCalls().get(0).yaw, 0.001f,
            "a turn toward " + expected + " arrives at the rate limit, not all at once");
    }

    @Test
    void aDiagonalIsWalkedByFacingItRatherThanByStrafing() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(1, 64, 1));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Boolean.FALSE, state.get(Input.LEFT));
        assertEquals(Boolean.FALSE, state.get(Input.RIGHT));
        assertEquals(Boolean.TRUE, state.get(Input.FORWARD));
    }

    @Test
    void arrivingReleasesEveryInputOnceAndGoesIdle() {
        followFlat(2);
        standOn(0, 64, 0, true);
        executor.tick(player);

        actuator.clear();
        standOn(2, 64, 0, true);
        executor.tick(player);

        Map<Input, Boolean> state = written();
        assertEquals(Input.values().length, state.size(), "the release states the whole set");
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, state.get(input), input + " must be released on arrival");
        }
        assertFalse(executor.active());

        actuator.clear();
        executor.tick(player);
        assertEquals(0, actuator.callCount(), "and then it is idle, writing nothing");
    }

    @Test
    void stopReleasesEveryInputOnceAndGoesIdle() {
        followFlat(10);
        standOn(0, 64, 0, true);
        executor.tick(player);
        actuator.clear();

        executor.stop();

        Map<Input, Boolean> state = written();
        assertEquals(Input.values().length, state.size());
        for (Input input : Input.values()) {
            assertEquals(Boolean.FALSE, state.get(input));
        }
        assertFalse(executor.active());
    }

    @Test
    void stopWhileIdleWritesNothing() {
        // Rule 2 makes stop idempotent, and AdapterRuntime calls it on every level transition --
        // including the ordinary world load where nothing was ever running. Releasing there would
        // stamp on the user's keyboard on every dimension change.
        executor.stop();

        assertEquals(0, actuator.callCount());
    }

    @Test
    void anUnnameableStepStopsRatherThanGuessing() {
        List<Pos> path = new ArrayList<Pos>();
        path.add(new Pos(0, 64, 0));
        path.add(new Pos(4, 68, 3));
        executor.follow(path, PathResults.stepsOf(path));
        standOn(0, 64, 0, true);

        executor.tick(player);

        assertFalse(executor.active());
        assertTrue(log.messages().toString().contains("cannot be named"),
            "the log must name the delta, since this means MovementKind's table has a hole");
    }
}
```

- [ ] **Step 2: Add the two test doubles the test needs**

Create `core-engine/src/test/java/dev/continuo/engine/RecordingLog.java`:

```java
package dev.continuo.engine;

import dev.continuo.core.RuntimeLog;

import java.util.ArrayList;
import java.util.List;

/** Captures log lines so a test can assert the executor explained why it stopped. */
final class RecordingLog implements RuntimeLog {

    private final List<String> messages = new ArrayList<String>();

    @Override
    public void info(String message) {
        messages.add(message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        messages.add(message);
    }

    List<String> messages() {
        return messages;
    }
}
```

Create `core-engine/src/test/java/dev/continuo/engine/EmptyWorld.java`:

```java
package dev.continuo.engine;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;

/**
 * A world that answers {@code UNKNOWN} everywhere.
 *
 * <p>Enough for the drive tests, which hand the executor a path directly and never search. A
 * search against this returns {@code NO_PATH}, which is what the search tests rely on.
 */
final class EmptyWorld implements BlockSource {

    @Override
    public BlockData at(int x, int y, int z) {
        return BlockData.UNKNOWN;
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        return 255;
    }
}
```

- [ ] **Step 3: Run the test and confirm it fails**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathExecutorDriveTest'`
Expected: FAIL, compilation error — `PathExecutor` does not exist.

- [ ] **Step 4: Implement the driving half of `PathExecutor`**

Create `core-engine/src/main/java/dev/continuo/engine/PathExecutor.java`. Task 8 adds the search
fields and methods; this step implements everything else.

```java
package dev.continuo.engine;

import dev.continuo.core.BlockSource;
import dev.continuo.core.RuntimeLog;
import dev.continuo.core.Yaw;
import dev.continuo.movement.MovementKind;
import dev.continuo.pathfinder.Pos;
import dev.continuo.pathfinder.Step;
import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Input;

import java.util.List;

/**
 * Turns a path into per-tick game inputs.
 *
 * <p><b>A function of the path and the player's current state, not a state machine.</b> Every tick
 * it recomputes where the player is on the path and writes the full input set that follows from
 * that, having remembered nothing about what it wrote last tick. Global rule 4 chose level-triggered
 * actuation specifically to make this shape possible: because the core re-states everything each
 * tick, a screen opening — which clears held key state on both target versions — costs one lost tick
 * and self-heals, instead of silently truncating a walk.
 *
 * <p><b>Driven from {@code PRE}.</b> {@link IActuator#setInput} and {@link IActuator#setLook} take
 * effect at the game's next input read, which is the current tick when called from
 * {@code TickPhase.PRE}.
 *
 * <p><b>While idle it writes nothing at all</b> — not even a release. Rule 4's second clause: a core
 * holding every input at {@code false} every tick would fight the user's own keyboard whenever the
 * bot is not running.
 */
public final class PathExecutor {

    private final BlockSource world;
    private final IActuator actuator;
    private final RuntimeLog log;

    private PathFollower follower;

    /**
     * @param world    the world searches read; never {@code null}
     * @param actuator the adapter's actuator, wrapped in the humanizer here; never {@code null}
     * @param player   the player the humanizer turns from; never {@code null}
     * @param log      where a termination is explained; never {@code null}
     * @throws IllegalArgumentException if any argument is null
     */
    public PathExecutor(BlockSource world, IActuator actuator, IPlayerView player, RuntimeLog log) {
        if (world == null || actuator == null || player == null || log == null) {
            throw new IllegalArgumentException("no constructor argument may be null");
        }
        this.world = world;
        this.actuator = new HumanizedActuator(actuator, player);
        this.log = log;
    }

    /**
     * Begins driving a path, replacing whatever was being driven.
     *
     * <p>Package-private because a caller outside this package names a destination rather than a
     * route; {@code walkTo} is the public entry.
     *
     * @param path  the route, start to end; never empty
     * @param steps its moves
     */
    void follow(List<Pos> path, List<Step> steps) {
        follower = new PathFollower(path, steps);
    }

    /** @return whether this executor is driving or searching, and therefore owes inputs */
    public boolean active() {
        return follower != null;
    }

    /**
     * Releases every input, once, and goes idle.
     *
     * <p>Idempotent, and writes nothing when already idle: global rule 2 has an adapter call the
     * core's {@code stop} on every client level transition, including the ordinary world load where
     * nothing was running, and releasing there would stamp on the user's keyboard.
     */
    public void stop() {
        if (follower == null) {
            return;
        }
        follower = null;
        releaseAll();
    }

    /**
     * Spends one tick: re-anchor, then drive.
     *
     * @param player where the player is now; never {@code null}
     */
    public void tick(IPlayerView player) {
        if (follower == null) {
            return;
        }
        if (!follower.reanchor(player)) {
            offPath(player);
            return;
        }
        if (follower.arrived(player)) {
            log.info("Continuo executor: arrived");
            stop();
            return;
        }
        Step step = follower.current();
        MovementKind kind = step.kind();
        if (kind == null || kind == MovementKind.PARKOUR) {
            String reason = kind == null
                ? "a step whose movement cannot be named"
                : "a parkour step, which this executor does not grant or execute";
            log.info("Continuo executor: stopping at " + step.to() + " -- " + reason);
            stop();
            return;
        }
        drive(kind, step, player);
    }

    /** Writes the whole input set and the desired facing for one step. */
    private void drive(MovementKind kind, Step step, IPlayerView player) {
        // Only ASCEND jumps, and only from the ground. Both versions gate the jump on their own
        // ground flag and arm a ten-tick cooldown that is cleared the instant the key is released
        // (1.7.10 EntityLivingBase.onLivingUpdate:1998-2016; 1.21.11 LivingEntity.aiStep:2926-2950),
        // so holding JUMP through a staircase throttles it to one block per ten ticks. Conditioning
        // on onGround() produces the release that clears the cooldown, without remembering anything.
        boolean jump = kind == MovementKind.ASCEND && player.onGround();

        // The full set, every tick, per global rule 4. Writing only what changed would leave a
        // JUMP held from a finished ascend, and would rely on a previous setInput persisting --
        // which rule 4 says a core may not do.
        actuator.setInput(Input.FORWARD, true);
        actuator.setInput(Input.JUMP, jump);
        actuator.setInput(Input.BACK, false);
        actuator.setInput(Input.LEFT, false);
        actuator.setInput(Input.RIGHT, false);
        actuator.setInput(Input.SNEAK, false);
        actuator.setInput(Input.SPRINT, false);

        // A diagonal is walked by facing its destination and holding FORWARD, which is why LEFT
        // and RIGHT are never pressed.
        actuator.setLook(Yaw.toward(player.x(), player.z(), step.to().x(), step.to().z()),
            player.pitch());
    }

    private void releaseAll() {
        for (Input input : Input.values()) {
            actuator.setInput(input, false);
        }
    }
}
```

`offPath(IPlayerView)` is Task 8's. For this task, implement it as:

```java
    private void offPath(IPlayerView player) {
        log.info("Continuo executor: off path, stopping");
        stop();
    }
```

Task 8 replaces that body with a repath. Leave `world` unused for now — Task 8 is its consumer, and
an unused private field will not fail the build.

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathExecutorDriveTest'`
Expected: PASS, 11 tests.

- [ ] **Step 6: Prove the two rule-4 tests can fail**

Mutation A — change `drive` to write only `FORWARD` and `JUMP`.
Expected: **FAIL** on `aDrivingTickStatesEverySevenInputs`.

Mutation B — change `stop()` to call `releaseAll()` unconditionally, before the null check.
Expected: **FAIL** on `stopWhileIdleWritesNothing`.

Mutation C — change `drive` so `jump` is `kind == MovementKind.ASCEND` without the `onGround()`
condition.
Expected: **FAIL** on `ascendPressesJumpOnlyWhileOnTheGround`, on its second assertion only.

**Revert all three and re-run to confirm green.** Record each observed failure in the commit body.
These are hypotheses: **if any mutation survives, that is a finding — report it rather than adjusting
the test to match.**

- [ ] **Step 7: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core-engine/src/main/java/dev/continuo/engine/PathExecutor.java core-engine/src/test/java/dev/continuo/engine/PathExecutorDriveTest.java core-engine/src/test/java/dev/continuo/engine/RecordingLog.java core-engine/src/test/java/dev/continuo/engine/EmptyWorld.java
git commit -m "feat(d2): the drive table, stating the full input set every tick

Every driving tick writes all seven Input constants, which is the strict reading
of rule 4 and makes a JUMP left over from a finished ascend structurally
impossible. Every idle tick writes nothing at all -- not even false -- which is
rule 4's second clause and the half an implementation is most likely to get
wrong.

ASCEND presses JUMP only while onGround(). Both versions gate the jump on their
own ground flag and arm a ten-tick cooldown cleared the instant the key is
released, so holding it through a staircase throttles the ascent; conditioning on
onGround() produces that release without remembering anything, which keeps the
drive table a pure function of the player's current state.

A diagonal is walked by facing it, so LEFT and RIGHT are never pressed."
```

---

### Task 8: `PathExecutor` — searching

Plan-ahead, repath, stuck, and termination. This is where decision 2 — overlapping search with
movement — actually lands.

**Files:**
- Modify: `core-engine/src/main/java/dev/continuo/engine/PathExecutor.java`
- Create: `core-engine/src/test/java/dev/continuo/engine/PathExecutorSearchTest.java`

**Interfaces:**
- Consumes: everything from Task 7, plus `SegmentedSearch`, `Run`, `Run.SLICE_NODES`, `GoalBlock`,
  `WorldSnapshot`, `CapabilitySet`.
- Produces:
  - `PathExecutor.walkTo(int x, int y, int z)`
  - constants `PLAN_AHEAD_STEPS`, `STUCK_TICKS`, `MAX_CONSECUTIVE_REPATHS`

- [ ] **Step 1: Write the failing test**

Create `core-engine/src/test/java/dev/continuo/engine/PathExecutorSearchTest.java`. Build a real
`BlockSource` fixture — a flat stone floor with two clear layers above, at least 40 blocks long — so
searches genuinely succeed. Cover:

```java
    @Test
    void walkToStandsStillUntilItHasAPathAndThenDrives() {
        // While searching, the executor is not driving, so rule 4's idle clause applies: it
        // writes nothing at all. It is still active(), because it owes inputs shortly.
    }

    @Test
    void aSearchIsSpentOneSliceATickRatherThanAllAtOnce() {
        // The property C5 exists for. Assert that a walkTo over a long route needs more than one
        // tick before it drives, and that no tick expands more than Run.SLICE_NODES nodes.
    }

    @Test
    void planAheadStartsBeforeThePathRunsOutAndInputsKeepBeingWritten() {
        // Decision 2, and BOTH clauses matter. Drive a path whose end is not the goal until
        // remaining() drops to PLAN_AHEAD_STEPS, then assert a search is in flight AND that the
        // same ticks still wrote a full input set. Without the second assertion this test passes
        // on a sequential implementation and decision 2 goes unenforced.
    }

    @Test
    void planAheadAdoptionAppendsAndLeavesTheAnchorAlone() {
    }

    @Test
    void goingOffPathReleasesEveryInputOnceThenRepathsFromThePlayer() {
        // Teleport the player far off the route. Assert: a full release on that tick, no drive
        // while the repath runs, then driving resumes on a path whose first node is near where
        // the player now is.
    }

    @Test
    void aRepathCancelsAPendingPlanAhead() {
        // A plan-ahead searches from a path end the repath is about to discard, so letting the
        // two race would append a continuation of a route that no longer exists. Assert through
        // Run.cancelled() by reference, not through behaviour.
    }

    @Test
    void stuckFiresAtStuckTicksAndNotAtOneFewer() {
        // Pin the player's position while driving. The negative half is what pins the boundary
        // rather than the behaviour.
    }

    @Test
    void noPathStopsWithoutRetrying() {
        // Against EmptyWorld, which answers UNKNOWN everywhere. NO_PATH is the only definitive
        // "stop retrying" signal the search produces (SegmentedResult.outcome():34-42), so a
        // retry here would re-search a goal already proven impossible.
    }

    @Test
    void theRepathBudgetTerminates() {
    }

    @Test
    void theRepathCounterResetsWhenTheAnchorAdvances() {
        // Otherwise a long route that repaths five times while making real progress is killed by
        // a budget meant for thrash.
    }

    @Test
    void stopCancelsAPendingRunByReference() {
        // A pending Run holds a WorldSnapshot wrapping a live BlockSource and so pins a level.
        // Asserted through Run.cancelled() rather than through behaviour, per C5's mutation 7.
    }

    @Test
    void theSegmentedResultIsNotRetainedAfterAdoption() {
        // expanded() accumulates across every segment -- roughly cap x nodeBudget entries. Held
        // for one search that is a C5 residual; held for the duration of a walk it would be
        // strictly worse than today. Assert the executor holds no reference to the result.
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathExecutorSearchTest'`
Expected: FAIL — `walkTo` does not exist.

- [ ] **Step 3: Add the constants**

In `PathExecutor`:

```java
    /**
     * How few steps may remain before the next segment is searched, while still walking.
     *
     * <p>Twenty. A search costs roughly thirteen slices, and at one slice per tick that is thirteen
     * ticks; a step of walking costs about 4.6 ticks at vanilla speed, so three remaining steps
     * would already cover a search. Twenty is margin — about 4.6 s, close to the 7.5 s Baritone
     * uses for the same purpose.
     *
     * <p><b>Extrapolated.</b> It also assumes this executor's searches cost roughly what the
     * probe's do, and they should cost less: the probe grants {@code PARKOUR} and this does not, so
     * its branching factor is lower. That makes twenty conservative in the safe direction, by an
     * unmeasured amount.
     */
    static final int PLAN_AHEAD_STEPS = 20;

    /**
     * How many consecutive driving ticks the anchor may fail to advance before repathing.
     *
     * <p>Forty — two seconds. Measuring path progress rather than position is what makes this
     * immune both to a player shuffling against a block boundary and to a legitimately slow tick,
     * and it is why the executor needs no velocity. <b>Extrapolated.</b>
     */
    static final int STUCK_TICKS = 40;

    /**
     * How many repaths may fail to advance the anchor before the executor gives up.
     *
     * <p>Three. The counter resets on any advance, so a long route that repaths repeatedly while
     * making real progress is not killed by a budget meant for thrash. <b>Extrapolated.</b>
     */
    static final int MAX_CONSECUTIVE_REPATHS = 3;
```

- [ ] **Step 4: Add the search state and `walkTo`**

Add fields: the `SegmentedSearch` (built once in the constructor, because
`AStarPathfinder`'s constructor runs `MovementRegistry.discover()` — an uncached `ServiceLoader`
classpath scan measured at 5.9 ms cold), the goal, the pending `Run`, its snapshot, a flag for
whether the pending run is a plan-ahead or a repath, the stuck counter, the last anchor, and the
consecutive-repath counter.

```java
    public void walkTo(int x, int y, int z) {
        goal = new Pos(x, y, z);
        consecutiveRepaths = 0;
        cancelPending();
        follower = null;
        beginSearch(startFromPlayerAt(x, y, z), false);
    }
```

Search granting is `CapabilitySet.none()` — decision 7. Do not grant `PARKOUR`.

- [ ] **Step 5: Extend `tick` with the search half**

The order inside `tick` is: spend a slice on any pending run and adopt it if it finished; then, if
there is nothing to drive, return **without writing anything**; then re-anchor, arrive, plan-ahead,
and drive. Adoption appends for a plan-ahead and replaces for a repath. Update the stuck counter
from whether the anchor advanced, and reset the repath counter on the same signal.

Replace Task 7's `offPath` body with: release every input once, cancel any pending plan-ahead, drop
the follower, and begin a repath from the player.

**The executor must copy `path()` and `steps()` out of the `SegmentedResult` and drop the result.**
Retaining it would hold `expanded()` — bounded by roughly `cap × nodeBudget` entries — for the
duration of a walk rather than of a search.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `./gradlew :core-engine:test --tests 'dev.continuo.engine.PathExecutorSearchTest'`
Expected: PASS.

- [ ] **Step 7: Prove the overlap test can fail**

Temporarily change the plan-ahead branch so it drops the follower before starting the search — the
sequential implementation decision 2 rejected.

Expected: **FAIL** on `planAheadStartsBeforeThePathRunsOutAndInputsKeepBeingWritten`, specifically on
the inputs-still-written clause. **If it fails only on the search-in-flight clause, the second
assertion is not doing its job** and decision 2 is unenforced — fix the test before proceeding.

**Revert and re-run to confirm green.** Record the observed failure in the commit body.

- [ ] **Step 8: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add core-engine/src/main/java/dev/continuo/engine/PathExecutor.java core-engine/src/test/java/dev/continuo/engine/PathExecutorSearchTest.java
git commit -m "feat(d2): search and walk at the same time

Plan-ahead and repath are deliberately different mechanisms, because only one of
them can be overlapped with movement. Plan-ahead searches from the path's end
when twenty steps remain and appends, preserving the anchor, so the player does
not break stride -- that is C5 section 10's finding, in step units. A repath
searches from the player and replaces the path whole, and stands still while it
computes, because continuing to walk a path the executor has just concluded it is
not on would be driving toward a node chosen by no current evidence. Overlap
hides the first and not the second, and this says so rather than implying
otherwise.

NO_PATH stops without retrying: it is the only definitive stop-retrying signal
the search produces. The repath budget bounds thrash and its counter resets on
any anchor advance, so a long route making real progress is not killed by it.

The executor copies the path and steps out of the result and drops the
SegmentedResult, so expanded()'s unbounded accumulation is held for one search
rather than for a whole walk."
```

---

### Task 9: Wiring, and the walk key

**Files:**
- Modify: `core-engine/src/main/java/dev/continuo/engine/ContinuoCore.java`
- Modify: `core-engine/src/test/java/dev/continuo/engine/ContinuoCoreTest.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` (expose the marked goal)
- Modify: `docs/smoke-checklist-a1.md`, `docs/smoke-checklist-a2.md`

**Interfaces:**
- Consumes: `PathExecutor` (Tasks 7–8).
- Produces: `ContinuoCore.walkTo(int, int, int)`; `PathProbe.goal()` → `Pos` or `null`.

- [ ] **Step 1: Delete the walk demo and drive the executor**

In `ContinuoCore`: delete `WALK_TICKS`, `requestWalk`, the `walking` and `tick` fields, and the walk
branch of `onClientTick`. Build a `PathExecutor` in `start` — after `blocks` exists, since the
executor takes it as its `BlockSource` — and make `onClientTick` this:

```java
    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE) {
            return;
        }
        executor.tick(context.player());
    }
```

`stop()` delegates to `executor.stop()` in place of its `FORWARD` release, and keeps
`blocks.clear()`.

Add:

```java
    /**
     * Walks to a block position, replacing any walk in progress.
     *
     * @param x the destination's X
     * @param y the destination's Y — the block the feet will occupy, per {@code IPlayerView.y()}
     * @param z the destination's Z
     * @throws IllegalStateException if {@code start} has not been called
     */
    public void walkTo(int x, int y, int z) {
        if (executor == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        executor.walkTo(x, y, z);
    }
```

Delete every `ContinuoCoreTest` case about the 40-tick walk and replace them with cases asserting
that a tick with no walk requested writes nothing, and that `stop` before `start` still throws.

- [ ] **Step 2: Expose the probe's marked goal**

In `PathProbe`, add:

```java
    /**
     * The marked goal, for a caller that wants to walk there rather than search to it.
     *
     * @return the mark, or {@code null} if none is set or a level change discarded it
     */
    public Pos goal() {
        return goal;
    }
```

- [ ] **Step 3: Rewire the walk key in both adapters**

In each adapter, the walk key's `onClick` currently logs and calls `core.requestWalk()`. Replace with
a read of the probe's goal:

```java
    Pos target = probe.goal();
    if (target == null) {
        LOGGER.info("Continuo: no goal marked -- stand on the destination and press the mark key");
    } else {
        LOGGER.info("Continuo: walking to {}", target);
        core.walkTo(target.x(), target.y(), target.z());
    }
```

Use each adapter's existing logging idiom; the Forge adapter is Java 8 and has no lambdas.

**Do not move either key poll.** `PathProbe.advance` must still be called before any path that may
call `start`, and `consumeClick`/`isPressed` drain a queued press as a side effect. No test in this
repository can catch a re-ordering.

- [ ] **Step 4: Update both smoke checklists**

In `docs/smoke-checklist-a1.md` and `docs/smoke-checklist-a2.md`, replace every step describing "the
40-tick walk travels ≈8 blocks" with the executor's behaviour: mark a goal with the mark key, press
the walk key, and the player walks there and stops on it. **Neither checklist may tell the runner to
run `./gradlew clean`** — check this while editing, since that habit is what the D1 session fixed.

- [ ] **Step 5: Verify the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Record the total test count from this full unfiltered run, summed across
every `TEST-*.xml`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(d2): the walk key walks to the marked goal

K stops being 40 unconditional presses of FORWARD and becomes the real thing:
mark a destination with H, press K, and the executor drives the player there.
The 40-tick demo was rule 4's only obeying consumer and is replaced by a
stronger one, so it goes, along with the two smoke checklist steps that
described it.

The adapters read the probe's mark and hand coordinates to the core. Neither key
poll moved: advance must still run before any path that may call start, and the
click consumers drain a queued press as a side effect, which nothing in this
repository can test."
```

---

## In-game verification

Not a task — it needs a human at a client, and it discharges spec §11's criteria 4 through 10. Run
on **both** versions and record the results in the spec as a new §15, following D1's precedent.

1. Mark a goal with H, press K: the player walks there and stops on it. Over flat ground, off a
   drop, and **up a staircase of at least four consecutive ascends** — four because §3.9's cooldown
   is ten ticks and a single ascend cannot expose a throttle.
2. Open the inventory mid-walk: the player must not stop. This is rule 4's regression check,
   re-pointed at the executor now the 40-tick walk is gone.
3. `/tp` mid-walk: the bot repaths and continues. **This is the criterion that discharges dissolved
   `onPositionCorrection`.** If the executor needs to be *told* it was moved, that is the finding
   that reopens the question as "add a mixin and a coremod".
4. Turning is visibly gradual rather than instant.
5. No standing-invariant notice from the probe on the same terrain.
6. **Every one of the seven extrapolated constants observed, and its javadoc rewritten with the
   measured figure.** A constant still stating a prediction after this run is an unmet done
   criterion, not a tidy-up.
7. **The mutation, executed rather than reasoned about:** raise `PathFollower.W` past a real spiral
   staircase's revolution in a live client and confirm the executor skips the loop. Then **revert it
   and check `git status`** — a subagent that finishes a mutation test has left one in the working
   tree before.

---

## Self-review

**Spec coverage.** §4.1–4.3 → Tasks 1, 4. §4.4 → Tasks 9. §5.1 → Task 2. §5.2 → Task 3. §5.3 →
Task 3. §6.1 → Task 9. §6.2 → Tasks 6, 8. §6.3 → Task 7. §6.4 → Tasks 7, 8. §7.1–7.4 → Task 8.
§8.1–8.3 → Task 5. §9 → Tasks 5, 6, 8. §10 → every task's tests. §11 criteria 1–3 → Tasks 3, 6, 9;
criteria 4–10 → the in-game section.

**Known gap, stated rather than hidden.** Task 8's test bodies are specified by comment and
signature rather than written out, because each needs a fixture world whose exact contents depend on
the search succeeding over it — the same reason Task 3 step 10 tells the implementer to iterate
until the coverage assertion passes rather than handing over a fixture that was never run. **Every
one of those tests names the property it must pin and, where it has one, the way it can silently
pass**; an implementer who writes a weaker test than the comment describes has not done the task.

**Type consistency.** `PathExecutor.follow(List<Pos>, List<Step>)` is package-private in Task 7 and
is the same entry Task 8's adoption uses. `PathResults.stepsOf` (Task 6) is the only public route to
step derivation and is used by Tasks 6, 7 and 8. `Run.SLICE_NODES` (Task 1) is consumed in Task 8.
`RuntimeLog` is `dev.continuo.core.RuntimeLog` from Task 4 onward, in Tasks 7 and 8.
