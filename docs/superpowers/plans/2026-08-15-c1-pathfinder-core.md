# C1 Pathfinder Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure, headless A\* pathfinder in a new `:core-pathfinder` module that finds correct paths through text-art fixture worlds using four movements, with zero Minecraft on the classpath.

**Architecture:** `BlockSource` (a three-method read interface in `:core`) is the only way the pathfinder sees the world; `BlockLookup` implements it live and test fixtures implement it directly. Standability is decided by reading `BlockData.collisionTop()` rather than by switching on `BlockShape`, because `PARTIAL` is a catch-all and a category test diverges across versions. Movements are a package-private `Move` interface over a fixed list, which C2 later publishes as a registry.

**Tech Stack:** Java 8 bytecode on a Java 21 toolchain, Gradle with the `continuo-pure-module` convention plugin, JUnit 5.

**Spec:** [`docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md`](../specs/2026-08-15-c1-pathfinder-core-design.md)

## Global Constraints

Every task's requirements implicitly include all of these.

- **Java 8 bytecode**, machine-checked by `checkCoreBytecode`. No `var`, no records, no `List.of`/`Set.of`, no text blocks, no switch expressions. Lambdas and diamond `<>` are fine.
- **Javadoc is build-failing** in pure modules (`-Xdoclint:all,-missing -Xwerror`). A broken `{@link}` fails the build. **Never `{@link}` a type that does not exist yet** — in particular `WorldSnapshot`, which is C3's.
- **Never run `./gradlew clean`.** It destroys the 1.7.10 decompiled sources at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`, the evidence base for every API claim in this project. Use `./gradlew build --rerun-tasks` for the same "nothing is UP-TO-DATE" guarantee.
- **Never set, export, or override `GRADLE_USER_HOME`.** It is already set to `C:\GradleHome`. An agent that set it to a relative path once built a 1.7 GB untracked cache inside the repo.
- **No SPI changes and no adapter changes.** Nothing under `platform/src/main/java/dev/continuo/platform/` or `adapters/` may be modified. This is a done-criterion checked against the diff.
- **Do not push and do not touch `.github/workflows/ci.yml`.** `origin` exists and is off-limits.
- **Split any parsed text on `\r?\n`.** Git converts `docs/` text files LF→CRLF in the working tree and there is no `.gitattributes`.
- **Append to your report file as you go**, not at the end. Session limits have killed subagents mid-task; recovery is cheap only when a partial report exists.
- Branch is `c1-pathfinder-core`. Commit per task.

---

## File Structure

**Created in `:core`:**

| File | Responsibility |
|---|---|
| `core/src/main/java/dev/continuo/core/BlockSource.java` | The three-method read interface the pathfinder codes against |

**Modified in `:core`:**

| File | Change |
|---|---|
| `core/src/main/java/dev/continuo/core/BlockLookup.java` | `implements BlockSource`; add `@Override` to `at`, `minY`, `maxY` |

**Created — build:**

| File | Responsibility |
|---|---|
| `settings.gradle.kts` (modify) | `include("core-pathfinder")` |
| `core-pathfinder/build.gradle.kts` | Pure-module conventions, `api(project(":core"))`, JUnit 5 |

**Created in `:core-pathfinder` main sources** (`core-pathfinder/src/main/java/dev/continuo/pathfinder/`):

| File | Responsibility |
|---|---|
| `package-info.java` | Package documentation |
| `Pos.java` | An immutable block position, plus the `long` packing A\* keys on |
| `Standability.java` | `passable` / `supports` / `standable` — the only place block facts become movement facts |
| `MovementCosts.java` | The derived tick constants and their source citations |
| `Move.java` | Package-private: `expand(world, x, y, z, sink)` |
| `MoveSink.java` | Package-private: `offer(x, y, z, cost)` |
| `TraverseMove.java` | Four cardinal steps on the level |
| `AscendMove.java` | Four cardinal steps up one |
| `DescendMove.java` | Four cardinal steps down up to the safe-fall limit |
| `DiagonalMove.java` | Four diagonal steps on the level, corner-cut checked |
| `Goal.java` | `isReached` + `heuristic` |
| `GoalBlock.java` | An exact position |
| `GoalXZ.java` | Any Y in a column |
| `PathOutcome.java` | `FOUND`, `NO_PATH`, `BUDGET_EXCEEDED` |
| `PathResult.java` | Outcome, path, expanded nodes, statistics |
| `PathNode.java` | Package-private mutable search node |
| `AStarPathfinder.java` | The search itself |

**Created in `:core-pathfinder` test sources** (`core-pathfinder/src/test/java/dev/continuo/pathfinder/`):

| File | Responsibility |
|---|---|
| `FixtureBlocks.java` | The canonical `BlockData` values behind the legend characters |
| `FixtureWorld.java` | Parses text art, implements `BlockSource` |
| `PathRenderer.java` | Renders world + path + expanded nodes back to the same format |
| one `*Test.java` per production class | |

`FixtureWorld` and `PathRenderer` live in **test** sources deliberately: the roadmap calls the renderer test-time, and C2 lives in this same module so it inherits both. C3 lives in `:core` and needs a fake `IBlockView` instead, which `platform-testkit` already provides.

`ExpansionContext` from the architecture doc is **not** built. Passing `(world, x, y, z)` is enough for four movements; C2 introduces the context type when the registry gives it a shape.

---

## Task 1: `BlockSource` and the `BlockLookup` retrofit

**Files:**
- Create: `core/src/main/java/dev/continuo/core/BlockSource.java`
- Modify: `core/src/main/java/dev/continuo/core/BlockLookup.java:21` (class declaration) and the three method declarations
- Test: `core/src/test/java/dev/continuo/core/BlockSourceTest.java`

**Interfaces:**
- Consumes: `BlockData`, `IBlockView` (both existing)
- Produces: `public interface BlockSource { BlockData at(int,int,int); int minY(); int maxY(); }` in `dev.continuo.core`. Every later task codes against this.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/BlockSourceTest.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockSourceTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    @Test
    void blockLookupIsABlockSource() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false));

        BlockSource source = new BlockLookup(view, new BlockClassifier(BlockTable.EMPTY));

        assertEquals(BlockShape.FULL, source.at(0, 64, 0).shape());
        assertEquals(view.minY(), source.minY());
        assertEquals(view.maxY(), source.maxY());
    }

    @Test
    void readingAnUnreadablePositionThroughTheInterfaceYieldsUnknown() {
        BlockSource source = new BlockLookup(new FakeBlockView(), new BlockClassifier(BlockTable.EMPTY));

        assertEquals(BlockShape.UNKNOWN, source.at(0, 64, 0).shape());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.continuo.core.BlockSourceTest"`
Expected: compilation failure — `cannot find symbol: class BlockSource`.

- [ ] **Step 3: Create `BlockSource`**

Create `core/src/main/java/dev/continuo/core/BlockSource.java`:

```java
package dev.continuo.core;

/**
 * A read-only view of classified blocks, with no assumption about where they come from.
 *
 * <p>This is the interface the pathfinder codes against. {@link BlockLookup} implements it over
 * a live world; test fixtures implement it directly over an array or a map, which is what makes
 * headless pathfinding tests possible without an {@code IBlockView}, the classifier, or a
 * per-version table.
 *
 * <p><b>Unreadable positions.</b> {@link #at} returns {@link BlockData#UNKNOWN} rather than
 * {@code null} or an exception, for every reason a position might be unreadable: outside
 * {@link #minY()}/{@link #maxY()}, in an unloaded chunk, or outside whatever region an
 * implementation happens to cover. One rule, no position-dependent special cases.
 *
 * <p><b>Call restrictions belong to the implementation, not to this interface.</b> A live
 * implementation inherits {@code IBlockView}'s main-thread delivery window; a frozen one has no
 * such restriction. Callers that hold a {@code BlockSource} of unknown provenance must assume
 * the stricter of the two.
 */
public interface BlockSource {

    /**
     * The classified block at a position.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, never {@code null}; {@link BlockData#UNKNOWN} if unreadable
     */
    BlockData at(int x, int y, int z);

    /** @return the lowest Y that can hold a block, inclusive */
    int minY();

    /** @return one past the highest Y that can hold a block, exclusive */
    int maxY();
}
```

- [ ] **Step 4: Retrofit `BlockLookup`**

In `core/src/main/java/dev/continuo/core/BlockLookup.java`, change the class declaration:

```java
public final class BlockLookup implements BlockSource {
```

Add `@Override` immediately above the existing `at`, `minY` and `maxY` method declarations. Change nothing else — the three methods already have exactly the required signatures and semantics.

Add one sentence to the class javadoc, after the existing `<p>The hot path is an {@code int}` paragraph:

```java
 * <p>This is the live implementation of {@link BlockSource}. Its reads are subject to
 * {@code IBlockView}'s delivery window; a caller holding only the interface cannot know that,
 * which is why the restriction is documented on both.
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "dev.continuo.core.BlockSourceTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Verify the whole module still builds, javadoc included**

Run: `./gradlew :core:check`
Expected: BUILD SUCCESSFUL. Javadoc is build-failing here, so this also proves no `{@link}` is broken.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/dev/continuo/core/BlockSource.java \
        core/src/main/java/dev/continuo/core/BlockLookup.java \
        core/src/test/java/dev/continuo/core/BlockSourceTest.java
git commit -m "feat(c1): add BlockSource and retrofit BlockLookup onto it

The pathfinder codes against this interface rather than any concrete type,
which is what lets fixture worlds implement it directly and lets C3's
snapshot slot in later without touching a call site.

BlockLookup already had all three methods with exactly these semantics, so
the retrofit is an implements clause and three @Override annotations."
```

---

## Task 2: The `:core-pathfinder` module, `Pos`, and the standability predicates

This is the module's first main code, so the scaffold ships with it — `checkCorePurity` fails loudly if a module has no main classes to scan, so an empty module is not a valid intermediate state.

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core-pathfinder/build.gradle.kts`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/package-info.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Standability.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/StandabilityTest.java`

**Interfaces:**
- Consumes: `BlockSource`, `BlockData`, `BlockShape`, `BlockTag`, `Fluid` from Task 1 and `:core`
- Produces:
  - `Pos` — `new Pos(int,int,int)`, `x()`, `y()`, `z()`, `packed()`, `Pos.pack(int,int,int)`, `Pos.unpack(long)`, `Pos.unpackX/Y/Z(long)`, value `equals`/`hashCode`
  - `Standability` — `passable(BlockData)`, `supports(BlockData)`, `standable(BlockSource,int,int,int)`, and the constants `PASSABLE_MAX_TOP`, `SUPPORT_MIN_TOP`, `SUPPORT_MAX_TOP`

- [ ] **Step 1: Add the module to the build**

In `settings.gradle.kts`, add after `include("core")`:

```kotlin
include("core-pathfinder")
```

Create `core-pathfinder/build.gradle.kts`:

```kotlin
plugins {
    id("continuo-pure-module")
}

dependencies {
    api(project(":core"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/package-info.java`:

```java
/**
 * The pathfinder: an A* search over an implicit graph of block positions.
 *
 * <p>Pure and headless. The only view of the world is {@link dev.continuo.core.BlockSource},
 * so everything here can be tested against a fixture world with no game, no adapter and no
 * classifier involved.
 *
 * <p><b>Block facts become movement facts in exactly one place</b> —
 * {@link dev.continuo.pathfinder.Standability}. Nothing else in this package reads
 * {@code collisionTop}, a {@code BlockShape} or a {@code Fluid} directly, so the rules about
 * what can be stood on live in one file rather than being restated per movement.
 */
package dev.continuo.pathfinder;
```

