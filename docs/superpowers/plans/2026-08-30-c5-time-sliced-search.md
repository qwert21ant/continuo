# C5 — Time-sliced search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a pathfinding search survive across client ticks without freezing the game, by storing the world snapshot in 4×4×4 sections behind a last-section memo and making both the A\* search and the segmented run resumable in bounded slices.

**Architecture:** Three layers, bottom-up. `:core` gets a `SectionStore` that replaces `HashMap<Long, BlockData>` inside `WorldSnapshot`/`SealedSnapshot`. `:core-pathfinder` lifts `AStarPathfinder.findPath`'s loop state into a `Search` object with `advance(int maxNodes)`, and does the same one level up for `SegmentedSearch` with a `Run`. `:runtime`'s `PathProbe` becomes the consumer that drives a `Run` one slice per tick. No threads anywhere; every SPI call stays on the client main thread.

**Tech Stack:** Java 8 bytecode (machine-checked), JUnit 5, Gradle 9.6.1, no new dependencies.

**Spec:** [`docs/superpowers/specs/2026-08-30-c5-time-sliced-search-design.md`](../specs/2026-08-30-c5-time-sliced-search-design.md)

## Global Constraints

- **Java 8 bytecode, machine-checked.** No `var`, no records, no `List.of`, no text blocks, no switch expressions. **No lambdas in main source**, and none at all in the 1.7.10 adapter. Tests use anonymous `org.junit.jupiter.api.function.Executable` classes — this is house style, not a defect.
- Write explicit generic type arguments (`new HashMap<Long, BlockData>()`), matching the surrounding code.
- **`GRADLE_USER_HOME` is already `C:\GradleHome`.** Never set, export or override it.
- **Gate on `./gradlew build`, never `:test`.** Javadoc is build-failing under `-Xdoclint:all,-missing -Xwerror`, and **a dead `{@link}` fails as hard as a missing symbol**. `:test` never runs javadoc, so a green `:test` can hide a broken build. Never write a `{@link}` to a class a later task creates — use `{@code}`.
- **Never run `./gradlew clean`** — it destroys the 1.7.10 decompiled sources. Use `./gradlew build --rerun-tasks`.
- **Work on a branch in place. Never a `git worktree`** — a fresh worktree has no decompiled sources.
- Files are **CRLF**. Multi-line `sed`/`perl` patterns written with `\n` match nothing; prefer single-line edits or the `Edit` tool.
- **Test counts are only valid after a full `./gradlew build --rerun-tasks`.** Filtered runs corrupt the XML. Baseline at the start of this plan: **484 tests, 0 skipped, 0 failures, 0 errors, 82 tasks, 52 TEST-\*.xml files.**
- **No new module and no new dependency.** Nothing in `settings.gradle.kts` or `allowedProjectDependencies` changes.
- **Do not touch `MovementRegistry`'s sort-then-register ordering.** `ServiceLoader` order is unspecified; the registry sorts by `id()` before registering and preserves registration order otherwise. Do not "simplify" that into one sort.
- **Adapters have no tests and cannot get any.** Every Minecraft API claim must be verified against the decompiled sources on disk (both versions are greppable in the Gradle caches). Review is the only gate.
- **Report discrepancies, do not adjust.** If a brief in this plan contradicts the code you find, stop and say so rather than making it fit. Three briefs were wrong in C4 and all three were caught this way.

---

## File Structure

| File | Responsibility |
|---|---|
| `core/src/main/java/dev/continuo/core/SectionStore.java` | **New.** 4×4×4 `BlockData[64]` sections keyed by packed section coordinate, with a last-section memo. The only place block storage layout is decided. |
| `core/src/main/java/dev/continuo/core/WorldSnapshot.java` | Modified. Swaps its `HashMap` for a `SectionStore`. Public contract unchanged; gains `slots()`. |
| `core/src/main/java/dev/continuo/core/SealedSnapshot.java` | Modified. Same swap. Gains `slots()`. Its thread-safety claim narrows — see Task 1 Step 7. |
| `core/src/test/java/dev/continuo/core/SectionStoreTest.java` | **New.** The store's own contract, including the four `covers()` cases and asymmetric-offset coverage. |
| `core/src/test/java/dev/continuo/core/SectionShapeSweepTest.java` | **New.** The committed calibration table behind the 4×4×4 choice. |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/Search.java` | **New.** One A\* search's whole state, advanced in bounded slices. |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java` | Modified. `findPath` becomes `begin(...)` + `advance(Integer.MAX_VALUE)`. |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java` | **New.** One segmented run's state: the current `Search`, the accumulated route, cancellation. |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java` | Modified. `run(...)` becomes `begin(...)` + `advance(Integer.MAX_VALUE)`. |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/SliceEquivalenceTest.java` | **New.** D7, exhaustively, for both `Search` and `Run`. |
| `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` | Modified. Gains `start`/`advance`/`cancel`; `run` is reimplemented on them and keeps its signature and behaviour. |
| `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` | Modified by addition only. Every existing test must pass unchanged. |
| `adapters/adapter-fabric-1.21.11/.../ContinuoFabricMod.java` | Modified. One block: `run` → `start`, plus a per-tick `advance`. |
| `adapters/adapter-forge-1.7.10/.../ContinuoForgeMod.java` | Modified. The same change, symmetrically. |

**One refinement of the spec, recorded here rather than silently.** Spec §7.2 says the existing `probe.onLevel(...)` call *becomes* the tick hook. It does not: `onLevel` is called before the null-player check, and an advance needs no position at all once the run has started. So `onLevel` keeps its signature and gains cancellation, and a sibling `probe.advance()` is added inside the same per-tick lambda, after the null check. This is strictly smaller than what the spec described.

---

## Task 1: The section store

**Files:**
- Create: `core/src/main/java/dev/continuo/core/SectionStore.java`
- Create: `core/src/test/java/dev/continuo/core/SectionStoreTest.java`
- Modify: `core/src/main/java/dev/continuo/core/WorldSnapshot.java`
- Modify: `core/src/main/java/dev/continuo/core/SealedSnapshot.java`

**Interfaces:**
- Consumes: `PositionKey.pack(int, int, int)`, `BlockData.UNKNOWN` — both already exist and are public in `dev.continuo.core`.
- Produces: package-private `SectionStore` with `BlockData get(int,int,int)`, `void put(int,int,int,BlockData)`, `boolean has(int,int,int)`, `int size()`, `long slots()`. Public `WorldSnapshot.slots()` and `SealedSnapshot.slots()` returning `long`. Task 2 uses `SectionStore` directly; Task 5 reads `slots()` through the snapshots.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/SectionStoreTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionStoreTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData AIR = new BlockData(
        BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    @Test
    void aStoredBlockComesBackFromTheSamePosition() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);

        assertSame(STONE, store.get(3, 70, 4));
        assertTrue(store.has(3, 70, 4));
        assertEquals(1, store.size());
    }

    @Test
    void anUnstoredPositionIsUnknownAndNotHeld() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);

        assertSame(BlockData.UNKNOWN, store.get(9, 70, 9));
        assertFalse(store.has(9, 70, 9),
            "never-read and read-as-UNKNOWN must stay distinguishable; covers() is built on it");
    }

    @Test
    void aStoredUnknownIsHeldRatherThanLookingUnread() {
        // The distinction covers() exists for. An unloaded chunk answered UNKNOWN at the moment it
        // was read, and that is a real answer; a position nobody looked at is not.
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, BlockData.UNKNOWN);

        assertSame(BlockData.UNKNOWN, store.get(3, 70, 4));
        assertTrue(store.has(3, 70, 4), "a stored UNKNOWN is covered; an absent one is not");
        assertEquals(1, store.size());
    }

    @Test
    void theThreeAxesAreNotInterchangeable() {
        // The offset packs three coordinates into one array index, and a transposed pair is
        // invisible to any test whose coordinates are symmetric. These three positions share a
        // section and differ only in which axis carries the odd value.
        SectionStore store = new SectionStore();
        store.put(1, 0, 0, STONE);
        store.put(0, 1, 0, AIR);

        assertSame(STONE, store.get(1, 0, 0), "x=1 must not alias y=1 or z=1");
        assertSame(AIR, store.get(0, 1, 0), "y=1 must not alias x=1 or z=1");
        assertSame(BlockData.UNKNOWN, store.get(0, 0, 1), "z=1 was never stored");
        assertFalse(store.has(0, 0, 1));
    }

    @Test
    void negativeCoordinatesLandInTheirOwnSections() {
        // Arithmetic shift floors toward negative infinity, so -1 >> 2 is -1 and 0 >> 2 is 0:
        // -1 and 0 are in different sections. A store using division would put them in the same
        // one and silently overwrite.
        SectionStore store = new SectionStore();
        store.put(-1, -1, -1, STONE);
        store.put(0, 0, 0, AIR);

        assertSame(STONE, store.get(-1, -1, -1));
        assertSame(AIR, store.get(0, 0, 0));
        assertEquals(2, store.size());
    }

    @Test
    void overwritingAPositionDoesNotCountItTwice() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);
        store.put(3, 70, 4, AIR);

        assertSame(AIR, store.get(3, 70, 4));
        assertEquals(1, store.size(), "size counts positions, not writes");
    }

    @Test
    void readingTheSameSectionRepeatedlyIsStillCorrect() {
        // The memo is the whole point of this class, and a memo that goes stale returns another
        // position's block. Sixty-four positions in one section, read in an order that revisits.
        SectionStore store = new SectionStore();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    store.put(x, y, z, new BlockData(
                        BlockShape.FULL, x * 100 + y * 10 + z, Fluid.NONE,
                        EnumSet.noneOf(BlockTag.class)));
                }
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        assertEquals(x * 100 + y * 10 + z, store.get(x, y, z).collisionTop(),
                            1.0e-9, "(" + x + ", " + y + ", " + z + ") on pass " + pass);
                    }
                }
            }
        }
    }

    @Test
    void crossingBetweenTwoSectionsRepeatedlyDoesNotStickToOne() {
        // The memo failure that a single-section test cannot see: hold the first section and never
        // update it, and every read in the second returns UNKNOWN.
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);
        store.put(4, 0, 0, AIR);

        for (int i = 0; i < 5; i++) {
            assertSame(STONE, store.get(0, 0, 0), "pass " + i);
            assertSame(AIR, store.get(4, 0, 0), "pass " + i);
        }
    }

    @Test
    void slotsCountsWhatWasAllocatedRatherThanWhatWasStored() {
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);

        assertEquals(1, store.size());
        assertEquals(64, store.slots(), "one 4x4x4 section is allocated whole");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.SectionStoreTest'`
