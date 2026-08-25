# C3 World Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `WorldSnapshot` — an immutable, point-in-time copy of the part of the world a search actually reads — by memoising a `BlockSource` and freezing it with a one-way `seal()`.

**Architecture:** A decorator, not a bulk copier. `WorldSnapshot` wraps any `BlockSource`, caches every position it reads in a `HashMap<Long, BlockData>` keyed by a packed `long`, and returns the cached answer forever after. `seal()` hands that map to a `SealedSnapshot` and invalidates the filling handle, so the sealed object structurally cannot call the SPI and its `final` fields give safe publication for free. There are no sections, no state ids, and no dependency on B1.

**Tech Stack:** Java 8 (machine-checked), Gradle, JUnit 5. Modules `:core`, `:core-pathfinder`, `:runtime`.

**Spec:** `docs/superpowers/specs/2026-08-25-c3-world-snapshot-design.md`

## Global Constraints

- **Java 8 bytecode, machine-checked by `checkCoreBytecode`.** No `var`, no records, no `List.of`, no text blocks, no switch expressions. **No lambdas in main source.** Explicit generic type arguments (`new HashMap<Long, BlockData>()`), matching the surrounding code.
- **Gate on `./gradlew build`, never `./gradlew :test`.** Javadoc is build-failing (`-Xdoclint:all,-missing -Xwerror`). **A dead `{@link}` fails the build exactly as hard as a missing symbol.** Only `{@link}` things that exist.
- **`./gradlew clean` destroys the 1.7.10 decompiled sources.** Use `build --rerun-tasks`. Never `clean`.
- **`GRADLE_USER_HOME` is already `C:\GradleHome`.** Never set, export or override it.
- **Filtered Gradle runs corrupt the XML test counts.** Count tests only after a full `build --rerun-tasks`.
- **No new module, no new dependency, no new SPI type, no `IGameEvents` method, no adapter edit.** A stated success condition, verifiable from the diff. Nothing in this plan touches `platform/` or `adapters/`.
- **`:core` may not depend on `:core-pathfinder`.** The dependency runs the other way (`:core-pathfinder` → `:core`), which is why Task 1 exists at all. `checkDependencyDirection` enforces it.
- **Report discrepancies rather than adjust.** If a step's expected output does not match what you see, stop and say so. Do not "fix" the plan by changing an assertion to match observed behaviour. Four briefs were wrong on the C1a branch and every one was caught this way.
- Test totals below are *estimates*. The real number comes from a full `build --rerun-tasks`. A previous branch shipped a commit correcting its own predicted total; do not treat these as targets.

---

## File Structure

| File | Responsibility |
|---|---|
| `core/src/main/java/dev/continuo/core/PositionKey.java` | **New.** The one definition of the packed-`long` position key |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java` | **Modify.** Delegates its packing arithmetic; public API unchanged |
| `core/src/main/java/dev/continuo/core/SealedSnapshot.java` | **New.** The frozen, any-thread half. `at`, `covers`, `size`, `reads` |
| `core/src/main/java/dev/continuo/core/WorldSnapshot.java` | **New.** The filling, main-thread half. `at`, `size`, `reads`, `seal` |
| `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` | **Modify.** Searches through a snapshot and reports its ratio |
| `core/src/test/java/dev/continuo/core/PositionKeyTest.java` | **New.** Pins the bits the extraction must not have changed |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java` | **New.** Pins that `Pos` still agrees with `PositionKey` |
| `core/src/test/java/dev/continuo/core/SealedSnapshotTest.java` | **New.** Freezing, and the `covers()` distinction M5 needs |
| `core/src/test/java/dev/continuo/core/RecordingSource.java` | **New.** Test-only `BlockSource` that counts calls per position |
| `core/src/test/java/dev/continuo/core/WorldSnapshotTest.java` | **New.** Memoising, stability, sealing, use-after-seal |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/SnapshotSearchTest.java` | **New.** A\* through a snapshot is byte-identical to A\* live |
| `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` | **Modify.** The probe's new summary figures |

---

## Task 1: `PositionKey`, and `Pos` delegating to it

`WorldSnapshot` needs one `long` per position to key its map on. A\* already has that packing in `dev.continuo.pathfinder.Pos` — X and Z at 26 signed bits, Y at 12 — but `:core` cannot reach `:core-pathfinder`. Extract it into `:core` and have `Pos` call through, so the scheme is defined once. Spec §4.1, decision D7.

**Files:**
- Create: `core/src/main/java/dev/continuo/core/PositionKey.java`
- Create: `core/src/test/java/dev/continuo/core/PositionKeyTest.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java:16-17` (the two mask constants) and `:60-88` (the four static methods)
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `dev.continuo.core.PositionKey`, with `public static long pack(int x, int y, int z)`, `public static int unpackX(long packed)`, `public static int unpackY(long packed)`, `public static int unpackZ(long packed)`. Tasks 2 and 3 key their maps with `PositionKey.pack`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/PositionKeyTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PositionKeyTest {

    /** Positions chosen to exercise both signs on every axis and both Y limits of both versions. */
    private static final int[][] AWKWARD = {
        {0, 0, 0},
        {0, 64, 0},
        {1, 0, 0},
        {0, 0, 1},
        {-1, -1, -1},
        {-1, 0, 0},
        {0, -64, 0},
        {0, 319, 0},
        {0, 255, 0},
        {-30000000, -64, 30000000},
        {30000000, 319, -30000000},
        {33554431, 2047, -33554432},
        {-33554432, -2048, 33554431},
    };

    @Test
    void theBitLayoutIsExactlyWhatPosUsedBeforeTheExtraction() {
        // Y occupies the low 12 bits, Z the next 26, X the top 26. These three literals pin the
        // layout itself: an extraction that shifted an axis by even one bit would still round
        // trip, and would still pass every other test in this file, while silently aliasing two
        // different positions onto one snapshot entry.
        assertEquals(64L, PositionKey.pack(0, 64, 0));
        assertEquals(1L << 12, PositionKey.pack(0, 0, 1));
        assertEquals(1L << 38, PositionKey.pack(1, 0, 0));
    }

    @Test
    void everyAwkwardPositionRoundTrips() {
        for (int i = 0; i < AWKWARD.length; i++) {
            int x = AWKWARD[i][0];
            int y = AWKWARD[i][1];
            int z = AWKWARD[i][2];
            long packed = PositionKey.pack(x, y, z);
            String where = "(" + x + ", " + y + ", " + z + ")";

            assertEquals(x, PositionKey.unpackX(packed), "X of " + where);
            assertEquals(y, PositionKey.unpackY(packed), "Y of " + where);
            assertEquals(z, PositionKey.unpackZ(packed), "Z of " + where);
        }
    }

    @Test
    void distinctPositionsGetDistinctKeys() {
        // The property a snapshot actually depends on. Without it two blocks share one cache
        // entry and the snapshot answers for the wrong one, which no round-trip test would see.
        for (int i = 0; i < AWKWARD.length; i++) {
            for (int j = i + 1; j < AWKWARD.length; j++) {
                assertNotEquals(
                    PositionKey.pack(AWKWARD[i][0], AWKWARD[i][1], AWKWARD[i][2]),
                    PositionKey.pack(AWKWARD[j][0], AWKWARD[j][1], AWKWARD[j][2]),
                    "positions " + i + " and " + j + " collided");
            }
        }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core:test --tests '*PositionKeyTest*'`