- [ ] **Step 2: Write the failing `Pos` test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/PosTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PosTest {

    @Test
    void packingRoundTripsThroughTheOrigin() {
        assertRoundTrip(0, 0, 0);
    }

    @Test
    void packingRoundTripsNegativeCoordinates() {
        assertRoundTrip(-1, -1, -1);
        assertRoundTrip(-30000000, -64, -30000000);
    }

    @Test
    void packingRoundTripsBothWorldHeights() {
        assertRoundTrip(0, 255, 0);     // 1.7.10's top
        assertRoundTrip(0, 319, 0);     // 1.21.11's top
        assertRoundTrip(0, -64, 0);     // 1.21.11's floor
    }

    @Test
    void packingRoundTripsFarHorizontalCoordinates() {
        assertRoundTrip(30000000, 64, 30000000);
    }

    @Test
    void distinctPositionsPackDistinctly() {
        assertNotEquals(Pos.pack(1, 0, 0), Pos.pack(0, 0, 1));
        assertNotEquals(Pos.pack(1, 0, 0), Pos.pack(0, 1, 0));
        assertNotEquals(Pos.pack(-1, 0, 0), Pos.pack(1, 0, 0));
    }

    @Test
    void equalPositionsAreEqualAndHashAlike() {
        assertEquals(new Pos(3, -4, 5), new Pos(3, -4, 5));
        assertEquals(new Pos(3, -4, 5).hashCode(), new Pos(3, -4, 5).hashCode());
        assertNotEquals(new Pos(3, -4, 5), new Pos(3, -4, 6));
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = Pos.pack(x, y, z);
        assertEquals(x, Pos.unpackX(packed), "x");
        assertEquals(y, Pos.unpackY(packed), "y");
        assertEquals(z, Pos.unpackZ(packed), "z");
        assertEquals(new Pos(x, y, z), Pos.unpack(packed));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PosTest"`
Expected: compilation failure — `cannot find symbol: class Pos`.

- [ ] **Step 4: Implement `Pos`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Pos.java`:

```java
package dev.continuo.pathfinder;

/**
 * An immutable block position, and the {@code long} packing the search keys nodes on.
 *
 * <p><b>The packing.</b> X and Z take 26 signed bits each and Y takes 12, which covers
 * &plusmn;33,554,432 horizontally — beyond Minecraft's world border on both versions — and
 * &minus;2048..2047 vertically, comfortably outside 1.7.10's {@code 0..256} and 1.21.11's
 * {@code -64..320}. A single {@code long} key means the open and closed collections are plain
 * maps of primitives rather than maps of objects with a hand-written hash.
 */
public final class Pos {

    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private final int x;
    private final int y;
    private final int z;

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     */
    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @return world X */
    public int x() {
        return x;
    }

    /** @return world Y */
    public int y() {
        return y;
    }

    /** @return world Z */
    public int z() {
        return z;
    }

    /** @return this position packed into a {@code long} */
    public long packed() {
        return pack(x, y, z);
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

    /**
     * @param packed a value from {@link #pack}
     * @return the position it encodes
     */
    public static Pos unpack(long packed) {
        return new Pos(unpackX(packed), unpackY(packed), unpackZ(packed));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pos)) {
            return false;
        }
        Pos other = (Pos) o;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return (int) (packed() ^ (packed() >>> 32));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
```

- [ ] **Step 5: Run the `Pos` tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PosTest"`
Expected: PASS, 6 tests.

- [ ] **Step 6: Write the failing `Standability` test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/StandabilityTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandabilityTest {

    private static BlockData block(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    }

    private static BlockData fluid(BlockShape shape, double top, Fluid f) {
        return new BlockData(shape, top, f, EnumSet.noneOf(BlockTag.class));
    }

    private static BlockData avoid(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.of(BlockTag.AVOID));
    }

    private static final BlockData AIR = block(BlockShape.AIR, 0.0);
    private static final BlockData STONE = block(BlockShape.FULL, 1.0);
    private static final BlockData TOP_SLAB = block(BlockShape.SLAB_TOP, 1.0);
    private static final BlockData STAIR = block(BlockShape.STAIR, 1.0);
    private static final BlockData BOTTOM_SLAB = block(BlockShape.SLAB_BOTTOM, 0.5);
    private static final BlockData FENCE = block(BlockShape.FENCE, 1.5);
    private static final BlockData CARPET_LEGACY = block(BlockShape.AIR, 0.0);
    private static final BlockData CARPET_MODERN = block(BlockShape.THIN_LAYER, 0.0625);
    private static final BlockData FARMLAND_LEGACY = block(BlockShape.FULL, 1.0);
    private static final BlockData FARMLAND_MODERN = block(BlockShape.PARTIAL, 0.9375);

    /** Taller than a cube but not classified FENCE — only the numeric upper bound rejects it. */
    private static final BlockData TALL_PARTIAL = block(BlockShape.PARTIAL, 1.5);

    /** A fence whose collision top would qualify — only the shape check rejects it. */
    private static final BlockData SHORT_FENCE = block(BlockShape.FENCE, 1.0);

    // --- passable -------------------------------------------------------

    @Test
    void airIsPassable() {
        assertTrue(Standability.passable(AIR));
    }

    @Test
    void solidBlocksAreNotPassable() {
        assertFalse(Standability.passable(STONE));
        assertFalse(Standability.passable(TOP_SLAB));
        assertFalse(Standability.passable(STAIR));
        assertFalse(Standability.passable(FENCE));
    }

    @Test
    void unknownIsNeverPassable() {
        assertFalse(Standability.passable(BlockData.UNKNOWN));
    }

    @Test
    void waterIsNotPassableEvenThoughItHasNoCollision() {
        assertFalse(Standability.passable(fluid(BlockShape.AIR, 0.0, Fluid.WATER)));
    }

    @Test
    void aWaterloggedSlabIsNotPassable() {
        assertFalse(Standability.passable(fluid(BlockShape.SLAB_TOP, 1.0, Fluid.WATER)));
    }

    @Test
    void avoidTaggedBlocksAreNotPassable() {
        assertFalse(Standability.passable(avoid(BlockShape.AIR, 0.0)));
    }

    // --- supports -------------------------------------------------------

    @Test
    void fullBlocksSlabTopsAndStairsSupport() {
        assertTrue(Standability.supports(STONE));
        assertTrue(Standability.supports(TOP_SLAB));
        assertTrue(Standability.supports(STAIR));
    }

    @Test
    void airDoesNotSupport() {
        assertFalse(Standability.supports(AIR));
    }

    @Test
    void unknownNeverSupports() {
        assertFalse(Standability.supports(BlockData.UNKNOWN));
    }

    @Test
    void aFenceIsNotAFloorDespiteItsCollisionTop() {
        assertFalse(Standability.supports(FENCE));
    }

    @Test
    void aWaterloggedSlabIsNotAFloor() {
        assertFalse(Standability.supports(fluid(BlockShape.SLAB_TOP, 1.0, Fluid.WATER)));
    }

    @Test
    void magmaIsNotAFloor() {
        assertFalse(Standability.supports(avoid(BlockShape.FULL, 1.0)));
    }

    // --- the two B1 divergences -----------------------------------------

    @Test
    void carpetIsPassableOnBothVersionsValues() {
        assertTrue(Standability.passable(CARPET_LEGACY));
        assertTrue(Standability.passable(CARPET_MODERN));
    }

    @Test
    void farmlandSupportsOnBothVersionsValues() {
        assertTrue(Standability.supports(FARMLAND_LEGACY));
        assertTrue(Standability.supports(FARMLAND_MODERN));
    }

    // --- each support guard, isolated -----------------------------------

    @Test
    void anythingTallerThanACubeIsNotAFloorEvenWhenNotClassifiedAsAFence() {
        assertFalse(Standability.supports(TALL_PARTIAL),
            "only the SUPPORT_MAX_TOP bound rejects this; the FENCE shape check does not fire");
    }

    @Test
    void aFenceIsNotAFloorEvenWhenItsCollisionTopWouldQualify() {
        assertFalse(Standability.supports(SHORT_FENCE),
            "only the FENCE shape check rejects this; the SUPPORT_MAX_TOP bound does not fire");
    }

    // --- the deliberate C1 limitation -----------------------------------

    @Test
    void aBottomSlabIsAnObstacleNeitherEnterableNorStandable() {
        assertFalse(Standability.passable(BOTTOM_SLAB));
        assertFalse(Standability.supports(BOTTOM_SLAB));
    }

    // --- standable ------------------------------------------------------

    @Test
    void standingNeedsAFloorFeetRoomAndHeadRoom() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        BlockSource source = source(world);

        assertTrue(Standability.standable(source, 0, 64, 0));
    }

    @Test
    void standingFailsWithoutAFloor() {
        assertFalse(Standability.standable(source(new HashMap<Long, BlockData>()), 0, 64, 0));
    }

    @Test
    void standingFailsWhenTheHeadIsBlocked() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        world.put(Pos.pack(0, 65, 0), STONE);

        assertFalse(Standability.standable(source(world), 0, 64, 0));
    }

    @Test
    void standingFailsWhenTheFeetBlockIsOccupied() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        world.put(Pos.pack(0, 64, 0), STONE);

        assertFalse(Standability.standable(source(world), 0, 64, 0));
    }

    /** A map-backed source. Absent positions are air; nothing here needs a real fixture yet. */
    private static BlockSource source(final Map<Long, BlockData> blocks) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(Long.valueOf(Pos.pack(x, y, z)));
                return found == null ? AIR : found;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int maxY() {
                return 256;
            }
        };
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.StandabilityTest"`
Expected: compilation failure — `cannot find symbol: class Standability`.

- [ ] **Step 8: Implement `Standability`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Standability.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

/**
 * Turns block facts into movement facts. The only place in the pathfinder that reads a
 * {@link BlockShape}, a {@link Fluid} or a collision top.
 *
 * <p><b>These are measurements, not category switches, and that is deliberate.</b> An earlier
 * draft decided support with a shape test over {@code FULL}, {@code SLAB_TOP} and {@code STAIR}.
 * That is wrong in two ways at once. {@link BlockShape#PARTIAL} is a catch-all covering
 * unrecognised modded geometry, so a category test refuses to walk on anything it does not
 * recognise. And farmland is classified {@code FULL} on 1.7.10 but {@code PARTIAL} with a
 * collision top of {@code 0.9375} on 1.21.11 — so a category test would walk across a legacy
 * farm and refuse a modern one. Reading the number makes both versions agree, which is what
 * {@link BlockShape}'s own documentation directs for code that needs a real measurement.
 *
 * <p>Shape is still consulted where the number cannot answer: {@link BlockShape#UNKNOWN} has a
 * collision top of {@code 0} that is indistinguishable from air while meaning "unreadable,
 * might be solid", and {@link BlockShape#FENCE} means "cannot be walked over" regardless of
 * geometry.
 */
public final class Standability {

    /**
     * The highest collision top a block may have and still be entered.
     *
     * <p>Set at the {@link BlockShape#THIN_LAYER} ceiling, so carpets and snow layers are walked
     * through rather than around. The player's feet then rest up to a quarter of a block above
     * where the search assumes; Minecraft's own step-up absorbs that.
     */
    public static final double PASSABLE_MAX_TOP = 0.25;

    /** The lowest collision top that counts as a floor. Admits farmland's {@code 0.9375}. */
    public static final double SUPPORT_MIN_TOP = 0.9;

    /**
     * The highest collision top that counts as a floor.
     *
     * <p>Anything above a full cube is a fence or a wall by B1's classification rule, which is
     * why this bound and the {@link BlockShape#FENCE} exclusion in {@link #supports} agree
     * today. Both are kept: this bound makes the predicate a self-contained measurement, and
     * the shape check preserves the behavioural intent if that rule ever changes.
     */
    public static final double SUPPORT_MAX_TOP = 1.0;

    private Standability() {
    }

    /**
     * Whether the player's body can occupy this block.
     *
     * @param block the block; never {@code null}
     * @return whether it can be moved into
     */
    public static boolean passable(BlockData block) {
        if (block.shape() == BlockShape.UNKNOWN) {
            return false;
        }
        if (block.fluid() != Fluid.NONE) {
            return false;
        }
        if (block.tags().contains(BlockTag.AVOID)) {
            return false;
        }
        return block.collisionTop() <= PASSABLE_MAX_TOP;
    }

    /**
     * Whether this block is a floor the player can stand on top of.
     *
     * @param block the block; never {@code null}
     * @return whether it supports standing
     */
    public static boolean supports(BlockData block) {
        if (block.shape() == BlockShape.UNKNOWN || block.shape() == BlockShape.FENCE) {
            return false;
        }
        if (block.fluid() != Fluid.NONE) {
            return false;
        }
        if (block.tags().contains(BlockTag.AVOID)) {
            return false;
        }
        double top = block.collisionTop();
        return top >= SUPPORT_MIN_TOP && top <= SUPPORT_MAX_TOP;
    }

    /**
     * Whether the player can stand with their feet in this block.
     *
     * @param world the world to read; never {@code null}
     * @param x feet X
     * @param y feet Y
     * @param z feet Z
     * @return whether standing here is possible
     */
    public static boolean standable(BlockSource world, int x, int y, int z) {
        return passable(world.at(x, y, z))
            && passable(world.at(x, y + 1, z))
            && supports(world.at(x, y - 1, z));
    }
}
```

- [ ] **Step 9: Run the tests**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS, 25 tests across `PosTest` and `StandabilityTest`.

- [ ] **Step 10: Prove the guard tests are not vacuous, by mutation**

Five of these tests exist to prove something does *not* happen, which is exactly the shape B1 found five vacuous tests in. Prove each one guards its mistake. **For each mutation: apply it, run the test, paste the actual failure output into your report, then revert.**

| # | Mutation to `Standability` | Test that must fail |
|---|---|---|
| 1 | Delete the `shape() == UNKNOWN` check from `passable` | `unknownIsNeverPassable` |
| 2 | Delete the `fluid() != Fluid.NONE` check from `passable` | `waterIsNotPassableEvenThoughItHasNoCollision` |
| 3 | Delete the `AVOID` check from `passable` | `avoidTaggedBlocksAreNotPassable` |
| 4 | Delete `shape() == BlockShape.FENCE` from `supports` | `aFenceIsNotAFloorEvenWhenItsCollisionTopWouldQualify` |
| 5 | Change `top <= SUPPORT_MAX_TOP` to `true` in `supports` | `anythingTallerThanACubeIsNotAFloorEvenWhenNotClassifiedAsAFence` |
| 6 | Change `PASSABLE_MAX_TOP` to `0.6` | `aBottomSlabIsAnObstacleNeitherEnterableNorStandable` |
| 7 | Change `SUPPORT_MIN_TOP` to `0.4` | `aBottomSlabIsAnObstacleNeitherEnterableNorStandable` |

**Why mutations 4 and 5 need their own fixtures.** `supports` guards against a fence twice — by shape and by the `1.0` upper bound — and the ordinary fence fixture has a collision top of `1.5`, so *either* guard alone rejects it. Neither mutation can kill a test that uses only that fixture, which makes `aFenceIsNotAFloorDespiteItsCollisionTop` unable to prove anything about either guard on its own. `TALL_PARTIAL` (`PARTIAL`, top `1.5`) is rejected only by the bound; `SHORT_FENCE` (`FENCE`, top `1.0`) only by the shape check. Run the two mutations separately against those two tests.

**`aWaterloggedSlabIsNotPassable` is not mutation 2's witness either**, for the same reason: its slab's collision top of `1.0` is already outside `PASSABLE_MAX_TOP`, so the fluid guard is not what rejects it. It stays in the suite as a behavioural test — it documents that 1.21.11 lets a block be solid *and* watery, which is the asymmetry the spec warns against assuming away — but the guard it names is proven by `waterIsNotPassableEvenThoughItHasNoCollision`, whose air-shaped fixture only the fluid check can reject. The equivalent test on the `supports` side, `aWaterloggedSlabIsNotAFloor`, *does* isolate its guard: a top of `1.0` sits inside the support band, so fluid is the only thing rejecting it.

Neither fixture is a state B1's classifier can currently emit — its rule 2 sends anything above `1.0` to `FENCE`. They exist because the spec deliberately keeps both guards so the predicate stays correct if that rule changes, and a guard nothing can fail is a guard nobody can trust.

Run each as: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.StandabilityTest"`