Expected: FAIL — compilation error, `SectionStore` does not exist.

- [ ] **Step 3: Write `SectionStore`**

Create `core/src/main/java/dev/continuo/core/SectionStore.java`:

```java
package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Block storage for a snapshot: 4&times;4&times;4 sections, with the last one touched held in a
 * field.
 *
 * <p><b>Why not a map keyed by position.</b> That is what this replaces, and it cost a boxed
 * {@code Long} allocation and a hash lookup on every read — 1.84 million of them in one measured
 * in-game search, which was most of that search's time. A search reads with great locality: the
 * positions a single movement inspects are almost always within a few blocks of each other. So a
 * read here is a section-key compare that usually hits, then an array index.
 *
 * <p><b>Why 4&times;4&times;4 and not Minecraft's 16&times;16&times;16.</b> Measured over the
 * design's §3.2 sweep: the 16-cube is the fastest shape and allocates 32.6&times; the references it
 * stores, because a search touches a section sparsely and a section is allocated whole. 4&times;4
 * &times;4 keeps 35 of the 41 percentage points for a tenth of the waste, at 30% occupancy rather
 * than 3%.
 *
 * <p><b>A {@code null} slot means never read.</b> A snapshot stores {@link BlockData#UNKNOWN} — a
 * real object — for a position it read and could not answer for, so {@code null} and
 * {@code UNKNOWN} stay distinguishable and {@link #has} can tell them apart. That distinction is
 * what {@code SealedSnapshot.covers} is built on.
 *
 * <p><b>The memo makes this stateful, so an instance belongs to one reader.</b> Two threads reading
 * one store would race on the memo fields and hand each other another position's block, with no
 * exception to notice. Nothing in this project reads a store from more than one thread; this note
 * is what keeps that true.
 */
final class SectionStore {

    /** Bits of each coordinate that index within a section: 2 gives a 4&times;4&times;4 section. */
    private static final int BITS = 2;
    private static final int MASK = (1 << BITS) - 1;
    private static final int SECTION_SIZE = 1 << (BITS * 3);

    private final Map<Long, BlockData[]> sections = new HashMap<Long, BlockData[]>();

    private int size;

    /**
     * The last section looked up, and its key. {@code null} means no lookup has happened yet, which
     * is why the key alone cannot be the guard: key 0 is a real section.
     */
    private BlockData[] memoSection;
    private long memoKey;
    private boolean memoValid;

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block stored, or {@link BlockData#UNKNOWN} if nothing was
     */
    BlockData get(int x, int y, int z) {
        BlockData[] section = section(x, y, z);
        if (section == null) {
            return BlockData.UNKNOWN;
        }
        BlockData held = section[offset(x, y, z)];
        return held == null ? BlockData.UNKNOWN : held;
    }

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return whether a value was stored here, which {@link BlockData#UNKNOWN} being a real stored
     *         value does not make true on its own
     */
    boolean has(int x, int y, int z) {
        BlockData[] section = section(x, y, z);
        return section != null && section[offset(x, y, z)] != null;
    }

    /**
     * Stores a block, replacing anything already there.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @param value the block; never {@code null}, because {@code null} is this class's "never read"
     */
    void put(int x, int y, int z, BlockData value) {
        long key = sectionKey(x, y, z);
        BlockData[] section;
        if (memoValid && memoKey == key) {
            section = memoSection;
        } else {
            section = sections.get(Long.valueOf(key));
            memoKey = key;
            memoSection = section;
            memoValid = true;
        }
        if (section == null) {
            section = new BlockData[SECTION_SIZE];
            sections.put(Long.valueOf(key), section);
            // The memo was just set to the absent section; point it at the real one, or the next
            // read of this same section returns UNKNOWN for everything in it.
            memoSection = section;
        }
        int at = offset(x, y, z);
        if (section[at] == null) {
            size++;
        }
        section[at] = value;
    }

    /** @return how many positions hold a value */
    int size() {
        return size;
    }

    /** @return how many array slots are allocated, which is {@link #size} plus the waste */
    long slots() {
        return (long) sections.size() * SECTION_SIZE;
    }

    private BlockData[] section(int x, int y, int z) {
        long key = sectionKey(x, y, z);
        if (memoValid && memoKey == key) {
            return memoSection;
        }
        BlockData[] found = sections.get(Long.valueOf(key));
        memoKey = key;
        memoSection = found;
        memoValid = true;
        return found;
    }

    private static long sectionKey(int x, int y, int z) {
        return PositionKey.pack(x >> BITS, y >> BITS, z >> BITS);
    }

    private static int offset(int x, int y, int z) {
        return ((y & MASK) << (BITS * 2)) | ((z & MASK) << BITS) | (x & MASK);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :core:test --tests 'dev.continuo.core.SectionStoreTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Swap `WorldSnapshot`'s storage**

In `core/src/main/java/dev/continuo/core/WorldSnapshot.java`:

Replace the field `private Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();` with:

```java
    /** Handed to the sealed snapshot rather than copied; {@code null} once that has happened. */
    private SectionStore blocks = new SectionStore();
```

Replace the body of `at` after the bounds check with:

```java
        BlockData cached = blocks.get(x, y, z);
        if (blocks.has(x, y, z)) {
            return cached;
        }
        BlockData fresh = live.at(x, y, z);
        blocks.put(x, y, z, fresh);
        return fresh;
```

**Report rather than adjust if this looks wrong to you:** it must be `has`, not `cached != null` and not `cached != UNKNOWN`. `at`'s javadoc says a stored `UNKNOWN` is kept verbatim and never re-asked, because "re-asking would both break the point-in-time guarantee and spend SPI calls on exactly the positions a search probes hardest". A `cached != UNKNOWN` test would re-ask every one of them.

Add after `size()`:

```java
    /**
     * @return how many array slots the store has allocated, which is {@link #size} plus whatever
     *         the sections hold that was never read. Reported by the probe so the storage shape's
     *         occupancy is measured on real terrain rather than assumed
     * @throws IllegalStateException if this snapshot has been sealed
     */
    public long slots() {
        requireFilling();
        return blocks.slots();
    }
```

Delete the now-unused `java.util.HashMap` and `java.util.Map` imports, and the `Long key = ...` line.

- [ ] **Step 6: Swap `SealedSnapshot`'s storage**

In `core/src/main/java/dev/continuo/core/SealedSnapshot.java`, change the field, the constructor parameter, `at` and `covers`:

```java
    private final SectionStore blocks;
```

```java
    SealedSnapshot(SectionStore blocks, int minY, int maxY, int reads) {
```

```java
    @Override
    public BlockData at(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return BlockData.UNKNOWN;
        }
        return blocks.get(x, y, z);
    }
```

```java
    public boolean covers(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return true;
        }
        return blocks.has(x, y, z);
    }
```

Add beside `size()`:

```java
    /** @return how many array slots the store allocated; {@link #size} plus the sections' waste */
    public long slots() {
        return blocks.slots();
    }
```

Delete the `java.util.Map` import.

- [ ] **Step 7: Narrow `SealedSnapshot`'s thread-safety claim**

Its class javadoc currently opens with *"Readable from any thread"* and argues from final-field freeze. A `SectionStore` carries a mutable memo, so that argument no longer holds. Replace that paragraph with:

```java
 * <p><b>Read by one thread at a time.</b> Its storage keeps a memo of the last section touched, so
 * two threads reading one instance would race on that field and hand each other another position's
 * block. This is a narrowing of what this class promised before C5: the promise existed for an
 * off-thread search, and C5 D2 rejected that design on measurement — the fill cannot leave the main
 * thread under global rule 1 whatever the search does, and the fault protocol an off-thread search
 * needs measured at 131 to 385 tick round trips per path. Nothing in this project reads a snapshot
 * from more than one thread.
