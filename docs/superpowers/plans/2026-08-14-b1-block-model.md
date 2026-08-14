# B1 Block Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both adapters report a block's raw physical facts through the SPI; a shared classifier in `:core` turns those facts into a `BlockData`; and the two adapters demonstrably agree on the same fixture world.

**Architecture:** The adapter never produces a `BlockData`. It produces a `BlockDescription` — a bag of primitives with no judgement in it. A version-independent `BlockClassifier` in `:core`, plus a small per-version JSON override table, produces the `BlockData`. So `BlockData`, `BlockShape`, `BlockTag` and `Fluid` live in `:core`, and `dev.continuo.platform` gains exactly two types, both of raw fact.

**Tech Stack:** Java 8 bytecode (`--release 8`) across `:platform`, `:core`, `:runtime`, `:platform-testkit`. JUnit 5 (`junit_version` in `gradle.properties`). Gradle 9.6.1 on a Java 25 daemon. Fabric Loom 1.17.17 (1.21.11) and RetroFuturaGradle (1.7.10).

**Spec:** [`docs/superpowers/specs/2026-08-14-b1-block-model-design.md`](../specs/2026-08-14-b1-block-model-design.md)

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 8 bytecode.** `:platform`, `:core`, `:runtime`, `:platform-testkit` compile with `options.release = 8`, enforced by `checkCoreBytecode` (fails above class-file major 52). **No records, no sealed types, no pattern switches, no `var`, no `List.of`/`Map.of`/`Set.of`, no `Optional.isEmpty`, no `String.isBlank`, no text blocks.** Use `Arrays.asList`, `new HashMap<>()`, `Collections.unmodifiable*`, `EnumSet`.
- **No Minecraft in pure modules.** `checkCorePurity` scans bytecode for `net/minecraft`, `net.minecraft`, `net/fabricmc`, `net.fabricmc`, `net/minecraftforge`, `net.minecraftforge`, `cpw/mods`, `cpw.mods` in **both** slash and dot form, and scans the dependency graph for those groups. It fails the build. This applies to string literals too.
- **Javadoc is build-failing.** Every pure module runs `javadoc` as part of `check` with `-Xdoclint:all,-missing` and `-Xwerror`. **A broken `{@link}` fails the build.** Every `{@link}` you write must resolve from that module's compile classpath.
- **`dev.continuo.platform`'s `package-info.java` is normative.** Where any spec and that javadoc differ, the javadoc wins. The four global rules are numbered 1 Threading, 2 Lifecycle, 3 Faults, 4 Input persistence, and **the numbering is load-bearing — do not renumber, and do not add a rule.**
- **No new Gradle modules.** If one is ever added it must be registered in `allowedProjectDependencies` in the root `build.gradle.kts` or `checkDependencyDirection` fails. This plan adds none.
- **TDD applies everywhere except adapter classes.** Adapters import `net.minecraft` and cannot run without a game; they have no automated tests and cannot get any. Do not flag missing adapter tests as a defect. Everything in `:platform`, `:core`, `:runtime` is test-first.
- **`GRADLE_USER_HOME` is `C:\GradleHome`**, not `~/.gradle`.
- **If `./gradlew clean` fails with `Unable to delete directory ...\build`**, that is a stale daemon, not your change. Run `./gradlew --stop`, wait ~10 seconds, retry.
- **Use `--rerun-tasks` for any verification run and for any documentation-only change.** A2b recorded `:core:test` reporting `UP-TO-DATE` on a first task and nearly producing a false green.
- **Do not touch `.github/workflows/ci.yml` and do not push.** An `origin` remote exists; it is not yours to use.

---

## File Structure

**Created in `:platform`** — the SPI addition, two types of raw fact:

| File | Responsibility |
|---|---|
| `platform/src/main/java/dev/continuo/platform/BlockDescription.java` | Immutable value class: id, stateKey, collision boxes, fluid id, climbable, gravity |
| `platform/src/main/java/dev/continuo/platform/IBlockView.java` | Live main-thread reader: `stateId`, `describe`, `isChunkLoaded`, `minY`, `maxY` |

**Modified in `:platform`:**

| File | Change |
|---|---|
| `platform/src/main/java/dev/continuo/platform/IPlatformContext.java` | Add `blocks()` |
| `platform/build.gradle.kts` | Add JUnit for `BlockDescription`'s tests |

**Created in `:core`** — the vocabulary, the classifier, the table:

| File | Responsibility |
|---|---|
| `core/src/main/java/dev/continuo/core/BlockShape.java` | The nine shape categories |
| `core/src/main/java/dev/continuo/core/BlockTag.java` | AVOID, FALLING, CLIMBABLE, SLOW |
| `core/src/main/java/dev/continuo/core/Fluid.java` | NONE, WATER, LAVA, OTHER |
| `core/src/main/java/dev/continuo/core/BlockData.java` | The immutable flyweight the core reads |
| `core/src/main/java/dev/continuo/core/JsonValue.java` | Parsed node of the JSON subset |
| `core/src/main/java/dev/continuo/core/JsonReader.java` | Strict hand-written parser for that subset |
| `core/src/main/java/dev/continuo/core/BlockTable.java` | Parsed override rows, `blocks` and `states` |
| `core/src/main/java/dev/continuo/core/BlockTableLoader.java` | Resource selection by version, strict load |
| `core/src/main/java/dev/continuo/core/BlockClassifier.java` | `BlockDescription` + `BlockTable` → `BlockData` |
| `core/src/main/java/dev/continuo/core/BlockLookup.java` | `stateId` → `BlockData` memo over an `IBlockView` |
| `core/src/main/resources/blocks/1.7.10.json` | Per-version override data |
| `core/src/main/resources/blocks/1.21.11.json` | Per-version override data |

**Created in `:runtime`:**

| File | Responsibility |
|---|---|
| `runtime/src/main/java/dev/continuo/runtime/BlockDumpWalker.java` | Walks a world region, emits the canonical parity dump |

**Created in `:platform-testkit`:**

| File | Responsibility |
|---|---|
| `platform-testkit/src/main/java/dev/continuo/testkit/FakeBlockView.java` | Array-backed `IBlockView` for headless tests |

**Modified elsewhere:** `FakePlatformContext` (implement `blocks()`), `ContinuoCore` (own a `BlockLookup`, clear on `stop`), both adapters (an `IBlockView` each, a context wiring, a dump keybind), both smoke checklists, the roadmap.

---

## Task Order and Rationale

Task 1 is the audit, because every geometry rule in Task 8 is an empirical claim about two Minecraft versions and the audit is what establishes them. Tasks 2–3 add SPI types that break nothing. Tasks 4–10 build the core side, fully headless. Tasks 11–12 implement the adapters. Task 13 adds `blocks()` and wires all three contexts *at once* — deliberately last of the structural changes, because adding it earlier breaks both adapters' compilation until their `IBlockView`s exist.

---

### Task 1: The block audit

This is a **research and documentation** task. It produces no code. It is first because Task 8's classifier rules are empirical claims that this task either confirms or corrects, and because it is the evidence the whole design rests on (spec §4).

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-b1-block-model-design.md` §4 (append the audit table) and §5.2 (replace the illustrative fragment with the real fixture layout)

**Interfaces:**
- Consumes: nothing
- Produces: the authoritative geometry table that Task 8 implements, and the fixture layout that Tasks 14–16 use

**Sources to read.** Both are on disk and greppable. Neither is in git.

- **1.21.11 (Mojmap):** `C:/projects/continuo/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-2ae02fda0f/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/sources`
- **1.7.10 (MCP):** `C:/projects/continuo/adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java` — this lives under `build/`, so if it is missing, regenerate it rather than assuming it never existed.

- [ ] **Step 1: Read the collision geometry for each audit block on 1.21.11**

For each block below, find its class and record the **collision** shape — `getCollisionShape`, never `getShape`. Many blocks build shapes from `Block.box(...)` / `Block.column(...)`, whose arguments are in **sixteenths** (16.0 = one block).

Blocks: `air`, `stone`, `smooth_stone_slab` (bottom and top), `oak_stairs`, `oak_fence`, `cobblestone_wall`, `glass_pane`, `ladder`, `vine`, `water`, `lava`, `gravel`, `sand`, `cobweb`, `soul_sand`, `ice`, `packed_ice`, `oak_door`, `oak_trapdoor`, `white_carpet`, `snow` (layer), `farmland`, `cactus`, `fire`, `magma_block`, `chest`, `oak_leaves`, `honey_block`.

- [ ] **Step 2: Read the collision geometry for each audit block on 1.7.10**

The corresponding classes are `BlockAir`, `Block` (stone), `BlockSlab`/`BlockHalfSlab`, `BlockStairs`, `BlockFence`, `BlockWall`, `BlockPane`, `BlockLadder`, `BlockVine`, `BlockStaticLiquid`/`BlockDynamicLiquid`, `BlockSand`, `BlockGravel`, `BlockWeb`, `BlockSoulSand`, `BlockIce`, `BlockPackedIce`, `BlockDoor`, `BlockTrapDoor`, `BlockCarpet`, `BlockSnow`, `BlockFarmland`, `BlockCactus`, `BlockFire`, `BlockChest`, `BlockLeaves`.

**Read `addCollisionBoxesToList` first, and `setBlockBoundsBasedOnState` only as a fallback.** This distinction is not cosmetic: `BlockFence.addCollisionBoxesToList` emits boxes with `maxY = 1.5F`, while `setBlockBoundsBasedOnState` resets `maxY` to `1.0F`. A block that overrides `addCollisionBoxesToList` has geometry that the bounds fields do not describe.

Record for each block whether `addCollisionBoxesToList` is overridden, and whether the shape depends on neighbours (a call to `canConnectFenceTo`, `canPaneConnectTo`, or similar).

- [ ] **Step 3: Write the audit table into spec §4**

One row per logical block. Append to §4 under a heading `#### Audit results — <date>`. Columns:

| Logical block | 1.7.10 class + boxes | 1.21.11 class + boxes | Expected `BlockShape` | Verdict |
|---|---|---|---|---|

`Verdict` is exactly one of **`geometry`** (the classifier derives it), **`table`** (needs an override row), or **`neither`**.

**A `neither` verdict is the B1 gate tripping (spec §6.1). Stop and escalate to the owner rather than inventing a mechanism.**

- [ ] **Step 4: Confirm or correct the classifier's geometry rules**

Spec §3.3 states these rules. Check each against what you found and **record a confirmation or a correction in the audit section**, because Task 8 implements whatever this step concludes:

1. no boxes → `AIR`
2. any box with `maxY > 1.0` → `FENCE`
3. single box, full footprint (`minX≈0, minZ≈0, maxX≈1, maxZ≈1`), `y 0..1` → `FULL`
4. single box, full footprint, `y 0..0.5` → `SLAB_BOTTOM`
5. single box, full footprint, `y 0.5..1` → `SLAB_TOP`
6. single box, full footprint, `y 0..h` where `h <= 0.25` → `THIN_LAYER`
7. contains a full-footprint `y 0..0.5` box **and** at least one non-full-footprint box with `y 0.5..1` → `STAIR`
8. anything else with collision → `PARTIAL`

Rule 7 is the least certain — stair collision shapes may be two or three boxes and may differ between versions. **If rules as written disagree with the sources, the sources win; correct the rule here and note that you did.**

- [ ] **Step 5: Write the fixture layout into spec §5.2**