- [ ] **Step 11: Verify no mutation survived on disk**

Run: `git diff --stat`
Expected: `Standability.java` is **not** listed as modified beyond your intended implementation. B1 had a deliberately-broken mutation left uncommitted; check rather than assume.

- [ ] **Step 12: Full build, including the purity and bytecode invariants on the new module**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL. This is the first run that proves `checkCorePurity` and `checkCoreBytecode` actually scan `:core-pathfinder` — both fail loudly if they find no class files, so a passing run means they ran.

- [ ] **Step 13: Commit**

```bash
git add settings.gradle.kts core-pathfinder/
git commit -m "feat(c1): add :core-pathfinder with Pos and the standability predicates

Standability is where block facts become movement facts, and it reads
collisionTop rather than switching on BlockShape. A category test over
{FULL, SLAB_TOP, STAIR} was written first and falsified against B1 §4:
PARTIAL is the catch-all every unrecognised modded block lands in, and
farmland is FULL on 1.7.10 but PARTIAL top 0.9375 on 1.21.11. Measuring
makes both versions agree, so B1's recorded carpet and farmland
divergences need no table row.

Shape is still consulted where the number cannot answer: UNKNOWN has a
collision top of 0 that is indistinguishable from air, and FENCE means
cannot-be-walked-over regardless of geometry.

SLAB_BOTTOM is an obstacle in C1 and tested as such. An integer node
cannot represent feet at y+0.5; that needs node state, which is I's."
```

---

## Task 3: Fixture worlds

**Files:**
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java`

**Interfaces:**
- Consumes: `BlockSource`, `BlockData`, `Pos`
- Produces:
  - `FixtureBlocks.legend()` returning `Map<Character, BlockData>`; named constants `AIR`, `STONE`, `BOTTOM_SLAB`, `TOP_SLAB`, `STAIR`, `CARPET`, `PARTIAL_FLOOR`, `FENCE`, `UNKNOWN`, `WATER`, `LAVA`
  - `FixtureWorld.parse(String)` and `FixtureWorld.parse(String, Map<Character, BlockData>)`; instance methods `at`, `minY`, `maxY`, `start()`, `goal()`, `minX()`, `maxX()`, `minZ()`, `maxZ()`, all returning `int` except `start()`/`goal()` which return `Pos`

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixtureWorldTest {

    private static final String FLAT =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "###\n"
            + "###\n"
            + "--- y=65\n"
            + "S.G\n"
            + "...\n";

    @Test
    void columnsRunEastAndRowsRunSouth() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(BlockShape.FULL, world.at(2, 64, 1).shape());
        assertEquals(BlockShape.AIR, world.at(1, 65, 0).shape());
    }

    @Test
    void startAndGoalComeFromTheArtAndTheirCellsAreAir() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(new Pos(0, 65, 0), world.start());
        assertEquals(new Pos(2, 65, 0), world.goal());
        assertEquals(BlockShape.AIR, world.at(0, 65, 0).shape());
        assertEquals(BlockShape.AIR, world.at(2, 65, 0).shape());
    }

    @Test
    void theOriginOffsetsEveryCoordinate() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: -5,-64,10\n"
                + "--- y=-64\n"
                + "#\n");

        assertEquals(BlockShape.FULL, world.at(-5, -64, 10).shape());
        assertEquals(BlockShape.UNKNOWN, world.at(-4, -64, 10).shape());
    }

    @Test
    void readsOutsideTheDeclaredExtentAreUnknownNotAir() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(BlockShape.UNKNOWN, world.at(-1, 64, 0).shape(), "west of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(3, 64, 0).shape(), "east of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 64, -1).shape(), "north of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 64, 2).shape(), "south of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 63, 0).shape(), "below the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 66, 0).shape(), "above the extent");
    }

    @Test
    void theVerticalExtentBecomesMinYAndMaxY() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(64, world.minY());
        assertEquals(66, world.maxY(), "maxY is exclusive, following IBlockView");
    }

    @Test
    void carriageReturnsAreToleratedSoWindowsCheckoutsParse() {
        FixtureWorld world = FixtureWorld.parse(FLAT.replace("\n", "\r\n"));

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(new Pos(0, 65, 0), world.start());
    }

    @Test
    void everyLegendCharacterParses() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,0,0\n"
                + "--- y=0\n"
                + ".#_^>cpf?~!\n");

        assertEquals(BlockShape.AIR, world.at(0, 0, 0).shape());
        assertEquals(BlockShape.FULL, world.at(1, 0, 0).shape());
        assertEquals(BlockShape.SLAB_BOTTOM, world.at(2, 0, 0).shape());
        assertEquals(BlockShape.SLAB_TOP, world.at(3, 0, 0).shape());
        assertEquals(BlockShape.STAIR, world.at(4, 0, 0).shape());
        assertEquals(BlockShape.THIN_LAYER, world.at(5, 0, 0).shape());
        assertEquals(BlockShape.PARTIAL, world.at(6, 0, 0).shape());
        assertEquals(BlockShape.FENCE, world.at(7, 0, 0).shape());
        assertEquals(BlockShape.UNKNOWN, world.at(8, 0, 0).shape());
        assertEquals(BlockShape.AIR, world.at(9, 0, 0).shape());
        assertEquals(BlockShape.AIR, world.at(10, 0, 0).shape());
    }

    @Test
    void anUnknownCharacterIsRejectedRatherThanGuessed() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\nZ\n"));

        assertEquals(true, thrown.getMessage().contains("Z"),
            "the message must name the offending character, got: " + thrown.getMessage());
    }

    @Test
    void extraCharactersCanBeRegisteredForOneFixture() {
        Map<Character, BlockData> extra =
            Collections.singletonMap(Character.valueOf('Z'), FixtureBlocks.STONE);

        FixtureWorld world = FixtureWorld.parse("origin: 0,0,0\n--- y=0\nZ\n", extra);

        assertEquals(BlockShape.FULL, world.at(0, 0, 0).shape());
    }

    @Test
    void commentLinesAreIgnoredSoRendererOutputReparses() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "// FOUND, 3 steps, 5 expanded, cost 12.0\n");

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(65, world.maxY(), "the comment is not a row");
    }

    @Test
    void raggedRowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\n###\n##\n"));
    }

    @Test
    void nonContiguousSlicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\n#\n--- y=2\n#\n"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.FixtureWorldTest"`
Expected: compilation failure — `cannot find symbol: class FixtureWorld`.