Expected: **compilation failure**, `cannot find symbol: class PositionKey`.

- [ ] **Step 3: Write `PositionKey`**

Create `core/src/main/java/dev/continuo/core/PositionKey.java`:

```java
package dev.continuo.core;

/**
 * The {@code long} that identifies a block position.
 *
 * <p><b>The packing.</b> X and Z take 26 signed bits each and Y takes 12, which covers
 * &plusmn;33,554,432 horizontally — beyond Minecraft's world border on both versions — and
 * &minus;2048..2047 vertically, comfortably outside 1.7.10's {@code 0..256} and 1.21.11's
 * {@code -64..320}. A single {@code long} gives each position one identity that a map can key on
 * directly, with no hand-written {@code hashCode} or {@code equals} over a composite key to get
 * wrong.
 *
 * <p><b>Why this lives in {@code :core} rather than beside the search.</b> Two consumers need it:
 * {@code dev.continuo.pathfinder.Pos}, which keys the search's node maps, and
 * {@link WorldSnapshot}, which keys its cache. {@code :core-pathfinder} depends on {@code :core}
 * and not the other way round, so the shared definition has to sit here. Two independent copies
 * of a bit layout is a silent aliasing bug waiting for someone to change one of them.
 */
public final class PositionKey {

    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private PositionKey() {
    }

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the three coordinates packed into one {@code long}
     */
    public static long pack(int x, int y, int z) {
        return ((long) x & XZ_MASK) << 38
            | ((long) z & XZ_MASK) << 12
            | ((long) y & Y_MASK);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the X coordinate, sign restored
     */
    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Y coordinate, sign restored
     */
    public static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Z coordinate, sign restored
     */
    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :core:test --tests '*PositionKeyTest*'`

Expected: PASS, 3 tests.

- [ ] **Step 5: Write the delegation test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.PositionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Pos} had no test of its own before C3 — its packing was covered only through the search
 * that uses it. This is the net under the extraction: whatever {@code Pos} promises, it must be
 * what {@link PositionKey} does.
 */
class PosTest {

    private static final int[][] AWKWARD = {
        {0, 0, 0},
        {0, 64, 0},
        {-1, -1, -1},
        {0, -64, 0},
        {0, 319, 0},
        {-30000000, -64, 30000000},
        {30000000, 319, -30000000},
    };

    @Test
    void posAgreesWithPositionKeyOnEveryAxisAndSign() {
        for (int i = 0; i < AWKWARD.length; i++) {
            int x = AWKWARD[i][0];
            int y = AWKWARD[i][1];
            int z = AWKWARD[i][2];
            String where = "(" + x + ", " + y + ", " + z + ")";

            long viaPos = Pos.pack(x, y, z);
            assertEquals(PositionKey.pack(x, y, z), viaPos, "pack of " + where);
            assertEquals(PositionKey.unpackX(viaPos), Pos.unpackX(viaPos), "X of " + where);
            assertEquals(PositionKey.unpackY(viaPos), Pos.unpackY(viaPos), "Y of " + where);
            assertEquals(PositionKey.unpackZ(viaPos), Pos.unpackZ(viaPos), "Z of " + where);
        }
    }

    @Test
    void theInstanceApiIsUnchangedByTheDelegation() {
        Pos pos = new Pos(-30000000, -64, 30000000);

        assertEquals(-30000000, pos.x());
        assertEquals(-64, pos.y());
        assertEquals(30000000, pos.z());
        assertEquals(PositionKey.pack(-30000000, -64, 30000000), pos.packed());
        assertEquals(pos, Pos.unpack(pos.packed()));
        assertEquals(pos.hashCode(), Pos.unpack(pos.packed()).hashCode());
    }
}
```

- [ ] **Step 6: Run it and verify it passes against the un-delegated `Pos`**

Run: `./gradlew :core-pathfinder:test --tests '*PosTest*'`

Expected: **PASS**. This is deliberate — `Pos` and `PositionKey` currently hold identical copies of the arithmetic, so the test passes *before* the refactor. That is what makes it a safety net: it is asserting the property the refactor must preserve, and it can only start failing if the refactor breaks it.

- [ ] **Step 7: Make `Pos` delegate**

In `core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java`:

Add the import below the package line:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.PositionKey;
```

Delete the two mask constants (lines 16-17):

```java
    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;
```

Replace the class javadoc's packing paragraph, since the description now lives in one place:

```java
/**
 * An immutable block position, and the {@code long} packing the search keys nodes on.
 *
 * <p><b>The packing is {@link PositionKey}'s</b>, which lives in {@code :core} because
 * {@code WorldSnapshot} keys its cache the same way and cannot reach this module. The methods
 * here delegate and exist so the search reads naturally; the bit layout and its ranges are
 * documented on {@code PositionKey}. The maps are {@code HashMap<Long, PathNode>}, so the keys
 * are still boxed; a primitive map would be a C4 concern, not a claim this packing already makes
 * good on.
 */
```

Replace the four static method bodies, keeping every signature and javadoc `@param`/`@return` exactly as they are:

```java
    public static long pack(int x, int y, int z) {
        return PositionKey.pack(x, y, z);
    }

    public static int unpackX(long packed) {
        return PositionKey.unpackX(packed);
    }

    public static int unpackY(long packed) {
        return PositionKey.unpackY(packed);
    }

    public static int unpackZ(long packed) {
        return PositionKey.unpackZ(packed);
    }
```

Leave `packed()`, `unpack()`, `equals`, `hashCode` and `toString` untouched — `packed()` already calls `pack(x, y, z)` and `hashCode()` already calls `packed()`.

- [ ] **Step 8: Run the affected suites and verify they pass**

Run: `./gradlew :core:test :core-pathfinder:test`

Expected: PASS. `AStarPathfinderTest`'s exact-path expectations are the real check here — the search keys every node through `Pos.pack`, so a changed bit layout would move which of two equal-cost paths comes back.

- [ ] **Step 9: Prove the delegation test bites**

Temporarily break `PositionKey.unpackZ` by changing its shift:

```java
    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 39);
    }
```

Run: `./gradlew :core:test --tests '*PositionKeyTest*' :core-pathfinder:test --tests '*PosTest*'`

Expected: **both** `everyAwkwardPositionRoundTrips` and `posAgreesWithPositionKeyOnEveryAxisAndSign` FAIL. Then revert the shift to `>> 38` and re-run to confirm green. **Record the observed failure output in the commit message.**

- [ ] **Step 10: Full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. Roughly 424 tests (419 before this task, plus 3 in `PositionKeyTest` and 2 in `PosTest`). Report the number you actually see rather than this one.

- [ ] **Step 11: Commit**