```

**This is a deliberate weakening of a public contract.** Call it out in the commit message; do not let it pass as an incidental edit.

- [ ] **Step 8: Run the whole `:core` suite, with exactly one permitted test edit**

`SealedSnapshotTest.fixture()` (lines 22–28) constructs a `SealedSnapshot` directly, passing the `Map` whose type this task changes. It is the **only** test that does, and it must be updated or `:core` will not compile. Replace its three construction lines:

```java
    private static SealedSnapshot fixture() {
        SectionStore blocks = new SectionStore();
        blocks.put(1, 70, 2, STONE);
        blocks.put(1, 71, 2, BlockData.UNKNOWN);
        return new SealedSnapshot(blocks, -64, 320, 4242);
    }
```

and drop its now-unused `java.util.HashMap` and `java.util.Map` imports.

**That is the entire permitted edit to any existing test in this task.** Every assertion and every test body in `SealedSnapshotTest` and `WorldSnapshotTest` stays byte-for-byte as it is. If anything else needs changing to pass, **stop and report it** — it means the storage swap changed behaviour, which it must not. Preserving those assertions untouched is the cheapest proof the contract survived.

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 9: Full build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL. Confirm the test total is **493** (484 + 9).

- [ ] **Step 10: Commit**

```bash
git add core/src/main/java/dev/continuo/core/SectionStore.java core/src/test/java/dev/continuo/core/SectionStoreTest.java core/src/main/java/dev/continuo/core/WorldSnapshot.java core/src/main/java/dev/continuo/core/SealedSnapshot.java
git commit -m "feat(c5): store snapshot blocks in 4x4x4 sections behind a last-section memo"
```

---

## Task 2: The section shape calibration table

**Files:**
- Create: `core/src/test/java/dev/continuo/core/SectionShapeSweepTest.java`

**Interfaces:**
- Consumes: `SectionStore` from Task 1 — but note it is fixed at 4×4×4 and has no shape parameter. This task adds a **test-local** parameterised store to sweep against, and asserts the shipped one is the right pick. It does not make `SectionStore` configurable; a shape knob nothing sets is a knob that rots.
- Produces: nothing other tasks consume.

- [ ] **Step 1: Write the test**

Create `core/src/test/java/dev/continuo/core/SectionShapeSweepTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table behind {@code SectionStore}'s 4x4x4 shape, in the shape of C4's MinProgressSweepTest:
 * a committed calibration rather than a paragraph.
 *
 * <p>This asserts <b>occupancy</b>, not speed. A wall-clock assertion in the suite would be flaky
 * on CI and on a loaded machine, and the design's §3.2 already records that absolute times from a
 * benchmark like this are not comparable between runs — its at() call site goes megamorphic where
 * production's does not. Occupancy is exact, deterministic, and is the axis on which 4x4x4 was
 * chosen over Baritone's 16x16x16.
 */
class SectionShapeSweepTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** A store whose section shape is a parameter, for the sweep only. */
    private static final class ShapedStore {

        private final Map<Long, Object> sections = new HashMap<Long, Object>();
        private final int bits;
        private final int sectionSize;
        private int size;

        ShapedStore(int bits) {
            this.bits = bits;
            this.sectionSize = 1 << (bits * 3);
        }

        void put(int x, int y, int z) {
            Long key = Long.valueOf(PositionKey.pack(x >> bits, y >> bits, z >> bits));
            boolean[] section = (boolean[]) sections.get(key);
            if (section == null) {
                section = new boolean[sectionSize];
                sections.put(key, section);
            }
            int mask = (1 << bits) - 1;
            int at = ((y & mask) << (bits * 2)) | ((z & mask) << bits) | (x & mask);
            if (!section[at]) {
                size++;
            }
            section[at] = true;
        }

        long slots() {
            return (long) sections.size() * sectionSize;
        }

        double occupancy() {
            return 100.0 * size / slots();
        }
    }

    /**
     * The positions a search actually touches, in the shape real terrain produces: a thin
     * horizontal band a few blocks tall over a wide area, which is what makes a tall section waste
     * so much. Deterministic, so the table is reproducible.
     */
    private static List<int[]> corridor() {
        List<int[]> positions = new ArrayList<int[]>();
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                for (int y = 62; y <= 66; y++) {
                    positions.add(new int[] {x, y, z});
                }
            }
        }
        return positions;
    }

    @Test
    void fourIsTheShapeThatKeepsMostOfTheSpeedForLeastOfTheWaste() {
        List<int[]> positions = corridor();

        ShapedStore four = new ShapedStore(2);
        ShapedStore eight = new ShapedStore(3);
        ShapedStore sixteen = new ShapedStore(4);
        for (int i = 0; i < positions.size(); i++) {
            int[] p = positions.get(i);
            four.put(p[0], p[1], p[2]);
            eight.put(p[0], p[1], p[2]);
            sixteen.put(p[0], p[1], p[2]);
        }

        // The finding, as an assertion rather than a comment: a bigger section wastes more, and
        // Baritone's 16-cube wastes most. Baritone does not pay this because it reads Minecraft's
        // already-allocated chunks and stores nothing per block; we allocate our own copy.
        assertTrue(four.occupancy() > eight.occupancy(),
            "4x4x4 " + four.occupancy() + "% vs 8x8x8 " + eight.occupancy() + "%");
        assertTrue(eight.occupancy() > sixteen.occupancy(),
            "8x8x8 " + eight.occupancy() + "% vs 16x16x16 " + sixteen.occupancy() + "%");
        assertTrue(sixteen.slots() > four.slots() * 3,
            "the 16-cube must allocate several times what the 4-cube does, or the trade this"
                + " constant was chosen on has changed; 4x4x4 " + four.slots()
                + " vs 16x16x16 " + sixteen.slots());
    }

    @Test
    void theShippedStoreUsesTheSweptShape() {
        // Pins the constant to the sweep. SectionStore's BITS is private, so this asserts it
        // through the only thing that observes it: one position allocates exactly 64 slots.
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);

        assertEquals(64, store.slots(),
            "SectionStore must be 4x4x4, the shape the sweep above picks. Changing BITS without"
                + " re-running that sweep is what this assertion exists to stop");
    }
}
```

- [ ] **Step 2: Run it and confirm it passes**

Run: `./gradlew :core:test --tests 'dev.continuo.core.SectionShapeSweepTest'`
Expected: PASS, 2 tests.

- [ ] **Step 3: Mutation-check the pin**

Temporarily change `SectionStore`'s `BITS` from `2` to `3`. Run the test. Expected: `theShippedStoreUsesTheSweptShape` FAILS with 512 ≠ 64. **Revert `BITS` to 2** and re-run to confirm PASS. Record both in the commit message.

- [ ] **Step 4: Full build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, **495 tests** (493 + 2).

- [ ] **Step 5: Commit**

```bash
git add core/src/test/java/dev/continuo/core/SectionShapeSweepTest.java
git commit -m "test(c5): commit the section shape sweep that picks 4x4x4"
```

---

## Task 3: The resumable A* search

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Search.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java:184-284`

**Interfaces:**
- Consumes: `PathNode`, `QueuedNode`, `QueuedNodeOrder`, `SegmentSelector`, `Pos`, `Goal`, `PathResult`, `PathOutcome` — all existing, all package-private or public in `dev.continuo.pathfinder`. `MutableExpansionContext(BlockSource)` and `ctx.moveTo(int,int,int)` from `dev.continuo.movement`.
- Produces: `public final class Search` with `boolean advance(int maxNodes)`, `PathResult result()`, `boolean finished()`, `int expandedCount()`. `AStarPathfinder.begin(BlockSource, int, int, int, Goal, CapabilitySet)` returning `Search`. Task 4 tests both; Task 5's `Run` drives them.

- [ ] **Step 1: Write the failing test**