- [ ] **Step 3: Implement `FixtureBlocks`**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical block values behind the fixture legend.
 *
 * <p>Values are chosen to match what B1's audit actually recorded, so a fixture exercises the
 * real numbers rather than round ones. {@link #PARTIAL_FLOOR} is farmland's 1.21.11 value and
 * {@link #CARPET} is carpet's; both are the version-divergent cases the predicates are designed
 * to reconcile.
 */
final class FixtureBlocks {

    static final BlockData AIR = plain(BlockShape.AIR, 0.0);
    static final BlockData STONE = plain(BlockShape.FULL, 1.0);
    static final BlockData BOTTOM_SLAB = plain(BlockShape.SLAB_BOTTOM, 0.5);
    static final BlockData TOP_SLAB = plain(BlockShape.SLAB_TOP, 1.0);
    static final BlockData STAIR = plain(BlockShape.STAIR, 1.0);
    static final BlockData CARPET = plain(BlockShape.THIN_LAYER, 0.0625);
    static final BlockData PARTIAL_FLOOR = plain(BlockShape.PARTIAL, 0.9375);
    static final BlockData FENCE = plain(BlockShape.FENCE, 1.5);
    static final BlockData UNKNOWN = BlockData.UNKNOWN;

    static final BlockData WATER =
        new BlockData(BlockShape.AIR, 0.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));

    static final BlockData LAVA =
        new BlockData(BlockShape.AIR, 0.0, Fluid.LAVA, EnumSet.of(BlockTag.AVOID));

    private static final Map<Character, BlockData> LEGEND = buildLegend();

    private FixtureBlocks() {
    }

    private static BlockData plain(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    }

    private static Map<Character, BlockData> buildLegend() {
        Map<Character, BlockData> legend = new LinkedHashMap<Character, BlockData>();
        legend.put(Character.valueOf('.'), AIR);
        legend.put(Character.valueOf('#'), STONE);
        legend.put(Character.valueOf('_'), BOTTOM_SLAB);
        legend.put(Character.valueOf('^'), TOP_SLAB);
        legend.put(Character.valueOf('>'), STAIR);
        legend.put(Character.valueOf('c'), CARPET);
        legend.put(Character.valueOf('p'), PARTIAL_FLOOR);
        legend.put(Character.valueOf('f'), FENCE);
        legend.put(Character.valueOf('?'), UNKNOWN);
        legend.put(Character.valueOf('~'), WATER);
        legend.put(Character.valueOf('!'), LAVA);
        return Collections.unmodifiableMap(legend);
    }

    /**
     * @return the character-to-block legend, in a stable iteration order so that the renderer's
     *         reverse lookup is deterministic
     */
    static Map<Character, BlockData> legend() {
        return LEGEND;
    }
}
```

- [ ] **Step 4: Implement `FixtureWorld`**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A world written as text art, implementing {@link BlockSource} directly.
 *
 * <p>This is the payoff B1 set up: a headless pathfinding test needs no {@code IBlockView}, no
 * classifier and no per-version table. It constructs {@link BlockData} values and hands them
 * over.
 *
 * <p><b>Format.</b> A header line declares the origin of the lowest slice's first cell, then one
 * slice per Y level, lowest first, contiguous. Within a slice, columns run +X and rows run +Z.
 *
 * <pre>
 * origin: 0,64,0
 * --- y=64
 * #####
 * #####
 * --- y=65
 * S...G
 * ..#..
 * </pre>
 *
 * <p>{@code S} and {@code G} mark the start and goal and read as air. Reads outside the declared
 * extent yield {@link BlockData#UNKNOWN}, never air — treating unmapped space as air is how a
 * pathfinder walks confidently into terrain it was never told about.
 *
 * <p>The renderer emits this same format, and its overlay characters read back as air, so a
 * failure dump pastes straight in as a new fixture. Lines starting with {@code //} are ignored,
 * which is how the renderer's summary line survives that round trip; no legend character is
 * {@code /}, so no terrain row can begin with one.
 */
final class FixtureWorld implements BlockSource {

    static final char START = 'S';
    static final char GOAL = 'G';
    static final char PATH = '*';
    static final char EXPANDED = '+';

    private final Map<Long, BlockData> blocks;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final Pos start;
    private final Pos goal;

    private FixtureWorld(Map<Long, BlockData> blocks, int minX, int maxX, int minY, int maxY,
                         int minZ, int maxZ, Pos start, Pos goal) {
        this.blocks = blocks;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.start = start;
        this.goal = goal;
    }

    /**
     * @param text the text art
     * @return the parsed world
     */
    static FixtureWorld parse(String text) {
        return parse(text, Collections.<Character, BlockData>emptyMap());
    }

    /**
     * @param text the text art
     * @param extra additional legend characters for this fixture only; may override the defaults
     * @return the parsed world
     */
    static FixtureWorld parse(String text, Map<Character, BlockData> extra) {
        Map<Character, BlockData> legend = new HashMap<Character, BlockData>(FixtureBlocks.legend());
        legend.putAll(extra);

        String[] lines = text.split("\r?\n");
        if (lines.length == 0 || !lines[0].startsWith("origin:")) {
            throw new IllegalArgumentException("first line must be 'origin: x,y,z', got: "
                + (lines.length == 0 ? "<empty>" : lines[0]));
        }

        String[] originParts = lines[0].substring("origin:".length()).trim().split(",");
        if (originParts.length != 3) {
            throw new IllegalArgumentException("origin must have three parts, got: " + lines[0]);
        }
        int originX = Integer.parseInt(originParts[0].trim());
        int originY = Integer.parseInt(originParts[1].trim());
        int originZ = Integer.parseInt(originParts[2].trim());

        Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
        Pos start = null;
        Pos goal = null;
        int width = -1;
        int depth = 0;
        int sliceCount = 0;
        int currentY = 0;
        int rowInSlice = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("--- y=")) {
                int declared = Integer.parseInt(line.substring("--- y=".length()).trim());
                int expected = originY + sliceCount;
                if (declared != expected) {
                    throw new IllegalArgumentException("slices must be contiguous and ascending;"
                        + " expected y=" + expected + " but found y=" + declared);
                }
                if (sliceCount == 1) {
                    depth = rowInSlice;
                } else if (sliceCount > 1 && rowInSlice != depth) {
                    throw new IllegalArgumentException("slice y=" + currentY + " has " + rowInSlice
                        + " rows but an earlier slice has " + depth);
                }
                currentY = declared;
                sliceCount++;
                rowInSlice = 0;
                continue;
            }
            if (sliceCount == 0) {
                throw new IllegalArgumentException("terrain before the first '--- y=' line: " + line);
            }
            if (width == -1) {
                width = line.length();
            } else if (line.length() != width) {
                throw new IllegalArgumentException("ragged row: expected " + width
                    + " characters but found " + line.length() + " in: " + line);
            }

            int y = currentY;
            int z = originZ + rowInSlice;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                int x = originX + c;
                if (ch == START || ch == GOAL || ch == PATH || ch == EXPANDED) {
                    if (ch == START) {
                        start = new Pos(x, y, z);
                    } else if (ch == GOAL) {
                        goal = new Pos(x, y, z);
                    }
                    blocks.put(Long.valueOf(Pos.pack(x, y, z)), FixtureBlocks.AIR);
                    continue;
                }
                BlockData data = legend.get(Character.valueOf(ch));
                if (data == null) {
                    throw new IllegalArgumentException("unknown legend character '" + ch
                        + "' at x=" + x + " y=" + y + " z=" + z);
                }
                blocks.put(Long.valueOf(Pos.pack(x, y, z)), data);
            }
            rowInSlice++;
        }

        if (sliceCount == 0) {
            throw new IllegalArgumentException("no slices; expected at least one '--- y=' line");
        }
        if (sliceCount == 1) {
            depth = rowInSlice;
        } else if (rowInSlice != depth) {
            throw new IllegalArgumentException("slice y=" + currentY + " has " + rowInSlice
                + " rows but an earlier slice has " + depth);
        }

        return new FixtureWorld(blocks, originX, originX + width - 1, originY,
            originY + sliceCount, originZ, originZ + depth - 1, start, goal);
    }

    @Override
    public BlockData at(int x, int y, int z) {
        BlockData found = blocks.get(Long.valueOf(Pos.pack(x, y, z)));
        return found == null ? BlockData.UNKNOWN : found;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    /** @return the lowest X in the extent, inclusive */
    int minX() {
        return minX;
    }

    /** @return the highest X in the extent, inclusive */
    int maxX() {
        return maxX;
    }

    /** @return the lowest Z in the extent, inclusive */
    int minZ() {
        return minZ;
    }

    /** @return the highest Z in the extent, inclusive */
    int maxZ() {
        return maxZ;
    }

    /** @return the position marked {@code S}, or {@code null} if the art marks none */
    Pos start() {
        return start;
    }

    /** @return the position marked {@code G}, or {@code null} if the art marks none */
    Pos goal() {
        return goal;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.FixtureWorldTest"`
Expected: PASS, 12 tests.

- [ ] **Step 6: Prove the out-of-extent test is not vacuous**

`readsOutsideTheDeclaredExtentAreUnknownNotAir` is a "does not happen" test — the mistake it guards is the single most dangerous one a fixture can hide, because a pathfinder that reads unmapped space as air walks into mountains.

Mutation: change `FixtureWorld.at`'s fallback from `BlockData.UNKNOWN` to `FixtureBlocks.AIR`.
Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.FixtureWorldTest"`
Expected: `readsOutsideTheDeclaredExtentAreUnknownNotAir` FAILS on all six assertions.
Record the actual output in your report, then revert and confirm with `git diff --stat`.

- [ ] **Step 7: Commit**

```bash
git add core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java
git commit -m "test(c1): add text-art fixture worlds implementing BlockSource

Y-slices lowest first, columns +X and rows +Z, with a fixed legend whose
values are B1's audited numbers rather than round ones — carpet at 0.0625
and a partial floor at farmland's 0.9375.

Reads outside the declared extent are UNKNOWN, never air, and that test
is mutation-proved. Parsing splits on \\r?\\n because git converts these
files LF to CRLF in the working tree and there is no .gitattributes."
```

---

## Task 4: The cost model

This task is **research first, code second**. Its deliverable is a set of constants that can be defended with citations, because the project's standing rule is that Minecraft behaviour is evidenced from the decompiled sources, never recalled. B1 caught three silent, one-version-only wrong answers this way.

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/MovementCosts.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementCostsTest.java`
- Modify: `docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md` (fill §6's table with the derived values and citations)

**Interfaces:**
- Produces: `MovementCosts` with `public static final double` constants `TRAVERSE`, `ASCEND`, `DIAGONAL`, `FALL_PER_BLOCK`, `public static final int MAX_SAFE_FALL`, and `public static double cheapestMove()`

- [ ] **Step 1: Make the 1.21.11 sources greppable**

The 1.7.10 tree is already unpacked at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`. The 1.21.11 tree is **not** currently on disk — only the compiled merged jar is.

Run: `./gradlew :adapters:adapter-fabric-1.21.11:genSources`

Loom writes the sources jar into the **project's own** `.gradle/loom-cache`, not into `GRADLE_USER_HOME` — verified 2026-08-16:

```
.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-<hash>/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-<hash>-1.21.11-...-sources.jar
```

Find it rather than assuming the path, since the `<hash>` segment varies:

```bash
find .gradle/loom-cache -name "minecraft-merged-*-sources.jar"
```

`.gradle/` is the first entry in `.gitignore`, so the jar is already excluded from git. Unpack it **outside the repository** — a previous agent built a 1.7 GB untracked directory inside it:

```bash
mkdir -p /c/Users/qwert/AppData/Local/Temp/continuo-mc-1.21.11-src
unzip -q -o "$(find .gradle/loom-cache -name 'minecraft-merged-*-sources.jar' | head -1)" \
  -d /c/Users/qwert/AppData/Local/Temp/continuo-mc-1.21.11-src
```

Do not set `GRADLE_USER_HOME`. If `genSources` reports `UP-TO-DATE` and you cannot find the jar, it has already run — search for it rather than forcing a re-run.

- [ ] **Step 2: Derive each constant, recording file and line**

Find and record, **for both versions**, with `path:line` for each:

| What | Where to look |
|---|---|
| Base walking speed, and the sprint multiplier | 1.7.10: `net/minecraft/entity/player/EntityPlayer.java` and `PlayerCapabilities`; 1.21.11: the player entity and its movement-speed attribute |
| Jump initial vertical velocity | 1.7.10: `net/minecraft/entity/EntityLivingBase.java`, the jump method; 1.21.11: the equivalent |
| Gravity per tick | the same classes' vertical-motion decrement |
| Fall-damage threshold | 1.7.10: `EntityLivingBase`'s fall handling; 1.21.11: the equivalent |

Write each finding into your report as you go, with the quoted line. Do not proceed on a constant you could not find — report it instead.

- [ ] **Step 3: Record the two decisions the spec requires**

Both go in your report and in the class javadoc:

1. **Walk figure or sprint figure, per movement.** M5's executor sprints wherever it can, so costing everything at the walk rate systematically misranks long straight runs. State which figure each movement uses and why.
2. **Whether the two versions' figures differ.** If they do, take the **slower** and say so. `:core-pathfinder` is pure and shared; there is no per-version cost seam and this task does not add one.

- [ ] **Step 4: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementCostsTest.java`. These are consistency properties, not value assertions — C1 cannot validate realism, only admissibility and coherence.

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementCostsTest {

    @Test
    void everyCostIsPositive() {
        assertTrue(MovementCosts.TRAVERSE > 0, "TRAVERSE");
        assertTrue(MovementCosts.ASCEND > 0, "ASCEND");
        assertTrue(MovementCosts.DIAGONAL > 0, "DIAGONAL");
        assertTrue(MovementCosts.FALL_PER_BLOCK > 0, "FALL_PER_BLOCK");
        assertTrue(MovementCosts.MAX_SAFE_FALL >= 1, "MAX_SAFE_FALL");
    }

    @Test
    void aDiagonalCostsMoreThanAStraightStep() {
        assertTrue(MovementCosts.DIAGONAL > MovementCosts.TRAVERSE,
            "a diagonal covers more ground and must not be a free shortcut");
    }

    @Test
    void climbingCostsMoreThanWalkingOnTheLevel() {
        assertTrue(MovementCosts.ASCEND > MovementCosts.TRAVERSE);
    }

    @Test
    void theCheapestMoveIsALowerBoundOnEveryMove() {
        double cheapest = MovementCosts.cheapestMove();

        assertTrue(cheapest <= MovementCosts.TRAVERSE, "TRAVERSE");
        assertTrue(cheapest <= MovementCosts.ASCEND, "ASCEND");
        assertTrue(cheapest <= MovementCosts.DIAGONAL, "DIAGONAL");
        assertTrue(cheapest <= MovementCosts.TRAVERSE + MovementCosts.FALL_PER_BLOCK,
            "the cheapest possible descend");
        assertTrue(cheapest > 0, "a zero lower bound would make the heuristic useless");
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.MovementCostsTest"`
Expected: compilation failure — `cannot find symbol: class MovementCosts`.

- [ ] **Step 6: Implement `MovementCosts`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/MovementCosts.java` with this exact structure. **Replace each `<derived>` with the value from Step 2 and each `<citation>` with the real `path:line` for both versions.** A constant you could not derive is a blocker to report, not a number to invent.

```java
package dev.continuo.pathfinder;

/**
 * Movement costs, in ticks.
 *
 * <p>Ticks rather than blocks or seconds because that is what the search must compare: a fall is
 * fast per block and a climb is slow, and only a time unit ranks them against each other.
 *
 * <p><b>Every value here is derived from the decompiled sources of both target versions, and
 * every one carries its citation.</b> This project's standing rule is that Minecraft behaviour is
 * evidenced, never recalled; B1 caught three silent, one-version-only wrong answers that way.
 *
 * <p><b>These numbers are admissible and self-consistent, not validated.</b> Nothing in C1
 * executes a path, so nothing in C1 can show they are realistic. M5 is the first thing that can
 * measure, and is where they should be revisited.
 *
 * <p><b>No per-version seam.</b> This module is pure and shared by both adapters. Where the two
 * versions disagree, the slower figure is taken and the disagreement is noted on the constant.
 */
public final class MovementCosts {

    /**
     * Ticks to cross one block on the level.
     *
     * <p>1.7.10: {@code <citation>}. 1.21.11: {@code <citation>}.
     * <p>Uses the {@code <walk|sprint>} figure because {@code <reason>}.
     */
    public static final double TRAVERSE = <derived>;

    /**
     * Ticks to cross one block while climbing one.
     *
     * <p>1.7.10: {@code <citation>}. 1.21.11: {@code <citation>}.
     */
    public static final double ASCEND = <derived>;

    /**
     * Ticks to cross one block diagonally.
     *
     * <p>Declared, not derived: {@link #TRAVERSE} times &radic;2, the geometric ratio.
     */
    public static final double DIAGONAL = TRAVERSE * Math.sqrt(2.0);

    /**
     * Ticks spent falling per block of drop, on top of the step that leaves the ledge.
     *
     * <p>1.7.10: {@code <citation>}. 1.21.11: {@code <citation>}.
     */
    public static final double FALL_PER_BLOCK = <derived>;

    /**
     * The greatest drop, in blocks, taken without damage.
     *
     * <p>1.7.10: {@code <citation>}. 1.21.11: {@code <citation>}.
     */
    public static final int MAX_SAFE_FALL = <derived>;

    private MovementCosts() {
    }

    /**
     * A lower bound on the cost of any single movement.
     *
     * <p>The heuristic multiplies this by a move count, so it must never exceed the true cost of
     * any movement the search can make — that is what keeps A* admissible. When C2 makes the
     * movement set open, this must become a minimum over the active set rather than a constant.
     *
     * @return the cheapest possible single movement, in ticks
     */
    public static double cheapestMove() {
        return Math.min(Math.min(TRAVERSE, ASCEND), Math.min(DIAGONAL, TRAVERSE + FALL_PER_BLOCK));
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.MovementCostsTest"`
Expected: PASS, 4 tests.

- [ ] **Step 8: Fill in the spec's §6 table**

In `docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md`, replace the "Status" column entries in §6's table with the derived value and its citation, and write the two Step 3 decisions into the prose below the table. The spec said no numbers would appear until they were derived; this is where that promise is kept.

- [ ] **Step 9: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/MovementCosts.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementCostsTest.java \
        docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md
git commit -m "feat(c1): derive the tick cost model from both decompiled trees

Every constant carries a file:line citation for 1.7.10 and 1.21.11. The
spec deliberately shipped without numbers so they could be derived rather
than recalled; §6's table is now filled in.

Recorded rather than assumed: which figure (walk or sprint) each movement
costs at, and whether the two versions disagree. Where they do, the slower
is taken — this module is pure and shared, and there is no per-version
cost seam.

The tests assert consistency and the admissibility lower bound, not
values. Nothing in C1 executes a path, so nothing in C1 can show these
are realistic; M5 is the first thing that can."
```

---

## Task 5: The `Move` seam and `TraverseMove`

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Move.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/MoveSink.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/TraverseMove.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/TraverseMoveTest.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/RecordingSink.java`

**Interfaces:**
- Consumes: `Standability`, `MovementCosts`, `FixtureWorld`, `Pos`
- Produces:
  - `interface Move { void expand(BlockSource world, int x, int y, int z, MoveSink sink); }` (package-private)
  - `interface MoveSink { void offer(int x, int y, int z, double cost); }` (package-private)
  - `final class TraverseMove implements Move` (package-private)
  - `RecordingSink` (test) — `offers()` returning `List<String>` of `"(x, y, z)=cost"` in offer order, and `positions()` returning `List<Pos>`
  - `Move.CARDINALS` — `int[][]` of `{dx, dz}` in the fixed order N, E, S, W (`{0,-1}, {1,0}, {0,1}, {-1,0}`)

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/RecordingSink.java`:

```java
package dev.continuo.pathfinder;

import java.util.ArrayList;
import java.util.List;

/** Captures offers in the order they are made, so tests can assert order as well as content. */
final class RecordingSink implements MoveSink {

    private final List<Pos> positions = new ArrayList<Pos>();
    private final List<Double> costs = new ArrayList<Double>();

    @Override
    public void offer(int x, int y, int z, double cost) {
        positions.add(new Pos(x, y, z));
        costs.add(Double.valueOf(cost));
    }

    List<Pos> positions() {
        return positions;
    }

    double costOf(Pos pos) {
        int index = positions.indexOf(pos);
        if (index < 0) {
            throw new AssertionError("no offer for " + pos + "; offers were " + positions);
        }
        return costs.get(index).doubleValue();
    }

    int size() {
        return positions.size();
    }
}
```

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/TraverseMoveTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraverseMoveTest {

    private final Move move = new TraverseMove();

    @Test
    void offersAllFourNeighboursOnAnOpenFloor() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(4, sink.size());
        assertTrue(sink.positions().containsAll(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1))));
    }

    @Test
    void offersNeighboursInAFixedOrderSoTheSearchIsDeterministic() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(Arrays.asList(
            new Pos(1, 65, 0), new Pos(2, 65, 1), new Pos(1, 65, 2), new Pos(0, 65, 1)),
            sink.positions(), "north, east, south, west");
    }

    @Test
    void everyStepCostsOneTraverse() {
        FixtureWorld world = openFloor();

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(MovementCosts.TRAVERSE, sink.costOf(new Pos(2, 65, 1)), 1.0e-9);
    }

    @Test
    void aWallIsNotOffered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "..#\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(3, sink.size());
        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)));
    }

    @Test
    void aHoleIsNotOfferedBecauseTraverseDoesNotFall() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "##.\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)));
    }

    @Test
    void unknownTerrainIsNeverOffered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "##?\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 1)),
            "unreadable terrain might be solid; the search must not assume it is walkable");
    }

    private static FixtureWorld openFloor() {
        return FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.TraverseMoveTest"`
Expected: compilation failure — `cannot find symbol: class Move`.

- [ ] **Step 3: Implement the seam**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/MoveSink.java`:

```java
package dev.continuo.pathfinder;

/**
 * Receives the neighbours a movement generates.
 *
 * <p>A sink rather than a returned collection so that expansion allocates nothing per node in
 * the search's hot loop.
 */
interface MoveSink {

    /**
     * @param x the neighbour's X
     * @param y the neighbour's Y
     * @param z the neighbour's Z
     * @param cost the cost of getting there from the node being expanded, in ticks
     */
    void offer(int x, int y, int z, double cost);
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Move.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/**
 * One kind of movement, able to generate the neighbours it can reach from a position.
 *
 * <p><b>Package-private on purpose.</b> C2 turns this into the public {@code IMovementType} with
 * a registry, capability filtering and {@code ServiceLoader} loading. Keeping it internal until
 * then means the published signature gets shaped by a real registry rather than frozen by the
 * four movements that happen to exist first.
 *
 * <p>Implementations must offer neighbours in a fixed order. The search breaks cost ties by
 * insertion sequence, so expansion order is what makes a path reproducible.
 */
interface Move {

    /** North, east, south and west as {@code {dx, dz}}, in the order every movement uses. */
    int[][] CARDINALS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * @param world the world to read; never {@code null}
     * @param x the X of the position being expanded
     * @param y the Y of the position being expanded
     * @param z the Z of the position being expanded
     * @param sink receives each reachable neighbour
     */
    void expand(BlockSource world, int x, int y, int z, MoveSink sink);
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/TraverseMove.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/** Walking one block to a cardinal neighbour at the same height. */
final class TraverseMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];
            if (Standability.standable(world, nx, y, nz)) {
                sink.offer(nx, y, nz, MovementCosts.TRAVERSE);
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.TraverseMoveTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Prove the unknown-terrain test is not vacuous**

Mutation: in `Standability.passable`, change `if (block.shape() == BlockShape.UNKNOWN) { return false; }` to `if (false) { return false; }`.
Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.TraverseMoveTest"`
Expected: `unknownTerrainIsNeverOffered` FAILS. Record the output, revert, confirm with `git diff --stat`.

- [ ] **Step 6: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/Move.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/MoveSink.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/TraverseMove.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/RecordingSink.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/TraverseMoveTest.java
git commit -m "feat(c1): add the Move seam and cardinal traverse

Move and MoveSink are package-private deliberately. C2 publishes them as
IMovementType with a registry and capability filtering; keeping them
internal until then means the published signature is shaped by a real
registry rather than frozen by the first four movements.

Expansion order is fixed (north, east, south, west) and asserted. The
search breaks cost ties by insertion sequence, so expansion order is what
makes a returned path reproducible."
```

---

## Task 6: `AscendMove`

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AscendMove.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/AscendMoveTest.java`

**Interfaces:**
- Consumes: `Move`, `MoveSink`, `Standability`, `MovementCosts`, `RecordingSink`, `FixtureWorld`
- Produces: `final class AscendMove implements Move` (package-private)

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/AscendMoveTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscendMoveTest {

    private final Move move = new AscendMove();

    /** A step up to the east of a player standing at (0, 65, 0). */
    private static final String STEP =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "##\n"
            + "--- y=65\n"
            + ".#\n"
            + "--- y=66\n"
            + "..\n"
            + "--- y=67\n"
            + "..\n";

    @Test
    void offersTheBlockAboveAStep() {
        RecordingSink sink = new RecordingSink();
        move.expand(FixtureWorld.parse(STEP), 0, 65, 0, sink);

        assertEquals(1, sink.size());
        assertEquals(new Pos(1, 66, 0), sink.positions().get(0));
    }

    @Test
    void climbingCostsAnAscend() {
        RecordingSink sink = new RecordingSink();
        move.expand(FixtureWorld.parse(STEP), 0, 65, 0, sink);

        assertEquals(MovementCosts.ASCEND, sink.costOf(new Pos(1, 66, 0)), 1.0e-9);
    }

    @Test
    void aCeilingOverTheOriginBlocksTheJump() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".#\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "#.\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 65, 0, sink);

        assertEquals(0, sink.size(),
            "y+2 above the origin is where the head goes during the jump");
    }

    @Test
    void aCeilingOverTheLandingBlocksTheClimb() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".#\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + ".#\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 65, 0, sink);

        assertEquals(0, sink.size());
    }

    @Test
    void thereIsNothingToClimbOnFlatGround() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + "..\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 65, 0, sink);

        assertEquals(0, sink.size());
    }

    @Test
    void aFenceIsNotClimbedOnto() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + ".f\n"
                + "--- y=66\n"
                + "..\n"
                + "--- y=67\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 65, 0, sink);

        assertTrue(!sink.positions().contains(new Pos(1, 66, 0)),
            "a fence is 1.5 tall and cannot be jumped onto");
    }

    @Test
    void offersInTheFixedCardinalOrder() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "###\n"
                + "#.#\n"
                + "###\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=67\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertEquals(4, sink.size());
        assertEquals(new Pos(1, 66, 0), sink.positions().get(0), "north first");
        assertEquals(new Pos(2, 66, 1), sink.positions().get(1), "then east");
        assertEquals(new Pos(1, 66, 2), sink.positions().get(2), "then south");
        assertEquals(new Pos(0, 66, 1), sink.positions().get(3), "then west");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AscendMoveTest"`
Expected: compilation failure — `cannot find symbol: class AscendMove`.

- [ ] **Step 3: Implement `AscendMove`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/AscendMove.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/**
 * Jumping up one block onto a cardinal neighbour.
 *
 * <p>Needs clearance two blocks above the origin as well as a landing: the player's head passes
 * through {@code y + 2} during the jump, and a ceiling there stops the movement even though the
 * destination itself is standable.
 */
final class AscendMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        if (!Standability.passable(world.at(x, y + 2, z))) {
            return;
        }
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];
            if (Standability.standable(world, nx, y + 1, nz)) {
                sink.offer(nx, y + 1, nz, MovementCosts.ASCEND);
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AscendMoveTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Prove the headroom test is not vacuous**

Mutation: delete the `if (!Standability.passable(world.at(x, y + 2, z))) { return; }` guard from `AscendMove.expand`.
Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AscendMoveTest"`
Expected: `aCeilingOverTheOriginBlocksTheJump` FAILS. Record the output, revert, confirm with `git diff --stat`.

- [ ] **Step 6: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/AscendMove.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/AscendMoveTest.java
git commit -m "feat(c1): add ascend

Requires clearance at y+2 above the origin, not just a standable landing.
The head passes through that block during the jump, and a ceiling there
stops the movement even though the destination is fine. That guard is
mutation-proved, because it is invisible in a test that only checks the
destination."
```

---

## Task 7: `DescendMove`

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/DescendMove.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/DescendMoveTest.java`

**Interfaces:**
- Consumes: `Move`, `MoveSink`, `Standability`, `MovementCosts`, `RecordingSink`, `FixtureWorld`
- Produces: `final class DescendMove implements Move` (package-private)

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/DescendMoveTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescendMoveTest {

    private final Move move = new DescendMove();

    /**
     * A shaft of the given depth to the east of a player standing at (0, 100, 0).
     * The floor of the shaft is {@code depth} blocks below the player's feet.
     */
    private static FixtureWorld shaft(int depth) {
        StringBuilder art = new StringBuilder();
        int floorY = 100 - depth - 1;
        art.append("origin: 0,").append(floorY).append(",0\n");
        art.append("--- y=").append(floorY).append("\n##\n");
        for (int y = floorY + 1; y < 100; y++) {
            art.append("--- y=").append(y).append("\n#.\n");
        }
        art.append("--- y=100\n..\n");
        art.append("--- y=101\n..\n");
        return FixtureWorld.parse(art.toString());
    }

    @Test
    void steppingDownOneIsOffered() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(1), 0, 100, 0, sink);

        assertEquals(1, sink.size());
        assertEquals(new Pos(1, 99, 0), sink.positions().get(0));
    }

    @Test
    void aDropCostsATraversePlusFallTimePerBlock() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(3), 0, 100, 0, sink);

        assertEquals(MovementCosts.TRAVERSE + 3 * MovementCosts.FALL_PER_BLOCK,
            sink.costOf(new Pos(1, 97, 0)), 1.0e-9);
    }

    @Test
    void onlyTheFirstFloorBelowIsOffered() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(2), 0, 100, 0, sink);

        assertEquals(1, sink.size(),
            "the search descends to the floor it lands on, not to every level above it");
    }

    @Test
    void aDropDeeperThanTheSafeLimitIsRefused() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(MovementCosts.MAX_SAFE_FALL + 1), 0, 100, 0, sink);

        assertEquals(0, sink.size(), "falling further than the safe limit takes damage");
    }

    @Test
    void aDropOfExactlyTheSafeLimitIsAccepted() {
        RecordingSink sink = new RecordingSink();
        move.expand(shaft(MovementCosts.MAX_SAFE_FALL), 0, 100, 0, sink);

        assertEquals(1, sink.size(), "the limit itself is safe; this pins the off-by-one");
    }

    @Test
    void aWallBesideTheLedgeBlocksTheStepOff() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,98,0\n"
                + "--- y=98\n"
                + "##\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + ".#\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(), "you cannot walk off a ledge through a wall");
    }

    @Test
    void unknownTerrainInTheShaftIsNotDescendedInto() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,97,0\n"
                + "--- y=97\n"
                + "##\n"
                + "--- y=98\n"
                + "#?\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + "..\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertEquals(0, sink.size(),
            "an unreadable block in the shaft might be solid, or might be a ledge");
    }

    @Test
    void lavaAtTheBottomIsNotALandingSite() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,98,0\n"
                + "--- y=98\n"
                + "#!\n"
                + "--- y=99\n"
                + "#.\n"
                + "--- y=100\n"
                + "..\n"
                + "--- y=101\n"
                + "..\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 0, 100, 0, sink);

        assertTrue(!sink.positions().contains(new Pos(1, 99, 0)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DescendMoveTest"`
Expected: compilation failure — `cannot find symbol: class DescendMove`.

- [ ] **Step 3: Implement `DescendMove`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/DescendMove.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/**
 * Walking off a ledge to a cardinal neighbour and falling to the first floor below.
 *
 * <p>Only the landing is offered, not every level passed through: the player has no control
 * during a fall, so the intermediate positions are not choices the search can make.
 *
 * <p>Stops at the first non-passable block in the shaft. If that block is not a floor — because
 * it is unreadable, lava, or a bottom slab — nothing is offered at all, rather than the search
 * assuming it can land somewhere it cannot.
 */
final class DescendMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];

            if (!Standability.passable(world.at(nx, y, nz))
                || !Standability.passable(world.at(nx, y + 1, nz))) {
                continue;
            }

            for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
                int landingY = y - drop;
                if (Standability.standable(world, nx, landingY, nz)) {
                    sink.offer(nx, landingY, nz,
                        MovementCosts.TRAVERSE + drop * MovementCosts.FALL_PER_BLOCK);
                    break;
                }
                if (!Standability.passable(world.at(nx, landingY, nz))) {
                    break;
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DescendMoveTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Prove the safe-fall limit is not vacuous**

Two mutations, run separately. Both must fail, and they must fail *different* tests — that is what pins the boundary rather than merely the direction.

| Mutation | Test that must fail |
|---|---|
| `drop <= MovementCosts.MAX_SAFE_FALL` → `drop <= MovementCosts.MAX_SAFE_FALL + 1` | `aDropDeeperThanTheSafeLimitIsRefused` |
| `drop <= MovementCosts.MAX_SAFE_FALL` → `drop < MovementCosts.MAX_SAFE_FALL` | `aDropOfExactlyTheSafeLimitIsAccepted` |

Run each as: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DescendMoveTest"`
Record both outputs, revert, confirm with `git diff --stat`.

- [ ] **Step 6: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/DescendMove.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/DescendMoveTest.java
git commit -m "feat(c1): add descend, bounded by the safe fall limit

Only the landing is offered, not every level passed through — the player
has no control during a fall, so intermediate positions are not choices.

The shaft scan stops at the first non-passable block. If that block is not
a floor (unreadable, lava, a bottom slab) nothing is offered, rather than
the search assuming it can land where it cannot.

Both sides of the fall limit are mutation-proved, and they fail different
tests, which is what pins the boundary rather than the direction."
```

---

## Task 8: `DiagonalMove`

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/DiagonalMoveTest.java`

**Interfaces:**
- Consumes: `Move`, `MoveSink`, `Standability`, `MovementCosts`, `RecordingSink`, `FixtureWorld`
- Produces: `final class DiagonalMove implements Move` (package-private), with `DIAGONALS` as a private `int[][]` in the order NE, SE, SW, NW (`{1,-1}, {1,1}, {-1,1}, {-1,-1}`)

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/DiagonalMoveTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagonalMoveTest {

    private final Move move = new DiagonalMove();

    private static FixtureWorld openFloor() {
        return FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");
    }

    @Test
    void offersAllFourDiagonalsOnAnOpenFloor() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(4, sink.size());
        assertTrue(sink.positions().containsAll(Arrays.asList(
            new Pos(2, 65, 0), new Pos(2, 65, 2), new Pos(0, 65, 2), new Pos(0, 65, 0))));
    }

    @Test
    void offersDiagonalsInAFixedOrder() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(Arrays.asList(
            new Pos(2, 65, 0), new Pos(2, 65, 2), new Pos(0, 65, 2), new Pos(0, 65, 0)),
            sink.positions(), "north-east, south-east, south-west, north-west");
    }

    @Test
    void aDiagonalCostsMoreThanAStraightStep() {
        RecordingSink sink = new RecordingSink();
        move.expand(openFloor(), 1, 65, 1, sink);

        assertEquals(MovementCosts.DIAGONAL, sink.costOf(new Pos(2, 65, 0)), 1.0e-9);
        assertTrue(MovementCosts.DIAGONAL > MovementCosts.TRAVERSE);
    }

    @Test
    void aCornerCannotBeCutThroughOneBlockedSide() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + ".#.\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the destination is standable but the north side of the corner is solid");
    }

    @Test
    void aCornerCannotBeCutThroughTheOtherBlockedSide() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "..#\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the destination is standable but the east side of the corner is solid");
    }

    @Test
    void aCornerBlockedAtHeadHeightAloneStillBlocks() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "..#\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)),
            "the player is two blocks tall; a corner blocked at head height blocks the squeeze");
    }

    @Test
    void aDiagonalOntoNothingIsNotOffered() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##.\n"
                + "###\n"
                + "###\n"
                + "--- y=65\n"
                + "...\n"
                + "...\n"
                + "...\n"
                + "--- y=66\n"
                + "...\n"
                + "...\n"
                + "...\n");

        RecordingSink sink = new RecordingSink();
        move.expand(world, 1, 65, 1, sink);

        assertTrue(!sink.positions().contains(new Pos(2, 65, 0)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DiagonalMoveTest"`
Expected: compilation failure — `cannot find symbol: class DiagonalMove`.

- [ ] **Step 3: Implement `DiagonalMove`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/**
 * Walking one block diagonally on the level.
 *
 * <p><b>Both orthogonal sides of the corner must be clear, at feet and at head height.</b>
 * Minecraft does not let a player squeeze between two blocks meeting at a corner, and a search
 * that allows it produces paths that look shorter and cannot be walked. Checking only the
 * destination is the classic version of this bug.
 */
final class DiagonalMove implements Move {

    /** North-east, south-east, south-west, north-west as {@code {dx, dz}}. */
    private static final int[][] DIAGONALS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < DIAGONALS.length; i++) {
            int dx = DIAGONALS[i][0];
            int dz = DIAGONALS[i][1];
            int nx = x + dx;
            int nz = z + dz;

            if (!Standability.standable(world, nx, y, nz)) {
                continue;
            }
            if (!clear(world, nx, y, z) || !clear(world, x, y, nz)) {
                continue;
            }
            sink.offer(nx, y, nz, MovementCosts.DIAGONAL);
        }
    }

    /** Whether a two-block-tall body fits at this column, feet at {@code y}. */
    private static boolean clear(BlockSource world, int x, int y, int z) {
        return Standability.passable(world.at(x, y, z))
            && Standability.passable(world.at(x, y + 1, z));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DiagonalMoveTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Prove the corner-cut checks are not vacuous**

Three mutations, run separately. Removing one check leaves the other guarding, so a single combined mutation would prove nothing about either.

| Mutation to `DiagonalMove.expand` | Test that must fail |
|---|---|
| Drop `!clear(world, nx, y, z)` from the condition | `aCornerCannotBeCutThroughOneBlockedSide` |
| Drop `!clear(world, x, y, nz)` from the condition | `aCornerCannotBeCutThroughTheOtherBlockedSide` |
| In `clear`, drop the `y + 1` check | `aCornerBlockedAtHeadHeightAloneStillBlocks` |

Run each as: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.DiagonalMoveTest"`
Record all three outputs, revert, confirm with `git diff --stat`.

- [ ] **Step 6: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/DiagonalMoveTest.java
git commit -m "feat(c1): add diagonal movement with corner-cut checks

Both orthogonal sides of the corner must be clear at feet and head height.
Minecraft does not let a player squeeze between two blocks meeting at a
corner, and a search that allows it returns paths that look shorter and
cannot be walked.

All three checks are mutation-proved separately, failing three different
tests — removing one leaves the others guarding, so a combined mutation
would prove nothing."
```

---

## Task 9: Goals and the heuristic

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Goal.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalBlock.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalXZ.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/GoalTest.java`

**Interfaces:**
- Consumes: `MovementCosts`
- Produces:
  - `public interface Goal { boolean isReached(int,int,int); double heuristic(int,int,int); }`
  - `public final class GoalBlock` — `new GoalBlock(int x, int y, int z)`
  - `public final class GoalXZ` — `new GoalXZ(int x, int z)`

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/GoalTest.java`:

```java
package dev.continuo.pathfinder;

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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.GoalTest"`
Expected: compilation failure — `cannot find symbol: class Goal`.

- [ ] **Step 3: Implement the three types**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/Goal.java`:

```java
package dev.continuo.pathfinder;

/**
 * What the search is trying to reach, and how far away it estimates itself to be.
 *
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here keep that guarantee by construction
 * rather than by argument: they count the fewest moves that could possibly close the gap and
 * multiply by the cheapest possible move.
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
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z);
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalBlock.java`:

```java
package dev.continuo.pathfinder;

/**
 * One exact block position.
 *
 * <p>The heuristic is {@code cheapestMove × max(|dx|, |dy|, |dz|)}. Every movement changes each
 * axis by at most one, so the largest single-axis gap is a lower bound on the number of moves
 * still needed, and multiplying it by the cheapest possible move cannot overestimate. Taking the
 * maximum rather than the sum is what makes a diagonal — which closes X and Z together — free of
 * double-counting.
 */
public final class GoalBlock implements Goal {

    private final int x;
    private final int y;
    private final int z;

    /**
     * @param x target X
     * @param y target Y
     * @param z target Z
     */
    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isReached(int px, int py, int pz) {
        return px == x && py == y && pz == z;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        int moves = Math.max(Math.abs(x - px), Math.max(Math.abs(y - py), Math.abs(z - pz)));
        return moves * MovementCosts.cheapestMove();
    }

    @Override
    public String toString() {
        return "GoalBlock(" + x + ", " + y + ", " + z + ")";
    }
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalXZ.java`:

```java
package dev.continuo.pathfinder;

/**
 * A column: any height at one X and Z.
 *
 * <p>The heuristic ignores Y entirely, which is what keeps it admissible — a candidate far above
 * the target column may still be one move from satisfying the goal if the terrain drops away.
 */
public final class GoalXZ implements Goal {

    private final int x;
    private final int z;

    /**
     * @param x target X
     * @param z target Z
     */
    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean isReached(int px, int py, int pz) {
        return px == x && pz == z;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        int moves = Math.max(Math.abs(x - px), Math.abs(z - pz));
        return moves * MovementCosts.cheapestMove();
    }

    @Override
    public String toString() {
        return "GoalXZ(" + x + ", " + z + ")";
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.GoalTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/Goal.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalBlock.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalXZ.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/GoalTest.java
git commit -m "feat(c1): add Goal, GoalBlock and GoalXZ

The heuristic is cheapestMove * max(|dx|, |dy|, |dz|). Every movement
changes each axis by at most one, so admissibility holds by construction
rather than by an argument about diagonal factors — and it keeps holding
when C2 adds movements, provided cheapestMove stays a real lower bound
over the active set.

Deliberately loose. C1 has no performance target to trade tightness
against; search effort is C4's subject."
```

---

## Task 10: The A\* search

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResult.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathNode.java`
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/AStarPathfinderTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–9
- Produces:
  - `enum PathOutcome { FOUND, NO_PATH, BUDGET_EXCEEDED }`
  - `PathResult` — `outcome()`, `path()` returning `List<Pos>`, `expanded()` returning `List<Pos>`, `cost()` returning `double`, `nodesExpanded()` returning `int`
  - `AStarPathfinder` — `new AStarPathfinder()` (default budget), `new AStarPathfinder(int nodeBudget)`, `findPath(BlockSource world, int x, int y, int z, Goal goal)` returning `PathResult`, and `DEFAULT_NODE_BUDGET`

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/AStarPathfinderTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarPathfinderTest {

    private final AStarPathfinder pathfinder = new AStarPathfinder();

    private static PathResult run(AStarPathfinder pathfinder, FixtureWorld world) {
        Pos start = world.start();
        Pos goal = world.goal();
        return pathfinder.findPath(world, start.x(), start.y(), start.z(),
            new GoalBlock(goal.x(), goal.y(), goal.z()));
    }

    @Test
    void walksStraightAcrossOpenGround() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(), "start plus four steps");
        assertEquals(new Pos(0, 65, 0), result.path().get(0));
        assertEquals(new Pos(4, 65, 0), result.path().get(4));
    }

    @Test
    void routesAroundAWall() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "..#..\n"
                + ".....\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertTrue(!result.path().contains(new Pos(2, 65, 0)));
        assertTrue(!result.path().contains(new Pos(2, 65, 1)));
        assertEquals(new Pos(4, 65, 0), result.path().get(result.path().size() - 1));
    }

    @Test
    void climbsAStaircase() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "####\n"
                + "--- y=65\n"
                + "S###\n"
                + "--- y=66\n"
                + "..##\n"
                + "--- y=67\n"
                + "...#\n"
                + "--- y=68\n"
                + "...G\n"
                + "--- y=69\n"
                + "....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(new Pos(3, 68, 0), result.path().get(result.path().size() - 1));
        assertEquals(4, result.path().size());
    }

    @Test
    void descendsASafeDrop() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##\n"
                + "--- y=65\n"
                + "##\n"
                + "--- y=66\n"
                + "G#\n"
                + "--- y=67\n"
                + ".S\n"
                + "--- y=68\n"
                + "..\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(new Pos(0, 66, 0), result.path().get(result.path().size() - 1));
        assertEquals(2, result.path().size(), "one step west, falling one block");
    }

    @Test
    void reportsNoPathWhenWalledOff() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome());
        assertTrue(result.path().isEmpty());
    }

    @Test
    void neverRoutesThroughUnknownTerrain() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##?##\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "unreadable ground might not be there; the search must not walk over it");
    }

    @Test
    void neverRoutesThroughLava() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.!.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome());
    }

    @Test
    void neverRoutesThroughWater() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.~.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "C1 has no swimming movement, so water is an obstacle rather than a shortcut");
    }

    @Test
    void carpetIsWalkedThroughOnBothVersionsValues() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.c.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(), "carpet is not an obstacle and not a step up");
    }

    @Test
    void aPartialFloorIsWalkedOnJustLikeAFullBlock() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "##p##\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(5, result.path().size(),
            "farmland's 1.21.11 value must be a floor, or modern farms become walls");
    }

    @Test
    void aBottomSlabIsAnObstacleInC1() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S._.G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "a documented C1 limitation: an integer node cannot hold feet at y+0.5");
    }

    @Test
    void reportsBudgetExceededWithoutAPartialPath() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = new AStarPathfinder(2).findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.BUDGET_EXCEEDED, result.outcome());
        assertTrue(result.path().isEmpty(),
            "returning the best node so far is incremental cost backoff, which is C4's");
    }

    @Test
    void theSameSearchReturnsTheIdenticalPathEveryTime() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + "....G\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        List<Pos> first = run(pathfinder, world).path();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, run(new AStarPathfinder(), world).path(),
                "ties must break identically or the tests become flaky");
        }
    }

    @Test
    void theHeuristicNeverExceedsTheCostActuallyPaid() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#..\n"
                + "..#..\n"
                + "....G\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);
        Goal goal = new GoalBlock(4, 65, 2);

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertTrue(goal.heuristic(0, 65, 0) <= result.cost(),
            "an overestimating heuristic silently gives up the shortest-path guarantee");
    }

    @Test
    void aGoalXzIsSatisfiedAtAnyHeight() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "####\n"
                + "--- y=65\n"
                + "S###\n"
                + "--- y=66\n"
                + "..##\n"
                + "--- y=67\n"
                + "...#\n"
                + "--- y=68\n"
                + "....\n"
                + "--- y=69\n"
                + "....\n");

        PathResult result = pathfinder.findPath(world, 0, 65, 0, new GoalXZ(3, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(3, result.path().get(result.path().size() - 1).x());
    }

    @Test
    void theStartItselfSatisfiesAnAlreadyReachedGoal() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#\n"
                + "--- y=65\n"
                + ".\n"
                + "--- y=66\n"
                + ".\n");

        PathResult result = pathfinder.findPath(world, 0, 65, 0, new GoalBlock(0, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(1, result.path().size());
    }

    @Test
    void expandedNodesAreReportedForTheRenderer() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S...G\n"
                + "--- y=66\n"
                + ".....\n");

        PathResult result = run(pathfinder, world);

        assertTrue(result.nodesExpanded() > 0);
        assertEquals(result.nodesExpanded(), result.expanded().size());
        assertTrue(result.expanded().contains(new Pos(0, 65, 0)), "the start is expanded first");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AStarPathfinderTest"`