Replace the illustrative fragment with the real layout: a one-wide row along +X at a fixed Y, one index per audit block, giving the exact block to place on each version. Mark version-exclusive rows (`honey_block`, `magma_block` on 1.21.11 only) and state that the diff skips them but still lists them.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-08-14-b1-block-model-design.md
git commit -m "docs(b1): record the block audit and the parity fixture layout"
```

---

### Task 2: `BlockDescription`

**Files:**
- Create: `platform/src/main/java/dev/continuo/platform/BlockDescription.java`
- Create: `platform/src/test/java/dev/continuo/platform/BlockDescriptionTest.java`
- Modify: `platform/build.gradle.kts`

**Interfaces:**
- Consumes: nothing
- Produces: `BlockDescription(String id, String stateKey, double[] collisionBoxes, String fluidId, boolean climbable, boolean gravity)` with accessors `id()`, `stateKey()`, `collisionBoxes()`, `fluidId()`, `climbable()`, `gravity()`. `fluidId()` may return `null`; nothing else may.

- [ ] **Step 1: Add JUnit to `:platform`**

`:platform` has no test source set today. Append to `platform/build.gradle.kts`:

```kotlin
dependencies {
    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

This adds no project dependency, so `checkDependencyDirection` is unaffected.

- [ ] **Step 2: Write the failing test**

Create `platform/src/test/java/dev/continuo/platform/BlockDescriptionTest.java`:

```java
package dev.continuo.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDescriptionTest {

    private static final double[] FULL_CUBE = {0, 0, 0, 1, 1, 1};

    private static BlockDescription stone() {
        return new BlockDescription(
            "minecraft:stone", "minecraft:stone", FULL_CUBE.clone(), null, false, false);
    }

    @Test
    void exposesWhatItWasGiven() {
        BlockDescription d = stone();
        assertEquals("minecraft:stone", d.id());
        assertEquals("minecraft:stone", d.stateKey());
        assertArrayEquals(FULL_CUBE, d.collisionBoxes());
        assertNull(d.fluidId());
        assertFalse(d.climbable());
        assertFalse(d.gravity());
    }

    @Test
    void copiesTheBoxArrayIn() {
        double[] caller = FULL_CUBE.clone();
        BlockDescription d = new BlockDescription("a:b", "a:b", caller, null, false, false);
        caller[4] = 99.0;
        assertEquals(1.0, d.collisionBoxes()[4], 0.0, "mutating the caller's array must not change the description");
    }

    @Test
    void copiesTheBoxArrayOut() {
        BlockDescription d = stone();
        double[] first = d.collisionBoxes();
        first[4] = 99.0;
        assertEquals(1.0, d.collisionBoxes()[4], 0.0, "mutating a returned array must not change the description");
        assertNotSame(first, d.collisionBoxes());
    }

    @Test
    void acceptsAnEmptyBoxArrayMeaningNoCollision() {
        BlockDescription d = new BlockDescription("a:air", "a:air", new double[0], null, false, false);
        assertEquals(0, d.collisionBoxes().length);
    }

    @Test
    void acceptsAFluidId() {
        BlockDescription d = new BlockDescription(
            "minecraft:water", "minecraft:water", new double[0], "minecraft:water", false, false);
        assertEquals("minecraft:water", d.fluidId());
    }

    @Test
    void carriesTheClimbableAndGravityFlags() {
        BlockDescription d = new BlockDescription("a:b", "a:b", new double[0], null, true, true);
        assertTrue(d.climbable());
        assertTrue(d.gravity());
    }

    @Test
    void rejectsANullId() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription(null, "a:b", new double[0], null, false, false));
    }

    @Test
    void rejectsANullStateKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", null, new double[0], null, false, false));
    }

    @Test
    void rejectsANullBoxArray() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", "a:b", null, null, false, false));
    }

    @Test
    void rejectsABoxArrayThatIsNotAWholeNumberOfSixTuples() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1}, null, false, false));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :platform:test --rerun-tasks
```

Expected: FAIL — `BlockDescription` does not exist, so compilation fails.

- [ ] **Step 4: Write the implementation**

Create `platform/src/main/java/dev/continuo/platform/BlockDescription.java`:

```java
package dev.continuo.platform;

/**
 * A block's raw physical facts, as one Minecraft version reports them.
 *
 * <p>Deliberately contains <em>no judgement</em>. An adapter reports what the game says and
 * nothing more; deciding that a given set of collision boxes is a slab, or that a given block
 * should be avoided, is the core's job and happens against a shared, version-independent
 * classifier. That division is what keeps two adapters from disagreeing about the same block,
 * and it is why this type carries an id and a box array rather than a shape.
 *
 * <p>This is the only value class in this package. It is one because an adapter constructs one
 * of these per distinct block state and would otherwise have to write a six-method class or an
 * anonymous inner class each time.
 *
 * <p>Immutable. Subject to all four global rules in this package's documentation.
 */
public final class BlockDescription {

    private final String id;
    private final String stateKey;
    private final double[] collisionBoxes;
    private final String fluidId;
    private final boolean climbable;
    private final boolean gravity;

    /**
     * @param id the block's namespaced registry name, such as {@code minecraft:oak_slab};
     *           never {@code null}
     * @param stateKey a human-meaningful key identifying this specific state, beginning with
     *                 {@code id}; never {@code null}
     * @param collisionBoxes flattened six-tuples of {@code minX, minY, minZ, maxX, maxY, maxZ}
     *                       in block-relative coordinates, so a full cube is
     *                       {@code {0,0,0, 1,1,1}}. Empty means no collision at all. Copied on
     *                       construction; never {@code null}
     * @param fluidId the namespaced id of the fluid occupying this block, or {@code null} if
     *                none. Reported verbatim as the platform names it — normalising
     *                {@code flowing_water} to water is classification and is not done here
     * @param climbable whether the platform considers this block climbable, such as a ladder
     *                  or a vine
     * @param gravity whether this block is affected by gravity, such as sand or gravel
     * @throws IllegalArgumentException if {@code id}, {@code stateKey} or
     *         {@code collisionBoxes} is {@code null}, or if {@code collisionBoxes} is not a
     *         whole number of six-tuples
     */
    public BlockDescription(String id,
                            String stateKey,
                            double[] collisionBoxes,
                            String fluidId,
                            boolean climbable,
                            boolean gravity) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (stateKey == null) {
            throw new IllegalArgumentException("stateKey must not be null");
        }
        if (collisionBoxes == null) {
            throw new IllegalArgumentException("collisionBoxes must not be null; use an empty array for no collision");
        }
        if (collisionBoxes.length % 6 != 0) {
            throw new IllegalArgumentException(
                "collisionBoxes must be a whole number of six-tuples, but had length " + collisionBoxes.length);
        }
        this.id = id;
        this.stateKey = stateKey;
        this.collisionBoxes = collisionBoxes.clone();
        this.fluidId = fluidId;
        this.climbable = climbable;
        this.gravity = gravity;
    }

    /** @return the namespaced registry name; never {@code null} */
    public String id() {
        return id;
    }

    /** @return the state key, which begins with {@link #id()}; never {@code null} */
    public String stateKey() {
        return stateKey;
    }

    /**
     * @return a fresh copy of the flattened collision boxes; never {@code null}, possibly empty
     */
    public double[] collisionBoxes() {
        return collisionBoxes.clone();
    }

    /** @return the occupying fluid's namespaced id, or {@code null} if there is none */
    public String fluidId() {
        return fluidId;
    }

    /** @return whether the platform considers this block climbable */
    public boolean climbable() {
        return climbable;
    }

    /** @return whether this block is affected by gravity */
    public boolean gravity() {
        return gravity;
    }
}
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :platform:test --rerun-tasks
```

Expected: PASS, 10 tests.

- [ ] **Step 6: Run the full module check**

```bash
./gradlew :platform:check --rerun-tasks
```

Expected: PASS. This runs `checkCorePurity`, `checkCoreBytecode` and `javadoc` with `-Xwerror` — the javadoc step is the one most likely to fail, on an unresolvable `{@link}`.

- [ ] **Step 7: Commit**

```bash
git add platform/build.gradle.kts platform/src/main/java/dev/continuo/platform/BlockDescription.java platform/src/test/java/dev/continuo/platform/BlockDescriptionTest.java
git commit -m "feat(platform): add BlockDescription, the adapter's raw block facts"
```

---

### Task 3: `IBlockView`

Adds the type only. Nothing implements it yet and nothing breaks.

**Files:**
- Create: `platform/src/main/java/dev/continuo/platform/IBlockView.java`

**Interfaces:**
- Consumes: `BlockDescription` from Task 2
- Produces: `int stateId(int x, int y, int z)`, `BlockDescription describe(int x, int y, int z)`, `boolean isChunkLoaded(int chunkX, int chunkZ)`, `int minY()`, `int maxY()`

- [ ] **Step 1: Write the interface**

There is no test in this step — the type is pure declaration, and its first behavioural assertions arrive in Task 10 against `FakeBlockView`. Create `platform/src/main/java/dev/continuo/platform/IBlockView.java`:

```java
package dev.continuo.platform;

/**
 * Reads blocks from the live world.
 *
 * <p>Note the direction: the adapter implements this and the core calls it — the opposite of
 * {@link IGameEvents}.
 *
 * <p><b>Call window.</b> Every method here MUST only be called while
 * {@link IGameEvents#onClientTick}'s delivery window is open — a world loaded and a local
 * player present. Outside that window the behaviour is unspecified. This deliberately reuses
 * that existing condition rather than stating a new one, so there is nothing extra for an
 * adapter to evaluate or get wrong.
 *
 * <p>Subject to all four global rules in this package's documentation, in particular rule 1:
 * these are main-thread calls and no implementation may block.
 */
public interface IBlockView {

    /**
     * The platform's own identifier for the block state at this position.
     *
     * <p>Cheap by contract. The core calls this once per block per pathfinding node, and
     * caches {@link #describe} results against the value it returns, so an implementation
     * MUST NOT do per-call work beyond reading the world. Both target versions have such an
     * identifier natively — 1.7.10 composes one from a block id and its metadata, and 1.21.11
     * has a global block-state registry id — so no adapter needs to invent one.
     *
     * <p>Values are <b>session-scoped</b>. They are stable while a client runs and MUST NOT
     * be persisted or compared across versions, mod sets, or runs.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the state id, or {@code -1} if the position is unreadable — outside
     *         {@link #minY()}/{@link #maxY()}, or in a chunk that is not loaded
     */
    int stateId(int x, int y, int z);

    /**
     * The full raw facts about the block at this position.
     *
     * <p>Expensive by contract, and called rarely: the core calls this only when it has not
     * yet seen the state id at that position, so it runs a few thousand times per session
     * rather than once per query.
     *
     * <p><b>Why this takes a position rather than a state id.</b> On 1.7.10 a block's collision
     * geometry is not a function of its state alone — fences, walls and panes compute their
     * boxes from their neighbours at a specific coordinate, and the metadata does not record
     * the result. An implementation handed only a state id could not answer for those blocks.
     *
     * <p>The core caches the result against {@link #stateId}, so for a neighbour-dependent
     * block the cached geometry is whichever instance was described first. That is sound only
     * because the core's shape categories are behavioural rather than literal.
     *
     * <p>MUST NOT be called for a position where {@link #stateId} returns {@code -1}.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the description; never {@code null}
     */
    BlockDescription describe(int x, int y, int z);

    /**
     * Whether the chunk at these chunk coordinates is loaded.
     *
     * <p>Distinct from {@link #stateId} returning {@code -1}, which conflates "not loaded"
     * with "outside the world". A caller planning a route needs to tell those apart: unloaded
     * terrain is unknown and might be solid, whereas above or below the world there is
     * definitely nothing.
     *
     * @param chunkX world X shifted right by four
     * @param chunkZ world Z shifted right by four
     * @return whether that chunk is loaded
     */
    boolean isChunkLoaded(int chunkX, int chunkZ);

    /**
     * The lowest Y coordinate that can hold a block, inclusive.
     *
     * <p>Follows Minecraft's own convention. 1.7.10 reports {@code 0}; 1.21.11 reports the
     * current dimension's floor, which is {@code -64} in the overworld.
     *
     * @return the inclusive lower bound
     */
    int minY();

    /**
     * One past the highest Y coordinate that can hold a block, exclusive.
     *
     * <p>1.7.10 reports {@code 256}; 1.21.11 reports the current dimension's ceiling, which is
     * {@code 320} in the overworld.
     *
     * @return the exclusive upper bound
     */
    int maxY();
}
```

- [ ] **Step 2: Verify it compiles and the javadoc resolves**

```bash
./gradlew :platform:check --rerun-tasks
```

Expected: PASS. The `{@link IGameEvents#onClientTick}` references are the ones at risk under `-Xwerror`.

- [ ] **Step 3: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/IBlockView.java
git commit -m "feat(platform): add IBlockView, the live main-thread block reader"
```

---

### Task 4: The core vocabulary — `BlockShape`, `BlockTag`, `Fluid`, `BlockData`

**Files:**
- Create: `core/src/main/java/dev/continuo/core/BlockShape.java`
- Create: `core/src/main/java/dev/continuo/core/BlockTag.java`
- Create: `core/src/main/java/dev/continuo/core/Fluid.java`
- Create: `core/src/main/java/dev/continuo/core/BlockData.java`
- Create: `core/src/test/java/dev/continuo/core/BlockDataTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `BlockShape` (9 constants), `BlockTag` (4 constants), `Fluid` (4 constants), and `BlockData` with constructor `BlockData(BlockShape shape, double collisionTop, Fluid fluid, EnumSet<BlockTag> tags)` plus `shape()`, `collisionTop()`, `fluid()`, `tags()`, `has(BlockTag)`, and the constant `BlockData.UNKNOWN`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/BlockDataTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDataTest {

    @Test
    void exposesWhatItWasGiven() {
        BlockData d = new BlockData(
            BlockShape.SLAB_BOTTOM, 0.5, Fluid.NONE, EnumSet.of(BlockTag.SLOW));
        assertEquals(BlockShape.SLAB_BOTTOM, d.shape());
        assertEquals(0.5, d.collisionTop(), 0.0);
        assertEquals(Fluid.NONE, d.fluid());
        assertTrue(d.has(BlockTag.SLOW));
        assertFalse(d.has(BlockTag.AVOID));
    }

    @Test
    void tagsAreNotModifiableThroughTheAccessor() {
        BlockData d = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
        assertThrows(UnsupportedOperationException.class, () -> d.tags().add(BlockTag.AVOID));
    }

    @Test
    void copiesTheTagSetIn() {
        EnumSet<BlockTag> caller = EnumSet.of(BlockTag.SLOW);
        BlockData d = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, caller);
        caller.add(BlockTag.AVOID);
        assertFalse(d.has(BlockTag.AVOID), "mutating the caller's set must not change the data");
    }

    @Test
    void unknownIsTheSingletonForUnreadablePositions() {
        assertEquals(BlockShape.UNKNOWN, BlockData.UNKNOWN.shape());
        assertEquals(0.0, BlockData.UNKNOWN.collisionTop(), 0.0);
        assertEquals(Fluid.NONE, BlockData.UNKNOWN.fluid());
        assertTrue(BlockData.UNKNOWN.tags().isEmpty());
    }

    @Test
    void rejectsANullShape() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(null, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)));
    }

    @Test
    void rejectsANullFluid() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(BlockShape.AIR, 0.0, null, EnumSet.noneOf(BlockTag.class)));
    }

    @Test
    void rejectsANullTagSet() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, null));
    }

    @Test
    void equalValuesAreEqual() {
        BlockData a = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.of(BlockTag.SLOW));
        BlockData b = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.of(BlockTag.SLOW));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differingValuesAreNotEqual() {
        BlockData a = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
        BlockData b = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));
        assertFalse(a.equals(b));
    }

    @Test
    void toStringIsStableAndCarriesEveryField() {
        BlockData d = new BlockData(BlockShape.STAIR, 1.0, Fluid.WATER, EnumSet.of(BlockTag.AVOID));
        assertEquals("STAIR top=1.0 fluid=WATER tags=[AVOID]", d.toString());
    }
}
```

`equals`, `hashCode` and a stable `toString` are not incidental — the parity dump in Task 14 is a text diff of `toString`, and the classifier tests compare whole `BlockData` values.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :core:test --rerun-tasks
```

Expected: FAIL — the types do not exist.

- [ ] **Step 3: Write the three enums**

Create `core/src/main/java/dev/continuo/core/BlockShape.java`:

```java
package dev.continuo.core;

/**
 * A block's collision geometry, reduced to the categories movement cares about.
 *
 * <p>These are <b>behavioural categories, not literal geometry.</b> {@link #FENCE} means "you
 * cannot walk over this and cannot jump it", not a specific set of boxes — which matters
 * because on 1.7.10 a fence's actual boxes depend on its neighbours and are not recoverable
 * from its state alone. Code that needs a real measurement should read
 * {@link BlockData#collisionTop()}.
 *
 * <p>Classification is exact-match: a shape that does not match a category's rule becomes
 * {@link #PARTIAL} rather than the nearest category. A near-miss classified as a slab would be
 * a silent wrong answer; {@code PARTIAL} with a truthful collision top is a correct answer that
 * merely carries less information.
 */
public enum BlockShape {

    /** The position could not be read — outside the world, or in an unloaded chunk. */
    UNKNOWN,

    /** No collision at all. Air, and also blocks like cobweb that you can move into. */
    AIR,

    /** One box filling the whole cube. */
    FULL,

    /** One full-footprint box occupying the lower half. */
    SLAB_BOTTOM,

    /** One full-footprint box occupying the upper half. */
    SLAB_TOP,

    /** One full-footprint box no more than a quarter high. Carpet, a snow layer. */
    THIN_LAYER,

    /** A full-footprint lower half plus at least one partial box above it. */
    STAIR,

    /** Collision extending above the cube. Fences, walls, and panes on some versions. */
    FENCE,

    /** Has collision, but matches no other category. Includes unrecognised modded geometry. */
    PARTIAL
}
```

Create `core/src/main/java/dev/continuo/core/BlockTag.java`:

```java
package dev.continuo.core;

/**
 * Semantic properties of a block that its geometry cannot express.
 *
 * <p>Almost none of these are derivable from collision boxes — soul sand is shaped exactly
 * like stone, and cobweb has no collision at all — so most arrive from the per-version
 * override table rather than from the classifier's geometry rules.
 */
public enum BlockTag {

    /** Harmful to occupy or touch. Fire, cactus, magma. */
    AVOID,

    /** Affected by gravity, so it may not be there later. Sand, gravel. */
    FALLING,

    /** Can be climbed. Ladders, vines. */
    CLIMBABLE,

    /** Slows movement through or across it. Soul sand, cobweb, honey. */
    SLOW
}
```

Create `core/src/main/java/dev/continuo/core/Fluid.java`:

```java
package dev.continuo.core;

/**
 * The fluid occupying a block, if any.
 *
 * <p>Separate from {@link BlockShape} because 1.21.11 lets a block be both solid and
 * waterlogged, while 1.7.10 has no such concept and simply never produces the combination.
 * That is an absent capability rather than a difference in shape.
 */
public enum Fluid {

    /** No fluid. */
    NONE,

    /** Water, still or flowing. */
    WATER,

    /** Lava, still or flowing. */
    LAVA,

    /** A fluid the table names but the core has no dedicated constant for. */
    OTHER
}
```

- [ ] **Step 4: Write `BlockData`**

Create `core/src/main/java/dev/continuo/core/BlockData.java`:

```java
package dev.continuo.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Everything the core knows about one block state.
 *
 * <p>Produced by {@link BlockClassifier} from a {@code BlockDescription} and the per-version
 * override table, never by an adapter. Keeping this type in {@code dev.continuo.core} rather
 * than in the SPI is what spares every future adapter from having to speak the core's
 * classification vocabulary.
 *
 * <p>Immutable, and interned per block state by {@link BlockLookup}.
 */
public final class BlockData {

    /** The value for a position that could not be read. */
    public static final BlockData UNKNOWN =
        new BlockData(BlockShape.UNKNOWN, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    private final BlockShape shape;
    private final double collisionTop;
    private final Fluid fluid;
    private final Set<BlockTag> tags;

    /**
     * @param shape the collision category; never {@code null}
     * @param collisionTop the highest Y any collision box reaches, in block-relative
     *                     coordinates: {@code 0} for no collision, {@code 1.0} for a full
     *                     cube, {@code 1.5} for a typical fence
     * @param fluid the occupying fluid; never {@code null}, use {@link Fluid#NONE}
     * @param tags the semantic tags; never {@code null}, copied on construction
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public BlockData(BlockShape shape, double collisionTop, Fluid fluid, EnumSet<BlockTag> tags) {
        if (shape == null) {
            throw new IllegalArgumentException("shape must not be null");
        }
        if (fluid == null) {
            throw new IllegalArgumentException("fluid must not be null; use Fluid.NONE");
        }
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null; use EnumSet.noneOf(BlockTag.class)");
        }
        this.shape = shape;
        this.collisionTop = collisionTop;
        this.fluid = fluid;
        this.tags = Collections.unmodifiableSet(EnumSet.copyOf(tags));
    }

    /** @return the collision category; never {@code null} */
    public BlockShape shape() {
        return shape;
    }

    /** @return the highest Y any collision box reaches; {@code 0} if there is no collision */
    public double collisionTop() {
        return collisionTop;
    }

    /** @return the occupying fluid; never {@code null} */
    public Fluid fluid() {
        return fluid;
    }

    /** @return the tags, unmodifiable; never {@code null} */
    public Set<BlockTag> tags() {
        return tags;
    }

    /**
     * @param tag the tag to test for
     * @return whether this block carries that tag
     */
    public boolean has(BlockTag tag) {
        return tags.contains(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockData)) {
            return false;
        }
        BlockData other = (BlockData) o;
        return shape == other.shape
            && Double.compare(collisionTop, other.collisionTop) == 0
            && fluid == other.fluid
            && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        int result = shape.hashCode();
        result = 31 * result + Double.valueOf(collisionTop).hashCode();
        result = 31 * result + fluid.hashCode();
        result = 31 * result + tags.hashCode();
        return result;
    }

    /**
     * A stable one-line form carrying every field.
     *
     * <p>Load-bearing: the cross-adapter parity dump is a text diff of this, so a change here
     * invalidates every checked-in dump file.
     *
     * @return {@code "SHAPE top=N fluid=F tags=[A, B]"}
     */
    @Override
    public String toString() {
        return shape + " top=" + collisionTop + " fluid=" + fluid + " tags=" + tags;
    }
}
```

`EnumSet.copyOf` rejects an empty non-`EnumSet`, but the parameter is declared as `EnumSet<BlockTag>`, so an empty one is fine.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core:test --rerun-tasks
```

Expected: PASS, 10 new tests plus the existing `ContinuoCoreTest` ones.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/continuo/core/BlockShape.java core/src/main/java/dev/continuo/core/BlockTag.java core/src/main/java/dev/continuo/core/Fluid.java core/src/main/java/dev/continuo/core/BlockData.java core/src/test/java/dev/continuo/core/BlockDataTest.java
git commit -m "feat(core): add the block vocabulary — BlockShape, BlockTag, Fluid, BlockData"
```

---

### Task 5: A strict JSON reader for the table subset

The spec (§3.5) leaves this open: *"The format is deliberately flat enough for a small hand-written reader... Worth confirming when the plan is written."* **Confirmed: hand-written.** Adding a JSON dependency to `:core` would put a third-party artifact on the classpath of the one module whose purity is machine-checked, to parse two files of a few dozen rows each.

The subset is deliberately tiny — objects, strings, and arrays of strings. **Anything else is a parse error**, which is the strictness the spec demands: a silently-ignored typo in a data table is the failure mode this whole design exists to avoid.

**Files:**
- Create: `core/src/main/java/dev/continuo/core/JsonValue.java`
- Create: `core/src/main/java/dev/continuo/core/JsonReader.java`
- Create: `core/src/test/java/dev/continuo/core/JsonReaderTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `JsonValue.parse(String)` → `JsonValue`; `JsonValue.isObject()`, `isString()`, `isArray()`, `asObject()` → `Map<String, JsonValue>`, `asString()` → `String`, `asStringArray()` → `List<String>`. Parse failures throw `IllegalArgumentException` with a message naming the character offset.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/JsonReaderTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReaderTest {

    private static JsonValue parse(String text) {
        return JsonValue.parse(text);
    }

    @Test
    void parsesAnEmptyObject() {
        assertTrue(parse("{}").isObject());
        assertTrue(parse("{}").asObject().isEmpty());
    }

    @Test
    void parsesAFlatStringObject() {
        Map<String, JsonValue> o = parse("{\"a\": \"b\"}").asObject();
        assertEquals(1, o.size());
        assertEquals("b", o.get("a").asString());
    }

    @Test
    void parsesNestedObjects() {
        Map<String, JsonValue> o = parse("{\"outer\": {\"inner\": \"v\"}}").asObject();
        assertEquals("v", o.get("outer").asObject().get("inner").asString());
    }

    @Test
    void parsesAStringArray() {
        JsonValue v = parse("{\"tags\": [\"SLOW\", \"AVOID\"]}").asObject().get("tags");
        assertTrue(v.isArray());
        assertEquals(Arrays.asList("SLOW", "AVOID"), v.asStringArray());
    }

    @Test
    void parsesAnEmptyArray() {
        assertEquals(0, parse("{\"tags\": []}").asObject().get("tags").asStringArray().size());
    }

    @Test
    void ignoresWhitespaceAndNewlines() {
        Map<String, JsonValue> o = parse("{\n  \"a\" :\t\"b\" ,\r\n \"c\" : \"d\"\n}").asObject();
        assertEquals("b", o.get("a").asString());
        assertEquals("d", o.get("c").asString());
    }

    @Test
    void handlesEscapesInStrings() {
        assertEquals("a\"b\\c\nd", parse("{\"k\": \"a\\\"b\\\\c\\nd\"}").asObject().get("k").asString());
    }

    @Test
    void rejectsNumbers() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> parse("{\"a\": 1}"));
        assertTrue(e.getMessage().contains("offset"), "message must locate the failure: " + e.getMessage());
    }

    @Test
    void rejectsBooleans() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": true}"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": null}"));
    }

    @Test
    void rejectsNestedArrays() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": [[\"b\"]]}"));
    }

    @Test
    void rejectsAnObjectInsideAnArray() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": [{}]}"));
    }

    @Test
    void rejectsATrailingComma() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\",}"));
    }

    @Test
    void rejectsAnUnterminatedObject() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\""));
    }

    @Test
    void rejectsAnUnterminatedString() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b}"));
    }

    @Test
    void rejectsTrailingContentAfterTheRootValue() {
        assertThrows(IllegalArgumentException.class, () -> parse("{} junk"));
    }

    @Test
    void rejectsANonObjectRoot() {
        assertThrows(IllegalArgumentException.class, () -> parse("\"just a string\""));
    }

    @Test
    void rejectsADuplicateKey() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\", \"a\": \"c\"}"));
    }

    @Test
    void asStringOnAnObjectIsAnError() {
        assertThrows(IllegalStateException.class, () -> parse("{\"a\": {}}").asObject().get("a").asString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :core:test --tests 'dev.continuo.core.JsonReaderTest' --rerun-tasks
```

Expected: FAIL — the types do not exist.

- [ ] **Step 3: Write `JsonValue`**

Create `core/src/main/java/dev/continuo/core/JsonValue.java`:

```java
package dev.continuo.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A node of the tiny JSON subset the block tables are written in.
 *
 * <p>The subset is objects, strings, and arrays of strings — nothing else. Numbers, booleans,
 * {@code null} and nested containers are parse errors rather than values, because the tables
 * have no use for them and a reader that silently accepts a typo is worse than one that stops.
 *
 * @see JsonReader
 */
public final class JsonValue {

    private final Map<String, JsonValue> object;
    private final String string;
    private final List<String> array;

    private JsonValue(Map<String, JsonValue> object, String string, List<String> array) {
        this.object = object;
        this.string = string;
        this.array = array;
    }

    static JsonValue ofObject(Map<String, JsonValue> value) {
        return new JsonValue(Collections.unmodifiableMap(value), null, null);
    }

    static JsonValue ofString(String value) {
        return new JsonValue(null, value, null);
    }

    static JsonValue ofArray(List<String> value) {
        return new JsonValue(null, null, Collections.unmodifiableList(value));
    }

    /**
     * Parses a document. The root must be an object.
     *
     * @param text the document
     * @return the root value
     * @throws IllegalArgumentException if the text is not valid within this subset
     */
    public static JsonValue parse(String text) {
        return new JsonReader(text).parseDocument();
    }

    /** @return whether this is an object */
    public boolean isObject() {
        return object != null;
    }

    /** @return whether this is a string */
    public boolean isString() {
        return string != null;
    }

    /** @return whether this is an array */
    public boolean isArray() {
        return array != null;
    }

    /**
     * @return this value's members, unmodifiable
     * @throws IllegalStateException if this is not an object
     */
    public Map<String, JsonValue> asObject() {
        if (object == null) {
            throw new IllegalStateException("not an object: " + this);
        }
        return object;
    }

    /**
     * @return this value's text
     * @throws IllegalStateException if this is not a string
     */
    public String asString() {
        if (string == null) {
            throw new IllegalStateException("not a string: " + this);
        }
        return string;
    }

    /**
     * @return this value's elements, unmodifiable
     * @throws IllegalStateException if this is not an array
     */
    public List<String> asStringArray() {
        if (array == null) {
            throw new IllegalStateException("not an array: " + this);
        }
        return array;
    }

    @Override
    public String toString() {
        if (object != null) {
            return "object" + object.keySet();
        }
        if (array != null) {
            return "array" + array;
        }
        return "string \"" + string + "\"";
    }
}
```

- [ ] **Step 4: Write `JsonReader`**

Create `core/src/main/java/dev/continuo/core/JsonReader.java`:

```java
package dev.continuo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict recursive-descent reader for the block tables' JSON subset.
 *
 * <p>Package-private; callers use {@link JsonValue#parse(String)}. Every failure carries the
 * character offset, because the whole point of parsing strictly is that a human can find the
 * typo.
 */
final class JsonReader {

    private final String text;
    private int pos;

    JsonReader(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        this.text = text;
    }

    JsonValue parseDocument() {
        skipWhitespace();
        if (peek() != '{') {
            throw error("the root of a block table must be an object");
        }
        JsonValue root = parseObject();
        skipWhitespace();
        if (pos != text.length()) {
            throw error("trailing content after the root object");
        }
        return root;
    }

    private JsonValue parseValue() {
        skipWhitespace();
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseStringArray();
        }
        if (c == '"') {
            return JsonValue.ofString(parseString());
        }
        throw error("expected an object, an array or a string, but found '" + c + "'");
    }

    private JsonValue parseObject() {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<String, JsonValue>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return JsonValue.ofObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a quoted member name");
            }
            String key = parseString();
            if (members.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            skipWhitespace();
            expect(':');
            members.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return JsonValue.ofObject(members);
            }
            throw error("expected ',' or '}' but found '" + c + "'");
        }
    }

    private JsonValue parseStringArray() {
        expect('[');
        List<String> elements = new ArrayList<String>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return JsonValue.ofArray(elements);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("arrays in a block table may only contain strings");
            }
            elements.add(parseString());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return JsonValue.ofArray(elements);
            }
            throw error("expected ',' or ']' but found '" + c + "'");
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"':  out.append('"');  break;
                case '\\': out.append('\\'); break;
                case '/':  out.append('/');  break;
                case 'n':  out.append('\n'); break;
                case 't':  out.append('\t'); break;
                case 'r':  out.append('\r'); break;
                default:
                    throw error("unsupported escape '\\" + esc + "'");
            }
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("expected '" + expected + "' but found '" + peek() + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Block table JSON is invalid at offset " + pos + ": " + message);
    }
}
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core:test --tests 'dev.continuo.core.JsonReaderTest' --rerun-tasks
```

Expected: PASS, 19 tests.

- [ ] **Step 6: Prove the strictness tests are not vacuous**

The rejection tests are the ones that matter, and they are exactly the shape A2b found two vacuous tests in — a test whose subject is "X is rejected" passes trivially if the code under test is never reached.

Temporarily change `parseValue`'s final line from `throw error(...)` to `return JsonValue.ofString("?")`, then run:

```bash
./gradlew :core:test --tests 'dev.continuo.core.JsonReaderTest' --rerun-tasks
```

Expected: `rejectsNumbers`, `rejectsBooleans` and `rejectsNull` **fail**, and the parsing tests still pass. **Revert the change** and re-run to confirm green.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/dev/continuo/core/JsonValue.java core/src/main/java/dev/continuo/core/JsonReader.java core/src/test/java/dev/continuo/core/JsonReaderTest.java
git commit -m "feat(core): add a strict JSON reader for the block-table subset"
```

---

### Task 6: `BlockTable` and `BlockTableLoader`

**Files:**
- Create: `core/src/main/java/dev/continuo/core/BlockTable.java`
- Create: `core/src/main/java/dev/continuo/core/BlockTableLoader.java`
- Create: `core/src/main/resources/blocks/1.7.10.json`
- Create: `core/src/main/resources/blocks/1.21.11.json`
- Create: `core/src/test/java/dev/continuo/core/BlockTableLoaderTest.java`

**Interfaces:**
- Consumes: `JsonValue` (Task 5); `BlockShape`, `BlockTag`, `Fluid` (Task 4)
- Produces: `BlockTable.Row` with `shape()` (nullable), `fluid()` (nullable), `tags()` (`Set<BlockTag>`, never null); `BlockTable.forBlock(String id)` and `BlockTable.forState(String stateKey)` returning a `Row` or `null`; `BlockTable.EMPTY`; `BlockTableLoader.parse(String)` and `BlockTableLoader.forVersion(String)`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/BlockTableLoaderTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockTableLoaderTest {

    @Test
    void parsesAnEmptyTable() {
        BlockTable t = BlockTableLoader.parse("{\"version\": \"test\", \"blocks\": {}, \"states\": {}}");
        assertNull(t.forBlock("minecraft:stone"));
        assertNull(t.forState("minecraft:stone"));
    }

    @Test
    void parsesABlockRowWithTags() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"minecraft:soul_sand\": {\"tags\": [\"SLOW\"]}}, \"states\": {}}");
        BlockTable.Row row = t.forBlock("minecraft:soul_sand");
        assertNotNull(row);
        assertTrue(row.tags().contains(BlockTag.SLOW));
        assertNull(row.shape());
        assertNull(row.fluid());
    }

    @Test
    void parsesABlockRowWithAFluid() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"minecraft:flowing_water\": {\"fluid\": \"WATER\"}}, \"states\": {}}");
        assertEquals(Fluid.WATER, t.forBlock("minecraft:flowing_water").fluid());
    }

    @Test
    void parsesAStateRowWithAShape() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {}, \"states\": {\"minecraft:stone_slab#8\": {\"shape\": \"SLAB_TOP\"}}}");
        assertEquals(BlockShape.SLAB_TOP, t.forState("minecraft:stone_slab#8").shape());
    }

    @Test
    void aRowWithNoTagsHasAnEmptyTagSet() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shape\": \"FULL\"}}, \"states\": {}}");
        assertTrue(t.forBlock("a:b").tags().isEmpty());
    }

    @Test
    void rejectsAnUnknownTagName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"tags\": [\"NOPE\"]}}, \"states\": {}}"));
        assertTrue(e.getMessage().contains("NOPE"), e.getMessage());
    }

    @Test
    void rejectsAnUnknownShapeName() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shape\": \"WOBBLY\"}}, \"states\": {}}"));
    }

    @Test
    void rejectsAnUnknownFluidName() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"fluid\": \"SYRUP\"}}, \"states\": {}}"));
    }

    @Test
    void rejectsAnUnknownKeyInARow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shpae\": \"FULL\"}}, \"states\": {}}"));
        assertTrue(e.getMessage().contains("shpae"), "a typo must be named, not ignored: " + e.getMessage());
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": \"test\", \"blocks\": {}, \"states\": {}, \"extra\": {}}"));
    }

    @Test
    void rejectsAMissingBlocksSection() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": \"test\", \"states\": {}}"));
    }

    @Test
    void rejectsAMissingVersion() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"blocks\": {}, \"states\": {}}"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> BlockTableLoader.parse("{"));
    }

    @Test
    void loadsTheShippedTableFor1710() {
        BlockTable t = BlockTableLoader.forVersion("1.7.10");
        assertNotNull(t.forBlock("minecraft:flowing_water"),
            "1.7.10 registers flowing_water as a distinct block and the table must normalise it");
        assertEquals(Fluid.WATER, t.forBlock("minecraft:flowing_water").fluid());
    }

    @Test
    void loadsTheShippedTableFor12111() {
        assertNotNull(BlockTableLoader.forVersion("1.21.11").forBlock("minecraft:soul_sand"));
    }

    @Test
    void anUnknownVersionYieldsAnEmptyTableRatherThanAnError() {
        assertNull(BlockTableLoader.forVersion("1.99.99").forBlock("minecraft:stone"));
    }
}
```