```bash
git add core/src/main/java/dev/continuo/core/PositionKey.java \
        core/src/test/java/dev/continuo/core/PositionKeyTest.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java
git commit -m "refactor(c3): one definition of the position packing

WorldSnapshot keys its cache on a packed long and lives in :core, which
cannot reach dev.continuo.pathfinder.Pos. PositionKey holds the 26/12/26
layout now and Pos delegates, so the scheme is defined once instead of
copied. Pos keeps its entire public API and no call site outside Pos.java
changes.

Pos had no test of its own; PosTest is the net under the extraction.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: `SealedSnapshot`

The frozen half. Built first because it depends on nothing but `PositionKey` and `BlockData`, and because Task 3's `seal()` needs it to exist. Spec §4.3 and §4.5.

**Files:**
- Create: `core/src/main/java/dev/continuo/core/SealedSnapshot.java`
- Create: `core/src/test/java/dev/continuo/core/SealedSnapshotTest.java`

**Interfaces:**
- Consumes: `PositionKey.pack(int, int, int)` from Task 1.
- Produces: `dev.continuo.core.SealedSnapshot implements BlockSource`, with a **package-private** constructor `SealedSnapshot(Map<Long, BlockData> blocks, int minY, int maxY, int reads)` and public `BlockData at(int, int, int)`, `int minY()`, `int maxY()`, `boolean covers(int, int, int)`, `int size()`, `int reads()`. Task 3's `WorldSnapshot.seal()` is the only production caller of the constructor.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/SealedSnapshotTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealedSnapshotTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /**
     * A sealed snapshot holding stone at (1, 70, 2) and an UNKNOWN that was genuinely read at
     * (1, 71, 2) — an unloaded chunk, say — over a world spanning -64..320.
     */
    private static SealedSnapshot fixture() {
        Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
        blocks.put(Long.valueOf(PositionKey.pack(1, 70, 2)), STONE);
        blocks.put(Long.valueOf(PositionKey.pack(1, 71, 2)), BlockData.UNKNOWN);
        return new SealedSnapshot(blocks, -64, 320, 4242);
    }

    @Test
    void aHeldPositionComesBack() {
        assertSame(STONE, fixture().at(1, 70, 2));
        assertEquals(-64, fixture().minY());
        assertEquals(320, fixture().maxY());
    }

    @Test
    void aPositionThatWasNeverReadIsUnknownAndIsNotCovered() {
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(9, 70, 9));
        assertFalse(sealed.covers(9, 70, 9),
            "a position this snapshot never read is a hole, and M5 must be able to tell");
    }

    @Test
    void aPositionReadAsUnknownIsUnknownAndIsCovered() {
        // THE distinction M5 needs, and the reason covers() exists at all. Both this and the test
        // above return UNKNOWN from at(), so an at()-only assertion cannot tell them apart. An
        // off-thread search must treat them completely differently: this one is terrain to route
        // around, that one is a question only the main thread can answer.
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(1, 71, 2));
        assertTrue(sealed.covers(1, 71, 2),
            "the world's own UNKNOWN was read and frozen; it is an answer, not a hole");
    }

    @Test
    void outsideTheWorldsYLimitsIsUnknownAndIsCovered() {
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(1, -65, 2));
        assertSame(BlockData.UNKNOWN, sealed.at(1, 320, 2));
        assertTrue(sealed.covers(1, -65, 2), "out of world is permanent, not a hole");
        assertTrue(sealed.covers(1, 320, 2), "maxY is exclusive, so 320 is already outside");
        assertFalse(sealed.covers(1, 319, 2),
            "but 319 is inside the world and was never read, so it IS a hole");
    }

    @Test
    void theCountersAreTheFrozenOnesAndReadingDoesNotMoveThem() {
        SealedSnapshot sealed = fixture();

        assertEquals(2, sealed.size());
        assertEquals(4242, sealed.reads());

        sealed.at(1, 70, 2);
        sealed.at(9, 70, 9);
        sealed.covers(1, 70, 2);

        assertEquals(2, sealed.size(), "reading a sealed snapshot must not change it");
        assertEquals(4242, sealed.reads(),
            "reads() is the filling handle's count at the moment of sealing; a sealed snapshot"
                + " cannot count its own reads without mutating, and immutability is what makes"
                + " it safe to publish to another thread");
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core:test --tests '*SealedSnapshotTest*'`

Expected: **compilation failure**, `cannot find symbol: class SealedSnapshot`.

- [ ] **Step 3: Write `SealedSnapshot`**

Create `core/src/main/java/dev/continuo/core/SealedSnapshot.java`:

```java
package dev.continuo.core;

import java.util.Map;

/**
 * A frozen {@code WorldSnapshot}: the part of the world one search read, and nothing else.
 *
 * <p>Readable from any thread. It holds no reference to a live source and has no method that
 * would use one, so it cannot call the SPI — that is a property of the type rather than a check
 * it performs. Its fields are {@code final} and its map is never mutated after construction, so
 * the JMM's final-field freeze guarantees that a thread seeing a properly constructed instance
 * sees the whole map. Handing one to an executor therefore needs no happens-before argument of
 * its own.
 *
 * <p><b>Staleness is the contract, not a bug.</b> A snapshot is a point-in-time copy by
 * definition. Nothing invalidates it and nothing refreshes it. When the world moves under a
 * computed path, M5's position resync notices and repaths.
 *
 * <p>Created only by {@code WorldSnapshot.seal()}, which hands over its map and invalidates
 * itself. There is deliberately no public constructor: an instance built over a map somebody else
 * still holds would be neither immutable nor safely publishable, and both of those are the whole
 * point.
 *
 * <p>The references to {@code WorldSnapshot} above are deliberately {@code @code} rather than
 * {@code @link}: this class is written first, and a dead {@code @link} fails the build exactly as
 * hard as a missing symbol.
 */
public final class SealedSnapshot implements BlockSource {

    private final Map<Long, BlockData> blocks;
    private final int minY;
    private final int maxY;
    private final int reads;

    /**
     * @param blocks the frozen positions; ownership passes to this object and the caller must
     *               never touch the map again
     * @param minY the world's inclusive lower bound at the time of filling
     * @param maxY the world's exclusive upper bound at the time of filling
     * @param reads the filling handle's {@code reads()} at the moment of sealing
     */
    SealedSnapshot(Map<Long, BlockData> blocks, int minY, int maxY, int reads) {
        this.blocks = blocks;
        this.minY = minY;
        this.maxY = maxY;
        this.reads = reads;
    }

    /**
     * The block frozen at a position.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, or {@link BlockData#UNKNOWN} if this snapshot has no answer — which
     *         {@link #covers} distinguishes from the world's own {@code UNKNOWN}
     */
    @Override
    public BlockData at(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return BlockData.UNKNOWN;
        }
        BlockData held = blocks.get(Long.valueOf(PositionKey.pack(x, y, z)));
        return held == null ? BlockData.UNKNOWN : held;
    }

    /** @return the world's inclusive lower bound, as it was when this was filled */
    @Override
    public int minY() {
        return minY;
    }

    /** @return the world's exclusive upper bound, as it was when this was filled */
    @Override
    public int maxY() {
        return maxY;
    }

    /**
     * Whether this snapshot can answer for a position authoritatively.
     *
     * <p>Four situations collapse to two answers. A position read while filling is covered,
     * whether the world gave a block or gave {@code UNKNOWN} for an unloaded chunk. A position
     * outside {@link #minY}/{@link #maxY} is covered too: there is no terrain there and there
     * never will be. Only a position that was never read is <em>not</em> covered.
     *
     * <p><b>Why this is not on {@link BlockSource}.</b> That interface has one rule — every
     * unreadable position yields {@code UNKNOWN}, no position-dependent special cases — and it
     * keeps it. This method exists here alone because an off-thread search needs the distinction
     * and nothing holding the bare interface does: an uncovered position is a question for the
     * main thread, a covered {@code UNKNOWN} is terrain to route around.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return whether {@link #at} speaks for the world here rather than for this snapshot's limits
     */
    public boolean covers(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return true;
        }
        return blocks.containsKey(Long.valueOf(PositionKey.pack(x, y, z)));
    }

    /** @return how many positions this snapshot holds */
    public int size() {
        return blocks.size();
    }

    /**
     * @return how many reads the filling handle served before sealing. Divided by {@link #size},
     *         this is how many times the average position was read — the figure that makes a
     *         snapshot cheaper than reading the world live
     */
    public int reads() {
        return reads;
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :core:test --tests '*SealedSnapshotTest*'`

