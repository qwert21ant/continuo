# C4 Segmentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A budget-exhausted search returns the reached node closest to the goal as a `PARTIAL`
path, a driver chains those segments into a run, and the probe reports both the segments and — for
the first time in this project — milliseconds.

**Architecture:** One package-private collaborator (`SegmentSelector`) tracks the lowest-`h` node
that beats the segment's start by `minProgress`; `AStarPathfinder` calls it once per expansion and
returns `PARTIAL` at the budget branch instead of nothing. `SegmentedSearch` re-searches from each
segment's end until `FOUND`, `NO_PATH`, `BUDGET_EXCEEDED` or a cap. `PathProbe` drives it, so **no
adapter file is touched**.

**Tech Stack:** Java 8 bytecode, JUnit 5, Gradle. Modules `:core-pathfinder` and `:runtime`.

**Spec:** `docs/superpowers/specs/2026-08-26-c4-segmentation-design.md` — read §2.1, §3 and §7
before Task 2. §2.1 records that this design's first version was measured and reversed; the rule
you are implementing is the *simple* one.

## Global Constraints

- **Gate on `./gradlew build`, never `:test`.** Javadoc is build-failing under
  `-Xdoclint:all,-missing -Xwerror`, and **a dead `{@link}` fails as hard as a missing symbol**.
  `:test` never runs javadoc, so a green `:test` can hide a broken build.
- **Java 8 bytecode, machine-checked.** No `var`, no records, no `List.of`, no text blocks, no
  switch expressions, no diamond-free generics shortcuts. **No lambdas in main source.**
- Tests use anonymous `Executable` classes for `assertThrows`. That is house style, not a defect.
- **`GRADLE_USER_HOME` is already `C:\GradleHome`.** Never set, export or override it.
- **Never run `./gradlew clean`** — it destroys the 1.7.10 decompiled sources. Use
  `build --rerun-tasks`.
- **Filtered Gradle runs corrupt the XML test counts.** Count tests only after a full
  `build --rerun-tasks`.
- Files are **CRLF**. Multi-line `sed`/`perl` patterns written with `\n` silently match nothing.
- No new module and no new dependency. If one seems needed, stop and report.
- **Report discrepancies, do not accommodate them.** Where this plan predicts a number it says so
  explicitly. If what you observe differs, write down what you saw and say so — three briefs were
  wrong in C3 and all three were caught this way.

---

## File Structure

| File | Responsibility |
|---|---|
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java` | **Modify.** Add `PARTIAL`; correct `BUDGET_EXCEEDED`'s javadoc, which currently promises no partial path |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentSelector.java` | **Create.** The rule, as pure arithmetic. No world, no search |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java` | **Modify.** One call per expansion; `PARTIAL` at the budget branch; `minProgressBlocks` |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedResult.java` | **Create.** What a run produced: outcome, concatenated path, cost, segment count |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java` | **Create.** The loop |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/TerrainFixture.java` | **Create.** Loads `/terrain/*.txt` off the test classpath |
| `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` | **Modify.** Drive `SegmentedSearch`; report segments and milliseconds |

Test files are named per task. `FixtureWorld`, `SegmentSelector` and `TerrainFixture` are all
package-private in `dev.continuo.pathfinder`, so every test lives in that package.

---

### Task 1: Wall-clock instrumentation

**Why first:** §1.2 defers cross-tick search on the grounds that no millisecond figure exists, and
§6 makes the default budget depend on one. Nothing else in C4 can be sized until this runs. It is
also fully independent of every other task.

**Files:**
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java:149-163`
- Test: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the probe summary gains `, N.N ms` before the `snapshot ...` clause. Task 5 extends the
  same line.

- [ ] **Step 1: Write the failing test**

Add to `PathProbeTest`:

```java
    @Test
    void theSummaryReportsHowLongTheSearchTook() {
        PathProbe probe = new PathProbe(1000);
        probe.markGoal(3, 65, 0);
        ProbeReport report = probe.run(new ProbeWorld(), 0, 65, 0);

        String summary = report.summary();
        Matcher m = Pattern.compile(", ([0-9]+\\.[0-9]) ms").matcher(summary);
        assertTrue(m.find(), "no millisecond figure in: " + summary);
        // Parsed as a number rather than matched as a substring. C3's review found that the
        // probe's two integers could be transposed with zero test failures because every
        // assertion matched substrings; this is the same class of defect, pre-empted.
        double ms = Double.parseDouble(m.group(1));
        assertTrue(ms >= 0.0, "negative elapsed time: " + ms);
    }
```

Add the imports `java.util.regex.Matcher`, `java.util.regex.Pattern` and, if absent,
`static org.junit.jupiter.api.Assertions.assertTrue`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :runtime:test --tests 'dev.continuo.runtime.PathProbeTest'
```

Expected: FAIL — `no millisecond figure in: Continuo path probe: FOUND, ...`

- [ ] **Step 3: Time the search**

In `PathProbe.run`, wrap only the search — not the render, not the snapshot seal:

```java
        WorldSnapshot snapshot = new WorldSnapshot(world);
        long startedAt = System.nanoTime();
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            snapshot, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
        double elapsedMs = (System.nanoTime() - startedAt) / 1000000.0;
        SealedSnapshot sealed = snapshot.seal();
```