The last case is a deliberate design decision worth stating: an unrecognised version is **not** an error. A new adapter that ships before its table does should classify everything from geometry rather than refuse to start, because geometry is the designed default path and `PARTIAL` is the safety net. A *malformed* table is still a hard error.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockTableLoaderTest' --rerun-tasks`
Expected: FAIL — the types do not exist.

- [ ] **Step 3: Write `BlockTable`**

Create `core/src/main/java/dev/continuo/core/BlockTable.java`:

```java
package dev.continuo.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The per-version overrides the classifier cannot derive from geometry.
 *
 * <p>Small by design — a few dozen rows, not thousands. A block with no row is the normal
 * case, classified from its collision boxes and description flags alone; rows exist only for
 * facts geometry cannot carry, such as soul sand slowing you down while being shaped exactly
 * like stone.
 *
 * <p>Two sections: {@code blocks} keyed by a block's registry id for whole-block rules, and
 * {@code states} keyed by a state key for the rare state-specific override. States win.
 */
public final class BlockTable {

    /** A table with no rows. Everything classifies from geometry. */
    public static final BlockTable EMPTY =
        new BlockTable(Collections.<String, Row>emptyMap(), Collections.<String, Row>emptyMap());

    private final Map<String, Row> blocks;
    private final Map<String, Row> states;

    BlockTable(Map<String, Row> blocks, Map<String, Row> states) {
        this.blocks = Collections.unmodifiableMap(blocks);
        this.states = Collections.unmodifiableMap(states);
    }

    /**
     * @param id a block's namespaced registry name
     * @return the whole-block row, or {@code null} if there is none
     */
    public Row forBlock(String id) {
        return blocks.get(id);
    }

    /**
     * @param stateKey a block state's key
     * @return the state-specific row, or {@code null} if there is none
     */
    public Row forState(String stateKey) {
        return states.get(stateKey);
    }

    /** One override row. Any of its three fields may be absent. */
    public static final class Row {

        private final BlockShape shape;
        private final Fluid fluid;
        private final Set<BlockTag> tags;

        Row(BlockShape shape, Fluid fluid, EnumSet<BlockTag> tags) {
            this.shape = shape;
            this.fluid = fluid;
            this.tags = Collections.unmodifiableSet(EnumSet.copyOf(tags));
        }

        /** @return the shape this row forces, or {@code null} to keep geometry's answer */
        public BlockShape shape() {
            return shape;
        }

        /** @return the fluid this row forces, or {@code null} to keep the derived answer */
        public Fluid fluid() {
            return fluid;
        }

        /**
         * @return tags this row adds, unmodifiable and never {@code null}. Rows only ever
         *         <em>add</em> tags; removal is deliberately unsupported
         */
        public Set<BlockTag> tags() {
            return tags;
        }
    }
}
```

- [ ] **Step 4: Write `BlockTableLoader`**

Create `core/src/main/java/dev/continuo/core/BlockTableLoader.java`:

```java
package dev.continuo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a {@link BlockTable} from the JSON shipped as a core resource.
 *
 * <p>Strict on purpose. An unrecognised shape, fluid or tag name, an unknown key, or a missing
 * section fails loudly rather than being skipped, because a silently-ignored typo in a data
 * table produces a bot that paths wrongly for reasons nobody can find.
 *
 * <p>One thing is deliberately <em>not</em> an error: an unrecognised game version yields an
 * empty table rather than throwing. Geometry is the designed default classification path, so a
 * version with no table still works — it simply has no overrides.
 */
public final class BlockTableLoader {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final Set<String> TOP_LEVEL_KEYS =
        new HashSet<String>(Arrays.asList("version", "blocks", "states"));

    private static final Set<String> ROW_KEYS =
        new HashSet<String>(Arrays.asList("shape", "fluid", "tags"));

    private BlockTableLoader() {
    }

    /**
     * Loads the table shipped for a game version.
     *
     * @param version the value of {@code IPlatformInfo.version()}, such as {@code "1.7.10"}
     * @return that version's table, or {@link BlockTable#EMPTY} if none is shipped
     * @throws IllegalArgumentException if a shipped table exists but is malformed
     */
    public static BlockTable forVersion(String version) {
        if (version == null) {
            return BlockTable.EMPTY;
        }
        String path = "/blocks/" + version + ".json";
        InputStream in = BlockTableLoader.class.getResourceAsStream(path);
        if (in == null) {
            return BlockTable.EMPTY;
        }
        try {
            return parse(readAll(in));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Block table " + path + " is invalid: " + e.getMessage(), e);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Parses a table document.
     *
     * @param json the document
     * @return the parsed table
     * @throws IllegalArgumentException if the document is malformed or names anything unknown
     */
    public static BlockTable parse(String json) {
        Map<String, JsonValue> root = JsonValue.parse(json).asObject();
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "unknown top-level key \"" + key + "\"; expected one of " + TOP_LEVEL_KEYS);
            }
        }
        require(root, "version");
        require(root, "blocks");
        require(root, "states");
        root.get("version").asString();
        return new BlockTable(
            parseSection(root.get("blocks").asObject()),
            parseSection(root.get("states").asObject()));
    }

    private static void require(Map<String, JsonValue> root, String key) {
        if (!root.containsKey(key)) {
            throw new IllegalArgumentException("missing required key \"" + key + "\"");
        }
    }

    private static Map<String, BlockTable.Row> parseSection(Map<String, JsonValue> section) {
        Map<String, BlockTable.Row> rows = new LinkedHashMap<String, BlockTable.Row>();
        for (Map.Entry<String, JsonValue> entry : section.entrySet()) {
            rows.put(entry.getKey(), parseRow(entry.getKey(), entry.getValue().asObject()));
        }
        return rows;
    }

    private static BlockTable.Row parseRow(String key, Map<String, JsonValue> row) {
        for (String k : row.keySet()) {
            if (!ROW_KEYS.contains(k)) {
                throw new IllegalArgumentException(
                    "unknown key \"" + k + "\" in row \"" + key + "\"; expected one of " + ROW_KEYS);
            }
        }
        BlockShape shape = null;
        if (row.containsKey("shape")) {
            shape = constant(BlockShape.class, row.get("shape").asString(), key);
        }
        Fluid fluid = null;
        if (row.containsKey("fluid")) {
            fluid = constant(Fluid.class, row.get("fluid").asString(), key);
        }
        EnumSet<BlockTag> tags = EnumSet.noneOf(BlockTag.class);
        if (row.containsKey("tags")) {
            List<String> names = row.get("tags").asStringArray();
            for (String name : names) {
                tags.add(constant(BlockTag.class, name, key));
            }
        }
        return new BlockTable.Row(shape, fluid, tags);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String key) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "\"" + name + "\" in row \"" + key + "\" is not a " + type.getSimpleName()
                    + "; expected one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read the block table: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the table has already been read or has already failed.
        }
    }
}
```

- [ ] **Step 5: Write the two table resources**

**These are first cuts. Task 1's audit is the authority** — if it found a block needing a row that is not here, add it; if it found a row here to be unnecessary, delete it and note that you did.

Create `core/src/main/resources/blocks/1.7.10.json`:

```json
{
  "version": "1.7.10",
  "blocks": {
    "minecraft:flowing_water": { "fluid": "WATER" },
    "minecraft:water":         { "fluid": "WATER" },
    "minecraft:flowing_lava":  { "fluid": "LAVA", "tags": ["AVOID"] },
    "minecraft:lava":          { "fluid": "LAVA", "tags": ["AVOID"] },
    "minecraft:soul_sand":     { "tags": ["SLOW"] },
    "minecraft:web":           { "tags": ["SLOW"] },
    "minecraft:fire":          { "tags": ["AVOID"] },
    "minecraft:cactus":        { "tags": ["AVOID"] }
  },
  "states": {}
}
```

Create `core/src/main/resources/blocks/1.21.11.json`:

```json
{
  "version": "1.21.11",
  "blocks": {
    "minecraft:water":       { "fluid": "WATER" },
    "minecraft:lava":        { "fluid": "LAVA", "tags": ["AVOID"] },
    "minecraft:soul_sand":   { "tags": ["SLOW"] },
    "minecraft:cobweb":      { "tags": ["SLOW"] },
    "minecraft:honey_block": { "tags": ["SLOW"] },
    "minecraft:fire":        { "tags": ["AVOID"] },
    "minecraft:soul_fire":   { "tags": ["AVOID"] },
    "minecraft:cactus":      { "tags": ["AVOID"] },
    "minecraft:magma_block": { "tags": ["AVOID"] }
  },
  "states": {}
}
```

Note the asymmetry that justifies the table mechanism existing at all: 1.7.10 names the block `minecraft:web` and registers still and flowing water as **two different blocks**, while 1.21.11 names it `minecraft:cobweb` and has one. Neither adapter normalises this; the tables do.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockTableLoaderTest' --rerun-tasks`
Expected: PASS, 16 tests.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/dev/continuo/core/BlockTable.java core/src/main/java/dev/continuo/core/BlockTableLoader.java core/src/main/resources/blocks core/src/test/java/dev/continuo/core/BlockTableLoaderTest.java
git commit -m "feat(core): add the per-version block override table and its strict loader"
```

---

### Task 7: `BlockClassifier` — the geometry rules

**Files:**
- Create: `core/src/main/java/dev/continuo/core/BlockClassifier.java`
- Create: `core/src/test/java/dev/continuo/core/BlockClassifierGeometryTest.java`

**Interfaces:**
- Consumes: `BlockDescription` (Task 2); `BlockShape`, `BlockData`, `Fluid`, `BlockTag` (Task 4); `BlockTable` (Task 6)
- Produces: `new BlockClassifier(BlockTable)` and `BlockData classify(BlockDescription)`

**Authority note.** The eight rules below are what spec §3.3 states. **Task 1's audit is the authority.** If step 4 of Task 1 corrected a rule, implement the corrected version and say so in the commit message. This is a plan defect being ruled on, not a plan-mandated choice to escalate.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/continuo/core/BlockClassifierGeometryTest.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockClassifierGeometryTest {

    private final BlockClassifier classifier = new BlockClassifier(BlockTable.EMPTY);

    private BlockShape shapeOf(double... boxes) {
        return classifier.classify(
            new BlockDescription("a:b", "a:b", boxes, null, false, false)).shape();
    }

    private double topOf(double... boxes) {
        return classifier.classify(
            new BlockDescription("a:b", "a:b", boxes, null, false, false)).collisionTop();
    }

    @Test
    void noBoxesIsAir() {
        assertEquals(BlockShape.AIR, shapeOf());
        assertEquals(0.0, topOf(), 0.0);
    }

    @Test
    void oneFullCubeIsFull() {
        assertEquals(BlockShape.FULL, shapeOf(0, 0, 0, 1, 1, 1));
        assertEquals(1.0, topOf(0, 0, 0, 1, 1, 1), 0.0);
    }

    @Test
    void aFullFootprintLowerHalfIsABottomSlab() {
        assertEquals(BlockShape.SLAB_BOTTOM, shapeOf(0, 0, 0, 1, 0.5, 1));
        assertEquals(0.5, topOf(0, 0, 0, 1, 0.5, 1), 0.0);
    }

    @Test
    void aFullFootprintUpperHalfIsATopSlab() {
        assertEquals(BlockShape.SLAB_TOP, shapeOf(0, 0.5, 0, 1, 1, 1));
    }

    @Test
    void aFullFootprintQuarterHighLayerIsThin() {
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 0.0625, 1));
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 0.25, 1));
    }

    @Test
    void aFullFootprintBoxBetweenThinAndSlabIsPartial() {
        assertEquals(BlockShape.PARTIAL, shapeOf(0, 0, 0, 1, 0.3, 1),
            "categories are exact-match; a near-miss must not be smoothed into a slab");
    }

    @Test
    void anythingTallerThanTheCubeIsFence() {
        assertEquals(BlockShape.FENCE, shapeOf(0.375, 0, 0.375, 0.625, 1.5, 0.625));
        assertEquals(1.5, topOf(0.375, 0, 0.375, 0.625, 1.5, 0.625), 0.0);
    }

    @Test
    void fenceWinsOverEveryOtherRuleEvenWithAFullFootprintBoxPresent() {
        assertEquals(BlockShape.FENCE, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0.375, 0, 0.375, 0.625, 1.5, 0.625));
    }

    @Test
    void aLowerHalfPlusAPartialUpperBoxIsAStair() {
        assertEquals(BlockShape.STAIR, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 1, 1, 0.5));
    }

    @Test
    void aThreeBoxStairIsStillAStair() {
        assertEquals(BlockShape.STAIR, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 0.5, 1, 0.5,
            0.5, 0.5, 0, 1, 1, 0.5));
    }

    @Test
    void aPartialFootprintBoxIsPartial() {
        assertEquals(BlockShape.PARTIAL, shapeOf(0.25, 0, 0.25, 0.75, 1, 0.75));
    }

    @Test
    void twoStackedFullFootprintBoxesAreNotAStair() {
        assertEquals(BlockShape.PARTIAL, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 1, 1, 1),
            "a stair's upper box must not cover the whole footprint");
    }

    @Test
    void collisionTopIsTheMaximumAcrossAllBoxes() {
        assertEquals(0.9, topOf(
            0, 0, 0, 1, 0.5, 1,
            0.25, 0.4, 0.25, 0.75, 0.9, 0.75), 1e-9);
    }

    @Test
    void toleratesFloatingPointNoiseFromSixteenthsArithmetic() {
        assertEquals(BlockShape.FULL, shapeOf(0, 0, 0, 0.9999999, 1.0000001, 1),
            "boxes built from sixteenths arrive with rounding noise and must still match");
    }

    @Test
    void classifiesWithNoTagsAndNoFluidWhenTheTableIsEmpty() {
        BlockData d = classifier.classify(
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));
        assertEquals(Fluid.NONE, d.fluid());
        assertEquals(0, d.tags().size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockClassifierGeometryTest' --rerun-tasks`
Expected: FAIL — `BlockClassifier` does not exist.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/dev/continuo/core/BlockClassifier.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.BlockDescription;

import java.util.EnumSet;

/**
 * Turns an adapter's raw {@code BlockDescription} into the core's {@link BlockData}.
 *
 * <p>A pure function of a description and a {@link BlockTable} — no Minecraft, no world, no
 * mutable state. That is what makes this fully headless-testable, and it is what makes
 * cross-adapter agreement structural rather than hoped-for: both adapters' descriptions run
 * through this same code, so the two cannot disagree about what a given set of collision boxes
 * means.
 *
 * <p>Shape comes from geometry, then a whole-block table row, then a state-specific row. Tags
 * are the union of what the description implies and what the rows add. See the class's tests
 * for the exact geometry rules.
 */
public final class BlockClassifier {

    /**
     * Collision boxes are built from sixteenths on both target versions, so exact equality
     * against values like {@code 0.5} would fail on rounding noise. One ten-thousandth is far
     * below the smallest distinction any rule makes — a sixteenth is {@code 0.0625} — and far
     * above the error such arithmetic produces.
     */
    private static final double EPS = 1e-4;

    /** A {@link BlockShape#THIN_LAYER} reaches no higher than this. */
    private static final double THIN_LAYER_MAX = 0.25;

    private final BlockTable table;