Expected: PASS, 5 tests.

- [ ] **Step 5: Prove the `covers()` distinction bites**

Temporarily collapse `covers` to ignore whether a position was read:

```java
    public boolean covers(int x, int y, int z) {
        return true;
    }
```

Run: `./gradlew :core:test --tests '*SealedSnapshotTest*'`

Expected: `aPositionThatWasNeverReadIsUnknownAndIsNotCovered` and `outsideTheWorldsYLimitsIsUnknownAndIsCovered` both FAIL.

Now the other direction — make it a plain map lookup that forgets the out-of-world case:

```java
    public boolean covers(int x, int y, int z) {
        return blocks.containsKey(Long.valueOf(PositionKey.pack(x, y, z)));
    }
```

Expected: `outsideTheWorldsYLimitsIsUnknownAndIsCovered` FAILS on the `covers(1, -65, 2)` assertion.

Restore the real implementation and re-run to confirm green. **Record both observed failures in the commit message.**

- [ ] **Step 6: Full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. Roughly 429 tests. This task leaves the tree green on its own — `SealedSnapshot` compiles and documents without `WorldSnapshot` existing, which is why its javadoc says `{@code WorldSnapshot}` rather than `{@link}`.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/dev/continuo/core/SealedSnapshot.java \
        core/src/test/java/dev/continuo/core/SealedSnapshotTest.java
git commit -m "feat(c3): SealedSnapshot, the frozen half

Any-thread reads over a map handed to it by WorldSnapshot.seal(). It holds
no live reference and has no method that would use one, so it cannot call
the SPI structurally rather than by a check. Final fields plus a map that is
never mutated after construction give M5 safe publication for free.

covers() is the one thing BlockSource cannot express. A position read as
UNKNOWN and a position never read both return UNKNOWN from at(), and an
off-thread search must treat them completely differently: the first is
terrain, the second is a question for the main thread. Out-of-world counts
as covered - there is no terrain there and never will be.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `WorldSnapshot`

The filling half, and the one-way transition. Spec §4.2, §4.4, §4.6, §4.7, §4.8.

**Files:**
- Create: `core/src/main/java/dev/continuo/core/WorldSnapshot.java`
- Create: `core/src/test/java/dev/continuo/core/RecordingSource.java`
- Create: `core/src/test/java/dev/continuo/core/WorldSnapshotTest.java`

**Interfaces:**
- Consumes: `PositionKey.pack` (Task 1); the package-private `SealedSnapshot(Map<Long, BlockData>, int, int, int)` constructor and its `size()`/`reads()`/`covers()` (Task 2).
- Produces: `dev.continuo.core.WorldSnapshot implements BlockSource`, with `public WorldSnapshot(BlockSource live)`, `public BlockData at(int, int, int)`, `public int minY()`, `public int maxY()`, `public int size()`, `public int reads()`, `public SealedSnapshot seal()`. Task 4 constructs one around the probe's live source and calls `seal()`.

- [ ] **Step 1: Write the test helper**

Create `core/src/test/java/dev/continuo/core/RecordingSource.java`:

```java
package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BlockSource} for tests: programmable per position, and counting every call.
 *
 * <p>Counting <em>per position</em> rather than in total is what lets a test assert that a
 * snapshot read the world exactly once for each position it holds — the claim the whole design
 * rests on. A total-only counter would pass on a snapshot that read one position twice and
 * another never.
 */
final class RecordingSource implements BlockSource {

    private final Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
    private final Map<Long, Integer> callsPerPosition = new HashMap<Long, Integer>();
    private int calls;
    private boolean refusing;

    /** Puts a block at a position. Anything not put reads as {@link BlockData#UNKNOWN}. */
    void put(int x, int y, int z, BlockData data) {
        blocks.put(Long.valueOf(PositionKey.pack(x, y, z)), data);
    }

    /** Makes every later read throw, so a test can prove nothing touched this source. */
    void refuseFurtherReads() {
        refusing = true;
    }

    /** @return how many times {@link #at} has been called in total */
    int calls() {
        return calls;
    }

    /** @return how many times {@link #at} has been called for one position */
    int callsAt(int x, int y, int z) {
        Integer n = callsPerPosition.get(Long.valueOf(PositionKey.pack(x, y, z)));
        return n == null ? 0 : n.intValue();
    }

    @Override
    public BlockData at(int x, int y, int z) {
        if (refusing) {
            throw new AssertionError(
                "the live source was read at (" + x + ", " + y + ", " + z + ")");
        }
        calls++;
        Long key = Long.valueOf(PositionKey.pack(x, y, z));
        Integer seen = callsPerPosition.get(key);
        callsPerPosition.put(key, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        BlockData held = blocks.get(key);
        return held == null ? BlockData.UNKNOWN : held;
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
```

- [ ] **Step 2: Write the failing test**