- [ ] **Step 4: Report it**

In the summary builder, after the `, budget N` clause and before `, snapshot`:

```java
            .append(", ").append(String.format(java.util.Locale.ROOT, "%.1f", 
                Double.valueOf(elapsedMs))).append(" ms")
```

`Locale.ROOT` for the same reason the existing repeat-factor format uses it: this line reaches a
log file read on other machines, and a default locale writes `3,8 ms` where the reader expects
`3.8 ms`.

- [ ] **Step 5: Document why this is not a stopping condition**

Add to `PathProbe`'s class javadoc, after the snapshot paragraph:

```java
 * <p><b>The elapsed time is reported and never consulted.</b> C1 section 5.1 makes determinism a
 * hard requirement -- tests assert which path comes back -- and a wall-clock stopping condition
 * would make every one of those assertions flaky. The budget stays counted in nodes. This figure
 * exists to size that budget and to settle whether a search can span a tick, which is C4's
 * deferred question.
```

- [ ] **Step 6: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. If javadoc fails, you have a dead `{@link}` — the text above
deliberately uses none.

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "feat(c4): the probe reports how long a search took

First nanoTime in the project. Reported, never consulted -- the budget stays
counted in nodes because C1 section 5.1 makes determinism a hard requirement.
Parsed as a number in the test rather than matched as a substring, which is the
defect class C3's review found in this same summary line."
```

---

### Task 2: `PathOutcome.PARTIAL` and `SegmentSelector`

**Files:**
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentSelector.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/SegmentSelectorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces, both used by Task 3:
  - `PathOutcome.PARTIAL`
  - `SegmentSelector(double startH, double minProgress)`; `void consider(long packed, double h)`;
    `boolean hasCandidate()`; `long candidate()`.

- [ ] **Step 1: Write the failing test**

Create `SegmentSelectorTest.java`:

```java
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
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SegmentSelectorTest'
```

Expected: FAIL to compile — `cannot find symbol: class SegmentSelector`.

- [ ] **Step 3: Write `SegmentSelector`**

```java
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
```

Note `!(minProgress > 0.0)` rather than `minProgress <= 0.0`: the former also rejects `NaN`.

- [ ] **Step 4: Add `PARTIAL` and correct the stale javadoc**

In `PathOutcome.java`, insert after `FOUND` and rewrite `BUDGET_EXCEEDED`'s comment, which
currently promises the opposite of what now happens:

```java
    /**
     * A real path, to somewhere that is not the goal.
     *
     * <p>The node budget ran out, and the search had reached somewhere meaningfully closer to the
     * goal than where it started. That prefix is returned as a segment: walk it, search again from
     * its end, repeat. {@link PathResult#cost()} is the segment's own cost, not an estimate of the
     * whole route.
     */
    PARTIAL,

    /**
     * The node budget ran out and nothing was salvageable.
     *
     * <p>Distinct from {@link #NO_PATH} because a path may well exist — the search simply did not
     * get to it. Distinct from {@link #PARTIAL} because no expanded node was closer to the goal
     * than the start by a useful margin, so there is nothing worth walking to.
     */
    BUDGET_EXCEEDED
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SegmentSelectorTest'
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. `AStarPathfinder`'s class javadoc still says a budget hit yields "no
path at all" — that is corrected in Task 3, and it is prose rather than a `{@link}`, so it does not
break the build now.

- [ ] **Step 7: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentSelector.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/SegmentSelectorTest.java
git commit -m "feat(c4): the backoff rule, as pure arithmetic

Lowest h among nodes beating the segment start by minProgress. No cost term --
spec section 2.1 measured every finite C failing to reach the goal, because
penalising g prefers nodes near the start and produces segments too timid to
commit. Tested with no world at all."
```

---

### Task 3: A\* returns `PARTIAL`

**Files:**
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/TerrainFixture.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/BackoffTest.java`

**Interfaces:**
- Consumes: `SegmentSelector`, `PathOutcome.PARTIAL` from Task 2.
- Produces, used by Task 4:
  - `AStarPathfinder.DEFAULT_MIN_PROGRESS_BLOCKS` (a `double`)
  - `AStarPathfinder(int nodeBudget, IMovementRegistry registry, double minProgressBlocks)`
  - package-private `IMovementRegistry registry()` and `double minProgressBlocks()`
  - `TerrainFixture.load(String name)` returning `FixtureWorld`

- [ ] **Step 1: Write the fixture loader**

Create `TerrainFixture.java`. The dumps are committed verbatim under
`core-pathfinder/src/test/resources/terrain/`, so they are on the test classpath.