    /**
     * @param table the per-version overrides; never {@code null}, use {@link BlockTable#EMPTY}
     */
    public BlockClassifier(BlockTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null; use BlockTable.EMPTY");
        }
        this.table = table;
    }

    /**
     * @param description the adapter's raw facts; never {@code null}
     * @return the classified block; never {@code null}
     */
    public BlockData classify(BlockDescription description) {
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        double[] boxes = description.collisionBoxes();

        BlockShape shape = shapeFromGeometry(boxes);
        double collisionTop = collisionTop(boxes);
        Fluid fluid = fluidFromId(description.fluidId());
        EnumSet<BlockTag> tags = tagsFromDescription(description);

        BlockTable.Row blockRow = table.forBlock(description.id());
        BlockTable.Row stateRow = table.forState(description.stateKey());

        shape = overrideShape(shape, blockRow, stateRow);
        fluid = overrideFluid(fluid, blockRow, stateRow);
        addRowTags(tags, blockRow);
        addRowTags(tags, stateRow);

        return new BlockData(shape, collisionTop, fluid, tags);
    }

    private static BlockShape shapeFromGeometry(double[] boxes) {
        int count = boxes.length / 6;
        if (count == 0) {
            return BlockShape.AIR;
        }
        if (collisionTop(boxes) > 1.0 + EPS) {
            return BlockShape.FENCE;
        }
        if (count == 1) {
            BlockShape single = singleBoxShape(boxes, 0);
            if (single != null) {
                return single;
            }
            return BlockShape.PARTIAL;
        }
        if (isStair(boxes, count)) {
            return BlockShape.STAIR;
        }
        return BlockShape.PARTIAL;
    }

    private static BlockShape singleBoxShape(double[] boxes, int index) {
        if (!fullFootprint(boxes, index)) {
            return null;
        }
        double minY = boxes[index * 6 + 1];
        double maxY = boxes[index * 6 + 4];
        if (near(minY, 0.0) && near(maxY, 1.0)) {
            return BlockShape.FULL;
        }
        if (near(minY, 0.0) && near(maxY, 0.5)) {
            return BlockShape.SLAB_BOTTOM;
        }
        if (near(minY, 0.5) && near(maxY, 1.0)) {
            return BlockShape.SLAB_TOP;
        }
        if (near(minY, 0.0) && maxY <= THIN_LAYER_MAX + EPS) {
            return BlockShape.THIN_LAYER;
        }
        return null;
    }

    /**
     * A full-footprint lower half, plus at least one box in the upper half that does
     * <em>not</em> cover the whole footprint. Two stacked full-footprint halves are a full
     * cube expressed oddly, not a stair.
     */
    private static boolean isStair(double[] boxes, int count) {
        boolean lowerHalf = false;
        boolean partialUpper = false;
        for (int i = 0; i < count; i++) {
            double minY = boxes[i * 6 + 1];
            double maxY = boxes[i * 6 + 4];
            if (fullFootprint(boxes, i) && near(minY, 0.0) && near(maxY, 0.5)) {
                lowerHalf = true;
            } else if (near(minY, 0.5) && near(maxY, 1.0) && !fullFootprint(boxes, i)) {
                partialUpper = true;
            }
        }
        return lowerHalf && partialUpper;
    }

    private static boolean fullFootprint(double[] boxes, int index) {
        int b = index * 6;
        return near(boxes[b], 0.0)
            && near(boxes[b + 2], 0.0)
            && near(boxes[b + 3], 1.0)
            && near(boxes[b + 5], 1.0);
    }

    private static double collisionTop(double[] boxes) {
        double top = 0.0;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (boxes[i + 4] > top) {
                top = boxes[i + 4];
            }
        }
        return top;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static Fluid fluidFromId(String fluidId) {
        if (fluidId == null) {
            return Fluid.NONE;
        }
        if ("minecraft:water".equals(fluidId)) {
            return Fluid.WATER;
        }
        if ("minecraft:lava".equals(fluidId)) {
            return Fluid.LAVA;
        }
        return Fluid.OTHER;
    }

    private static EnumSet<BlockTag> tagsFromDescription(BlockDescription description) {
        EnumSet<BlockTag> tags = EnumSet.noneOf(BlockTag.class);
        if (description.climbable()) {
            tags.add(BlockTag.CLIMBABLE);
        }
        if (description.gravity()) {
            tags.add(BlockTag.FALLING);
        }
        return tags;
    }

    private static BlockShape overrideShape(BlockShape shape, BlockTable.Row block, BlockTable.Row state) {
        BlockShape result = shape;
        if (block != null && block.shape() != null) {
            result = block.shape();
        }
        if (state != null && state.shape() != null) {
            result = state.shape();
        }
        return result;
    }

    private static Fluid overrideFluid(Fluid fluid, BlockTable.Row block, BlockTable.Row state) {
        Fluid result = fluid;
        if (block != null && block.fluid() != null) {
            result = block.fluid();
        }
        if (state != null && state.fluid() != null) {
            result = state.fluid();
        }
        return result;
    }

    private static void addRowTags(EnumSet<BlockTag> tags, BlockTable.Row row) {
        if (row != null) {
            tags.addAll(row.tags());
        }
    }
}
```

The table-handling private methods are written here because they are one coherent unit with the geometry pass; **Task 8 tests them.** Do not treat their presence as Task 8 being already done.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockClassifierGeometryTest' --rerun-tasks`
Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/continuo/core/BlockClassifier.java core/src/test/java/dev/continuo/core/BlockClassifierGeometryTest.java
git commit -m "feat(core): classify block shape from collision geometry"
```

---

### Task 8: `BlockClassifier` — table precedence, tags and fluid

No production code should be needed; Task 7 wrote it. **If a test here fails, that is the point** — fix `BlockClassifier`, do not weaken the test.

**Files:**
- Create: `core/src/test/java/dev/continuo/core/BlockClassifierTableTest.java`
- Modify (only if a test fails): `core/src/main/java/dev/continuo/core/BlockClassifier.java`

**Interfaces:**
- Consumes: everything from Task 7
- Produces: nothing new

- [ ] **Step 1: Write the test**

Create `core/src/test/java/dev/continuo/core/BlockClassifierTableTest.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockClassifierTableTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private static BlockClassifier with(String json) {
        return new BlockClassifier(BlockTableLoader.parse(json));
    }

    private static BlockDescription desc(String id, String stateKey) {
        return new BlockDescription(id, stateKey, CUBE.clone(), null, false, false);
    }

    @Test
    void aBlockWithNoRowIsClassifiedFromGeometryAlone() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{},\"states\":{}}")
            .classify(desc("a:unknown", "a:unknown"));
        assertEquals(BlockShape.FULL, d.shape());
        assertTrue(d.tags().isEmpty());
    }

    @Test
    void aBlockRowAddsTagsWithoutChangingShape() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:soul\":{\"tags\":[\"SLOW\"]}},\"states\":{}}")
            .classify(desc("a:soul", "a:soul"));
        assertEquals(BlockShape.FULL, d.shape());
        assertTrue(d.has(BlockTag.SLOW));
    }

    @Test
    void aBlockRowCanOverrideGeometrysShape() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:odd\":{\"shape\":\"PARTIAL\"}},\"states\":{}}")
            .classify(desc("a:odd", "a:odd"));
        assertEquals(BlockShape.PARTIAL, d.shape());
    }

    @Test
    void aStateRowBeatsABlockRowOnShape() {
        BlockData d = with("{\"version\":\"t\","
            + "\"blocks\":{\"a:s\":{\"shape\":\"FULL\"}},"
            + "\"states\":{\"a:s#8\":{\"shape\":\"SLAB_TOP\"}}}")
            .classify(desc("a:s", "a:s#8"));
        assertEquals(BlockShape.SLAB_TOP, d.shape());
    }

    @Test
    void aStateRowDoesNotApplyToADifferentState() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{},"
            + "\"states\":{\"a:s#8\":{\"shape\":\"SLAB_TOP\"}}}")
            .classify(desc("a:s", "a:s#0"));
        assertEquals(BlockShape.FULL, d.shape());
    }

    @Test
    void tagsFromBothRowsAreUnioned() {
        BlockData d = with("{\"version\":\"t\","
            + "\"blocks\":{\"a:s\":{\"tags\":[\"SLOW\"]}},"
            + "\"states\":{\"a:s#8\":{\"tags\":[\"AVOID\"]}}}")
            .classify(desc("a:s", "a:s#8"));
        assertTrue(d.has(BlockTag.SLOW), "the block row's tag must survive the state row");
        assertTrue(d.has(BlockTag.AVOID));
    }

    @Test
    void aRowCannotRemoveATagTheDescriptionImplied() {
        BlockDescription climbable =
            new BlockDescription("a:ladder", "a:ladder", new double[0], null, true, false);
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:ladder\":{\"tags\":[\"SLOW\"]}},\"states\":{}}")
            .classify(climbable);
        assertTrue(d.has(BlockTag.CLIMBABLE), "tag removal is deliberately unsupported");
        assertTrue(d.has(BlockTag.SLOW));
    }

    @Test
    void climbableInTheDescriptionBecomesTheClimbableTag() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:l", "a:l", new double[0], null, true, false));
        assertTrue(d.has(BlockTag.CLIMBABLE));
        assertFalse(d.has(BlockTag.FALLING));
    }

    @Test
    void gravityInTheDescriptionBecomesTheFallingTag() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:g", "a:g", CUBE.clone(), null, false, true));
        assertTrue(d.has(BlockTag.FALLING));
    }

    @Test
    void theTwoVanillaFluidIdsAreKnownWithoutATableRow() {
        BlockClassifier c = new BlockClassifier(BlockTable.EMPTY);
        assertEquals(Fluid.WATER, c.classify(
            new BlockDescription("a:w", "a:w", new double[0], "minecraft:water", false, false)).fluid());
        assertEquals(Fluid.LAVA, c.classify(
            new BlockDescription("a:l", "a:l", new double[0], "minecraft:lava", false, false)).fluid());
    }

    @Test
    void anUnrecognisedFluidIdBecomesOther() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:x", "a:x", new double[0], "mod:syrup", false, false));
        assertEquals(Fluid.OTHER, d.fluid());
    }

    @Test
    void aTableRowSuppliesAFluidTheDescriptionDidNotReport() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"minecraft:flowing_water\":{\"fluid\":\"WATER\"}},\"states\":{}}")
            .classify(new BlockDescription(
                "minecraft:flowing_water", "minecraft:flowing_water", new double[0], null, false, false));
        assertEquals(Fluid.WATER, d.fluid(),
            "this is exactly how 1.7.10's separate flowing_water block is normalised");
    }

    @Test
    void aTableRowCanOverrideAReportedFluid() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:x\":{\"fluid\":\"LAVA\"}},\"states\":{}}")
            .classify(new BlockDescription("a:x", "a:x", new double[0], "minecraft:water", false, false));
        assertEquals(Fluid.LAVA, d.fluid());
    }

    @Test
    void theShipped1710TableClassifiesFlowingWaterAsWater() {
        BlockClassifier c = new BlockClassifier(BlockTableLoader.forVersion("1.7.10"));
        BlockData d = c.classify(new BlockDescription(
            "minecraft:flowing_water", "minecraft:flowing_water", new double[0], null, false, false));
        assertEquals(Fluid.WATER, d.fluid());
        assertEquals(BlockShape.AIR, d.shape());
    }

    @Test
    void theShipped12111TableTagsSoulSandSlow() {
        BlockClassifier c = new BlockClassifier(BlockTableLoader.forVersion("1.21.11"));
        assertTrue(c.classify(desc("minecraft:soul_sand", "minecraft:soul_sand")).has(BlockTag.SLOW));
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockClassifierTableTest' --rerun-tasks`
Expected: PASS, 15 tests. If any fails, fix `BlockClassifier`.

- [ ] **Step 3: Prove the precedence tests are not vacuous**

Temporarily change `overrideShape` so the `state` branch is never taken (delete the `if (state != null ...)` block). Run the suite.

Expected: `aStateRowBeatsABlockRowOnShape` **fails**, and nothing else does. **Revert** and re-run to confirm green.

Then temporarily change `addRowTags` to `tags.clear(); tags.addAll(row.tags());`. Expected: `tagsFromBothRowsAreUnioned` and `aRowCannotRemoveATagTheDescriptionImplied` **fail**. **Revert** and confirm green.

- [ ] **Step 4: Commit**

```bash
git add core/src/test/java/dev/continuo/core/BlockClassifierTableTest.java core/src/main/java/dev/continuo/core/BlockClassifier.java
git commit -m "test(core): pin the classifier's table precedence, tag union and fluid rules"
```

---

### Task 9: `FakeBlockView` and `BlockLookup`

**Files:**
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/FakeBlockView.java`
- Create: `core/src/main/java/dev/continuo/core/BlockLookup.java`
- Create: `core/src/test/java/dev/continuo/core/BlockLookupTest.java`

**Interfaces:**
- Consumes: `IBlockView`, `BlockDescription` (Tasks 2–3); `BlockClassifier`, `BlockData` (Tasks 4, 7)
- Produces: `FakeBlockView` with `put(int x, int y, int z, BlockDescription)`, `setChunkLoaded(int cx, int cz, boolean)`, `describeCallCount()`, `stateIdCallCount()`, `failIfTouched()`; `new BlockLookup(IBlockView, BlockClassifier)` with `BlockData at(int x, int y, int z)` and `void clear()`

`FakeBlockView` goes in `:platform-testkit` rather than in `:core`'s test sources because M4's tests and `:runtime`'s dump-walker tests both need it. The testkit is already `api`-scoped for JUnit and both `:core` and `:runtime` already depend on it in test scope, so no build change is needed.

- [ ] **Step 1: Write `FakeBlockView`**

This is test infrastructure, so it is written before the test that uses it rather than test-first itself. Create `platform-testkit/src/main/java/dev/continuo/testkit/FakeBlockView.java`:

```java
package dev.continuo.testkit;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An array-free, map-backed {@link IBlockView} for headless tests.
 *
 * <p>Assigns each distinct {@link BlockDescription} a state id in insertion order, which is
 * enough to exercise every caller that treats state ids as opaque session-scoped integers —
 * which is all of them, by contract.
 *
 * <p>Counts its calls, so a test can assert that a memo actually memoised rather than merely
 * returning the right answer.
 */
public final class FakeBlockView implements IBlockView {

    private final Map<Long, Integer> stateIdByPosition = new HashMap<Long, Integer>();
    private final Map<Integer, BlockDescription> descriptionByStateId = new HashMap<Integer, BlockDescription>();
    private final Map<BlockDescription, Integer> stateIdByDescription = new HashMap<BlockDescription, Integer>();
    private final Set<Long> unloadedChunks = new HashSet<Long>();

    private int nextStateId;
    private int stateIdCalls;
    private int describeCalls;
    private boolean failIfTouched;
    private int minY;
    private int maxY = 256;

    /**
     * Places a block.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @param description what {@link #describe} will return there; never {@code null}
     * @return this, for chaining
     */
    public FakeBlockView put(int x, int y, int z, BlockDescription description) {
        Integer id = stateIdByDescription.get(description);
        if (id == null) {
            id = Integer.valueOf(nextStateId++);
            stateIdByDescription.put(description, id);
            descriptionByStateId.put(id, description);
        }
        stateIdByPosition.put(Long.valueOf(key(x, y, z)), id);
        return this;
    }

    /**
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @param loaded whether that chunk should report as loaded
     * @return this, for chaining
     */
    public FakeBlockView setChunkLoaded(int chunkX, int chunkZ, boolean loaded) {
        Long k = Long.valueOf(key(chunkX, 0, chunkZ));
        if (loaded) {
            unloadedChunks.remove(k);
        } else {
            unloadedChunks.add(k);
        }
        return this;
    }

    /**
     * Sets the vertical range this view reports.
     *
     * @param min inclusive lower bound
     * @param max exclusive upper bound
     * @return this, for chaining
     */
    public FakeBlockView setVerticalRange(int min, int max) {
        this.minY = min;
        this.maxY = max;
        return this;
    }

    /**
     * Makes every subsequent call throw. Used to assert that a sealed or cleared consumer
     * stops calling back into the platform.
     */
    public void failIfTouched() {
        this.failIfTouched = true;
    }

    /** @return how many times {@link #stateId} has been called */
    public int stateIdCallCount() {
        return stateIdCalls;
    }

    /** @return how many times {@link #describe} has been called */
    public int describeCallCount() {
        return describeCalls;
    }

    @Override
    public int stateId(int x, int y, int z) {
        guard();
        stateIdCalls++;
        if (y < minY || y >= maxY) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        Integer id = stateIdByPosition.get(Long.valueOf(key(x, y, z)));
        return id == null ? -1 : id.intValue();
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        guard();
        describeCalls++;
        Integer id = stateIdByPosition.get(Long.valueOf(key(x, y, z)));
        if (id == null) {
            throw new IllegalStateException("describe called for an unreadable position " + x + "," + y + "," + z);
        }
        return descriptionByStateId.get(id);
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        guard();
        return !unloadedChunks.contains(Long.valueOf(key(chunkX, 0, chunkZ)));
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    private void guard() {
        if (failIfTouched) {
            throw new AssertionError("the view was called after failIfTouched()");
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `core/src/test/java/dev/continuo/core/BlockLookupTest.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLookupTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private FakeBlockView view;
    private BlockLookup lookup;

    private static BlockDescription stone() {
        return new BlockDescription("minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false);
    }

    @BeforeEach
    void setUp() {
        view = new FakeBlockView();
        lookup = new BlockLookup(view, new BlockClassifier(BlockTable.EMPTY));
    }

    @Test
    void classifiesABlockItHasNotSeenBefore() {
        view.put(0, 64, 0, stone());
        assertEquals(BlockShape.FULL, lookup.at(0, 64, 0).shape());
    }

    @Test
    void describesEachStateOnlyOnce() {
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        view.put(2, 64, 0, stone());

        lookup.at(0, 64, 0);
        lookup.at(1, 64, 0);
        lookup.at(2, 64, 0);

        assertEquals(1, view.describeCallCount(), "three positions, one state, one describe");
        assertEquals(3, view.stateIdCallCount(), "stateId is the hot path and is called every time");
    }

    @Test
    void returnsTheSameInternedInstanceForTheSameState() {
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        assertSame(lookup.at(0, 64, 0), lookup.at(1, 64, 0));
    }

    @Test
    void anUnreadablePositionIsUnknownAndIsNotDescribed() {
        assertSame(BlockData.UNKNOWN, lookup.at(0, 64, 0));
        assertEquals(0, view.describeCallCount(), "describe must never be called for a -1 state id");
    }

    @Test
    void aPositionInAnUnloadedChunkIsUnknown() {
        view.put(0, 64, 0, stone());
        view.setChunkLoaded(0, 0, false);
        assertSame(BlockData.UNKNOWN, lookup.at(0, 64, 0));
    }

    @Test
    void aPositionOutsideTheVerticalRangeIsUnknown() {
        view.setVerticalRange(0, 256);
        assertSame(BlockData.UNKNOWN, lookup.at(0, 300, 0));
        assertSame(BlockData.UNKNOWN, lookup.at(0, -1, 0));
    }

    @Test
    void clearForgetsWhatItHadClassified() {
        view.put(0, 64, 0, stone());
        lookup.at(0, 64, 0);
        assertEquals(1, view.describeCallCount());

        lookup.clear();
        lookup.at(0, 64, 0);

        assertEquals(2, view.describeCallCount(), "clear() must force a fresh describe");
    }

    @Test
    void exposesTheViewsVerticalRange() {
        view.setVerticalRange(-64, 320);
        assertEquals(-64, lookup.minY());
        assertEquals(320, lookup.maxY());
    }

    @Test
    void rejectsANullView() {
        assertThrows(IllegalArgumentException.class,
            () -> new BlockLookup(null, new BlockClassifier(BlockTable.EMPTY)));
    }

    @Test
    void rejectsANullClassifier() {
        assertThrows(IllegalArgumentException.class, () -> new BlockLookup(new FakeBlockView(), null));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockLookupTest' --rerun-tasks`
Expected: FAIL — `BlockLookup` does not exist.

- [ ] **Step 4: Write `BlockLookup`**

Create `core/src/main/java/dev/continuo/core/BlockLookup.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.IBlockView;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads classified blocks from a live {@link IBlockView}, classifying each state once.
 *
 * <p>The hot path is an {@code int}: {@link IBlockView#stateId} is called per block, and the
 * far more expensive {@code describe}-and-classify path only when a state id has not been seen
 * before. Over a session that is a few thousand classifications rather than one per query.
 *
 * <p><b>Lifecycle.</b> State ids are session-scoped, so this memo must not outlive the level it
 * was built against. {@link #clear()} is called from {@code ContinuoCore.stop()}, which global
 * rule 2 already requires the adapter to call on every level transition — so no new machinery
 * and no new condition. A {@code HashMap} rather than an array because 1.21.11's state id space
 * is around 26,000 entries of which a session touches a small fraction.
 */
public final class BlockLookup {

    private final IBlockView view;
    private final BlockClassifier classifier;
    private final Map<Integer, BlockData> byStateId = new HashMap<Integer, BlockData>();

    /**
     * @param view the live reader; never {@code null}
     * @param classifier the shared classifier; never {@code null}
     */
    public BlockLookup(IBlockView view, BlockClassifier classifier) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (classifier == null) {
            throw new IllegalArgumentException("classifier must not be null");
        }
        this.view = view;
        this.classifier = classifier;
    }

    /**
     * The classified block at a position.
     *
     * <p>May only be called while {@code IGameEvents.onClientTick}'s delivery window is open;
     * that restriction comes from {@link IBlockView} and is not restated here.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block, or {@link BlockData#UNKNOWN} if the position is unreadable
     */
    public BlockData at(int x, int y, int z) {
        int stateId = view.stateId(x, y, z);
        if (stateId == -1) {
            return BlockData.UNKNOWN;
        }
        Integer key = Integer.valueOf(stateId);
        BlockData cached = byStateId.get(key);
        if (cached != null) {
            return cached;
        }
        BlockData classified = classifier.classify(view.describe(x, y, z));
        byStateId.put(key, classified);
        return classified;
    }

    /** Discards everything classified so far. Called on every level transition. */
    public void clear() {
        byStateId.clear();
    }

    /** @return the world's inclusive lower bound */
    public int minY() {
        return view.minY();
    }

    /** @return the world's exclusive upper bound */
    public int maxY() {
        return view.maxY();
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:test :platform-testkit:test --rerun-tasks`
Expected: PASS.

- [ ] **Step 6: Prove the memo test is not vacuous**

Temporarily delete the `if (cached != null) { return cached; }` early return. Run the suite.

Expected: `describesEachStateOnlyOnce` and `returnsTheSameInternedInstanceForTheSameState` **fail**; `clearForgetsWhatItHadClassified` also fails. **Revert** and confirm green.

- [ ] **Step 7: Commit**

```bash
git add platform-testkit/src/main/java/dev/continuo/testkit/FakeBlockView.java core/src/main/java/dev/continuo/core/BlockLookup.java core/src/test/java/dev/continuo/core/BlockLookupTest.java
git commit -m "feat(core): add BlockLookup, the per-session classified-block memo"
```

---

### Task 10: `FabricBlockView`

**No tests.** Adapters import `net.minecraft` and cannot run without a game; this is the project's standing constraint, not an omission. Verification is compilation, then the manual dump in Task 16.

**Files:**
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricBlockView.java`

**Interfaces:**
- Consumes: `IBlockView`, `BlockDescription`
- Produces: `FabricBlockView(Minecraft)`, package-private

- [ ] **Step 1: Confirm the API names against the 1.21.11 sources**

These moved in recent versions and **must be checked, not assumed**. Source root:
`C:/projects/continuo/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-2ae02fda0f/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/sources`

Confirm each and correct the code below where it differs:

| Needed | Believed name | Where to check |
|---|---|---|
| world vertical range | `Level.getMinY()` / `getMaxY()` | `net/minecraft/world/level/LevelHeightAccessor.java` — note whether `getMaxY()` is inclusive |
| chunk loaded | `ClientChunkCache.hasChunk(int, int)` | `net/minecraft/client/multiplayer/ClientChunkCache.java` |
| state id | `Block.getId(BlockState)` | `net/minecraft/world/level/block/Block.java` (verified: line ~134) |
| collision boxes | `BlockState.getCollisionShape(BlockGetter, BlockPos)` then `VoxelShape.toAabbs()` | `net/minecraft/world/phys/shapes/VoxelShape.java` |
| block registry key | `BuiltInRegistries.BLOCK.getKey(Block)` | `net/minecraft/core/registries/BuiltInRegistries.java` |
| fluid registry key | `BuiltInRegistries.FLUID.getKey(Fluid)` | same |

**`getMaxY()` being inclusive matters** — `IBlockView.maxY()` is exclusive, so if it is inclusive the adapter must add one.

- [ ] **Step 2: Write the implementation**

Create `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricBlockView.java`:

```java
package dev.continuo.adapter.fabric;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Translation only.
 *
 * <p>Reports what 1.21.11 says about a block and nothing more. Every judgement — what a set of
 * boxes means, whether a fluid id counts as water, which blocks to avoid — belongs to the
 * core's shared classifier, so that this adapter and the 1.7.10 one cannot reach different
 * conclusions about the same block.
 */
final class FabricBlockView implements IBlockView {

    private final Minecraft minecraft;

    FabricBlockView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public int stateId(int x, int y, int z) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return -1;
        }
        if (y < minY() || y >= maxY()) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        return Block.getId(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        ClientLevel level = minecraft.level;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);

        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        // toAabbs() returns block-relative boxes, which is what BlockDescription wants.
        List<AABB> aabbs = state.getCollisionShape(level, pos).toAabbs();
        double[] boxes = new double[aabbs.size() * 6];
        for (int i = 0; i < aabbs.size(); i++) {
            AABB b = aabbs.get(i);
            boxes[i * 6] = b.minX;
            boxes[i * 6 + 1] = b.minY;
            boxes[i * 6 + 2] = b.minZ;
            boxes[i * 6 + 3] = b.maxX;
            boxes[i * 6 + 4] = b.maxY;
            boxes[i * 6 + 5] = b.maxZ;
        }

        FluidState fluid = state.getFluidState();
        String fluidId = fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();

        return new BlockDescription(
            id,
            stateKey(id, state),
            boxes,
            fluidId,
            state.is(BlockTags.CLIMBABLE),
            state.getBlock() instanceof FallingBlock);
    }

    /**
     * {@code minecraft:oak_slab[type=bottom,waterlogged=false]}.
     *
     * <p>Built by hand rather than from {@code BlockState.toString()}, which wraps the block in
     * {@code Block{...}} and would not match the key format the 1.7.10 tables use.
     */
    private static String stateKey(String id, BlockState state) {
        Map<Property<?>, Comparable<?>> values = state.getValues();
        if (values.isEmpty()) {
            return id;
        }
        StringBuilder out = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(entry.getKey().getName()).append('=').append(entry.getValue());
        }
        return out.append(']').toString();
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ClientLevel level = minecraft.level;
        return level != null && level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    @Override
    public int minY() {
        ClientLevel level = minecraft.level;
        return level == null ? 0 : level.getMinY();
    }

    @Override
    public int maxY() {
        ClientLevel level = minecraft.level;
        // IBlockView.maxY() is exclusive. Adjust here if step 1 found getMaxY() is inclusive.
        return level == null ? 0 : level.getMaxY();
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:compileJava --rerun-tasks`
Expected: BUILD SUCCESSFUL. Compilation is the only automated verification this class can have.

- [ ] **Step 4: Commit**

```bash
git add adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricBlockView.java
git commit -m "feat(fabric): report raw block facts through IBlockView"
```

---

### Task 11: `ForgeBlockView`

**No tests**, for the same reason as Task 10.

**Files:**
- Create: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgeBlockView.java`

**Interfaces:**
- Consumes: `IBlockView`, `BlockDescription`
- Produces: `ForgeBlockView(Minecraft)`, package-private

- [ ] **Step 1: Understand the two traps before writing anything**

Both were found by reading the 1.7.10 sources and both produce **silent** wrong answers.

**Trap 1 — the bounds fields do not describe the collision shape.** `BlockFence.addCollisionBoxesToList` emits boxes with `maxY = 1.5F`, but `setBlockBoundsBasedOnState` resets `maxY` to `1.0F`, and `getCollisionBoundingBoxFromPool` reads those reset fields. Taking the bounds-then-read route classifies every fence as `FULL` on 1.7.10 only. **Use `addCollisionBoxesToList`.**

However, `Block.addCollisionBoxesToList`'s *base* implementation reads the bounds fields, and vanilla's `World.getCollidingBoundingBoxes` does **not** set them first (verified at `World.java:1619`). So blocks that do not override the method rely on whatever the bounds were last set to. **Call `setBlockBoundsBasedOnState` first and then `addCollisionBoxesToList`** — that is strictly more correct than vanilla, and harmless for overriding blocks, which set their own bounds internally.

**Trap 2 — the AABB pool reuses instances.** `getCollisionBoundingBoxFromPool`'s javadoc says the returned box "can change after the pool has been cleared to be reused". **Copy the six doubles out immediately; never retain an `AxisAlignedBB`.**

- [ ] **Step 2: Write the implementation**

Create `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgeBlockView.java`:

```java
package dev.continuo.adapter.forge;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Translation only.
 *
 * <p>Reports what 1.7.10 says about a block and nothing more. Notably it does <em>not</em>
 * normalise {@code flowing_water} to water — 1.7.10 registers still and flowing water as two
 * distinct blocks, and collapsing them is classification, which the core's per-version table
 * does instead.
 */
final class ForgeBlockView implements IBlockView {

    /** 1.7.10's world is fixed at 0..256. */
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 256;

    private final Minecraft minecraft;

    ForgeBlockView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public int stateId(int x, int y, int z) {
        World world = minecraft.theWorld;
        if (world == null) {
            return -1;
        }
        if (y < MIN_Y || y >= MAX_Y) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        // The format's own composition: 12 bits of block id, 4 of metadata.
        return (Block.getIdFromBlock(block) << 4) | (meta & 0xF);
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        World world = minecraft.theWorld;
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);

        String id = String.valueOf(Block.blockRegistry.getNameForObject(block));

        return new BlockDescription(
            id,
            id + "#" + meta,
            collisionBoxes(world, block, x, y, z),
            fluidId(block, id),
            block.isLadder(world, x, y, z, null),
            block instanceof BlockFalling);
    }

    /**
     * The block's collision boxes, in block-relative coordinates.
     *
     * <p>The mask reaches two blocks above the target because fences and walls emit boxes 1.5
     * tall, and {@code addCollisionBoxesToList} only adds a box that intersects the mask.
     */
    private static double[] collisionBoxes(World world, Block block, int x, int y, int z) {
        // Base Block.addCollisionBoxesToList reads the bounds fields, and vanilla does not set
        // them first. Setting them here is strictly more correct, and harmless for the blocks
        // that override the method, since those set their own bounds internally.
        block.setBlockBoundsBasedOnState(world, x, y, z);

        AxisAlignedBB mask = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 2.0D, z + 1.0D);
        List<AxisAlignedBB> collected = new ArrayList<AxisAlignedBB>();
        block.addCollisionBoxesToList(world, x, y, z, mask, collected, null);

        double[] boxes = new double[collected.size() * 6];
        for (int i = 0; i < collected.size(); i++) {
            // Copied out immediately: these instances come from a pool that is cleared and
            // reused, so retaining one and reading it later yields another block's geometry.
            AxisAlignedBB b = collected.get(i);
            boxes[i * 6] = b.minX - x;
            boxes[i * 6 + 1] = b.minY - y;
            boxes[i * 6 + 2] = b.minZ - z;
            boxes[i * 6 + 3] = b.maxX - x;
            boxes[i * 6 + 4] = b.maxY - y;
            boxes[i * 6 + 5] = b.maxZ - z;
        }
        return boxes;
    }

    /**
     * The block's own registry name when it is a fluid block, reported verbatim.
     *
     * <p>1.7.10 has no separate fluid concept — water <em>is</em> a block — so the fluid id is
     * the block id, and the per-version table maps {@code minecraft:flowing_water} onto water.
     */
    private static String fluidId(Block block, String id) {
        Material material = block.getMaterial();
        if (material == Material.water || material == Material.lava) {
            return id;
        }
        return null;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        World world = minecraft.theWorld;
        return world != null && world.getChunkProvider().chunkExists(chunkX, chunkZ);
    }

    @Override
    public int minY() {
        return MIN_Y;
    }

    @Override
    public int maxY() {
        return MAX_Y;
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :adapters:adapter-forge-1.7.10:compileJava --rerun-tasks`
Expected: BUILD SUCCESSFUL.

`Block.isLadder`'s last parameter is an `EntityLivingBase` and vanilla implementations ignore it, so `null` is safe. If a compile error says otherwise, cast: `(net.minecraft.entity.EntityLivingBase) null`.

- [ ] **Step 4: Commit**

```bash
git add adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgeBlockView.java
git commit -m "feat(forge): report raw block facts through IBlockView"
```

---

### Task 12: Wire `blocks()` through the SPI and into `ContinuoCore`

This is the one task that changes `IPlatformContext`, and it necessarily touches every implementation at once — adding the method earlier would have broken both adapters' compilation until Tasks 10 and 11 existed.

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlatformContext.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlatformContext.java`
- Modify: `core/src/main/java/dev/continuo/core/ContinuoCore.java`
- Modify: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`

**Interfaces:**
- Consumes: `IBlockView` (Task 3), `BlockLookup` (Task 9), `FabricBlockView`/`ForgeBlockView` (Tasks 10–11)
- Produces: `IPlatformContext.blocks()`; `FakePlatformContext.fakeBlockView()`; `ContinuoCore.blocks()` returning the live `BlockLookup`

**A note the reviewer will otherwise raise.** `ContinuoCore` selects its table with `context.info().gameVersion()`, and `IPlatformInfo`'s javadoc says that string *"is not for feature detection"*. That warning is about branching **behaviour**; this selects **data**. It is the roadmap's "version differences are data, not branches" rule working exactly as written — no core code path changes, and a version with no table classifies from geometry rather than behaving differently. Do not "fix" this by adding a capability check.

- [ ] **Step 1: Write the failing test**

Append to `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java` — inside the existing class, and add `import dev.continuo.platform.BlockDescription;` and `import static org.junit.jupiter.api.Assertions.assertNotNull;` / `assertSame` at the top:

```java
    @Test
    void exposesAClassifyingLookupOverThePlatformsBlockView() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        assertEquals(BlockShape.FULL, core.blocks().at(0, 64, 0).shape());
    }

    @Test
    void theLookupIsTheSameInstanceAcrossCalls() {
        assertSame(core.blocks(), core.blocks());
    }

    @Test
    void stopClearsTheBlockMemoSoStateIdsCannotOutliveTheirLevel() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        core.blocks().at(0, 64, 0);
        assertEquals(1, ctx.fakeBlockView().describeCallCount());

        core.stop();
        core.blocks().at(0, 64, 0);

        assertEquals(2, ctx.fakeBlockView().describeCallCount(),
            "global rule 2 requires stop() on every level transition, and state ids are session-scoped");
    }

    @Test
    void blocksBeforeStartIsAnError() {
        ContinuoCore fresh = new ContinuoCore();
        assertThrows(IllegalStateException.class, fresh::blocks);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.continuo.core.ContinuoCoreTest' --rerun-tasks`
Expected: FAIL — `fakeBlockView()` and `core.blocks()` do not exist.

- [ ] **Step 3: Add `blocks()` to `IPlatformContext`**

Add to `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`, inside the interface:

```java
    /**
     * The block reader for this platform.
     *
     * <p>The returned instance is valid for the adapter's lifetime and internally reads
     * whichever level is current, so the core may cache it — but its <em>methods</em> may only
     * be called while {@link IGameEvents#onClientTick}'s delivery window is open. See
     * {@link IBlockView}.
     *
     * @return the block view; never {@code null}, and the same instance on every call
     */
    IBlockView blocks();
```

- [ ] **Step 4: Implement it in the three contexts**

In `FakePlatformContext`, add the field, the override, and an accessor:

```java
    private final FakeBlockView blockView = new FakeBlockView();

    @Override
    public IBlockView blocks() {
        return blockView;
    }

    public FakeBlockView fakeBlockView() {
        return blockView;
    }
```

with `import dev.continuo.platform.IBlockView;` added.

In `FabricPlatformContext`, add a field initialised in the constructor and the override:

```java
    private final IBlockView blocks;
    // in the constructor, alongside the actuator:
    this.blocks = new FabricBlockView(minecraft);

    @Override
    public IBlockView blocks() {
        return blocks;
    }
```

In `ForgePlatformContext`, the same with `new ForgeBlockView(minecraft)`.

- [ ] **Step 5: Give `ContinuoCore` the lookup**

In `ContinuoCore`, add a field and build it in `start`:

```java
    private BlockLookup blocks;
```

At the end of `start(IPlatformContext context)`, after the null check and assignment:

```java
        this.blocks = new BlockLookup(
            context.blocks(),
            new BlockClassifier(BlockTableLoader.forVersion(context.info().gameVersion())));
```

Add the accessor:

```java
    /**
     * Classified block reads for the current level.
     *
     * <p>Nothing in the core consumes this yet — M4's pathfinder is its first reader. It is
     * wired now so the whole chain, from an adapter's raw facts through the shared classifier
     * to a memoised {@link BlockData}, is exercised and its lifecycle is real rather than
     * hypothetical.
     *
     * @return the lookup; never {@code null} after {@code start}
     * @throws IllegalStateException if {@code start} has not been called
     */
    public BlockLookup blocks() {
        if (blocks == null) {
            throw new IllegalStateException("start(IPlatformContext) must be called first");
        }
        return blocks;
    }
```

And in `stop()`, after the existing input release, before the field resets:

```java
        if (blocks != null) {
            blocks.clear();
        }
```

Note `stop()` must keep working when `start` was never called — it already throws `IllegalStateException` in that case, so the null guard is belt and braces rather than the primary defence.

- [ ] **Step 6: Run the whole build**

Run: `./gradlew clean build --rerun-tasks`
Expected: BUILD SUCCESSFUL. This is the first point at which every module, both adapters, all three purity and direction checks, and the whole test suite run together against the new SPI method.

If `clean` fails with `Unable to delete directory`, that is a stale daemon: `./gradlew --stop`, wait ten seconds, retry.

- [ ] **Step 7: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/IPlatformContext.java platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java adapters core/src/main/java/dev/continuo/core/ContinuoCore.java core/src/test/java/dev/continuo/core/ContinuoCoreTest.java
git commit -m "feat: expose IBlockView through IPlatformContext and wire it into the core"
```

---

### Task 13: `BlockDumpWalker`

The parity evidence generator. It lives in `:runtime` so both adapters share it and neither grows test code — the same reasoning that put `AdapterRuntime` there.

**Files:**
- Create: `runtime/src/main/java/dev/continuo/runtime/BlockDumpWalker.java`
- Create: `runtime/src/test/java/dev/continuo/runtime/BlockDumpWalkerTest.java`

**Interfaces:**
- Consumes: `IBlockView` (Task 3), `BlockClassifier`, `BlockData` (Tasks 4, 7), `FakeBlockView` (Task 9)
- Produces: `BlockDumpWalker.dump(IBlockView, BlockClassifier, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)` returning a `String`

- [ ] **Step 1: Write the failing test**

Create `runtime/src/test/java/dev/continuo/runtime/BlockDumpWalkerTest.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockTable;
import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDumpWalkerTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private final BlockClassifier classifier = new BlockClassifier(BlockTable.EMPTY);

    private static BlockDescription stone() {
        return new BlockDescription("minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false);
    }

    @Test
    void emitsOneLinePerPositionInIndexOrder() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());

        String dump = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 64, 0);
        String[] lines = dump.split("\n");

        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("0\t"), lines[0]);
        assertTrue(lines[1].startsWith("1\t"), lines[1]);
    }

    @Test
    void eachLineCarriesTheIdTheStateKeyAndTheClassifiedData() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());

        String line = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 0, 64, 0);

        assertEquals("0\tminecraft:stone\tminecraft:stone\tFULL top=1.0 fluid=NONE tags=[]", line);
    }

    @Test
    void anUnreadablePositionIsRecordedRatherThanSkipped() {
        FakeBlockView view = new FakeBlockView();

        String line = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 0, 64, 0);

        assertEquals("0\t-\t-\tUNKNOWN top=0.0 fluid=NONE tags=[]", line,
            "a hole in the dump must be visible, not absent");
    }

    @Test
    void walksXThenZThenY() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        view.put(0, 64, 1, stone());
        view.put(0, 65, 0, stone());

        String[] lines = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 65, 1).split("\n");

        assertEquals(8, lines.length, "2x2x2 region");
        assertTrue(lines[0].startsWith("0\t"));
        assertTrue(lines[7].startsWith("7\t"));
    }

    @Test
    void doesNotDescribeAStateItHasAlreadySeen() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());

        BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 64, 0);

        assertEquals(1, view.describeCallCount(),
            "the walker must reuse the core's memo rather than reclassifying every position");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :runtime:test --rerun-tasks`
Expected: FAIL — `BlockDumpWalker` does not exist.

- [ ] **Step 3: Write the implementation**

Create `runtime/src/main/java/dev/continuo/runtime/BlockDumpWalker.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockData;
import dev.continuo.core.BlockLookup;
import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;