Add to a new file `core-pathfinder/src/test/java/dev/continuo/pathfinder/SliceEquivalenceTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.CapabilitySet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7: a sliced search returns bit-identical results to an unsliced one, at every slice size.
 *
 * <p>This is the whole proof obligation for slicing, and it is cheap enough to test exhaustively
 * rather than at a sample. Slice size 1 is the interesting one: a boundary between every pair of
 * expansions is the strongest available test that suspension leaves no state in a local.
 */
class SliceEquivalenceTest {

    private static final int[] SLICE_SIZES = {1, 2, 3, 7, 64, 1000, Integer.MAX_VALUE};

    /**
     * Committed real terrain rather than a hand-written fixture, and that is a correction rather
     * than a preference.
     *
     * <p>A hand-written world whose topmost slice is the one the player walks on gives every square
     * zero head clearance: a position outside a {@code FixtureWorld}'s declared extent reads as
     * {@code UNKNOWN}, never air, and {@code Standability.standable} requires the block above the
     * feet to be passable. The search then returns {@code NO_PATH} over what looks like open
     * ground — and a slice-equivalence test built on it passes vacuously, every slice size agreeing
     * on {@code NO_PATH} after one expansion. {@code SnapshotSearchTest}'s own fixture carries the
     * same warning. {@code d-cliff} needs 273 expansions and is pinned by {@code BackoffTest} and
     * {@code SegmentedSearchTest} already, so it cannot quietly become unwalkable.
     */
    private static FixtureWorld cliff() {
        return TerrainFixture.load("d-cliff.txt");
    }

    private static Goal goalOf(FixtureWorld world) {
        return new GoalBlock(world.goal().x(), world.goal().y(), world.goal().z());
    }

    private static PathResult unsliced(FixtureWorld world) {
        return new AStarPathfinder(25000).findPath(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
    }

    private static PathResult sliced(FixtureWorld world, int sliceSize) {
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        int guard = 0;
        while (!search.advance(sliceSize)) {
            guard++;
            if (guard > 1000000) {
                throw new AssertionError("advance never finished at slice size " + sliceSize);
            }
        }
        return search.result();
    }

    @Test
    void everySliceSizeProducesTheIdenticalSearch() {
        FixtureWorld world = cliff();
        PathResult expected = unsliced(world);

        // The guard that stops this test passing vacuously, and it is not hypothetical: the first
        // fixture written for it was unwalkable, so both sides returned NO_PATH after one
        // expansion and every assertion below held while proving nothing about slicing.
        assertEquals(PathOutcome.FOUND, expected.outcome(),
            "the fixture must be pathable, or this compares two NO_PATHs and passes for free");
        assertTrue(expected.nodesExpanded() > 100,
            "the fixture must need enough search that a slice boundary falls inside it; got "
                + expected.nodesExpanded() + " expansions");

        for (int i = 0; i < SLICE_SIZES.length; i++) {
            int slice = SLICE_SIZES[i];
            PathResult actual = sliced(world, slice);

            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0,
                "cost must be bit-identical at slice " + slice);
            assertEquals(expected.path(), actual.path(), "path at slice " + slice);
            // The expansion sequence, not merely its length. A slice boundary that reordered the
            // open set would keep the count and change the order, and the path could still come
            // back the same on a world with one optimal route.
            assertEquals(expected.expanded(), actual.expanded(),
                "expansion order at slice " + slice);
        }
    }

    @Test
    void aSearchThatHasNotFinishedRefusesToGiveAResult() {
        FixtureWorld world = cliff();
        final Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertFalse(search.finished(), "a search that has not advanced cannot be finished");
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                search.result();
            }
        });
    }

    @Test
    void suspendingDoesNotProduceAnOutcome() {
        // D6, the design's sharpest failure mode. If a slice boundary produced PARTIAL, every
        // search longer than one slice would trigger C4's backoff and return a worse route for a
        // reason with nothing to do with the budget C4 calibrated.
        FixtureWorld world = cliff();
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertFalse(search.advance(1), "one expansion cannot finish this search");
        assertFalse(search.finished());
        assertEquals(1, search.expandedCount(), "one slice of one node expands exactly one node");
    }

    @Test
    void advancingAFinishedSearchIsANoOp() {
        FixtureWorld world = cliff();
        Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        while (!search.advance(1000)) {
            // drive to completion
        }
        PathResult first = search.result();
        int expandedAfterFinish = search.expandedCount();

        assertTrue(search.advance(1000), "a finished search stays finished");
        assertEquals(expandedAfterFinish, search.expandedCount(),
            "advancing past the end must not expand another node");
        assertEquals(first.cost(), search.result().cost(), 0.0);
        List<Pos> path = search.result().path();
        assertEquals(first.path(), path, "the result must not change after it is settled");
    }

    @Test
    void aNonPositiveSliceIsRejectedRatherThanLoopingForever() {
        FixtureWorld world = cliff();
        final Search search = new AStarPathfinder(25000).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                search.advance(0);
            }
        });
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SliceEquivalenceTest'`
Expected: FAIL — compilation error, `Search` and `AStarPathfinder.begin` do not exist.

- [ ] **Step 3: Write `Search`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Search.java`. **This is a faithful lift of `AStarPathfinder.findPath`'s loop into fields — the statement order inside the loop must not change, because D7 depends on it.**

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MutableExpansionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One A* search, advanced in bounded slices rather than run to completion.
 *
 * <p><b>This is the same loop {@code AStarPathfinder} always ran, with its locals lifted into
 * fields.</b> Not a second implementation: {@code findPath} is this class driven with an unbounded
 * slice, so every existing caller and fixture exercises exactly this code. That is what makes the
 * design's D7 — a sliced search returns bit-identical results to an unsliced one — provable by
 * construction rather than by hope.
 *
 * <p><b>A slice boundary is not an outcome.</b> {@link #advance} returning {@code false} means "ask
 * again", nothing more: no {@link PathOutcome}, no partial path, no change to the node budget's
 * accounting. The node budget and the slice budget share a unit and are otherwise unrelated — the
 * first is a property of the search, the second of how it is being driven. Conflating them would
 * turn every search longer than one slice into a {@link PathOutcome#PARTIAL}, firing C4's backoff
 * for a reason that has nothing to do with the budget it was calibrated against.
 *
 * <p><b>Single-threaded.</b> It reads a {@code BlockSource} that may be filling from the live world,
 * so it inherits {@code IBlockView}'s main-thread delivery window, and its storage keeps a
 * last-section memo that two readers would race on.
 */
public final class Search {

    private final int nodeBudget;
    private final Goal goal;
    private final HeuristicRates rates;
    private final List<IMovementType> moves;
    private final SegmentSelector selector;
    private final Map<Long, PathNode> nodes = new HashMap<Long, PathNode>();
    private final List<Pos> expanded = new ArrayList<Pos>();
    private final PriorityQueue<QueuedNode> open =
        new PriorityQueue<QueuedNode>(64, QueuedNodeOrder.INSTANCE);
    private final MutableExpansionContext ctx;
    private final MoveSink sink;

    private int discovered;
    private PathNode current;
    private PathResult result;

    Search(BlockSource world, int startX, int startY, int startZ, Goal goal, CapabilitySet caps,
           int nodeBudget, double minProgressBlocks, ActiveMovements active) {
        this.nodeBudget = nodeBudget;
        this.goal = goal;
        this.rates = active.rates();
        this.moves = active.movements();

        double hStart = goal.heuristic(startX, startY, startZ, rates);
        this.selector = new SegmentSelector(hStart, minProgressBlocks * rates.horizontal());

        long startPacked = Pos.pack(startX, startY, startZ);
        PathNode start = new PathNode(startPacked);
        start.g = 0.0;
        nodes.put(Long.valueOf(startPacked), start);
        open.add(new QueuedNode(startPacked, hStart, 0.0, discovered++));

        this.ctx = new MutableExpansionContext(world);
        // An anonymous class rather than a lambda: main source is Java 8 bytecode and lambda-free
        // by house rule. It reads this.current, which is why current is a field.
        this.sink = new MoveSink() {
            @Override
            public void offer(int nx, int ny, int nz, double cost) {
                Long key = Long.valueOf(Pos.pack(nx, ny, nz));
                PathNode neighbour = nodes.get(key);
                if (neighbour == null) {
                    neighbour = new PathNode(key.longValue());
                    nodes.put(key, neighbour);
                }
                if (neighbour.closed) {
                    return;
                }
                double tentative = current.g + cost;
                if (tentative >= neighbour.g) {
                    return;
                }
                neighbour.g = tentative;
                neighbour.parent = current;
                // A fresh immutable entry, never a mutation of one already queued -- see
                // QueuedNode. The old entry stays in the heap and is discarded on poll,
                // because by then this node is closed.
                open.add(new QueuedNode(neighbour.packed,
                    tentative + goal.heuristic(nx, ny, nz, rates), tentative, discovered++));
            }
        };
    }

    /**
     * Expands up to {@code maxNodes} nodes.
     *
     * <p>A stale heap entry for a node already closed is discarded without spending any of the
     * slice: the budget counts expansions, exactly as {@code nodeBudget} does.
     *
     * @param maxNodes how many nodes this slice may expand; must be positive
     * @return whether the search has finished and {@link #result} is available
     * @throws IllegalArgumentException if {@code maxNodes} is not positive
     */
    public boolean advance(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive, got " + maxNodes);
        }
        if (result != null) {
            return true;
        }
        int remaining = maxNodes;
        while (!open.isEmpty()) {
            if (remaining == 0) {
                return false;
            }
            QueuedNode entry = open.poll();
            current = nodes.get(Long.valueOf(entry.packed));
            if (current.closed) {
                continue;
            }
            current.closed = true;
            remaining--;

            final int cx = Pos.unpackX(current.packed);
            final int cy = Pos.unpackY(current.packed);
            final int cz = Pos.unpackZ(current.packed);
            expanded.add(new Pos(cx, cy, cz));

            // h is recomputed rather than taken as entry.f - entry.g. The subtraction is exact,
            // but only by an argument about stale heap entries, and this project's reviews exist
            // to catch invariants that subtle. The saving is arithmetic that reads no world.
            selector.consider(current.packed, goal.heuristic(cx, cy, cz, rates));

            if (goal.isReached(cx, cy, cz)) {
                result = new PathResult(PathOutcome.FOUND, reconstruct(current), expanded,
                    current.g);
                return true;
            }
            if (expanded.size() >= nodeBudget) {
                if (selector.hasCandidate()) {
                    PathNode best = nodes.get(Long.valueOf(selector.candidate()));
                    result = new PathResult(PathOutcome.PARTIAL, reconstruct(best), expanded,
                        best.g);
                    return true;
                }
                result = new PathResult(PathOutcome.BUDGET_EXCEEDED,
                    Collections.<Pos>emptyList(), expanded, 0.0);
                return true;
            }

            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).expand(ctx, sink);
            }
        }

        result = new PathResult(PathOutcome.NO_PATH, Collections.<Pos>emptyList(), expanded, 0.0);
        return true;
    }

    /** @return whether this search has produced its result */
    public boolean finished() {
        return result != null;
    }

    /**
     * @return what the search produced
     * @throws IllegalStateException if it has not finished
     */
    public PathResult result() {
        if (result == null) {
            throw new IllegalStateException("this search has not finished;"
                + " advance(int) until it returns true");
        }
        return result;
    }

    /** @return how many nodes have been expanded so far, across every slice */
    public int expandedCount() {
        return expanded.size();
    }

    private static List<Pos> reconstruct(PathNode goalNode) {
        List<Pos> path = new ArrayList<Pos>();
        for (PathNode n = goalNode; n != null; n = n.parent) {
            path.add(Pos.unpack(n.packed));
        }
        Collections.reverse(path);
        return path;
    }
}
```