```java
package dev.continuo.pathfinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the committed real-terrain probe dumps.
 *
 * <p>A dump needs no conversion: the probe emits exactly {@link FixtureWorld}'s format, and
 * {@code //} lines are skipped by the parser. The {@code *} and {@code +} overlays parse back as
 * air, which is correct, because they mark feet positions that were air.
 *
 * <p><b>Two of these fixtures carry caveats recorded in the design, section 7.1.</b>
 * {@code a-big-obstacle} does not reproduce its captured in-game route, because 397 unnamed cells
 * sit near the optimal one; it is a valid world but never evidence about a real run.
 * {@code e-long-range} is clamped, so its goal lies outside the map and {@code goal()} is null --
 * supply the goal by hand from the clamp notice inside the file.
 */
final class TerrainFixture {

    private TerrainFixture() {
    }

    static FixtureWorld load(String name) {
        InputStream in = TerrainFixture.class.getResourceAsStream("/terrain/" + name);
        if (in == null) {
            throw new IllegalArgumentException("no such terrain fixture on the classpath: " + name);
        }
        try {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                while (n > 0) {
                    out.write(buf, 0, n);
                    n = in.read(buf);
                }
                return FixtureWorld.parse(new String(out.toByteArray(), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read terrain fixture " + name, e);
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `BackoffTest.java`. Read the comments on the two predicted numbers before running.

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a budget-exhausted search returns.
 *
 * <p>These run on committed real terrain rather than hand-drawn worlds, because the design's
 * synthetic traps were built to pin predictions that section 2.1 then measured as false. Real
 * terrain is what disproved them and is what pins the rule now.
 */
class BackoffTest {

    /** A world with no route out, so nothing can ever beat the start's h. */
    private static final String BOXED = "origin: 0,64,0\n"
        + "--- y=64\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "--- y=65\n"
        + "#####\n"
        + "#S..#\n"
        + "#...#\n"
        + "#...#\n"
        + "#####\n"
        + "--- y=66\n"
        + "#####\n"
        + "#...#\n"
        + "#...#\n"
        + "#...#\n"
        + "#####\n"
        + "--- y=67\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n"
        + "#####\n";

    private static PathResult search(FixtureWorld world, Goal goal, int budget) {
        Pos s = world.start();
        return new AStarPathfinder(budget).findPath(world, s.x(), s.y(), s.z(), goal);
    }

    @Test
    void aBudgetHitWithSomewhereWorthGoingReturnsPartial() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos g = world.goal();
        // 232 is 84% of the 273 expansions this fixture's search needs. Section 2.1 measured the
        // whole run reaching the goal in two segments at this budget, so the FIRST search must be
        // the partial one.
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(PathOutcome.PARTIAL, r.outcome());
        assertTrue(r.path().size() > 1, "a partial path must go somewhere: " + r.path().size());
        assertTrue(r.cost() > 0.0, "a partial path has a real cost, got " + r.cost());
    }

    @Test
    void thePartialPathStartsAtTheStartAndDoesNotReachTheGoal() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos s = world.start();
        Pos g = world.goal();
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(s, r.path().get(0));
        Pos end = r.path().get(r.path().size() - 1);
        assertTrue(!end.equals(g), "a PARTIAL must not end on the goal, ended at " + end);
    }

    @Test
    void aBudgetHitWithNowhereWorthGoingStillReturnsNothing() {
        FixtureWorld world = FixtureWorld.parse(BOXED);
        Pos s = world.start();
        // The goal is outside the box, so nothing reachable improves h by minProgress. The budget
        // is larger than the box, so the open set empties first -- which is NO_PATH, not
        // BUDGET_EXCEEDED. Budget 3 forces the budget branch instead.
        PathResult r = new AStarPathfinder(3)
            .findPath(world, s.x(), s.y(), s.z(), new GoalBlock(100, 65, 100));

        assertEquals(PathOutcome.BUDGET_EXCEEDED, r.outcome());
        assertTrue(r.path().isEmpty(), "BUDGET_EXCEEDED still means no path at all");
        assertEquals(0.0, r.cost(), 0.0);
    }

    @Test
    void openSetExhaustionNeverBacksOff() {
        // D5, on real terrain. e-long-range is clamped, so its goal is outside the map and must be
        // retyped from the clamp notice in the file. The search advances a long way -- h at the
        // start is about 509 -- and still returns NO_PATH when the open set empties.
        FixtureWorld world = TerrainFixture.load("e-long-range.txt");
        Pos s = world.start();
        assertNotNull(s, "e-long-range must still carry its S marker");

        PathResult r = new AStarPathfinder(100000)
            .findPath(world, s.x(), s.y(), s.z(), new GoalBlock(1737, 72, -786));

        assertEquals(PathOutcome.NO_PATH, r.outcome(),
            "proving a goal unreachable is the one thing a search can say definitively;"
                + " backing off would destroy it");
        assertTrue(r.path().isEmpty());
    }

    @Test
    void aReachableGoalIsUnaffected() {
        FixtureWorld world = TerrainFixture.load("c-short-hop.txt");
        Pos g = world.goal();
        PathResult r = search(world, new GoalBlock(g.x(), g.y(), g.z()), 10000);

        assertEquals(PathOutcome.FOUND, r.outcome());
        assertEquals(73.08618290174553, r.cost(), 0.0);
    }

    @Test
    void theSameSearchTwiceReturnsTheSameSegment() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos g = world.goal();
        PathResult a = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);
        PathResult b = search(world, new GoalBlock(g.x(), g.y(), g.z()), 232);

        assertEquals(a.outcome(), b.outcome());
        assertEquals(a.path(), b.path());
        assertEquals(a.cost(), b.cost(), 0.0);
    }
}
```