/**
 * Produces the cross-adapter parity dump.
 *
 * <p>The conformance suite cannot see an adapter's block view — asserting it needs a live world,
 * which is the same structural reason adapters have no automated tests at all. This walker is
 * the substitute: an owner runs it once per version against the same fixture structure, the two
 * outputs are checked in, and a headless test diffs them.
 *
 * <p>Dev-only. Nothing calls it during normal operation.
 *
 * <p>Each line carries the raw {@code id} and {@code stateKey} alongside the classified result,
 * so a mismatch can be read as either "the classifier decided differently" or "the wrong block
 * was placed" without a second run.
 */
public final class BlockDumpWalker {

    private BlockDumpWalker() {
    }

    /**
     * Walks a region and renders it, one line per position.
     *
     * <p>Positions are visited X fastest, then Z, then Y, and numbered from zero in that order.
     * All bounds are inclusive.
     *
     * @param view the live reader
     * @param classifier the shared classifier, built with the version's table
     * @param minX region start X
     * @param minY region start Y
     * @param minZ region start Z
     * @param maxX region end X, inclusive
     * @param maxY region end Y, inclusive
     * @param maxZ region end Z, inclusive
     * @return the dump; lines separated by {@code \n}, with no trailing newline
     */
    public static String dump(IBlockView view,
                              BlockClassifier classifier,
                              int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
        BlockLookup lookup = new BlockLookup(view, classifier);
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (index > 0) {
                        out.append('\n');
                    }
                    out.append(index).append('\t').append(line(view, lookup, x, y, z));
                    index++;
                }
            }
        }
        return out.toString();
    }

    private static String line(IBlockView view, BlockLookup lookup, int x, int y, int z) {
        BlockData data = lookup.at(x, y, z);
        if (view.stateId(x, y, z) == -1) {
            return "-\t-\t" + data;
        }
        BlockDescription description = view.describe(x, y, z);
        return description.id() + '\t' + description.stateKey() + '\t' + data;
    }
}
```

`line` calls `describe` directly rather than through the memo, which is why the memo test asserts one `describe` call for the *classification* path — re-reading a description for the dump text is deliberate and cheap at this scale.

Correction if step 5 disagrees: if `doesNotDescribeAStateItHasAlreadySeen` fails because `line` adds calls, change the assertion to count classifications rather than describes, or cache descriptions in a local map. **Prefer the local map** — the test is asserting something real about not doing redundant work.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :runtime:test --rerun-tasks`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/BlockDumpWalker.java runtime/src/test/java/dev/continuo/runtime/BlockDumpWalkerTest.java
git commit -m "feat(runtime): add the dev-only block dump walker for cross-adapter parity"
```

---

### Task 14: The dump keybind in both adapters

**No tests**, per the standing adapter constraint.

**Files:**
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java`