- [ ] **Step 4: Rewrite `findPath` on top of it**

In `AStarPathfinder.java`, replace the entire body of the six-argument `findPath` (currently lines 184–284, from the null checks to the closing brace) with:

```java
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal,
                               CapabilitySet caps) {
        Search search = begin(world, startX, startY, startZ, goal, caps);
        search.advance(Integer.MAX_VALUE);
        return search.result();
    }

    /**
     * Starts a search without expanding anything, so a caller can spend it a slice at a time.
     *
     * <p>{@link #findPath} is this method plus one unbounded slice. There is no second
     * implementation and no second loop — which is what makes a sliced search returning the same
     * answer as an unsliced one a property of the construction rather than of the tests.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return a search that has expanded nothing yet; never {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public Search begin(BlockSource world, int startX, int startY, int startZ, Goal goal,
                        CapabilitySet caps) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (caps == null) {
            throw new IllegalArgumentException("caps must not be null; use CapabilitySet.none()");
        }
        return new Search(world, startX, startY, startZ, goal, caps, nodeBudget,
            minProgressBlocks, registry.activeFor(caps));
    }
```

Then delete from `AStarPathfinder` the now-unused `reconstruct` method and these imports: `ActiveMovements`, `HeuristicRates`, `IMovementType`, `MoveSink`, `MutableExpansionContext`, `java.util.ArrayList`, `java.util.Collections`, `java.util.HashMap`, `java.util.Map`, `java.util.PriorityQueue`. Keep `IMovementRegistry`, `MovementRegistry`, `BlockSource`, `CapabilitySet`, `java.util.List` only if still referenced — check each, and let the compiler's unused-import behaviour not fool you, since javac does not warn on those. Run the build to confirm.

- [ ] **Step 5: Run the new test and the whole pathfinder suite**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS. **Every existing test in `:core-pathfinder` must pass unchanged**, including `OctileSearchTest`'s exact 257-expansion assertion and `BackoffTest`'s in-game-derived costs. If any needs editing, stop and report — the lift changed behaviour, which it must not.

- [ ] **Step 6: Mutation-check the slice boundary**

Apply each of these, confirm the named test fails, revert:

1. In `advance`, move `remaining--` to before `if (current.closed) continue;` → stale entries consume budget. Expected: `everySliceSizeProducesTheIdenticalSearch` still passes (this is an equivalent mutant for the *result*) but `suspendingDoesNotProduceAnOutcome`'s `expandedCount` assertion is unaffected too. **Record this as an equivalent mutant** — the result is genuinely unchanged, only the number of `advance` calls differs. Do not add a test for it.
2. In `advance`, change `if (result != null) return true;` to `return false;` → `advancingAFinishedSearchIsANoOp` fails.
3. In `advance`, move the `remaining == 0` check to after `open.poll()` → an entry is polled and dropped. Expected: `everySliceSizeProducesTheIdenticalSearch` fails at slice size 1.

- [ ] **Step 7: Full build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, **500 tests** (495 + 5).