Create `core/src/test/java/dev/continuo/core/WorldSnapshotTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData AIR = new BlockData(
        BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** A source whose answer at one position changes on every read. */
    private static final class ShiftingSource implements BlockSource {
        private int served;

        @Override
        public BlockData at(int x, int y, int z) {
            served++;
            return new BlockData(BlockShape.FULL, served, Fluid.NONE,
                EnumSet.noneOf(BlockTag.class));
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

    @Test
    void aFillingSnapshotAnswersExactlyAsTheLiveSourceDoes() {
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        live.put(3, 71, 4, AIR);

        WorldSnapshot snapshot = new WorldSnapshot(live);

        for (int y = 68; y < 74; y++) {
            for (int x = 1; x < 6; x++) {
                for (int z = 2; z < 7; z++) {
                    assertSame(live.at(x, y, z), snapshot.at(x, y, z),
                        "(" + x + ", " + y + ", " + z + ")");
                }
            }
        }
        assertEquals(live.minY(), snapshot.minY());
        assertEquals(live.maxY(), snapshot.maxY());
    }

    @Test
    void everyPositionIsReadFromTheLiveSourceExactlyOnce() {
        // The claim the whole design rests on: measured on real terrain, a search reads each
        // position it touches between 4 and 16 times, and a snapshot turns all of those into one
        // SPI call. Asserting per position, not in total - a total would pass on a snapshot that
        // read one position twice and another never.
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        WorldSnapshot snapshot = new WorldSnapshot(live);

        for (int i = 0; i < 20; i++) {
            snapshot.at(3, 70, 4);
            snapshot.at(3, 71, 4);
        }

        assertEquals(1, live.callsAt(3, 70, 4));
        assertEquals(1, live.callsAt(3, 71, 4));
        assertEquals(2, live.calls(), "two distinct positions, two live reads");
        assertEquals(2, snapshot.size());
        assertEquals(40, snapshot.reads(), "forty reads served from two live ones");
    }

    @Test
    void theFirstAnswerIsTheOnlyAnswerEvenWhenTheWorldMoves() {
        // The stability property C3 was chosen for. A search that spans more than one tick can
        // have a chunk load or a block break under it; a snapshot means one search sees one
        // world. ShiftingSource returns a different block on every single call, so anything that
        // re-reads is caught immediately.
        ShiftingSource live = new ShiftingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        BlockData first = snapshot.at(0, 70, 0);

        for (int i = 0; i < 50; i++) {
            assertSame(first, snapshot.at(0, 70, 0),
                "read " + i + " disagreed with the first, so the snapshot is not a snapshot");
        }
    }

    @Test
    void anUnknownIsStoredAndNeverAskedAgain() {
        // "Unloaded is not air", and the reason it matters twice over. An unloaded chunk is a
        // real answer at the moment it was read, so storing it preserves stability - and it is
        // also what stops the repeat factor from re-hitting the SPI on exactly the positions a
        // search probes hardest, the edges of what it can see.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        assertSame(BlockData.UNKNOWN, snapshot.at(8, 70, 8));

        live.put(8, 70, 8, STONE);

        for (int i = 0; i < 10; i++) {
            assertSame(BlockData.UNKNOWN, snapshot.at(8, 70, 8),
                "the chunk loading later must not change what this snapshot already read");
        }
        assertEquals(1, live.callsAt(8, 70, 8));
        assertEquals(1, snapshot.size(), "a stored UNKNOWN occupies an entry like any other");
    }

    @Test
    void outsideTheWorldsYLimitsCostsNothingAndStoresNothing() {
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        assertSame(BlockData.UNKNOWN, snapshot.at(0, -65, 0));
        assertSame(BlockData.UNKNOWN, snapshot.at(0, 320, 0));

        assertEquals(0, live.calls(), "out of world is computable; do not ask the world");
        assertEquals(0, snapshot.size(), "and do not fill the map with entries carrying nothing");
        assertEquals(2, snapshot.reads(), "but they were still reads this snapshot served");
    }

    @Test
    void sealingKeepsTheAnswersAndStopsTouchingTheWorld() {
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 71, 4);

        SealedSnapshot sealed = snapshot.seal();
        live.refuseFurtherReads();

        assertSame(STONE, sealed.at(3, 70, 4));
        assertSame(BlockData.UNKNOWN, sealed.at(3, 71, 4));
        assertTrue(sealed.covers(3, 71, 4));
        assertSame(BlockData.UNKNOWN, sealed.at(99, 70, 99));
        assertFalse(sealed.covers(99, 70, 99),
            "a position the search never reached is a hole, and reading it must not go looking");
    }

    @Test
    void theSealedCountersAreTheFillingHandlesLastValues() {
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 71, 4);

        int size = snapshot.size();
        int reads = snapshot.reads();
        SealedSnapshot sealed = snapshot.seal();

        assertEquals(2, size);
        assertEquals(3, reads);
        assertEquals(size, sealed.size());
        assertEquals(reads, sealed.reads());
    }

    @Test
    void aSealedHandleRefusesEverythingThatWouldLie() {
        // Returning UNKNOWN from a sealed-out handle would present to the caller as terrain, and
        // a phantom wall that appears only after a seal is the worst failure this design has.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.seal();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.at(3, 70, 4);
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.size();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.reads();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.seal();
            }
        });
    }

    @Test
    void aSealedHandleStillKnowsTheWorldsLimits() {
        // minY and maxY are values captured at construction, not state that seal() gives away, so
        // they keep answering. Nothing can be misled by them: at() is the method that would lie.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.seal();

        assertEquals(-64, snapshot.minY());
        assertEquals(320, snapshot.maxY());
    }

    @Test
    void aNullLiveSourceIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    new WorldSnapshot(null);
                }
            });
    }
}
```

Note the `Executable` anonymous classes: **`assertThrows` normally takes a lambda, and lambdas are banned in main source but allowed in tests** — however this codebase writes them out longhand in `PathProbeTest` (see `runningWithNoGoalMarkedIsReportedRatherThanThrown`). Match that, and note `snapshot` must be effectively final for the anonymous class to capture it, which it is.

- [ ] **Step 3: Run the test and verify it fails**

Run: `./gradlew :core:test --tests '*WorldSnapshotTest*'`

Expected: **compilation failure**, `cannot find symbol: class WorldSnapshot`.

- [ ] **Step 4: Write `WorldSnapshot`**

Create `core/src/main/java/dev/continuo/core/WorldSnapshot.java`:

```java
package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A world copy being built: every position it reads, it remembers.
 *
 * <p><b>It decorates a {@link BlockSource}, not the SPI.</b> So it needs no state ids, no
 * classifier and no per-version table — it stores the {@link BlockData} the source handed back,
 * and {@link BlockLookup} has already interned one instance per state id, so a snapshot of forty
 * thousand positions holds a few dozen distinct objects and forty thousand references to them.
 *
 * <p><b>Why this is cheaper than reading the world.</b> {@code BlockLookup} memoises
 * classification by state id and never by position, so a block read sixteen times costs sixteen
 * {@code IBlockView.stateId} calls. Measured against real terrain, a search reads each position it
 * touches between four and sixteen times. A snapshot turns all of them into one.
 *
 * <p><b>Main thread only, while filling.</b> The restriction is inherited from whatever source is
 * wrapped rather than declared here: a live source carries {@code IBlockView}'s delivery window,
 * a fixture carries nothing. Once {@link #seal() sealed} the restriction is gone with the
 * reference that caused it.
 *
 * <p><b>This object has no lifecycle.</b> Nothing holds one across ticks, so there is nothing to
 * discard on a level transition and global rule 2 gains no new condition. The first thing that
 * keeps a snapshot alive between ticks inherits that question.
 */
public final class WorldSnapshot implements BlockSource {

    /** Cleared by {@link #seal()}, which is what makes a later fill impossible rather than wrong. */
    private BlockSource live;

    /** Handed to the sealed snapshot rather than copied; {@code null} once that has happened. */
    private Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();

    private final int minY;
    private final int maxY;

    private int reads;

    /**
     * @param live the source to copy from; never {@code null}
     * @throws IllegalArgumentException if {@code live} is {@code null}
     */
    public WorldSnapshot(BlockSource live) {
        if (live == null) {
            throw new IllegalArgumentException("live must not be null");
        }
        this.live = live;
        this.minY = live.minY();
        this.maxY = live.maxY();
    }

    /**
     * The block at a position, read from the live source at most once.
     *
     * <p>A position outside {@link #minY}/{@link #maxY} yields {@code UNKNOWN} without asking the
     * source and without being stored: that answer is permanent and computable, and storing it
     * would let a search probing above or below grow the map with entries carrying nothing.
     *
     * <p>Everything else is stored verbatim, {@link BlockData#UNKNOWN} included. An unloaded
     * chunk is a real answer at the moment it was read, and re-asking would both break the
     * point-in-time guarantee and spend SPI calls on exactly the positions a search probes
     * hardest.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block; never {@code null}
     * @throws IllegalStateException if this snapshot has been sealed
     */
    @Override
    public BlockData at(int x, int y, int z) {
        requireFilling();
        reads++;
        if (y < minY || y >= maxY) {
            return BlockData.UNKNOWN;
        }
        Long key = Long.valueOf(PositionKey.pack(x, y, z));
        BlockData cached = blocks.get(key);
        if (cached != null) {
            return cached;
        }
        BlockData fresh = live.at(x, y, z);
        blocks.put(key, fresh);
        return fresh;
    }

    /**
     * @return the world's inclusive lower bound, captured at construction. Still answers after
     *         sealing, because it is a value this object was given rather than state it gave away
     */
    @Override
    public int minY() {
        return minY;
    }

    /** @return the world's exclusive upper bound, captured at construction */
    @Override
    public int maxY() {
        return maxY;
    }

    /**
     * @return how many positions have been stored
     * @throws IllegalStateException if this snapshot has been sealed
     */
    public int size() {
        requireFilling();
        return blocks.size();
    }

    /**
     * @return how many {@link #at} calls have been served, including out-of-world ones
     * @throws IllegalStateException if this snapshot has been sealed
     */
    public int reads() {
        requireFilling();
        return reads;
    }

    /**
     * Freezes this snapshot, one way.
     *
     * <p>The map is handed over rather than copied, so this costs nothing and doubles no memory —
     * and this object is invalidated in the same breath, because a {@link SealedSnapshot} whose
     * map somebody else can still write to is neither immutable nor safely publishable.
     *
     * @return the frozen snapshot; never {@code null}
     * @throws IllegalStateException if this snapshot has already been sealed
     */
    public SealedSnapshot seal() {
        requireFilling();
        SealedSnapshot sealed = new SealedSnapshot(blocks, minY, maxY, reads);
        blocks = null;
        live = null;
        return sealed;
    }

    private void requireFilling() {
        if (blocks == null) {
            throw new IllegalStateException("this WorldSnapshot has been sealed;"
                + " read the SealedSnapshot that seal() returned instead");
        }
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `./gradlew :core:test`

Expected: PASS. `SealedSnapshotTest` and `WorldSnapshotTest` both green, and `SealedSnapshot`'s javadoc links now resolve.

- [ ] **Step 6: Prove the two starred tests bite**

Both have "Y does not happen" as their subject, which is the shape two vacuous tests hid in on an earlier branch. Run each mutation, confirm the named test fails, then restore.

**Mutation A — the snapshot stops being a snapshot.** In `at`, always ask the live source:

```java
        BlockData cached = blocks.get(key);
        if (false && cached != null) {
            return cached;
        }
```

Expected FAIL: `theFirstAnswerIsTheOnlyAnswerEvenWhenTheWorldMoves`, `everyPositionIsReadFromTheLiveSourceExactlyOnce`, `anUnknownIsStoredAndNeverAskedAgain`.

**Mutation B — `UNKNOWN` is treated as "not yet read".** In `at`, skip storing it:

```java
        BlockData fresh = live.at(x, y, z);
        if (fresh != BlockData.UNKNOWN) {
            blocks.put(key, fresh);
        }
        return fresh;
```

Expected FAIL: `anUnknownIsStoredAndNeverAskedAgain`. This is the mutation that matters most — it is the plausible one, the version a reasonable person writes thinking "there is nothing to cache here", and it silently reintroduces both the SPI cost and the staleness bug.

**Mutation C — out-of-world reads get stored.** Delete the early return's `return` so the bound check falls through to the map:

```java
        if (y < minY || y >= maxY) {
            blocks.put(Long.valueOf(PositionKey.pack(x, y, z)), BlockData.UNKNOWN);
            return BlockData.UNKNOWN;
        }
```

Expected FAIL: `outsideTheWorldsYLimitsCostsNothingAndStoresNothing`.

**Mutation D — `seal()` copies instead of transferring.** Replace the two `null` assignments with nothing:

```java
        SealedSnapshot sealed = new SealedSnapshot(blocks, minY, maxY, reads);
        return sealed;