**Interfaces:**
- Consumes: `BlockDumpWalker` (Task 13), each adapter's existing keybind registration
- Produces: a second keybind per adapter that writes `continuo-block-dump.txt` to the game directory

- [ ] **Step 1: Decide the region, once, for both adapters**

The dump covers **the 32 blocks starting at the player's feet and running +X**, one high, one deep. The player stands at the west end of the fixture row facing east.

Region, given the player's block position `px, py, pz`: `dump(view, classifier, px, py, pz, px + 31, py, pz)`.

Thirty-two rather than the audit's ~30 leaves headroom without changing the code when a row is added; unused indices dump as `UNKNOWN` and the diff skips them by fixture-layout convention.

- [ ] **Step 2: Add the keybind to the Fabric adapter**

Register a second `KeyMapping` next to the existing walk key, on `GLFW_KEY_J`, using the same category. In the tick handler where the walk key's `consumeClick()` is already polled, add a second poll that writes the dump:

```java
        if (dumpKey.consumeClick() && minecraft.player != null) {
            java.nio.file.Path out = minecraft.gameDirectory.toPath().resolve("continuo-block-dump.txt");
            net.minecraft.core.BlockPos at = minecraft.player.blockPosition();
            String text = dev.continuo.runtime.BlockDumpWalker.dump(
                context.blocks(),
                new dev.continuo.core.BlockClassifier(
                    dev.continuo.core.BlockTableLoader.forVersion(context.info().gameVersion())),
                at.getX(), at.getY(), at.getZ(),
                at.getX() + 31, at.getY(), at.getZ());
            try {
                java.nio.file.Files.write(out, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LOGGER.info("Continuo: wrote block dump to {}", out);
            } catch (java.io.IOException e) {
                LOGGER.error("Continuo: could not write the block dump", e);
            }
        }
```

Fully-qualified names are used here so this snippet can be pasted without reconciling imports; tidy them into imports afterwards if you prefer, but do not change behaviour.

**The dump must not be able to crash the game.** It runs inside the tick callback, so an escaping exception would violate global rule 3's spirit even though the rule binds core faults specifically. The `try`/`catch` above covers I/O; wrap the `dump` call itself too if you are not confident the region is always readable.

- [ ] **Step 3: Add the keybind to the Forge adapter**

Register a second `KeyBinding` next to the existing walk key, on LWJGL2's `Keyboard.KEY_J`, with the same `String` category. In the tick handler where the walk key's `isPressed()` is already polled, add:

```java
        if (dumpKey.isPressed() && minecraft.thePlayer != null) {
            int px = net.minecraft.util.MathHelper.floor_double(minecraft.thePlayer.posX);
            int py = net.minecraft.util.MathHelper.floor_double(minecraft.thePlayer.posY);
            int pz = net.minecraft.util.MathHelper.floor_double(minecraft.thePlayer.posZ);
            java.io.File out = new java.io.File(minecraft.mcDataDir, "continuo-block-dump.txt");
            String text = dev.continuo.runtime.BlockDumpWalker.dump(
                context.blocks(),
                new dev.continuo.core.BlockClassifier(
                    dev.continuo.core.BlockTableLoader.forVersion(context.info().gameVersion())),
                px, py, pz,
                px + 31, py, pz);
            java.io.OutputStream stream = null;
            try {
                stream = new java.io.FileOutputStream(out);
                stream.write(text.getBytes("UTF-8"));
                LOGGER.info("Continuo: wrote block dump to " + out.getAbsolutePath());
            } catch (java.io.IOException e) {
                LOGGER.error("Continuo: could not write the block dump", e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (java.io.IOException ignored) {
                        // Already written or already failed; nothing useful to do.
                    }
                }
            }
        }
```