Expected: compilation failure — `cannot find symbol: class AStarPathfinder`.

- [ ] **Step 3: Implement `PathOutcome` and `PathResult`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java`:

```java
package dev.continuo.pathfinder;

/** How a search ended. */
public enum PathOutcome {

    /** A path to the goal was found. */
    FOUND,

    /** Everything reachable was searched and the goal was not among it. */
    NO_PATH,

    /**
     * The node budget ran out first.
     *
     * <p>Distinct from {@link #NO_PATH} because a path may well exist — the search simply did not
     * get to it. Nothing partial is returned; salvaging the best node reached is incremental cost
     * backoff, which is C4's subject.
     */
    BUDGET_EXCEEDED
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResult.java`:

```java
package dev.continuo.pathfinder;

import java.util.Collections;
import java.util.List;

/** What a search produced: the outcome, the path if there is one, and enough to draw it. */
public final class PathResult {

    private final PathOutcome outcome;
    private final List<Pos> path;
    private final List<Pos> expanded;
    private final double cost;

    /**
     * @param outcome how the search ended; never {@code null}
     * @param path start-to-goal inclusive, empty unless the outcome is {@link PathOutcome#FOUND}
     * @param expanded every node taken off the open set, in expansion order
     * @param cost the path's total cost in ticks, {@code 0} when there is no path
     */
    PathResult(PathOutcome outcome, List<Pos> path, List<Pos> expanded, double cost) {
        this.outcome = outcome;
        this.path = Collections.unmodifiableList(path);
        this.expanded = Collections.unmodifiableList(expanded);
        this.cost = cost;
    }