```

Expected FAIL: `aSealedHandleRefusesEverythingThatWouldLie`.

**Report which of these did NOT fail as expected, if any.** A mutation that fails to fail is more informative than one that is caught.

- [ ] **Step 7: Full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. Roughly 439 tests. Report the number you see.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/dev/continuo/core/WorldSnapshot.java \
        core/src/test/java/dev/continuo/core/RecordingSource.java \
        core/src/test/java/dev/continuo/core/WorldSnapshotTest.java
git commit -m "feat(c3): WorldSnapshot, the filling half

Decorates a BlockSource and remembers every position it reads, so a search
that reads a position sixteen times costs one IBlockView call instead of
sixteen. BlockLookup memoises classification by state id and never by
position, which is why the saving exists at all.

UNKNOWN is stored verbatim: an unloaded chunk is a real answer at the moment
it was read, and re-asking would break the point-in-time guarantee as well
as spend SPI calls on the positions a search probes hardest. Out-of-world
reads are answered without asking and without storing - that answer is
permanent and computable.

seal() hands the map over rather than copying it and invalidates this
object in the same breath, because a SealedSnapshot whose map someone else
can still write to is neither immutable nor safely publishable. A sealed
handle throws rather than returning UNKNOWN, which would present to the
caller as terrain.

Four mutations recorded, including the plausible one: skipping the put for
UNKNOWN, which a reasonable person writes thinking there is nothing to
cache, and which silently reintroduces both the cost and the staleness.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: The consumers — A\* transparency and the probe

Spec §5.1 and decision D8. Two consumers: a test proving the decorator is invisible to the search, and `PathProbe`, which gives C3 a production caller and puts its central claim in the probe's output where every future run measures it on real terrain.

**Files:**
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/SnapshotSearchTest.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` — imports, class javadoc, and the body of `run`
- Modify: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` — one new test

**Interfaces:**
- Consumes: `WorldSnapshot(BlockSource)`, `WorldSnapshot.at`, `WorldSnapshot.seal()`, `SealedSnapshot.size()`, `SealedSnapshot.reads()` from Tasks 2 and 3.
- Produces: nothing further tasks depend on. This is the last task.

- [ ] **Step 1: Write the A\* transparency test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/SnapshotSearchTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.core.SealedSnapshot;
import dev.continuo.core.WorldSnapshot;
import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot must be invisible to the search. If searching through one returns a different route
 * from searching live, the snapshot is not a copy of the world — and because A* is deterministic
 * by construction, "different" here means any difference at all, not a worse one.
 */
class SnapshotSearchTest {

    /**
     * Flat ground with two walls the route has to thread, so the path is not a straight line.
     *
     * <p><b>The y=65 and y=66 slices are load-bearing, not padding.</b> A position outside a
     * {@code FixtureWorld}'s declared extent reads as {@code UNKNOWN}, which is impassable, so a
     * world that stops at the floor the player stands on gives every square zero head clearance
     * and the search returns {@code NO_PATH} over open ground.
     */
    private static final String WORLD =
        "origin: 0,63,0\n"
            + "--- y=63\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "##########\n"
            + "--- y=64\n"
            + "S.........\n"
            + "####.#####\n"
            + "..........\n"
            + "#####.####\n"
            + ".........G\n"
            + "--- y=65\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "--- y=66\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n"
            + "..........\n";

    @Test
    void searchingThroughASnapshotReturnsTheSameRouteAsSearchingLive() {
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();
        GoalBlock target = new GoalBlock(goal.x(), goal.y(), goal.z());

        PathResult live = new AStarPathfinder(10000).findPath(
            world, start.x(), start.y(), start.z(), target, CapabilitySet.none());

        WorldSnapshot snapshot = new WorldSnapshot(world);
        PathResult through = new AStarPathfinder(10000).findPath(
            snapshot, start.x(), start.y(), start.z(), target, CapabilitySet.none());

        assertEquals(PathOutcome.FOUND, live.outcome(), "the fixture must have a route at all");
        assertEquals(live.outcome(), through.outcome());
        assertEquals(live.cost(), through.cost(), 0.0, "cost must be bit-identical, not close");
        assertEquals(live.nodesExpanded(), through.nodesExpanded());
        assertEquals(live.path(), through.path(), "A* is deterministic; any difference is a bug");
    }

    @Test
    void theSnapshotServedManyMoreReadsThanItStoredPositions() {
        // C3's cost claim, asserted rather than asserted-about. Measured on real terrain the
        // repeat factor runs 4x to 16x; this fixture is tiny, so the bound is deliberately weak
        // and only has to show the effect exists.
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();

        WorldSnapshot snapshot = new WorldSnapshot(world);
        new AStarPathfinder(10000).findPath(snapshot, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.none());
        SealedSnapshot sealed = snapshot.seal();

        assertTrue(sealed.size() > 0, "the search must have read something");
        assertTrue(sealed.reads() > sealed.size(),
            "a search re-reads positions, which is the whole reason this class exists;"
                + " served " + sealed.reads() + " reads over " + sealed.size() + " positions");
    }

    @Test
    void aSealedSnapshotOfASearchCoversTheRouteItFound() {
        // What M5 gets: the region a search of this shape needs is exactly what the sealed
        // snapshot holds. Every position on the returned route reads back without going anywhere.
        FixtureWorld world = FixtureWorld.parse(WORLD);
        Pos start = world.start();
        Pos goal = world.goal();

        WorldSnapshot snapshot = new WorldSnapshot(world);
        PathResult result = new AStarPathfinder(10000).findPath(
            snapshot, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.none());
        SealedSnapshot sealed = snapshot.seal();

        for (int i = 0; i < result.path().size(); i++) {
            Pos step = result.path().get(i);
            assertTrue(sealed.covers(step.x(), step.y(), step.z()),
                "the route ran through " + step + " but the snapshot has no answer there");
            assertEquals(world.at(step.x(), step.y(), step.z()),
                sealed.at(step.x(), step.y(), step.z()), "at " + step);
        }
    }

    /** Guards the fixture itself: a world whose start or goal failed to parse proves nothing. */
    @Test
    void theFixtureHasBothMarkers() {
        FixtureWorld world = FixtureWorld.parse(WORLD);

        assertEquals(new Pos(0, 64, 0), world.start());
        assertEquals(new Pos(9, 64, 4), world.goal());
    }

    /**
     * A snapshot must be usable everywhere a {@link BlockSource} is, since that is the only type
     * the search knows about. Assigning to the interface is the assertion.
     */
    @Test
    void aWorldSnapshotIsABlockSource() {
        FixtureWorld world = FixtureWorld.parse(WORLD);
        BlockSource source = new WorldSnapshot(world);

        assertEquals(world.minY(), source.minY());
        assertEquals(world.maxY(), source.maxY());
    }
}
```

- [ ] **Step 2: Run it and verify it passes**

Run: `./gradlew :core-pathfinder:test --tests '*SnapshotSearchTest*'`

Expected: PASS, 5 tests.

**This fixture was run before the plan was written.** It parses to `start=(0, 64, 0)`, `goal=(9, 64, 4)`, and searching it live gives `FOUND, 14 steps, 30 expanded, cost 46.326800000000006`. If you see anything else, the text art was transcribed wrongly — **report it rather than editing an assertion to match**. Columns run +X and rows run +Z, and the trailing `y=65`/`y=66` slices must be present or every square has zero head clearance and the search returns `NO_PATH` over open ground.

- [ ] **Step 3: Write the failing probe test**

Add to `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`, immediately before `aGoalBeyondTheRenderLimitProducesAMapThatSaysItWasClamped`:

```java
    @Test
    void theSummaryReportsWhatTheSnapshotSavedOverReadingLive() {
        // C3's central claim, put where every future probe run measures it on real terrain
        // instead of on a fixture. The search reads each position it touches several times and
        // the snapshot turns all of them into one, so reads must exceed positions.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.summary().contains("snapshot "),
            "the summary must carry the snapshot's figures\n" + report.summary());
        assertTrue(report.summary().contains(" positions"), report.summary());
        assertTrue(report.summary().contains(" reads"), report.summary());
        assertFalse(report.summary().contains("snapshot 0 positions"),
            "a search that read nothing means the probe is not reading through the snapshot\n"
                + report.summary());
        assertTrue(report.summary().contains("x)"),
            "and the ratio, which is the number worth looking at\n" + report.summary());
    }
```