`java.nio.file.Files` and `StandardCharsets` are avoided here: this module targets a Java 8 runtime but the surrounding 1.7.10 code style is Java 6-era, and `FileOutputStream` matches it. `MathHelper.floor_double` rather than a cast, because a cast truncates towards zero and gives the wrong block at negative coordinates — which is a real bug at spawn on many worlds, not a nicety.

`LOGGER` is the adapter's existing log4j logger; note its `error(String, Throwable)` signature differs from SLF4J's brace-formatting, so do not copy the Fabric call verbatim.

- [ ] **Step 4: Verify both compile**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:compileJava :adapters:adapter-forge-1.7.10:compileJava --rerun-tasks`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add adapters
git commit -m "feat(adapters): add a dev-only keybind that writes the block dump"
```

---

### Task 15: The parity diff test

Written and proved against **synthetic** dumps committed as test resources, so the test is real and non-vacuous before the owner ever runs a client. Task 17 swaps in the real dumps.

**Files:**
- Create: `core/src/test/java/dev/continuo/core/BlockParityTest.java`
- Create: `core/src/test/resources/parity/sample-a.txt`, `sample-b.txt`, `sample-mismatch.txt`
- Create: `docs/parity/README.md`
- Create: `docs/parity/fixture-layout.md`

**Interfaces:**
- Consumes: the dump format from Task 13
- Produces: `BlockParityTest`, which reads `docs/parity/blocks-1.7.10.txt`, `docs/parity/blocks-1.21.11.txt` and `docs/parity/blocks-expected.txt`

- [ ] **Step 1: Write the fixture-layout and README docs**

`docs/parity/fixture-layout.md` holds the index-to-block table produced by Task 1 step 5, plus: where the player stands, which direction the row runs, and which indices are version-exclusive.

`docs/parity/README.md` states in three sentences what these files are, that they are **generated by a human running a client**, and that editing them by hand defeats their entire purpose.

- [ ] **Step 2: Write the synthetic dumps**

`core/src/test/resources/parity/sample-a.txt`:

```
0	minecraft:stone	minecraft:stone	FULL top=1.0 fluid=NONE tags=[]
1	minecraft:oak_fence	minecraft:oak_fence	FENCE top=1.5 fluid=NONE tags=[]
2	-	-	UNKNOWN top=0.0 fluid=NONE tags=[]
```

`sample-b.txt` — same classified data, different native names, which is the whole point:

```
0	minecraft:stone	minecraft:stone	FULL top=1.0 fluid=NONE tags=[]
1	minecraft:fence	minecraft:fence#0	FENCE top=1.5 fluid=NONE tags=[]
2	-	-	UNKNOWN top=0.0 fluid=NONE tags=[]
```

`sample-mismatch.txt` — index 1 classified differently:

```
0	minecraft:stone	minecraft:stone	FULL top=1.0 fluid=NONE tags=[]
1	minecraft:fence	minecraft:fence#0	FULL top=1.0 fluid=NONE tags=[]
2	-	-	UNKNOWN top=0.0 fluid=NONE tags=[]
```

- [ ] **Step 3: Write the test**

Create `core/src/test/java/dev/continuo/core/BlockParityTest.java`:

```java
package dev.continuo.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-adapter parity: both adapters must classify the same fixture world identically.
 *
 * <p>The real dumps are produced by a human running each client, so the three cases that read
 * them are skipped until those files exist. The mechanism itself is proved against synthetic
 * dumps, which run always.
 */
class BlockParityTest {

    /** One dump line: index, native id, native state key, and the classified data as text. */
    private static final class Entry {
        final int index;
        final String id;
        final String stateKey;
        final String data;

        Entry(int index, String id, String stateKey, String data) {
            this.index = index;
            this.id = id;
            this.stateKey = stateKey;
            this.data = data;
        }
    }

    private static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<Entry>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                    "dump line " + (i + 1) + " has " + parts.length + " fields, expected 4: " + line);
            }
            entries.add(new Entry(Integer.parseInt(parts[0]), parts[1], parts[2], parts[3]));
        }
        return entries;
    }

    private static String resource(String name) {
        InputStream in = BlockParityTest.class.getResourceAsStream("/parity/" + name);
        assertTrue(in != null, "missing test resource /parity/" + name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Compares two dumps on the classified data only. Native ids and state keys legitimately
     * differ between versions and are never compared — only reported when something else does.
     */
    private static void assertParity(String leftName, String left, String rightName, String right) {
        List<Entry> a = parse(left);
        List<Entry> b = parse(right);
        assertEquals(a.size(), b.size(), leftName + " and " + rightName + " have different lengths");
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i);
            Entry y = b.get(i);
            assertEquals(x.index, y.index, "index mismatch at line " + (i + 1));
            assertEquals(x.data, y.data,
                "index " + x.index + " differs: " + leftName + " has " + x.id + " (" + x.stateKey + ") -> " + x.data
                    + "; " + rightName + " has " + y.id + " (" + y.stateKey + ") -> " + y.data);
        }
    }

    private static Path dump(String name) {
        return Paths.get("..", "docs", "parity", name);
    }

    static boolean realDumpsExist() {
        return Files.exists(dump("blocks-1.7.10.txt"))
            && Files.exists(dump("blocks-1.21.11.txt"))
            && Files.exists(dump("blocks-expected.txt"));
    }

    private static String readDump(String name) {
        try {
            return new String(Files.readAllBytes(dump(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + dump(name), e);
        }
    }

    @Test
    void identicalClassificationsAgreeEvenWhenNativeNamesDiffer() {
        assertParity("sample-a", resource("sample-a.txt"), "sample-b", resource("sample-b.txt"));
    }

    @Test
    void aDifferingClassificationIsADisagreement() {
        AssertionError e = assertThrows(AssertionError.class, () ->
            assertParity("sample-a", resource("sample-a.txt"),
                "sample-mismatch", resource("sample-mismatch.txt")));
        assertTrue(e.getMessage().contains("index 1"), e.getMessage());
        assertTrue(e.getMessage().contains("minecraft:oak_fence"),
            "the failure must name the native blocks so it can be diagnosed without a rerun");
    }

    @Test
    void aMalformedDumpLineIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> parse("0\tonly\ttwo"));
    }

    @Test
    void aTruncatedDumpIsADisagreement() {
        assertThrows(AssertionError.class, () ->
            assertParity("full", resource("sample-a.txt"), "short", "0\tminecraft:stone\tminecraft:stone\tFULL top=1.0 fluid=NONE tags=[]"));
    }

    @Test
    @EnabledIf("realDumpsExist")
    void theTwoAdaptersAgreeOnTheFixtureWorld() {
        assertParity("1.7.10", readDump("blocks-1.7.10.txt"), "1.21.11", readDump("blocks-1.21.11.txt"));
    }

    @Test
    @EnabledIf("realDumpsExist")
    void bothAdaptersMatchTheGolden() {
        assertParity("golden", readDump("blocks-expected.txt"), "1.7.10", readDump("blocks-1.7.10.txt"));
        assertParity("golden", readDump("blocks-expected.txt"), "1.21.11", readDump("blocks-1.21.11.txt"));
    }
}
```

**The golden is not redundant with the two-way diff.** Without it, a change that broke both versions identically would pass — both dumps would still agree with each other while both being wrong.

The relative path `../docs/parity/...` is resolved from `:core`'s project directory, which is Gradle's working directory for its test task.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockParityTest' --rerun-tasks`
Expected: PASS, 4 tests run and 2 skipped (`@EnabledIf` — the real dumps do not exist yet).

**Confirm the skip count.** If the two `@EnabledIf` tests report as *passed* rather than *skipped*, `realDumpsExist` is wrong and the parity gate is silently vacuous.

- [ ] **Step 5: Prove the diff is not vacuous**

Temporarily change `assertParity`'s `assertEquals(x.data, y.data, ...)` to `assertEquals(x.data, x.data, ...)`. Run.

Expected: `aDifferingClassificationIsADisagreement` **fails**. **Revert** and confirm green. This is the single most important mutation in the plan — a parity test that compares a value with itself is exactly the vacuous shape A2b found twice.

- [ ] **Step 6: Commit**

```bash
git add core/src/test/java/dev/continuo/core/BlockParityTest.java core/src/test/resources/parity docs/parity
git commit -m "test(core): add the cross-adapter parity diff, proved against synthetic dumps"
```

---

### Task 16: Smoke-checklist steps for the dump

**Files:**
- Modify: `docs/smoke-checklist-a1.md`
- Modify: `docs/smoke-checklist-a2.md`

- [ ] **Step 1: Add the dump steps to both checklists**

Append a section to each, matching the file's existing step style, covering: enter a creative superflat world; build the fixture row per `docs/parity/fixture-layout.md`; stand at the west end facing east; press **J**; confirm the log line naming the output path; copy the file to `docs/parity/blocks-<version>.txt`.

- [ ] **Step 2: Carry the existing disclaimers forward**

Both checklists already state that a green run does **not** cover global rule 3, the click drain, or PRE/POST pairing. Add one more in the same voice: **a green dump does not prove the block model is correct, only that the two adapters agree.** Both could be wrong in the same way — that is what the golden in Task 15 exists to catch, and the golden is only as good as the audit that produced it.

- [ ] **Step 3: Record the deliberate testkit gap**

Spec §5.3 requires the *absence* of conformance cases to be documented rather than left silent, following A2b's precedent. Add a paragraph to `platform-testkit/src/main/java/dev/continuo/testkit/package-info.java`, in the voice of what is already there:

> **B1 adds no conformance cases, deliberately.** This suite asserts `AdapterRuntime`, which both adapters delegate to. `IBlockView` is implemented by each adapter *directly*, and asserting it needs a live world — the same structural reason adapters have no automated tests at all. `FakeBlockView` in this package is a fixture for headless core tests, **not** a conformance harness, and a green run here says nothing about whether either adapter reports a block truthfully. The cross-adapter dump in `docs/parity/` is the substitute, and it is a manual step.

This is the kind of file the final review is specifically instructed to read (Task 19), because a future session is told to trust it.

- [ ] **Step 4: Verify the javadoc still builds**

Run: `./gradlew :platform-testkit:check --rerun-tasks`
Expected: PASS. `-Xwerror` is live here, so an unresolvable `{@link}` in the new paragraph fails the build — write `IBlockView` as `{@code}` unless you add the import-visible link form.

- [ ] **Step 5: Commit**

```bash
git add docs/smoke-checklist-a1.md docs/smoke-checklist-a2.md platform-testkit/src/main/java/dev/continuo/testkit/package-info.java
git commit -m "docs: add the block-dump step to both checklists and record the testkit gap"
```

---

### Task 17: OWNER TASK — generate the real dumps

**This task is run by a human, not an agent.** It is the only source of evidence that either adapter reports the truth, and no amount of headless testing substitutes for it.

- [ ] **Step 1: Run the 1.21.11 client**

```bash
./gradlew :adapters:adapter-fabric-1.21.11:runClient
```

Build the fixture row, press **J**, copy the output to `docs/parity/blocks-1.21.11.txt`.

- [ ] **Step 2: Run the 1.7.10 client**

```bash
./gradlew :adapters:adapter-forge-1.7.10:runClient
```

Same fixture, same key, output to `docs/parity/blocks-1.7.10.txt`.

An `UnsatisfiedLinkError` mentioning `AL10` is expected on 1.7.10 — the dev client has no sound by design. An `IllegalAccessError` is **not** expected and means the access transformer failed.

- [ ] **Step 3: Create the golden**

Review the two dumps against `docs/parity/fixture-layout.md` and Task 1's audit **by eye**, one index at a time, asking "is this the right answer?" rather than "do these match?". Then copy the reviewed file to `docs/parity/blocks-expected.txt`.

**This review is the golden's entire value.** Copying a dump without reading it produces a file that certifies whatever the code currently does.

- [ ] **Step 4: Run the parity test**

Run: `./gradlew :core:test --tests 'dev.continuo.core.BlockParityTest' --rerun-tasks`
Expected: PASS, 6 tests, **0 skipped**.

If it fails, **do not patch one adapter to match the other.** Read the failure message — it names both native blocks — and understand which side is wrong first.

- [ ] **Step 5: Re-run both full smoke checklists**

B1 changed `IPlatformContext` and both adapters. The A2b precedent applies: the suite cannot see the platform binding, and only a live client shows it is still wired correctly. Record the results in both checklist files as the existing runs are recorded.

- [ ] **Step 6: Commit**

```bash
git add docs/parity docs/smoke-checklist-a1.md docs/smoke-checklist-a2.md
git commit -m "docs(parity): record the cross-adapter block dumps from both real clients"
```

---

### Task 18: The B1 gate, the SPI audit, and the roadmap

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-b1-block-model-design.md` (§6.1, §6.2)
- Modify: `docs/superpowers/specs/2026-08-01-mc-automation-roadmap-design.md`

- [ ] **Step 1: Evaluate the B1 gate**

> *If either adapter cannot produce a faithful `BlockDescription` without judgement logic, or if any field can be answered honestly on only one version, stop and redesign.*

Evaluate **against both adapters as built, not predicted**. Write the finding into spec §6.1: the verdict, the evidence, and — following the M2 gate's precedent — **what it does not cover**. The M2 gate's own "what this finding does not cover" paragraph is the model.

- [ ] **Step 2: Run the standing SPI audit**

Count lines in each adapter that are **logic** rather than **translation**:

```bash
wc -l adapters/*/src/main/java/dev/continuo/adapter/*/*.java
```

Read `FabricBlockView` and `ForgeBlockView` line by line. Any `if` about **block identity** is logic and has leaked out of the core. Conditionals about *null levels* or *bounds* are translation and are fine. Record the count and the verdict in §6.2.

- [ ] **Step 3: Amend the roadmap**

Mark M3 done with the date; record the B1 gate finding next to the M2 one; confirm the world view is bound to M4; and carry forward the three items §6.3 lists as still open — the client-shutdown soft spot, M5 actuation, and `guarded(core::stop)` — stating explicitly that **B1 did not resolve any of them**.

- [ ] **Step 4: Full verification**

Run: `./gradlew clean build --rerun-tasks`
Expected: BUILD SUCCESSFUL, all checks green, no skipped parity tests.

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs(b1): record the gate finding, the SPI audit, and close M3"
```

---

### Task 19: Final whole-branch review

**Not optional, and not a formality.** The final whole-branch review has found Important issues that every per-task review missed, four sub-projects running: M1 found 6 after 7 per-task reviews, the contract 6 after 10, A2a 6 after 8, A2b 4 after 10.

- [ ] **Step 1: Dispatch the review**

Use `superpowers:requesting-code-review` on the **most capable model available**. The dispatch must:

- State **read-only, no file mutation**. This instruction has prevented incidents in every sub-project that used it.
- Instruct the reviewer to **read files the diff does not include** — specifically `docs/superpowers/specs/2026-08-14-b1-block-model-design.md`, `docs/parity/fixture-layout.md`, `docs/smoke-checklist-a1.md`, `docs/smoke-checklist-a2.md`, and `platform/src/main/java/dev/continuo/platform/package-info.java`. Three of A2b's four late findings sat in documents a future session is told to trust.
- State that **adapters have no tests and cannot get any**, so missing adapter tests are not a finding.
- Ask specifically whether any claim in the spec is now false about the tree.

- [ ] **Step 2: Triage findings by the two-kinds rule**

- If the plan made a **deliberate choice** a reviewer dislikes (style, structure) → **surface to the owner**, do not unilaterally change it.
- If the plan's text is **defective against the spec** — a test that does not assert what the spec requires asserted → **rule on it, fix it, and record it**.

Both kinds occurred in A2b and they need different handling.

- [ ] **Step 3: Apply the fixes and re-verify**

Run: `./gradlew clean build --rerun-tasks`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Finish the branch**

Use `superpowers:finishing-a-development-branch`. **Ask the owner before merging** — they have chosen an in-place feature branch four times running to keep the Loom and RFG caches warm, and the choice is theirs each time.

---