- [ ] **Step 3: Run and watch them fail**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.BackoffTest'
```

Expected: the three `PARTIAL` tests fail with `expected: PARTIAL but was: BUDGET_EXCEEDED`. The
other three should already pass — they pin behaviour that must not change.

**If `aReachableGoalIsUnaffected` fails, stop and report.** It asserts a cost measured in game and
verified to replay exactly; a failure there means something about the fixtures or the search has
moved, not that the assertion is wrong.

- [ ] **Step 4: Add the constant and the constructor**

In `AStarPathfinder`, after `DEFAULT_NODE_BUDGET`:

```java
    /**
     * How much closer to the goal a backoff candidate must be, in blocks, when none is given.
     *
     * <p>In blocks rather than ticks because blocks are the unit a person reasons in; it is
     * multiplied by {@code HeuristicRates.horizontal()} at search time, so it stays meaningful
     * when a changed movement set changes the cheapest rate.
     *
     * <p>PROVISIONAL until Task 6's sweep replaces it. Any positive value is correct for
     * termination — the design's proof holds for all of them — so this affects how often a segment
     * is offered, never whether a run ends.
     */
    public static final double DEFAULT_MIN_PROGRESS_BLOCKS = 4.0;

    private final double minProgressBlocks;
```

Have the existing two-argument constructor delegate, and add:

```java
    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @param registry the movements this pathfinder may use; never {@code null}
     * @param minProgressBlocks how much closer a backoff candidate must be; must be positive
     * @throws IllegalArgumentException if the budget or the margin is not positive, or the
     *         registry is null
     */
    public AStarPathfinder(int nodeBudget, IMovementRegistry registry, double minProgressBlocks) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (!(minProgressBlocks > 0.0)) {
            throw new IllegalArgumentException(
                "minProgressBlocks must be positive, got " + minProgressBlocks);
        }
        this.nodeBudget = nodeBudget;
        this.registry = registry;
        this.minProgressBlocks = minProgressBlocks;
    }

    /** @return the movements this pathfinder uses; for {@link SegmentedSearch} */
    IMovementRegistry registry() {
        return registry;
    }

    /** @return the backoff margin in blocks; for {@link SegmentedSearch} */
    double minProgressBlocks() {
        return minProgressBlocks;
    }
```

- [ ] **Step 5: Wire the selector into the search**

After `final List<IMovementType> moves = active.movements();`:

```java
        final double hStart = goal.heuristic(startX, startY, startZ, rates);
        final SegmentSelector selector =
            new SegmentSelector(hStart, minProgressBlocks * rates.horizontal());
```

Inside the expansion block, immediately after `expanded.add(new Pos(cx, cy, cz));`:

```java
            // h is recomputed rather than taken as entry.f - entry.g. The subtraction is exact,
            // but only by an argument about stale heap entries, and this project's reviews exist
            // to catch invariants that subtle. The saving is arithmetic that reads no world.
            selector.consider(current.packed, goal.heuristic(cx, cy, cz, rates));
```

Place it **after** the `expanded.add`, so a node counted as expanded is also a node offered.

Then replace the budget branch:

```java
            if (expanded.size() >= nodeBudget) {
                if (selector.hasCandidate()) {
                    PathNode best = nodes.get(Long.valueOf(selector.candidate()));
                    return new PathResult(PathOutcome.PARTIAL,
                        reconstruct(best), expanded, best.g);
                }
                return new PathResult(PathOutcome.BUDGET_EXCEEDED,
                    Collections.<Pos>emptyList(), expanded, 0.0);
            }