- [ ] **Step 8: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/Search.java core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java core-pathfinder/src/test/java/dev/continuo/pathfinder/SliceEquivalenceTest.java
git commit -m "feat(c5): lift the A* loop into a search that advances in bounded slices"
```

---

## Task 4: The resumable segmented run

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java:51-92`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/SliceEquivalenceTest.java`

**Interfaces:**
- Consumes: `Search`, `AStarPathfinder.begin(...)`, `AStarPathfinder.registry()`, `AStarPathfinder.minProgressBlocks()` from Task 3 — the last two are package-private and stay so.
- Produces: `public final class Run` with `boolean advance(int maxNodes)`, `SegmentedResult result()`, `boolean finished()`, `void cancel()`, `boolean cancelled()`, `int expandedCount()`. `SegmentedSearch.begin(BlockSource, int, int, int, Goal, CapabilitySet)` returning `Run`. Task 5's probe drives exactly this.

- [ ] **Step 1: Write the failing test**

Append to `SliceEquivalenceTest.java` (inside the class):

```java
    /**
     * A cave climb, for the multi-segment equivalence: 726 expansions of real terrain, where the
     * A* equivalence above uses {@code d-cliff}'s 273. Both are committed fixtures already pinned
     * by C4's own tests, so neither can quietly become unwalkable.
     */
    private static FixtureWorld cave() {
        return TerrainFixture.load("b-cave-climb.txt");
    }

    /**
     * The budget C4 already proved segments {@code d-cliff}: 232 is 84% of its 273 expansions, and
     * {@code SegmentedSearchTest} pins the two-segment result it produces at cost
     * 274.41707435261833. Reusing that number rather than inventing one means this test inherits a
     * configuration known to segment instead of hoping one does.
     */
    private static final int SEGMENTING_BUDGET = 232;

    private static SegmentedResult unslicedRun(FixtureWorld world, int budget) {
        return new SegmentedSearch(new AStarPathfinder(budget)).run(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
    }

    private static SegmentedResult slicedRun(FixtureWorld world, int budget, int sliceSize) {
        Run run = new SegmentedSearch(new AStarPathfinder(budget)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        int guard = 0;
        while (!run.advance(sliceSize)) {
            guard++;
            if (guard > 1000000) {
                throw new AssertionError("run never finished at slice size " + sliceSize);
            }
        }
        return run.result();
    }

    @Test
    void everySliceSizeProducesTheIdenticalSegmentedRun() {
        FixtureWorld world = cave();
        SegmentedResult expected = unslicedRun(world, 25000);

        // The same vacuity guard the A* equivalence test carries, for the same reason: an
        // unwalkable fixture makes every slice size agree on NO_PATH and passes for free.
        assertEquals(PathOutcome.FOUND, expected.outcome(),
            "the fixture must be pathable, or this compares two NO_PATHs");
        assertTrue(expected.expanded().size() > 100,
            "the fixture must need enough search that a slice boundary falls inside it; got "
                + expected.expanded().size() + " expansions");

        for (int i = 0; i < SLICE_SIZES.length; i++) {
            int slice = SLICE_SIZES[i];
            SegmentedResult actual = slicedRun(world, 25000, slice);

            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0, "cost at slice " + slice);
            assertEquals(expected.path(), actual.path(), "path at slice " + slice);
            assertEquals(expected.expanded(), actual.expanded(), "expansions at slice " + slice);
            assertEquals(expected.segments(), actual.segments(), "segments at slice " + slice);
        }
    }

    @Test
    void aSliceBoundaryLandingOnASegmentBoundaryIsNotObservable() {
        // Spec §5.5. A slice boundary and a segment boundary can coincide, and when they do the
        // run's PARTIAL must be the segment's own, never an artefact of tick scheduling. The
        // budget is chosen so the first segment ends mid-run, then swept across slice sizes that
        // bracket that expansion count from both sides.
        FixtureWorld world = cliff();
        SegmentedResult expected = unslicedRun(world, SEGMENTING_BUDGET);
        assertTrue(expected.segments() > 1,
            "d-cliff at budget " + SEGMENTING_BUDGET + " must segment or this test proves nothing;"
                + " got " + expected.segments() + " segments");

        // 1..40 walk a boundary through the first segment's interior; 231, 232 and 233 bracket the
        // exact expansion where the first segment ends, which is the coincidence this test exists
        // for; 464 swallows two whole segments in one slice.
        int[] boundarySlices = {1, 2, 3, 7, 40, 115, 231, 232, 233, 464};
        for (int b = 0; b < boundarySlices.length; b++) {
            int slice = boundarySlices[b];
            SegmentedResult actual = slicedRun(world, SEGMENTING_BUDGET, slice);
            assertEquals(expected.segments(), actual.segments(), "segments at slice " + slice);
            assertEquals(expected.cost(), actual.cost(), 0.0, "cost at slice " + slice);
            assertEquals(expected.outcome(), actual.outcome(), "outcome at slice " + slice);
        }
    }

    @Test
    void aCancelledRunReleasesItsWorldAndRefusesToContinue() {
        // The level-pinning hazard, asserted by reference rather than by behaviour. A run holds
        // the world it reads; under slicing that run lives for hundreds of milliseconds, so a
        // level change during one must not leave the old level reachable.
        FixtureWorld world = cave();
        final Run run = new SegmentedSearch(new AStarPathfinder(25000)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());
        run.advance(5);

        run.cancel();

        assertTrue(run.cancelled());
        assertTrue(run.finished(), "a cancelled run is over, not merely paused");
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                run.result();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                run.advance(5);
            }
        });
    }

    @Test
    void cancellingTwiceIsHarmless() {
        // The adapter calls onLevel every tick and compares by identity; a defensive second cancel
        // is the normal path on the tick after a transition, exactly as ContinuoCore.stop() is.
        FixtureWorld world = cave();
        Run run = new SegmentedSearch(new AStarPathfinder(25000)).begin(world,
            world.start().x(), world.start().y(), world.start().z(),
            goalOf(world), CapabilitySet.none());

        run.cancel();
        run.cancel();

        assertTrue(run.cancelled());
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.SliceEquivalenceTest'`
Expected: FAIL — `Run` and `SegmentedSearch.begin` do not exist.

- [ ] **Step 3: Write `Run`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.HeuristicRates;

import java.util.ArrayList;
import java.util.List;

/**
 * One segmented run, advanced in bounded slices: the current search, the route so far, and the
 * world it all reads.
 *
 * <p><b>This is {@code SegmentedSearch.run}'s loop with its locals lifted into fields</b>, exactly
 * as {@code Search} is for {@code AStarPathfinder.findPath}. {@code run} is this class driven with
 * an unbounded slice, so there is one loop rather than two and the design's D7 extends to segment
 * counts by construction.
 *
 * <p><b>It owns the world it reads, and that is a lifecycle obligation.</b> Under C5 a run lives
 * for hundreds of milliseconds rather than for one call, and the source it holds may be a snapshot
 * still filling from the client level. A level change during a run must therefore
 * {@link #cancel} it, or the run keeps the old level reachable. C3 §4.7's "this object has no
 * lifecycle" was true only while nothing outlived a tick; this is the thing that does.
 */
public final class Run {

    private final AStarPathfinder pathfinder;
    private final Goal goal;
    private final CapabilitySet caps;
    private final double minProgress;
    private final int cap;

    private final List<Pos> path = new ArrayList<Pos>();
    private final List<Pos> expanded = new ArrayList<Pos>();

    /** Nulled by {@link #cancel}, which is what stops a cancelled run pinning a level. */
    private BlockSource world;

    private double cost;
    private int segments;
    private int x;
    private int y;
    private int z;
    private Search current;
    private SegmentedResult result;
    private boolean cancelled;

    Run(AStarPathfinder pathfinder, BlockSource world, int startX, int startY, int startZ,
        Goal goal, CapabilitySet caps) {
        this.pathfinder = pathfinder;
        this.world = world;
        this.goal = goal;
        this.caps = caps;
        this.x = startX;
        this.y = startY;
        this.z = startZ;

        HeuristicRates rates = pathfinder.registry().activeFor(caps).rates();
        this.minProgress = pathfinder.minProgressBlocks() * rates.horizontal();
        double hStart = goal.heuristic(startX, startY, startZ, rates);
        // The design's own termination bound, evaluated once, with no margin added: h falls by at
        // least minProgress per segment and cannot go below zero. A correct implementation never
        // reaches this. Reaching it means h stopped being admissible -- C1 section 5.3 records
        // that admissibility here is a checked numeric property, not a structural one -- so it is
        // reported rather than swallowed.
        this.cap = (int) Math.ceil(hStart / minProgress) + 1;
    }

    /**
     * Expands up to {@code maxNodes} nodes, starting a new segment whenever the current one ends.
     *
     * @param maxNodes how many nodes this slice may expand; must be positive
     * @return whether the run has finished and {@link #result} is available
     * @throws IllegalArgumentException if {@code maxNodes} is not positive
     * @throws IllegalStateException if this run was cancelled
     */
    public boolean advance(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive, got " + maxNodes);
        }
        if (cancelled) {
            throw new IllegalStateException("this run was cancelled");
        }
        if (result != null) {
            return true;
        }
        int remaining = maxNodes;
        while (remaining > 0) {
            if (current == null) {
                if (segments >= cap) {
                    result = new SegmentedResult(PathOutcome.BUDGET_EXCEEDED, path, expanded,
                        cost, segments);
                    return true;
                }
                current = pathfinder.begin(world, x, y, z, goal, caps);
            }
            int before = current.expandedCount();
            boolean done = current.advance(remaining);
            remaining -= current.expandedCount() - before;
            if (!done) {
                return false;
            }

            PathResult r = current.result();
            current = null;
            segments++;
            expanded.addAll(r.expanded());

            if (r.outcome() != PathOutcome.PARTIAL) {
                append(path, r.path());
                result = new SegmentedResult(r.outcome(), path, expanded, cost + r.cost(),
                    segments);
                return true;
            }

            append(path, r.path());
            cost += r.cost();
            Pos end = r.path().get(r.path().size() - 1);
            x = end.x();
            y = end.y();
            z = end.z();
        }
        return false;
    }

    /**
     * Ends this run and releases the world it was reading.
     *
     * <p>Idempotent, because the adapter poll that triggers it compares the client level by
     * identity every tick and a second call on the following tick is the normal path — the same
     * shape {@code ContinuoCore.stop()} has under global rule 2.
     */
    public void cancel() {
        cancelled = true;
        current = null;
        world = null;
    }

    /** @return whether {@link #cancel} was called */
    public boolean cancelled() {
        return cancelled;
    }

    /** @return whether this run is over, by finishing or by cancellation */
    public boolean finished() {
        return result != null || cancelled;
    }

    /**
     * @return what the run produced
     * @throws IllegalStateException if it has not finished, or was cancelled
     */
    public SegmentedResult result() {
        if (cancelled) {
            throw new IllegalStateException("this run was cancelled and has no result");
        }
        if (result == null) {
            throw new IllegalStateException("this run has not finished;"
                + " advance(int) until it returns true");
        }
        return result;
    }

    /** @return how many nodes have been expanded across every segment so far */
    public int expandedCount() {
        return expanded.size() + (current == null ? 0 : current.expandedCount());
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

- [ ] **Step 4: Rewrite `SegmentedSearch.run` on top of it**

Replace `SegmentedSearch`'s `run` method body (lines 51–92) with:

```java
    public SegmentedResult run(BlockSource world, int startX, int startY, int startZ,
                               Goal goal, CapabilitySet caps) {
        Run run = begin(world, startX, startY, startZ, goal, caps);
        run.advance(Integer.MAX_VALUE);
        return run.result();
    }

    /**
     * Starts a run without expanding anything, so a caller can spend it a slice at a time.
     *
     * <p>{@link #run} is this method plus one unbounded slice.
     *
     * @param world the world to read; never {@code null}
     * @param startX where the run begins
     * @param startY where the run begins
     * @param startZ where the run begins
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return a run that has expanded nothing yet; never {@code null}
     * @throws IllegalArgumentException if {@code world}, {@code goal} or {@code caps} is null
     */
    public Run begin(BlockSource world, int startX, int startY, int startZ, Goal goal,
                     CapabilitySet caps) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (caps == null) {
            throw new IllegalArgumentException("caps must not be null; use CapabilitySet.none()");
        }
        return new Run(pathfinder, world, startX, startY, startZ, goal, caps);
    }
```

Delete the now-unused `append` method and the `HeuristicRates`, `java.util.ArrayList` and `java.util.List` imports from `SegmentedSearch`. Update its class javadoc: the paragraph beginning **"Single-tick."** is now false — replace it with:

```java
 * <p><b>A run can span ticks.</b> {@code begin} returns a {@code Run} that advances a slice at a
 * time, and every segment reads the one {@code BlockSource} the run was given, so a snapshot passed
 * here outlives the tick it was created in. That run owns the source's lifecycle: see
 * {@code Run.cancel()}.
```

**Note the javadoc trap:** `{@code Run.cancel()}` rather than `{@link Run#cancel()}` is not required here — `Run` exists by this step — but if you write this edit before creating `Run`, the build fails on a dead link. Create `Run` first.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS. **Every existing `SegmentedSearchTest` and `BackoffTest` assertion must pass unchanged**, including the mutation-checked `274.41707435261833`.

- [ ] **Step 6: Mutation-check**

Apply each, confirm the named test fails, revert:

1. In `Run.advance`, `remaining -= current.expandedCount() - before;` → `remaining--` → `everySliceSizeProducesTheIdenticalSegmentedRun` fails or hangs (the guard catches it).
2. In `Run.advance`, start the next segment before checking `segments >= cap` → the cap stops bounding the run.
3. In `Run.cancel`, remove `world = null;` → `aCancelledRunReleasesItsWorldAndRefusesToContinue` still passes. **This is the gap to close**: add an assertion that reaches the field, or accept it and record it as unpinned in the commit message. Do not leave it silently unmutated.

- [ ] **Step 7: Full build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, **504 tests** (500 + 4).

- [ ] **Step 8: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/Run.java core-pathfinder/src/main/java/dev/continuo/pathfinder/SegmentedSearch.java core-pathfinder/src/test/java/dev/continuo/pathfinder/SliceEquivalenceTest.java
git commit -m "feat(c5): lift the segmented loop into a run that advances in slices and can be cancelled"
```

---

## Task 5: The probe drives a sliced run

**Files:**
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Modify: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` (addition only)

**Interfaces:**
- Consumes: `SegmentedSearch.begin(...)` and `Run` from Task 4; `WorldSnapshot.slots()` from Task 1.
- Produces: `PathProbe.SLICE_NODES` (public static final int), `ProbeReport start(BlockSource, int, int, int)` returning a report only when the run could not start, `ProbeReport advance()` returning `null` until the run finishes, `void cancel()`. `run(BlockSource, int, int, int)` keeps its exact signature and behaviour. Task 6 calls `start`, `advance` and the existing `onLevel`.

- [ ] **Step 1: Write the failing test**

Add to `PathProbeTest.java`:

```java
    @Test
    void aSlicedRunReachesTheSameRouteAsAnUnslicedOne() {
        // D7 through the probe, which is the path the adapters take. run() drives the same Run
        // with an unbounded slice, so this compares the two ways of spending one search.
        ProbeWorld world = new ProbeWorld();
        PathProbe unsliced = new PathProbe();
        unsliced.markGoal(6, ProbeWorld.WALK_Y, 0);
        ProbeReport whole = unsliced.run(world, 0, ProbeWorld.WALK_Y, 0);

        PathProbe sliced = new PathProbe();
        sliced.markGoal(6, ProbeWorld.WALK_Y, 0);
        assertNull(sliced.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0),
            "a run that starts returns no report yet");
        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = sliced.advance();
        }

        assertNotNull(done, "the sliced run never finished");
        assertEquals(whole.outcome(), done.outcome());
        assertEquals(costFrom(whole.summary()), costFrom(done.summary()), 0.0,
            "a sliced run must reach the identical route\n" + whole.summary() + "\n"
                + done.summary());
    }

    @Test
    void aSlicedRunReportsHowManySlicesItTook() {
        PathProbe probe = new PathProbe();
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);
        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = probe.advance();
        }

        assertNotNull(done);
        assertTrue(done.summary().contains("slices"), done.summary());
        assertTrue(figureBefore(done.summary(), "slices") >= 1, done.summary());
    }

    @Test
    void advancingWithNothingStartedDoesNothing() {
        // The adapter calls advance() every tick whether or not the key was pressed.
        assertNull(new PathProbe().advance());
    }

    @Test
    void aLevelChangeCancelsAnInFlightRun() {
        // The hazard C3 §9 left behind: a run holds the source it reads, and under slicing that
        // run lives across ticks. A dimension change must end it rather than leave it searching a
        // level that no longer exists.
        PathProbe probe = new PathProbe();
        Object overworld = new Object();
        probe.onLevel(overworld);
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        probe.onLevel(new Object());

        assertNull(probe.advance(),
            "a run cancelled by a level change must not go on producing a report");
    }

    @Test
    void startingASecondRunWhileOneIsInFlightReplacesIt() {
        // Pressing the key twice is the likeliest thing an owner does. Two live runs sharing one
        // probe would interleave slices and report a route neither of them took.
        PathProbe probe = new PathProbe();
        probe.markGoal(12, ProbeWorld.WALK_Y, 12);
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);
        probe.advance();
        probe.start(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        ProbeReport done = null;
        for (int tick = 0; tick < 10000 && done == null; tick++) {
            done = probe.advance();
        }
        assertNotNull(done, "the replacing run must still finish");
        assertEquals(PathOutcome.FOUND, done.outcome(), done.summary());
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :runtime:test --tests 'dev.continuo.runtime.PathProbeTest'`
Expected: FAIL — `start`, `advance` do not exist.

- [ ] **Step 3: Add the sliced driving to `PathProbe`**

Add the constant beside `NODE_BUDGET`:

```java
    /**
     * How many nodes one slice expands.
     *
     * <p>4,000, from the design §5.4's arithmetic rather than from taste: a 25,053-expansion route
     * finishes in 7 slices, and a slice costs roughly 4,000 expansions of search plus the fill for
     * about 23,000 newly-touched positions at the measured 88 ns each — near 7 ms, comfortably
     * inside a 50 ms tick.
     *
     * <p><b>Provisional until an in-game run sets it.</b> The per-slice cost is not uniform: early
     * slices touch all-new terrain and pay the fill, later ones hit the snapshot's memo, and C4
     * §13.3 measured that non-linearity directly. A node budget buys determinism — C1 §5.1, and a
     * wall-clock slice boundary would make every path assertion in the suite flaky — at the price
     * of a variable millisecond cost.
     */
    public static final int SLICE_NODES = 4000;
```

Add fields:

```java
    private Run active;
    private WorldSnapshot activeSnapshot;
    private Pos activeStart;
    private int slices;
    private double worstSliceMs;
    private double totalSliceMs;
```

Add the three methods. `start` captures the world in a snapshot the run then owns:

```java
    /**
     * Begins a sliced run to the marked goal, replacing any run already in flight.
     *
     * @param world the world to read; never {@code null}
     * @param startX where the run begins
     * @param startY where the run begins
     * @param startZ where the run begins
     * @return a report if the run could not be started, or {@code null} if it was — in which case
     *         {@link #advance} produces the report when it finishes
     */
    public ProbeReport start(BlockSource world, int startX, int startY, int startZ) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        cancel();
        if (goal == null) {
            return ProbeReport.notRun("Continuo path probe: no goal marked."
                + " Stand on the destination, press the mark key, then try again.");
        }
        activeSnapshot = new WorldSnapshot(world);
        activeStart = new Pos(startX, startY, startZ);
        active = new SegmentedSearch(new AStarPathfinder(nodeBudget)).begin(
            activeSnapshot, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));
        slices = 0;
        worstSliceMs = 0.0;
        totalSliceMs = 0.0;
        return null;
    }

    /**
     * Spends one slice on the run in flight, if there is one.
     *
     * <p>Call once per tick. Cheap and safe when nothing is running, which is the normal case.
     *
     * @return the report when the run finishes on this call, otherwise {@code null}
     */
    public ProbeReport advance() {
        if (active == null) {
            return null;
        }
        long at = System.nanoTime();
        boolean done = active.advance(SLICE_NODES);
        double ms = msSince(at);
        slices++;
        totalSliceMs += ms;
        if (ms > worstSliceMs) {
            worstSliceMs = ms;
        }
        if (!done) {
            return null;
        }
        SegmentedResult result = active.result();
        WorldSnapshot snapshot = activeSnapshot;
        Pos start = activeStart;
        int sliceCount = slices;
        double worst = worstSliceMs;
        double total = totalSliceMs;
        active = null;
        activeSnapshot = null;
        activeStart = null;
        return report(snapshot, start, result, setupMs, total, sliceCount, worst);
    }

    /** Ends any run in flight and releases the world it held. Idempotent. */
    public void cancel() {
        if (active != null) {
            active.cancel();
        }
        active = null;
        activeSnapshot = null;
        activeStart = null;
    }
```

In `onLevel`, add `cancel();` immediately after `goal = null;`.

**Then extract the reporting.** Move everything in the existing `run` from `SealedSnapshot sealed = snapshot.seal();` to the final `return ProbeReport.of(...)` into a private method with **exactly** this signature:

```java
    private ProbeReport report(WorldSnapshot snapshot, Pos start, SegmentedResult result,
                               double setupMs, double liveMs, int sliceCount, double worstMs)
```

`liveMs` is the search's total wall-clock time and replaces the local `elapsedMs` the moved code used — for a sliced run it is the sum of every slice, which is the same quantity. There is deliberately no separate `totalMs` parameter: carrying both would let the summary's two clauses disagree.

Keep the two sealed replays and the divergence check from `46ed86f` exactly where they are, inside `report`. `setupMs` is measured in `start` — add a `private double setupMs;` field, set it there around the `new SegmentedSearch(new AStarPathfinder(nodeBudget))` construction, and pass it through.

Two additions to the summary. First, extend the existing snapshot clause with occupancy — `slots()` is added by Task 1 for this caller, and a public method with no caller is what B2 §9 warns against:

```java
        summary.append(", ").append(sealed.slots()).append(" slots");
        if (sealed.slots() > 0) {
            summary.append(" (").append(Math.round(
                100.0 * sealed.size() / sealed.slots())).append("% full)");
        }
```

Second, the slice clause, appended after the existing split clause and only for a sliced run:

```java
        if (sliceCount > 0) {
            summary.append(", sliced ").append(sliceCount).append(" slices")
                .append(", worst ").append(fmt(worstMs)).append("ms");
        }
```

Finally, reimplement `run` so **its signature and behaviour are unchanged**:

```java
    public ProbeReport run(BlockSource world, int startX, int startY, int startZ) {
        ProbeReport early = start(world, startX, startY, startZ);
        if (early != null) {
            return early;
        }
        long startedAt = System.nanoTime();
        active.advance(Integer.MAX_VALUE);
        double elapsedMs = msSince(startedAt);
        SegmentedResult result = active.result();
        WorldSnapshot snapshot = activeSnapshot;
        Pos start = activeStart;
        active = null;
        activeSnapshot = null;
        activeStart = null;
        return report(snapshot, start, result, setupMs, elapsedMs, 0, 0.0);
    }
```

`advance()`'s finishing branch calls the same method with `report(snapshot, start, result, setupMs, totalSliceMs, sliceCount, worst)`.

- [ ] **Step 3b: Fix a pre-existing flaky tolerance in `PathProbeTest`**

Not part of the sliced-run work, but this file is yours this task and the test fails at random until it is fixed. In `theSummaryReportsWhatTheFillCostAgainstWhatTheSearchCost`, this assertion is too tight:

```java
        assertEquals(live - second, fill, 0.05,
```

`live` and `second` are each parsed back out of a string formatted to one decimal place, so each carries up to ±0.05 of rounding error, while `fill` was formatted from the **unrounded** difference and carries ±0.05 of its own. The three can legitimately disagree by up to 0.15, so a 0.05 tolerance fails at random — observed once during Task 4 as `12.199999999999996` against `12.1`. Widen it and say why:

```java
        // 0.16, not 0.05. live and second are each parsed back from a string formatted to one
        // decimal, so each carries up to +/-0.05 of rounding, and fill was formatted from the
        // unrounded difference and carries +/-0.05 of its own. The legitimate disagreement is
        // therefore up to 0.15, and a tighter tolerance makes this test fail at random rather
        // than when the arithmetic is actually wrong. Observed as 12.199999999999996 vs 12.1.
        assertEquals(live - second, fill, 0.16,
```

The assertion still does its job: it catches a hardcoded fill, or one computed from the first replay instead of the second, both of which differ by far more than 0.16.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :runtime:test`
Expected: PASS. **Every pre-existing `PathProbeTest` test must pass with no edit**, including the four from `46ed86f` about the fill/search split. If `theSummaryReportsWhatTheFillCostAgainstWhatTheSearchCost` fails, the split clause moved — fix the code, not the test.

- [ ] **Step 5: Full build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, **509 tests** (504 + 5).

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/PathProbe.java runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "feat(c5): the probe drives a sliced run one slice per tick"
```

---

## Task 6: Adapter wiring

**Files:**
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java:139-174`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java:200-230`

**Interfaces:**
- Consumes: `PathProbe.start`, `PathProbe.advance`, `PathProbe.onLevel` from Task 5.
- Produces: nothing. This is the last task.

**Before you start:** adapters have no tests and cannot get any. **Verify every Minecraft API call you touch against the decompiled sources on disk** — both versions are greppable under the Gradle caches. Change nothing beyond what is described. `./gradlew clean` would destroy the 1.7.10 sources; never run it.

- [ ] **Step 1: Read both call sites first**

Read `ContinuoFabricMod.java:139-174` and `ContinuoForgeMod.java:200-230` in full. Note that both call `probe.onLevel(...)` **before** the null-player check and compute the player position **after** it, and that the two compute the position differently — Fabric from `blockPosition()`, 1.7.10 from `floor(posX)`, `floor(boundingBox.minY)`, `floor(posZ)`. Do not unify them.

- [ ] **Step 2: Change the Fabric adapter**

Replace the `if (path) { ... }` block's body so it starts a run instead of running one, and add an advance after it. The block becomes:

```java
                if (path) {
                    ProbeReport refused = probe.start(
                        core.blocks(), at.getX(), at.getY(), at.getZ());
                    if (refused != null) {
                        LOGGER.info(refused.summary());
                    }
                }
                // Once per tick, whether or not the key was pressed: a sliced run advances on the
                // tick, not on the keypress. Cheap and a no-op when nothing is in flight.
                ProbeReport report = probe.advance();
                if (report != null) {
                    LOGGER.info(report.summary());
                    if (report.ran()) {
                        Path out = client.gameDirectory.toPath()
                            .resolve("continuo-path-probe.txt");
                        Files.write(out, report.map().getBytes(StandardCharsets.UTF_8));
                        LOGGER.info("Continuo: wrote path probe map to {}", out);
                    }
                }
```

Leave `probe.onLevel(client.level);` exactly where it is — it now cancels an in-flight run as well as discarding the goal, and it must stay before the null check for the reason its existing comment gives.

- [ ] **Step 3: Change the Forge adapter identically**

Apply the same shape at `ContinuoForgeMod.java`, using that file's own position variables (`px`, `py`, `pz`) and its `Log4j` logger. **No lambdas** — the 1.7.10 adapter has none and must keep none.

- [ ] **Step 4: Build both adapters**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, **509 tests** (adapters have no tests, so the count does not change).

- [ ] **Step 5: Commit**

```bash
git add adapters/
git commit -m "feat(c5): both adapters start a sliced run and advance it once per tick"
```

- [ ] **Step 6: Hand back for the in-game run**

Done criterion 6 needs a client and cannot be discharged by an implementer. Report to the owner:

> Ready for the in-game run. `./gradlew :adapters:adapter-fabric-1.21.11:runClient`, then **H** at `(1737, 72, −786)` to mark, walk to `(1588, 71, −967)`, **L** to path. Send the `Continuo path probe:` line from `adapters/adapter-fabric-1.21.11/run/logs/latest.log`. The figures that matter are `slices`, `worst` and `total` — **`worst` is what sets `SLICE_NODES`**, and §5.4's 4,000 is provisional until it does.

---

## Self-Review

**Spec coverage.** §4 (section store) → Task 1. §4.2 (memo placement, D5) → Task 1 Steps 3 and 7. §5.1–5.4 (resumable search, D6, D7, slice budget) → Tasks 3 and 5 Step 3. §5.5 (segmented run sliced) → Task 4. §6 (snapshot lifetime, cancellation) → Task 4 (`Run.cancel`) and Task 5 (`onLevel`). §7.1 (headless verification) → Tasks 2, 3, 4. §7.2 (probe as consumer, D10, D11) → Tasks 5 and 6. §8's test table → Tasks 1–5; §8's mutation list → the mutation steps in Tasks 2, 3 and 4. §9 criteria 1–5 and 7 → the per-task full builds; criterion 6 → Task 6 Step 6.

**Gaps I am recording rather than hiding.**

1. **§8 mutation 3** ("memo key compared with `equals` on a boxed `Long` rather than `==` on a `long`") is predicted by the spec to be caught by nothing, and this plan does not catch it either — `SectionStore` compares `memoKey == key` on primitives, so the mutation would be a rewrite rather than a tweak. Left as the spec left it: named, unpinned.
2. **Task 4 Step 6 mutation 3** (`Run.cancel` not nulling `world`) has no assertion that fails. The plan says so explicitly and asks the implementer to close it or record it, rather than pretending the test covers it.
3. **`SegmentedResult.expanded()`'s unbounded accumulation** (spec §5.5, ~925k `Pos` worst case) is carried forward unchanged and unbounded, exactly as C4 left it. No task touches it.
4. **The 4,000-node slice is unmeasured** until Task 6 Step 6. Every task before it uses it only as a default.

**Type consistency.** `Search.advance(int)`/`result()`/`finished()`/`expandedCount()` are used identically in Tasks 3, 4 and 5. `Run.advance(int)`/`result()`/`finished()`/`cancel()`/`cancelled()`/`expandedCount()` likewise in Tasks 4, 5 and 6. `SectionStore.get`/`put`/`has`/`size`/`slots` match between Task 1's implementation and Task 2's use. `PathProbe.start` returns `ProbeReport` (null on success), `advance` returns `ProbeReport` (null until done) — consistent across Tasks 5 and 6.

**Test count arithmetic:** 484 → 493 → 495 → 500 → 504 → 509. Each task's step re-checks it after a full `--rerun-tasks`.