- [ ] **Step 4: Run it and verify it fails**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`

Expected: `theSummaryReportsWhatTheSnapshotSavedOverReadingLive` FAILS — the summary has no `"snapshot "` in it.

- [ ] **Step 5: Wire the probe**

In `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`, add two imports beside the existing `dev.continuo.core.BlockSource`:

```java
import dev.continuo.core.BlockSource;
import dev.continuo.core.SealedSnapshot;
import dev.continuo.core.WorldSnapshot;
```

Replace the search and the summary construction inside `run`. The block currently reads:

```java
        Pos start = new Pos(startX, startY, startZ);
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            world, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
```

Replace it with:

```java
        Pos start = new Pos(startX, startY, startZ);

        // The search reads through a snapshot; the render below does not. A render window can be
        // 64 blocks per axis and touches each cell once, so pushing it through the snapshot would
        // add a quarter of a million entries to save nothing. The repeat reads are in the search.
        WorldSnapshot snapshot = new WorldSnapshot(world);
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            snapshot, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
        SealedSnapshot sealed = snapshot.seal();
```

Leave the two `PathRenderer`/`ProbeBounds` lines exactly as they are — both keep taking `world`.

Then extend the summary. It currently ends:

```java
            .append(", budget ").append(nodeBudget);
```

Change that to:

```java
            .append(", budget ").append(nodeBudget)
            .append(", snapshot ").append(sealed.size()).append(" positions / ")
            .append(sealed.reads()).append(" reads");
        if (sealed.size() > 0) {
            // Locale.ROOT because this reaches a log file that gets read on other machines, and a
            // default locale writes "3,8x" where the reader expects "3.8x".
            summary.append(" (").append(String.format(java.util.Locale.ROOT, "%.1f",
                (double) sealed.reads() / sealed.size())).append("x)");
        }
```

Finally, correct the class javadoc's third paragraph, which currently argues that C3 is not a prerequisite. Replace:

```java
 * <p><b>Main thread only.</b> A live {@code BlockSource} inherits {@code IBlockView}'s delivery
 * window, and this reads through it synchronously. That is also why C3's {@code WorldSnapshot} is
 * not a prerequisite: a snapshot is what makes reads safe <em>off</em> the main thread, and
 * nothing here leaves it.
```

with:

```java
 * <p><b>Main thread only.</b> A live {@code BlockSource} inherits {@code IBlockView}'s delivery
 * window, and this reads through it synchronously.
 *
 * <p><b>The search reads through a {@link WorldSnapshot}; the render does not.</b> Off-thread
 * safety is not why — nothing here leaves the main thread. It is that a search reads each
 * position it touches several times over and the snapshot turns all of them into one SPI call,
 * which the summary reports so that every run measures the saving on real terrain. The render is
 * left reading live because its window touches each cell once and can be 64 blocks per axis.
```

- [ ] **Step 6: Run the probe tests and verify they pass**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`

Expected: PASS, including every pre-existing test. The path, cost and outcome assertions in `markingAGoalThenRunningFindsTheRoute` and `aRouteOnlyParkourCanTakeIsFoundThroughTheProbeItself` are the check that the snapshot changed nothing about the search.

- [ ] **Step 7: Prove the probe test bites**

Revert the probe to searching live — change `findPath(snapshot, ...)` back to `findPath(world, ...)` while leaving the snapshot construction and the summary in place.

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`

Expected: `theSummaryReportsWhatTheSnapshotSavedOverReadingLive` FAILS on the `"snapshot 0 positions"` assertion — the snapshot exists but nothing ever read through it. Restore and re-run.

- [ ] **Step 8: Full build**

Run: `./gradlew build --rerun-tasks`

Expected: BUILD SUCCESSFUL. Roughly 445 tests. Report the number you see, and confirm `checkCorePurity`, `checkCoreBytecode` and `checkDependencyDirection` all ran.

- [ ] **Step 9: Confirm the diff touched no adapter and no SPI**

Run: `git diff --stat master`

Expected: no file under `platform/` or `adapters/`. Spec §8 criterion 3 is a stated success condition, so check it rather than assume it.

- [ ] **Step 10: Commit**

```bash
git add core-pathfinder/src/test/java/dev/continuo/pathfinder/SnapshotSearchTest.java \
        runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "feat(c3): search through the snapshot, and report what it saved

Gives C3 a production consumer rather than shipping a class nothing
constructs - the situation that got B2 folded into M4 in the first place.
The search reads through the snapshot; ProbeBounds and PathRenderer keep
reading live, because a render window can be 64 blocks per axis and touches
each cell once, so routing it through the snapshot would add a quarter of a
million entries to save nothing.

The summary now carries positions, reads and the ratio, so every future
probe run measures C3's central claim on real terrain instead of on a
fixture. Locale.ROOT on the ratio: the default locale writes 3,8x.

SnapshotSearchTest pins that the decorator is invisible to the search -
same outcome, same expansions, same path, and cost compared at zero delta
rather than approximately, because A* is deterministic by construction.

Corrects PathProbe's class javadoc, which argued C3 was not a prerequisite
because nothing here leaves the main thread. Still true, and no longer the
whole reason.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Final: whole-branch review and merge

- [ ] **Step 1: Delete the throwaway harnesses**

Both are parked outside the source tree at
`C:\Users\qwert\AppData\Local\Temp\claude\C--projects-continuo\ba82ad40-122c-40a4-97f0-2d6d449bddd5\scratchpad\`.
Confirm neither is in the repo:

```bash
git ls-files | grep -i throwaway
```

Expected: no output. Spec §8 criterion 7.

- [ ] **Step 2: Whole-branch review**

Use `superpowers:requesting-code-review`. **The reviewer must execute mutations rather than read the diff, and must report what failed to fail.** That has found what per-task reviews could not for four consecutive sub-projects, and on the C1a branch it caught two tests that were named for a property they did not test. Five of that branch's twenty mutations were not caught, and those five were the informative ones.

Specific things to point the reviewer at:

- **`covers()`'s four cases.** Rows 2 and 4 of spec §4.5 both return `UNKNOWN` from `at()`. Any test that asserts only on `at()` cannot separate them.
- **`PositionKey`'s bit layout.** A shift that moves an axis by one bit still round-trips. Only the three literal assertions in `PositionKeyTest` catch it, and only `distinctPositionsGetDistinctKeys` catches an aliasing collision.
- **The `UNKNOWN`-is-stored rule.** Skipping the `put` for `UNKNOWN` is the plausible wrong version and breaks two separate guarantees at once.
- **Safe publication.** `SealedSnapshot`'s immutability argument holds only if `WorldSnapshot` genuinely cannot write to the map after `seal()`. Check the invalidation, not the intent.

- [ ] **Step 3: Merge**

`--no-ff` merge commit with `VERIFIED` and `NOT VERIFIED` sections, matching `git show a534292`. `master` is local-only, so `git pull` is **not** part of the flow, and **do not push** — the remote and CI are off-limits.

`NOT VERIFIED` must at minimum record:

- Nothing has been run in a Minecraft client unless a probe run happened. The summary's snapshot figures are the thing to look at when one does.
- The stability property is **latent**: a synchronous main-thread search already sees a stable world, so nothing exercises it until a search spans more than one tick.
- `covers()` has no production consumer. M5 is its first, and M5 does not exist.
- Whatever the mutation round found that did not fail.