```

- [ ] **Step 6: Correct the class javadoc**

The existing paragraph promises the opposite of the new behaviour. Replace it:

```java
 * <p><b>The node budget is a stopping condition with a fallback.</b> Exhausting it yields
 * {@link PathOutcome#PARTIAL} and the path to the reached node closest to the goal, provided one
 * beat the start by {@code minProgressBlocks}; otherwise {@link PathOutcome#BUDGET_EXCEEDED} and
 * no path. Chaining those segments into a run is {@link SegmentedSearch}.
```

- [ ] **Step 7: Run the tests**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.BackoffTest'
```

Expected: PASS, 6 tests.

- [ ] **Step 8: Run the whole build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, with **every pre-existing test still passing and unedited**. §4 of the
design requires existing test files to change by addition only. **If an existing assertion now
fails, stop and report it rather than editing it** — it means `FOUND` or `NO_PATH` behaviour moved,
which this task must not do.

- [ ] **Step 9: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/TerrainFixture.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/BackoffTest.java
git commit -m "feat(c4): a budget-exhausted search returns its best segment

One call per expansion and a different return at the budget branch; the search
itself is untouched. FOUND and NO_PATH keep their exact meaning, so every
existing test file changes by addition only.

Tested on committed real terrain rather than hand-drawn worlds, because the
synthetic traps this plan first specified were built to pin predictions that
spec section 2.1 then measured as false."
```

---

### Task 4: `SegmentedSearch`

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedResult.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/SegmentedSearchTest.java`

**Interfaces:**
- Consumes: `AStarPathfinder.registry()`, `minProgressBlocks()`, `PathOutcome.PARTIAL`,
  `TerrainFixture.load` from Task 3.
- Produces, used by Task 5:
  - `SegmentedSearch(AStarPathfinder pathfinder)`
  - `SegmentedResult run(BlockSource world, int startX, int startY, int startZ, Goal goal,
    CapabilitySet caps)`
  - `SegmentedResult`: `outcome()`, `path()`, `expanded()`, `cost()`, `segments()`

- [ ] **Step 1: Write the failing test**

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chaining segments into a run. */
class SegmentedSearchTest {

    private static SegmentedResult run(String fixture, int budget) {
        FixtureWorld world = TerrainFixture.load(fixture);
        Pos s = world.start();
        Pos g = world.goal();
        return new SegmentedSearch(new AStarPathfinder(budget))
            .run(world, s.x(), s.y(), s.z(), new GoalBlock(g.x(), g.y(), g.z()),
                CapabilitySet.none());
    }

    @Test
    void aRunReachesAGoalOneSearchCannotAtTheSameBudget() {
        // 232 is 84% of d-cliff's 273. Section 2.1 measured this reaching the goal in two
        // segments. PREDICTED: segments() == 2 and cost() == 274.42..., which is the unsegmented
        // optimum exactly -- a ratio of 1.000. Report what you actually see.
        SegmentedResult r = run("d-cliff.txt", 232);

        assertEquals(PathOutcome.FOUND, r.outcome());
        assertTrue(r.segments() > 1, "a single search at this budget cannot reach the goal");
    }

    @Test
    void theConcatenatedPathIsContiguous() {
        SegmentedResult r = run("d-cliff.txt", 232);
        assertEquals(PathOutcome.FOUND, r.outcome());

        for (int i = 1; i < r.path().size(); i++) {
            Pos a = r.path().get(i - 1);
            Pos b = r.path().get(i);
            int dx = Math.abs(a.x() - b.x());
            int dz = Math.abs(a.z() - b.z());
            assertTrue(dx <= 1 && dz <= 1 && !a.equals(b),
                "segments must join without a gap or a repeat at index " + i
                    + ": " + a + " then " + b);
        }
    }

    @Test
    void theRunStartsWhereItWasAskedTo() {
        FixtureWorld world = TerrainFixture.load("d-cliff.txt");
        Pos s = world.start();
        SegmentedResult r = run("d-cliff.txt", 232);
        assertEquals(s, r.path().get(0));
    }

    @Test
    void aSingleSearchThatSucceedsIsOneSegment() {
        // 400 is above d-cliff's 273, so the first search finds the goal outright.
        SegmentedResult r = run("d-cliff.txt", 400);
        assertEquals(PathOutcome.FOUND, r.outcome());
        assertEquals(1, r.segments());
        assertEquals(274.4170743526183, r.cost(), 1e-9);
    }

    @Test
    void aRunThatCannotProceedFailsSafeRatherThanLooping() {
        // 498 is 39% of a-big-obstacle's 1,247. Section 2.1 measured every rule failing here; the
        // required behaviour is that it STOPS, with no path, rather than ping-ponging.
        SegmentedResult r = run("a-big-obstacle.txt", 498);

        assertTrue(r.outcome() != PathOutcome.FOUND,
            "this budget is too small to reach the goal; a FOUND here would be a surprise worth"
                + " reporting rather than accepting");
        assertTrue(r.segments() < 20,
            "the run must terminate quickly, not ping-pong; saw " + r.segments() + " segments");
    }

    @Test
    void anUnreachableGoalEndsTheRunWithoutSegmenting() {
        FixtureWorld world = TerrainFixture.load("e-long-range.txt");
        Pos s = world.start();
        SegmentedResult r = new SegmentedSearch(new AStarPathfinder(100000))
            .run(world, s.x(), s.y(), s.z(), new GoalBlock(1737, 72, -786),
                CapabilitySet.none());

        assertEquals(PathOutcome.NO_PATH, r.outcome());
        assertEquals(1, r.segments());
        assertTrue(r.path().isEmpty());
    }
}
```

- [ ] **Step 2: Run and watch it fail**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SegmentedSearchTest'
```

Expected: FAIL to compile — `cannot find symbol: class SegmentedSearch`.

- [ ] **Step 3: Write `SegmentedResult`**

```java
package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a segmented run produced: how it ended, the whole route, and how many searches it took. */
public final class SegmentedResult {

    private final PathOutcome outcome;
    private final List<Pos> path;
    private final List<Pos> expanded;
    private final double cost;
    private final int segments;

    SegmentedResult(PathOutcome outcome, List<Pos> path, List<Pos> expanded,
                    double cost, int segments) {
        this.outcome = outcome;
        this.path = Collections.unmodifiableList(new ArrayList<Pos>(path));
        this.expanded = Collections.unmodifiableList(new ArrayList<Pos>(expanded));
        this.cost = cost;
        this.segments = segments;
    }

    /** @return how the run ended: {@code FOUND}, {@code NO_PATH} or {@code BUDGET_EXCEEDED} */
    public PathOutcome outcome() {
        return outcome;
    }

    /**
     * @return every segment joined end to end, start to wherever the run stopped, unmodifiable;
     *         empty when the first search produced nothing
     */
    public List<Pos> path() {
        return path;
    }

    /** @return every node expanded across every segment, in order, unmodifiable */
    public List<Pos> expanded() {
        return expanded;
    }

    /** @return the whole route's cost in ticks */
    public double cost() {
        return cost;
    }

    /** @return how many searches ran; 1 when the first one settled it */
    public int segments() {
        return segments;
    }

    @Override
    public String toString() {
        return "SegmentedResult[" + outcome + ", " + segments + " segments, "
            + path.size() + " steps, cost " + cost + "]";
    }
}
```

`PARTIAL` is deliberately absent from `outcome()`'s list: a run either arrives, proves it cannot,
or exhausts itself. A segment is an internal step.

- [ ] **Step 4: Write `SegmentedSearch`**

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs a search, walks the segment it returns, and searches again from the segment's end, until
 * the goal is reached or the run cannot continue.
 *
 * <p><b>Segmentation is a safety net, not the way to reach a distant goal.</b> Measured across
 * real terrain, backoff reaches the goal only when the budget is a large fraction of what the
 * search needs, and fails below roughly 70% of it. The primary answer to a far goal is a budget
 * big enough for it; this keeps the bot moving usefully when even that is exceeded. Section 2.1
 * of the design has the measurements.
 *
 * <p><b>Single-tick.</b> Every segment reads the same {@code BlockSource}, so nothing here holds a
 * world across ticks and none of C3's snapshot-lifetime questions apply.
 */
public final class SegmentedSearch {

    private final AStarPathfinder pathfinder;

    /**
     * @param pathfinder the search each segment uses; never {@code null}
     * @throws IllegalArgumentException if it is null
     */
    public SegmentedSearch(AStarPathfinder pathfinder) {
        if (pathfinder == null) {
            throw new IllegalArgumentException("pathfinder must not be null");
        }
        this.pathfinder = pathfinder;
    }

    /**
     * @param world the world to read; never {@code null}
     * @param startX where the run begins
     * @param startY where the run begins
     * @param startZ where the run begins
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return the run's result; never {@code null}
     */
    public SegmentedResult run(BlockSource world, int startX, int startY, int startZ,
                               Goal goal, CapabilitySet caps) {
        HeuristicRates rates = pathfinder.registry().activeFor(caps).rates();
        double minProgress = pathfinder.minProgressBlocks() * rates.horizontal();
        double hStart = goal.heuristic(startX, startY, startZ, rates);

        // The design's own termination bound, evaluated once, with no margin added: h falls by at
        // least minProgress per segment and cannot go below zero. A correct implementation never
        // reaches this. Reaching it means h stopped being admissible -- C1 section 5.3 records
        // that admissibility here is a checked numeric property, not a structural one -- so it is
        // reported rather than swallowed.
        int cap = (int) Math.ceil(hStart / minProgress) + 1;

        List<Pos> path = new ArrayList<Pos>();
        List<Pos> expanded = new ArrayList<Pos>();
        double cost = 0.0;
        int segments = 0;
        int x = startX;
        int y = startY;
        int z = startZ;

        while (segments < cap) {
            PathResult r = pathfinder.findPath(world, x, y, z, goal, caps);
            segments++;
            expanded.addAll(r.expanded());

            if (r.outcome() != PathOutcome.PARTIAL) {
                append(path, r.path());
                return new SegmentedResult(r.outcome(), path, expanded,
                    cost + r.cost(), segments);
            }

            append(path, r.path());
            cost += r.cost();
            Pos end = r.path().get(r.path().size() - 1);
            x = end.x();
            y = end.y();
            z = end.z();
        }

        return new SegmentedResult(PathOutcome.BUDGET_EXCEEDED, path, expanded, cost, segments);
    }

    /**
     * Joins a segment onto the route, dropping its first position.
     *
     * <p>Every segment after the first begins where the previous one ended, so appending whole
     * would repeat that position and make the route non-contiguous by its own test.
     */
    private static void append(List<Pos> path, List<Pos> segment) {
        if (segment.isEmpty()) {
            return;
        }
        if (path.isEmpty()) {
            path.addAll(segment);
            return;
        }
        path.addAll(segment.subList(1, segment.size()));
    }
}
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SegmentedSearchTest'
```

Expected: PASS, 6 tests. **Record the actual `segments()` and `cost()` from
`aRunReachesAGoalOneSearchCannotAtTheSameBudget`** — the plan predicts 2 and 274.42 but that came
from a prototype, not this code.

- [ ] **Step 6: Full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedResult.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/SegmentedSearchTest.java
git commit -m "feat(c4): chain segments into a run

Search, take the segment, search again from its end. Capped at the design's own
termination bound with no margin, so reaching the cap means h stopped being
admissible and is reported rather than swallowed.

Single-tick throughout: every segment reads the same BlockSource, so none of
C3's snapshot-lifetime questions apply."
```

---

### Task 5: The probe drives the run

**Files:**
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Test: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`

**No adapter file is touched.** The existing **L** key already calls `PathProbe.run`; changing what
`run` does internally is enough. That matters because adapters have no tests and cannot get any, so
every avoided adapter edit is a defect that cannot happen.

**Interfaces:**
- Consumes: `SegmentedSearch`, `SegmentedResult` from Task 4; the timing from Task 1.
- Produces: nothing later depends on.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void theSummaryReportsHowManySegmentsTheRunTook() {
        PathProbe probe = new PathProbe(1000);
        probe.markGoal(3, 65, 0);
        ProbeReport report = probe.run(new ProbeWorld(), 0, 65, 0);

        Matcher m = Pattern.compile(", ([0-9]+) segments?").matcher(report.summary());
        assertTrue(m.find(), "no segment count in: " + report.summary());
        assertEquals(1, Integer.parseInt(m.group(1)),
            "a goal this close is one search; segmenting it would mean the budget is being"
                + " exhausted where it should not be");
    }
```

- [ ] **Step 2: Run and watch it fail**

```bash
./gradlew :runtime:test --tests 'dev.continuo.runtime.PathProbeTest'
```

Expected: FAIL — `no segment count in: ...`.

- [ ] **Step 3: Swap the search for the run**

Replace the search block from Task 1 with:

```java
        WorldSnapshot snapshot = new WorldSnapshot(world);
        long startedAt = System.nanoTime();
        SegmentedResult result = new SegmentedSearch(new AStarPathfinder(nodeBudget)).run(
            snapshot, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
        double elapsedMs = (System.nanoTime() - startedAt) / 1000000.0;
        SealedSnapshot sealed = snapshot.seal();
```

Swap the imports: `SegmentedResult` and `SegmentedSearch` in, `PathResult` out.

`ProbeBounds.around` and `PathRenderer.render` both take a `PathResult`. Build one from the run so
the render draws the whole route:

```java
        PathResult combined = result.asPathResult();
```

and add to `SegmentedResult`:

```java
    /**
     * @return this run as a single {@link PathResult}, so the renderer and the bounds calculator
     *         can draw a whole run exactly as they draw one search
     */
    public PathResult asPathResult() {
        return new PathResult(outcome, path, expanded, cost);
    }
```

`PathResult`'s constructor is package-private and `SegmentedResult` is in the same package, so this
compiles without widening anything.

- [ ] **Step 4: Report the segment count**

In the summary builder, after the outcome and before `, N steps`:

```java
            .append(", ").append(result.segments())
            .append(result.segments() == 1 ? " segment" : " segments")
```

**Exactly two existing references must move to `combined`**, because `SegmentedResult` carries
`outcome()`, `path()` and `cost()` under the same names but has no `nodesExpanded()` and is not a
`PathResult`:

| Line | Was | Becomes |
|---|---|---|
| the `PathRenderer.render(...)` call | `start, goal, result` | `start, goal, combined` |
| the summary's expanded count | `result.nodesExpanded()` | `combined.nodesExpanded()` |

`ProbeBounds.around(world, start, goal, result.path())` needs no change — `SegmentedResult.path()`
returns the same `List<Pos>` type and now covers the whole run, which is what the window should be
drawn around.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :runtime:test --tests 'dev.continuo.runtime.PathProbeTest'
```

Expected: PASS. Existing probe tests must still pass unedited.

- [ ] **Step 6: Full build**

```bash
./gradlew build --rerun-tasks
```

Expected: BUILD SUCCESSFUL. **Record the test count** — this is the first full rerun since the
branch started, and filtered runs corrupt the XML. The pre-C4 baseline is 448.

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "feat(c4): the probe reports a segmented run

The L key already calls PathProbe.run, so changing what run does internally
needs no adapter edit at all -- and adapters have no tests and cannot get any,
so an avoided adapter edit is a defect that cannot happen.

A goal reachable in one search still reports one segment and the same route, so
the change is invisible until a budget is actually exhausted."
```

---

### Task 6: Calibrate `minProgress`, choose the budget, close the spec

**Files:**
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/MinProgressSweepTest.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Modify: `docs/superpowers/specs/2026-08-26-c4-segmentation-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the committed sweep table; final values for `DEFAULT_MIN_PROGRESS_BLOCKS`,
  `DEFAULT_NODE_BUDGET` and `PathProbe.NODE_BUDGET`.

- [ ] **Step 1: Write the sweep**

A test that prints a table and asserts only what must hold. It is a measurement instrument that
lives in the suite, so it must stay fast and must not assert a tuned number into permanence.

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence behind {@link AStarPathfinder#DEFAULT_MIN_PROGRESS_BLOCKS}.
 *
 * <p>Prints quality = cost(segmented run) / cost(single unbounded search) for each fixture at each
 * candidate margin. The assertions are deliberately weak: this exists to produce a table a person
 * reads, and pinning a tuned figure here would turn a measurement into a regression test for
 * itself.
 */
class MinProgressSweepTest {

    private static final String[] FIXTURES = {
        "d-cliff.txt", "b-cave-climb.txt", "a-big-obstacle.txt"};
    private static final double[] MARGINS = {1.0, 2.0, 4.0, 8.0, 16.0};

    @Test
    void sweep() {
        for (int f = 0; f < FIXTURES.length; f++) {
            FixtureWorld world = TerrainFixture.load(FIXTURES[f]);
            Pos s = world.start();
            Pos g = world.goal();
            GoalBlock goal = new GoalBlock(g.x(), g.y(), g.z());

            PathResult optimal = new AStarPathfinder(200000)
                .findPath(world, s.x(), s.y(), s.z(), goal);
            int need = optimal.nodesExpanded();
            int budget = need * 84 / 100;

            System.out.println(FIXTURES[f] + ": needs " + need + " expansions, optimal cost "
                + optimal.cost() + ", sweeping at budget " + budget + " (84% of need)");

            for (int m = 0; m < MARGINS.length; m++) {
                SegmentedResult r = new SegmentedSearch(
                    new AStarPathfinder(budget, AStarPathfinder.defaultRegistry(), MARGINS[m]))
                    .run(world, s.x(), s.y(), s.z(), goal, CapabilitySet.none());
                String quality = r.outcome() == PathOutcome.FOUND
                    ? String.format(java.util.Locale.ROOT, "%.3f",
                        Double.valueOf(r.cost() / optimal.cost()))
                    : "-";
                System.out.println("  minProgress " + MARGINS[m] + " blocks: " + r.outcome()
                    + ", " + r.segments() + " segments, cost " + r.cost()
                    + ", quality " + quality);
                assertTrue(r.segments() >= 1);
            }
        }
    }
}
```

- [ ] **Step 2: Run it and read the table**

```bash
./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.MinProgressSweepTest' -i
```

Choose the margin that reaches `FOUND` on the most fixtures, breaking ties by lowest quality ratio.
**Write the table down** — it goes in the commit message and in spec §6.

- [ ] **Step 3: Set the margin**

Update `DEFAULT_MIN_PROGRESS_BLOCKS` to the chosen value and replace its "PROVISIONAL" paragraph
with the measured justification, naming the table.

- [ ] **Step 4: Choose the budget**

Two inputs, both now in hand: the per-route expansion needs in spec §6, and the millisecond figure
Task 1 produces. Set `AStarPathfinder.DEFAULT_NODE_BUDGET` and `PathProbe.NODE_BUDGET` so every
route measured so far sits inside a single search — spec §6 puts that at about 25,000 — unless the
millisecond figure says the client cannot afford it, in which case **stop and report** rather than
choosing between a stutter and a truncated search. That trade is §1.2's deferred question and it is
the owner's.

Replace `DEFAULT_NODE_BUDGET`'s javadoc promise that "C4 replaces this with a real search-effort
policy" with what the number now means and where it came from.

- [ ] **Step 5: Update the spec**

In `docs/superpowers/specs/2026-08-26-c4-segmentation-design.md`:
- §6: paste the sweep table and the chosen margin.
- §6: record the chosen budget and the millisecond figure behind it.
- §10: tick criterion 2.

- [ ] **Step 6: Full build**

```bash
./gradlew build --rerun-tasks
```

Expected: BUILD SUCCESSFUL. Record the final test count.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(c4): calibrate minProgress and set the search budget

<paste the sweep table here>

The budget is set from the in-game millisecond figure and the per-route
expansion needs, not from the sweep: spec section 7.1.2 measures replayed
expansion counts running 3.6-7.6x below the same route in game, so a budget
fitted to them would be fitted to a fiction."
```

---

## After the tasks

**Before claiming C4 done**, two things this plan cannot do for you:

1. **The final whole-branch review must execute the mutations in spec §9.1**, not read the diff.
   Five consecutive sub-projects have had the final review find, by running broken code, what four
   task reviews missed. Ask the reviewer to build their own mutants too — C3's best find was a
   mutant no plan had named.
2. **Done criterion 6 needs one in-game run**, and its route is already chosen and already proven:
   (1626, 62, −863) → (1737, 72, −786), which returned `BUDGET_EXCEEDED` at 10,000 and `FOUND` at
   17,423. Reaching it and reporting milliseconds discharges the criterion.

---

## Self-Review

**Spec coverage.** D1 → Tasks 1, 6. D2 → Task 2. D3 → Task 2. D4 → Task 2. D5 → Task 3
(`openSetExhaustionNeverBacksOff`) and Task 4. D6 → Task 1. D7 → Task 3 uses real terrain plus the
boxed synthetic. D8 → Task 4. D9 → Task 6's budget choice. §8's probe changes → Tasks 1 and 5. §9.1
mutations → handed to the final review, which is where the spec puts them.

**Two deliberate departures from the spec, both recorded here rather than silently:**

- **§8 says the probe "gains a key" for the driver. It does not.** The existing **L** key already
  calls `PathProbe.run`, so driving the run inside `run` needs no adapter edit — and adapters have
  no tests and cannot get any. Trading an untestable edit for nothing is the same call the project
  already made when it declined to route the probe's verdict through `RuntimeLog`.
- **§7.2's three synthetic traps are reduced to one.** They were drafted to pin predictions §2.1
  measured as false; two of them would pin fiction. Real terrain, which is what disproved those
  predictions, pins the rule instead. Only the boxed start survives, because the empty
  `BUDGET_EXCEEDED` branch has no natural occurrence.

**Type consistency.** `SegmentSelector.consider(long, double)` is called with exactly that
signature in Task 3. `AStarPathfinder.registry()` and `minProgressBlocks()` are declared in Task 3
and consumed in Task 4. `SegmentedResult.asPathResult()` is added in Task 5, where it is first
needed, and relies on `PathResult`'s package-private constructor — both are in
`dev.continuo.pathfinder`.

**Numbers that are predictions, not facts.** Every one is labelled in place: `d-cliff` reaching the
goal in 2 segments at cost 274.42, `a-big-obstacle` failing at 39%, and the ~25,000 budget. They
come from a throwaway prototype, not from this code. Report what you observe.