    /** @return how the search ended; never {@code null} */
    public PathOutcome outcome() {
        return outcome;
    }

    /** @return the path from start to goal inclusive, unmodifiable; empty if none was found */
    public List<Pos> path() {
        return path;
    }

    /** @return every expanded node in expansion order, unmodifiable; for the renderer */
    public List<Pos> expanded() {
        return expanded;
    }

    /** @return the path's total cost in ticks; {@code 0} when no path was found */
    public double cost() {
        return cost;
    }

    /** @return how many nodes were expanded */
    public int nodesExpanded() {
        return expanded.size();
    }

    @Override
    public String toString() {
        return "PathResult[" + outcome + ", " + path.size() + " steps, "
            + expanded.size() + " expanded, cost " + cost + "]";
    }
}
```

- [ ] **Step 4: Implement `PathNode` and `AStarPathfinder`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathNode.java`:

```java
package dev.continuo.pathfinder;

/**
 * A position the search has reached.
 *
 * <p>Mutable and package-private: A* updates {@code g} and {@code f} in place when it finds a
 * cheaper route to a node it has already seen.
 */
final class PathNode {

    final long packed;
    final int sequence;
    double g;
    double f;
    PathNode parent;
    boolean closed;

    PathNode(long packed, int sequence) {
        this.packed = packed;
        this.sequence = sequence;
        this.g = Double.POSITIVE_INFINITY;
        this.f = Double.POSITIVE_INFINITY;
    }
}
```

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over an implicit graph of block positions.
 *
 * <p><b>Deterministic by construction.</b> Movements expand in a fixed order, each node carries
 * the sequence number it was discovered at, and the open set orders by {@code f}, then by the
 * heuristic, then by that sequence. Every comparison is therefore total, so an identical search
 * over an identical world returns an identical path — which is what makes it possible to assert
 * *which* path a test expects rather than merely that one exists.
 *
 * <p><b>The node budget is a stopping condition, not a fallback.</b> Exhausting it yields
 * {@link PathOutcome#BUDGET_EXCEEDED} and no path at all. Returning the best node reached so far
 * is incremental cost backoff, which is C4's subject and not something to half-build here.
 */
public final class AStarPathfinder {

    /**
     * The node budget a search uses when none is given.
     *
     * <p>Chosen to be far above anything a fixture world can need and far below anything that
     * would hang a test. C4 replaces this with a real search-effort policy.
     */
    public static final int DEFAULT_NODE_BUDGET = 100000;

    private static final Move[] MOVES = {
        new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
    };

    private final int nodeBudget;

    /** Creates a pathfinder with {@link #DEFAULT_NODE_BUDGET}. */
    public AStarPathfinder() {
        this(DEFAULT_NODE_BUDGET);
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @throws IllegalArgumentException if the budget is not positive
     */
    public AStarPathfinder(int nodeBudget) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        this.nodeBudget = nodeBudget;
    }

    /**
     * Searches for a path.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }

        final Map<Long, PathNode> nodes = new HashMap<Long, PathNode>();
        final List<Pos> expanded = new ArrayList<Pos>();
        final int[] discovered = {0};

        PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(64, new Comparator<PathNode>() {
            @Override
            public int compare(PathNode a, PathNode b) {
                int byF = Double.compare(a.f, b.f);
                if (byF != 0) {
                    return byF;
                }
                int byG = Double.compare(b.g, a.g);
                if (byG != 0) {
                    return byG;
                }
                return Integer.compare(a.sequence, b.sequence);
            }
        });

        PathNode start = new PathNode(Pos.pack(startX, startY, startZ), discovered[0]++);
        start.g = 0.0;
        start.f = goal.heuristic(startX, startY, startZ);
        nodes.put(Long.valueOf(start.packed), start);
        open.add(start);

        while (!open.isEmpty()) {
            final PathNode current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;

            final int cx = Pos.unpackX(current.packed);
            final int cy = Pos.unpackY(current.packed);
            final int cz = Pos.unpackZ(current.packed);
            expanded.add(new Pos(cx, cy, cz));

            if (goal.isReached(cx, cy, cz)) {
                return new PathResult(PathOutcome.FOUND, reconstruct(current), expanded, current.g);
            }
            if (expanded.size() >= nodeBudget) {
                return new PathResult(PathOutcome.BUDGET_EXCEEDED,
                    Collections.<Pos>emptyList(), expanded, 0.0);
            }

            final PriorityQueue<PathNode> openRef = open;
            MoveSink sink = new MoveSink() {
                @Override
                public void offer(int nx, int ny, int nz, double cost) {
                    long key = Pos.pack(nx, ny, nz);
                    PathNode neighbour = nodes.get(Long.valueOf(key));
                    if (neighbour == null) {
                        neighbour = new PathNode(key, discovered[0]++);
                        nodes.put(Long.valueOf(key), neighbour);
                    }
                    if (neighbour.closed) {
                        return;
                    }
                    double tentative = current.g + cost;
                    if (tentative >= neighbour.g) {
                        return;
                    }
                    neighbour.g = tentative;
                    neighbour.f = tentative + goal.heuristic(nx, ny, nz);
                    neighbour.parent = current;
                    openRef.add(neighbour);
                }
            };

            for (int i = 0; i < MOVES.length; i++) {
                MOVES[i].expand(world, cx, cy, cz, sink);
            }
        }

        return new PathResult(PathOutcome.NO_PATH, Collections.<Pos>emptyList(), expanded, 0.0);
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

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AStarPathfinderTest"`
Expected: PASS, 17 tests.

If `theSameSearchReturnsTheIdenticalPathEveryTime` fails, the comparator is not a total order — do **not** work around it by relaxing the assertion. A non-deterministic search makes every path assertion in this suite flaky, and diagnosing that later costs far more than fixing the comparator now.

- [ ] **Step 6: Prove the refusal tests are not vacuous**

Each of these asserts that a path is *not* found, and a test asserting `NO_PATH` passes trivially if the search is broken in an unrelated way. Prove each one responds to its own subject: mutate the world, not the code, so the test must flip to `FOUND`.

| # | Change | Test that must fail |
|---|---|---|
| 1 | In `neverRoutesThroughUnknownTerrain`, change `##?##` to `#####` | `neverRoutesThroughUnknownTerrain` — now finds a path |
| 2 | In `neverRoutesThroughLava`, change `S.!.G` to `S...G` | `neverRoutesThroughLava` |
| 3 | In `neverRoutesThroughWater`, change `S.~.G` to `S...G` | `neverRoutesThroughWater` |
| 4 | In `aBottomSlabIsAnObstacleInC1`, change `S._.G` to `S...G` | `aBottomSlabIsAnObstacleInC1` |

This is the inverse of the usual mutation: the test claims a specific obstacle causes the refusal, so removing that obstacle must change the outcome. If it does not, the test was passing for some other reason.

Run each as: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.AStarPathfinderTest"`
Record all four outputs, revert, confirm with `git diff --stat`.

- [ ] **Step 7: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/PathOutcome.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/PathResult.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/PathNode.java \
        core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/AStarPathfinderTest.java
git commit -m "feat(c1): add the A* search

Deterministic by construction: movements expand in a fixed order, nodes
carry a discovery sequence, and the open set orders by f, then by g, then
by that sequence. Every comparison is total, so a test can assert which
path comes back rather than merely that one does.

A budget hit returns BUDGET_EXCEEDED and no partial path. Salvaging the
best node reached is incremental cost backoff, which is C4's.

The four refusal tests are mutation-proved in the inverse direction: the
obstacle is removed from the fixture and the outcome must flip to FOUND.
A test asserting NO_PATH otherwise passes trivially when the search is
broken for an unrelated reason."
```

---

## Task 11: The path renderer

**Files:**
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java`

**Interfaces:**
- Consumes: `FixtureWorld`, `FixtureBlocks`, `PathResult`, `Pos`
- Produces: `PathRenderer.render(FixtureWorld world, PathResult result)` returning `String`

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java`:

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathRendererTest {

    private static final String FLAT =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "#####\n"
            + "--- y=65\n"
            + "S...G\n"
            + "--- y=66\n"
            + ".....\n";

    @Test
    void drawsTerrainStartGoalAndPath() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.contains("origin: 0,64,0"), rendered);
        assertTrue(rendered.contains("--- y=65"), rendered);
        assertTrue(rendered.contains("S**"), "the path between start and goal is marked\n" + rendered);
        assertTrue(rendered.contains("G"), rendered);
    }

    @Test
    void marksExpandedNodesThatAreNotOnThePath() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + ".....\n"
                + "--- y=66\n"
                + ".....\n"
                + ".....\n");
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.indexOf(FixtureWorld.EXPANDED) >= 0,
            "detouring around the wall expands nodes that the final path does not use\n"
                + rendered);
    }

    @Test
    void terrainSurvivesARenderParseRoundTrip() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        FixtureWorld reparsed = FixtureWorld.parse(PathRenderer.render(world, result));

        for (int y = world.minY(); y < world.maxY(); y++) {
            for (int x = world.minX(); x <= world.maxX(); x++) {
                for (int z = world.minZ(); z <= world.maxZ(); z++) {
                    assertEquals(world.at(x, y, z), reparsed.at(x, y, z),
                        "terrain differs at " + new Pos(x, y, z));
                }
            }
        }
    }

    @Test
    void theRoundTripPreservesTheExtent() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        FixtureWorld reparsed = FixtureWorld.parse(PathRenderer.render(world, result));

        assertEquals(world.minX(), reparsed.minX());
        assertEquals(world.maxX(), reparsed.maxX());
        assertEquals(world.minY(), reparsed.minY());
        assertEquals(world.maxY(), reparsed.maxY());
        assertEquals(world.minZ(), reparsed.minZ());
        assertEquals(world.maxZ(), reparsed.maxZ());
    }

    @Test
    void aFailedSearchStillRendersSoTheFailureCanBeRead() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "#####\n"
                + "--- y=65\n"
                + "S.#.G\n"
                + "--- y=66\n"
                + ".....\n");
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        assertEquals(PathOutcome.NO_PATH, result.outcome());

        String rendered = PathRenderer.render(world, result);

        assertTrue(rendered.contains("NO_PATH"), "the outcome belongs in the dump\n" + rendered);
        assertTrue(rendered.contains("#"), rendered);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PathRendererTest"`
Expected: compilation failure — `cannot find symbol: class PathRenderer`.

- [ ] **Step 3: Implement `PathRenderer`**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders a world and a search result as text art, in the same format fixtures are written in.
 *
 * <p>ASCII rather than an image, deliberately. The people and agents who debug this read test
 * output as text; a PNG written to the build directory would be invisible to every one of them.
 *
 * <p><b>The output round-trips.</b> Overlay characters read back as air when parsed, so a
 * rendered failure can be pasted straight into a test as a new fixture — the terrain survives
 * and the annotations degrade harmlessly. Put this in an assertion message rather than reasoning
 * about a failing path from coordinates alone.
 */
final class PathRenderer {

    private PathRenderer() {
    }

    /**
     * @param world the world that was searched; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    static String render(FixtureWorld world, PathResult result) {
        Map<BlockData, Character> reverse = new HashMap<BlockData, Character>();
        for (Map.Entry<Character, BlockData> entry : FixtureBlocks.legend().entrySet()) {
            if (!reverse.containsKey(entry.getValue())) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }

        Set<Long> path = new HashSet<Long>();
        for (Pos pos : result.path()) {
            path.add(Long.valueOf(pos.packed()));
        }
        Set<Long> expanded = new HashSet<Long>();
        for (Pos pos : result.expanded()) {
            expanded.add(Long.valueOf(pos.packed()));
        }

        Long start = result.path().isEmpty()
            ? null : Long.valueOf(result.path().get(0).packed());
        Long goal = result.path().isEmpty()
            ? null : Long.valueOf(result.path().get(result.path().size() - 1).packed());

        StringBuilder out = new StringBuilder();
        out.append("origin: ").append(world.minX()).append(',')
            .append(world.minY()).append(',').append(world.minZ()).append('\n');

        for (int y = world.minY(); y < world.maxY(); y++) {
            out.append("--- y=").append(y).append('\n');
            for (int z = world.minZ(); z <= world.maxZ(); z++) {
                for (int x = world.minX(); x <= world.maxX(); x++) {
                    Long key = Long.valueOf(Pos.pack(x, y, z));
                    if (key.equals(start)) {
                        out.append(FixtureWorld.START);
                    } else if (key.equals(goal)) {
                        out.append(FixtureWorld.GOAL);
                    } else if (path.contains(key)) {
                        out.append(FixtureWorld.PATH);
                    } else if (expanded.contains(key)) {
                        out.append(FixtureWorld.EXPANDED);
                    } else {
                        Character ch = reverse.get(world.at(x, y, z));
                        out.append(ch == null ? '?' : ch.charValue());
                    }
                }
                out.append('\n');
            }
        }

        out.append("// ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost()).append('\n');
        return out.toString();
    }
}
```

The summary line uses `//` rather than `#` because `#` is the legend character for stone — a summary line starting with it would parse as a terrain row. Task 3's parser already skips `//` lines, so this round-trips with no change to `FixtureWorld`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PathRendererTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Prove the round-trip test is not vacuous**

Mutation: in `PathRenderer.render`, replace the terrain lookup `Character ch = reverse.get(world.at(x, y, z));` with `Character ch = Character.valueOf('.');`.
Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PathRendererTest"`
Expected: `terrainSurvivesARenderParseRoundTrip` FAILS at the first stone block. Record the output, revert, confirm with `git diff --stat`.

- [ ] **Step 6: Commit**

```bash
git add core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java
git commit -m "test(c1): add the ASCII path renderer

Same format the fixtures are written in, so the output round-trips:
overlay characters read back as air, terrain survives, and a rendered
failure pastes straight into a test as a new fixture. That property is
what makes the renderer worth building now rather than waiting for the
web UI at M7.

ASCII rather than PNG deliberately — the agents and reviewers who debug
this read test output as text and cannot open an image."
```

---

## Task 12: Acceptance and whole-branch verification

**Files:**
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java`
- Modify: `docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md` (§8 done criteria, tick them off)

**Interfaces:**
- Consumes: everything

- [ ] **Step 1: Write the acceptance test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java`. This is the suite a reader should be able to open to see what C1 actually does, and it is where the renderer earns its place — every failure prints the world.

```java
package dev.continuo.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderAcceptanceTest {

    /**
     * A world with a wall, a staircase over it, and a drop on the far side. The only route is
     * up and over, which exercises all four movements in one search.
     */
    private static final String OBSTACLE_COURSE =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "#######\n"
            + "#######\n"
            + "#######\n"
            + "--- y=65\n"
            + "S..#..G\n"
            + "...#...\n"
            + "...#...\n"
            + "--- y=66\n"
            + "...#...\n"
            + ".......\n"
            + "...#...\n"
            + "--- y=67\n"
            + ".......\n"
            + ".......\n"
            + ".......\n";

    @Test
    void findsTheOnlyRouteOverTheWall() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        assertEquals(PathOutcome.FOUND, result.outcome(), render(world, result));
        assertEquals(new Pos(0, 65, 0), result.path().get(0), render(world, result));
        assertEquals(new Pos(6, 65, 0), result.path().get(result.path().size() - 1),
            render(world, result));
        assertTrue(result.path().contains(new Pos(3, 66, 1)),
            "the only way past the wall is over its top at z=1\n" + render(world, result));
    }

    @Test
    void everyStepOfEveryPathIsStandable() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        for (Pos pos : result.path()) {
            assertTrue(Standability.standable(world, pos.x(), pos.y(), pos.z()),
                "the search returned a position the player cannot occupy: " + pos + "\n"
                    + render(world, result));
        }
    }

    @Test
    void consecutiveStepsAreAlwaysAdjacent() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(6, 65, 0));

        for (int i = 1; i < result.path().size(); i++) {
            Pos from = result.path().get(i - 1);
            Pos to = result.path().get(i);
            int dx = Math.abs(to.x() - from.x());
            int dz = Math.abs(to.z() - from.z());
            int dy = to.y() - from.y();

            assertTrue(dx <= 1 && dz <= 1, "teleported horizontally " + from + " -> " + to
                + "\n" + render(world, result));
            assertTrue(dy <= 1, "climbed more than one block " + from + " -> " + to
                + "\n" + render(world, result));
            assertTrue(-dy <= MovementCosts.MAX_SAFE_FALL, "fell further than the safe limit "
                + from + " -> " + to + "\n" + render(world, result));
            assertTrue(dx + dz > 0, "did not move horizontally " + from + " -> " + to
                + "\n" + render(world, result));
        }
    }

    @Test
    void theHeuristicNeverOverestimatesFromAnyPointOnAFoundPath() {
        FixtureWorld world = FixtureWorld.parse(OBSTACLE_COURSE);
        Goal goal = new GoalBlock(6, 65, 0);
        AStarPathfinder pathfinder = new AStarPathfinder();
        PathResult whole = pathfinder.findPath(world, 0, 65, 0, goal);

        assertEquals(PathOutcome.FOUND, whole.outcome());

        for (Pos pos : whole.path()) {
            PathResult fromHere = pathfinder.findPath(world, pos.x(), pos.y(), pos.z(), goal);
            assertEquals(PathOutcome.FOUND, fromHere.outcome(), render(world, fromHere));
            assertTrue(goal.heuristic(pos.x(), pos.y(), pos.z()) <= fromHere.cost() + 1.0e-9,
                "the heuristic overestimates from " + pos + ": "
                    + goal.heuristic(pos.x(), pos.y(), pos.z()) + " > " + fromHere.cost());
        }
    }

    private static String render(FixtureWorld world, PathResult result) {
        return "\n" + PathRenderer.render(world, result);
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :core-pathfinder:test --tests "dev.continuo.pathfinder.PathfinderAcceptanceTest"`
Expected: PASS, 4 tests.

If `findsTheOnlyRouteOverTheWall` fails, read the rendered world in the failure message before changing anything — that dump is the whole reason the renderer was built.

- [ ] **Step 3: Full build from scratch**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL, every task re-run, all suites green.

**Do not run `./gradlew clean`.** It deletes the 1.7.10 decompiled sources that Task 4's citations point at.

- [ ] **Step 4: Verify the "no SPI, no adapter" done criterion against the diff**

Run:

```bash
git diff --stat master...HEAD -- platform/ adapters/
```

Expected: **empty output.** Spec §8 criterion 4 states this as a success condition rather than an outcome, so it gets checked rather than assumed. If anything appears, that is a finding to report, not to quietly revert.

- [ ] **Step 5: Verify the Java 8 and purity invariants covered the new module**

Run:

```bash
./gradlew :core-pathfinder:checkCoreBytecode :core-pathfinder:checkCorePurity --rerun-tasks
```

Expected: BUILD SUCCESSFUL. Both tasks fail loudly when they find no class files to scan, so passing means they really ran against `:core-pathfinder`.

- [ ] **Step 6: Confirm nothing from a mutation survived**

Run:

```bash
git status --porcelain
```

Expected: clean. Every mutation across Tasks 2, 3, 5, 6, 7, 8, 10 and 11 must have been reverted. B1 left one broken on disk; check rather than assume.

- [ ] **Step 7: Tick off the spec's done criteria**

In `docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md` §8, mark each of the six criteria with its evidence — the command run and what it printed. Criterion 5 (cost citations) was satisfied in Task 4; criterion 6 (no smoke checklist) is satisfied by Step 4's empty diff.

- [ ] **Step 8: Commit**

```bash
git add core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java \
        docs/superpowers/specs/2026-08-15-c1-pathfinder-core-design.md
git commit -m "test(c1): add the acceptance suite and close out C1's done criteria

Four properties that hold of any path the search returns, not just the
ones in the fixtures: every step is standable, consecutive steps are
adjacent within one movement's reach, no drop exceeds the safe limit, and
the heuristic never overestimates from any point along the path.

Every assertion prints the rendered world on failure, which is what the
renderer was built for.

Done criteria in the spec are ticked off with the command and output that
evidenced each, including the empty diff against platform/ and adapters/
that criterion 4 asks for explicitly."
```

---

## After the plan

C1 is complete when Task 12 is committed and green. Then:

1. **Request a whole-branch code review.** B1's final review found 2 Important issues after 19 per-task reviews and 8 fix rounds, and it has never come back empty across five sub-projects. **Point it at files the diff does not include** — that is where the yield was. For C1 that means `BlockShape`, `BlockData`, `BlockLookup` and B1 §4's audit table, against which every threshold in `Standability` is a claim.
2. **Do not merge C1 alone if C2 follows immediately.** The repo's convention is one `--no-ff` merge commit per sub-project, matching the "Merge B1 …" style with a `NOT VERIFIED` section. Whether C1–C4 merge separately or as one M4 merge is the owner's call.
3. **Carry forward into C3's spec:** B2 §4's pre-warm-before-seal obligation on M5. C1 does not touch it and C3 must not rediscover it.
