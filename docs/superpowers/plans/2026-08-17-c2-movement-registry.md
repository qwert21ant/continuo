# C2 Movement Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn C1's fixed array of four package-private movements into a published, capability-filtered, `ServiceLoader`-discoverable registry, and prove the seam by adding a parkour jump from a separate Gradle module that cannot compile against the pathfinder.

**Architecture:** A new pure `:core-movement` module publishes `IMovementType`, the registry, and the two classes a movement author needs (`MovementCosts`, `Standability`). `:core-pathfinder` keeps A\* and the four built-ins, which now implement the public interface. A new `:movement-parkour` module depends on `:core-movement` only — enforced by `checkDependencyDirection`, which is what makes the seam a build failure rather than a convention. The heuristic's multiplier stops being a constant and becomes `min(minCostPerAxisStep)` over the active set, which makes admissibility structural instead of a checked numeric coincidence.

**Tech Stack:** Java 8 bytecode (toolchain 21, `options.release = 8`), Gradle Kotlin DSL, JUnit 5, `java.util.ServiceLoader`. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-08-17-c2-movement-registry-design.md` (committed as `6eaf83d`)

## Global Constraints

- **Java 8 bytecode, machine-checked** by `checkCoreBytecode` in `:platform`, `:core`, `:core-pathfinder`, `:runtime`, `:platform-testkit` and both new modules. **No `var`, no records, no `List.of`/`Set.of`, no text blocks, no switch expressions.** Match C1's style: explicit type arguments (`new ArrayList<Pos>()`), not the diamond.
- **Javadoc is build-failing** in every pure module: `-Xdoclint:all,-missing -Xwerror`. A `{@link}` to a type that does not exist yet breaks the build. Introduce types before the javadoc that references them, within the same task.
- **`checkCorePurity`** fails on any reference to `net.minecraft`, `net.fabricmc`, `net.minecraftforge`, `cpw.mods` in bytecode or dependencies. Both new modules are pure.
- **A new module MUST be added to both `settings.gradle.kts` and `allowedProjectDependencies` in the root `build.gradle.kts`**, or `checkDependencyDirection` fails the whole build. C1's plan missed this.
- **Build with `./gradlew build --rerun-tasks`. Never `./gradlew clean`** — clean destroys the 1.7.10 decompiled sources at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`, which are the evidence base for every cost citation in `MovementCosts`.
- **`GRADLE_USER_HOME` is already set to `C:\GradleHome`. Never set, export, or override it.**
- **CI and the remote are off-limits.** `origin` exists; do not push, do not touch `.github/workflows/ci.yml`.
- **C2 adds no SPI type and touches neither adapter.** A non-empty diff against `platform/` or `adapters/` is a defect. Adapters have no tests and cannot get any.
- **If a test and the code disagree, report it with the output you got. Do not adjust either side.** Ten of C1's defects were in the plan, not in the implementers' work, and every one surfaced this way.
- **Append to your task report as you go, never compose it at the end.** Session limits killed three subagents mid-task during C1; recovery was cheap every time a report file already existed.
- **Mutation proof requires the actual failing output pasted into the report**, plus `git diff --stat` afterwards to prove the mutation was reverted. C1 had one left broken on disk.

## Two deliberate refinements to the approved spec

Both are called out here rather than applied silently. If the owner prefers the spec's letter, these are the two places to say so.

1. **`findPath` gains an overload rather than a parameter.** Spec §6.2 says `findPath` takes a `CapabilitySet`. It is called from 20 places in C1's tests. This plan keeps `findPath(world, x, y, z, goal)` as an overload delegating with `CapabilitySet.none()`, and adds `findPath(world, x, y, z, goal, caps)`. This satisfies the spec's intent, avoids 20 mechanical edits, and makes spec §6.3's "C1's results are provably unchanged" directly checkable — the old signature still exists and must still return the same paths.
2. **`CARDINALS` is published as an immutable accessor, not an array constant.** Spec §4.1 says `Move.CARDINALS` moves onto `IMovementType` as a constant. An `int[][]` on an interface is a public mutable global; in a *plugin* API, third-party code could mutate it and silently break every built-in movement's expansion order, which is what C1's determinism rests on. This plan publishes a final `Cardinals` class with `count()`, `dx(i)`, `dz(i)` instead. Same sharing, same order, no mutable global.

---

## File Structure

**New module `:core-movement`** — package `dev.continuo.movement`:

| File | Responsibility |
|---|---|
| `IMovementType.java` | The published movement contract |
| `ExpansionContext.java` | Read-only view of the position being expanded |
| `MutableExpansionContext.java` | The one implementation, reused per search |
| `MoveSink.java` | Receives offered neighbours (relocated, made public) |
| `Cardinals.java` | The four cardinal steps, in the order every movement uses |
| `Capability.java` | What a movement requires |
| `CapabilitySet.java` | Immutable set of granted capabilities |
| `IMovementRegistry.java` | Registration and capability filtering |
| `ActiveMovements.java` | A filtered set bound to its heuristic multiplier |
| `MovementRegistry.java` | The implementation, plus `ServiceLoader` discovery |
| `MovementContract.java` | Executable check of `minCostPerAxisStep()` |
| `MovementCosts.java` | Relocated from `:core-pathfinder` |
| `Standability.java` | Relocated from `:core-pathfinder` |
| `package-info.java` | Module contract, including API stability |

**Modified in `:core-pathfinder`** — `TraverseMove`, `AscendMove`, `DescendMove`, `DiagonalMove` (implement `IMovementType`), `AStarPathfinder` (registry), `Goal`, `GoalBlock`, `GoalXZ` (multiplier parameter), `Move.java` (deleted), `MoveSink.java` (deleted), `MovementCosts.java` + `Standability.java` (moved out), `package-info.java` (links).

**New module `:movement-parkour`** — package `dev.continuo.movement.parkour`: `ParkourMove.java`, `src/main/resources/META-INF/services/dev.continuo.movement.IMovementType`.

---

## Task 1: The `:core-movement` module and its value types

Creates the module and the types that carry no behaviour worth arguing about, so that later tasks have somewhere to land. Ends with a green build and a module that `checkDependencyDirection` accepts.

**Files:**
- Create: `core-movement/build.gradle.kts`
- Create: `core-movement/src/main/java/dev/continuo/movement/Capability.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/CapabilitySet.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/Cardinals.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/package-info.java`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts:10-23`
- Test: `core-movement/src/test/java/dev/continuo/movement/CapabilitySetTest.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/CardinalsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Capability.PARKOUR`; `CapabilitySet.none()`, `CapabilitySet.of(Capability...)`, `CapabilitySet.grants(Set<Capability>)`; `Cardinals.count()`, `Cardinals.dx(int)`, `Cardinals.dz(int)`.

- [ ] **Step 1: Register the module in the build**

In `settings.gradle.kts`, add after `include("core-pathfinder")`:

```kotlin
include("core-movement")
```

In root `build.gradle.kts`, change the `allowedProjectDependencies` map so these three lines read:

```kotlin
    ":core-movement" to setOf(":core"),
    ":core-pathfinder" to setOf(":core", ":core-movement"),
```

(`":core-pathfinder" to setOf(":core")` is the existing line at `build.gradle.kts:13`; replace it.)

Create `core-movement/build.gradle.kts`:

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

- [ ] **Step 2: Run the dependency check to confirm the module is wired**

Run: `./gradlew checkDependencyDirection`
Expected: PASS. (If it fails with "`:core-movement` is not listed", Step 1's map edit did not land.)

- [ ] **Step 3: Write the failing tests for `CapabilitySet` and `Cardinals`**

Create `core-movement/src/test/java/dev/continuo/movement/CapabilitySetTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitySetTest {

    @Test
    void anEmptySetGrantsNothing() {
        assertFalse(CapabilitySet.none().grants(EnumSet.of(Capability.PARKOUR)));
    }

    @Test
    void everySetGrantsAnEmptyRequirement() {
        assertTrue(CapabilitySet.none().grants(EnumSet.noneOf(Capability.class)),
            "a movement that requires nothing must be active for every caller");
    }

    @Test
    void aSetGrantsWhatItContains() {
        assertTrue(CapabilitySet.of(Capability.PARKOUR).grants(EnumSet.of(Capability.PARKOUR)));
    }

    @Test
    void mutatingTheCallersSetCannotChangeWhatWasGranted() {
        EnumSet<Capability> caller = EnumSet.of(Capability.PARKOUR);
        CapabilitySet caps = CapabilitySet.copyOf(caller);
        caller.clear();

        assertTrue(caps.grants(EnumSet.of(Capability.PARKOUR)),
            "CapabilitySet must copy on construction, or a caller can retroactively change "
                + "which movements a registry considered active");
    }

    @Test
    void theExposedSetCannotBeMutated() {
        Set<Capability> exposed = CapabilitySet.of(Capability.PARKOUR).capabilities();

        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                exposed.clear();
            }
        });
    }

    @Test
    void equalSetsAreEqualAndHashAlike() {
        assertEquals(CapabilitySet.of(Capability.PARKOUR), CapabilitySet.of(Capability.PARKOUR));
        assertEquals(CapabilitySet.of(Capability.PARKOUR).hashCode(),
            CapabilitySet.of(Capability.PARKOUR).hashCode());
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                CapabilitySet.copyOf(null);
            }
        });
    }
}
```

Create `core-movement/src/test/java/dev/continuo/movement/CardinalsTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardinalsTest {

    @Test
    void theFourStepsAreNorthEastSouthWestInThatOrder() {
        assertEquals(4, Cardinals.count());

        assertEquals(0, Cardinals.dx(0));
        assertEquals(-1, Cardinals.dz(0));

        assertEquals(1, Cardinals.dx(1));
        assertEquals(0, Cardinals.dz(1));

        assertEquals(0, Cardinals.dx(2));
        assertEquals(1, Cardinals.dz(2));

        assertEquals(-1, Cardinals.dx(3));
        assertEquals(0, Cardinals.dz(3));
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :core-movement:test`
Expected: FAIL — compilation error, `Capability`, `CapabilitySet` and `Cardinals` do not exist.

- [ ] **Step 5: Write `Capability`**

Create `core-movement/src/main/java/dev/continuo/movement/Capability.java`:

```java
package dev.continuo.movement;

/**
 * Something a movement needs before the search may use it.
 *
 * <p><b>One value, because one has a consumer.</b> The source architecture names three sources
 * for the active set — the platform's world capabilities, the player's current capabilities, and
 * the caller's settings. <b>C2 supplies only the third.</b> Neither of the other two exists:
 * there is no {@code IPlayerState} in the codebase, and {@code IPlatformInfo} carries only a
 * version string and a loader, with its own javadoc stating that the version is not for feature
 * detection.
 *
 * <p>So this enum must not be read as evidence that platform negotiation exists. It does not. The
 * first movement that genuinely needs a version-dependent capability — elytra is the architecture
 * doc's own example — is when the SPI addition earns its cost, and it should be batched with the
 * slipperiness and fluid-height work C1 deferred, so that the two untestable adapter modules are
 * edited once rather than twice.
 */
public enum Capability {

    /**
     * The caller permits jumping gaps.
     *
     * <p>A policy bit, not a platform fact: every Minecraft version can jump a one-block gap.
     * It exists so that enabling parkour is a decision the caller makes rather than a silent
     * change to every search.
     */
    PARKOUR
}
```

- [ ] **Step 6: Write `CapabilitySet`**

Create `core-movement/src/main/java/dev/continuo/movement/CapabilitySet.java`:

```java
package dev.continuo.movement;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the caller grants a search.
 *
 * <p>Immutable, and copies its input on construction. A public API that aliased a caller's
 * mutable set would let the caller change which movements a registry considered active after the
 * filtering decision was made; {@code BlockData} copies its tags for the same reason.
 */
public final class CapabilitySet {

    private static final CapabilitySet NONE =
        new CapabilitySet(EnumSet.noneOf(Capability.class));

    private final Set<Capability> capabilities;

    private CapabilitySet(EnumSet<Capability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    /** @return a set granting nothing; every movement with no requirements is still active */
    public static CapabilitySet none() {
        return NONE;
    }

    /**
     * @param capabilities the capabilities to grant; never {@code null}, and no element may be
     *                     {@code null}
     * @return a set granting exactly those
     * @throws IllegalArgumentException if the argument or any element is {@code null}
     */
    public static CapabilitySet of(Capability... capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (int i = 0; i < capabilities.length; i++) {
            if (capabilities[i] == null) {
                throw new IllegalArgumentException("capability " + i + " must not be null");
            }
            set.add(capabilities[i]);
        }
        return new CapabilitySet(set);
    }

    /**
     * @param capabilities the capabilities to grant; never {@code null}, copied
     * @return a set granting exactly those
     * @throws IllegalArgumentException if the argument is {@code null}
     */
    public static CapabilitySet copyOf(Set<Capability> capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        set.addAll(capabilities);
        return new CapabilitySet(set);
    }

    /**
     * @param required what a movement declares it needs; never {@code null}
     * @return whether every required capability is granted. An empty requirement is always
     *         granted, which is what keeps a movement that needs nothing active for every caller
     */
    public boolean grants(Set<Capability> required) {
        if (required == null) {
            throw new IllegalArgumentException("required must not be null");
        }
        return capabilities.containsAll(required);
    }

    /** @return the granted capabilities, unmodifiable; never {@code null} */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CapabilitySet)) {
            return false;
        }
        return capabilities.equals(((CapabilitySet) o).capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities;
    }
}
```

- [ ] **Step 7: Write `Cardinals`**

Create `core-movement/src/main/java/dev/continuo/movement/Cardinals.java`:

```java
package dev.continuo.movement;

/**
 * The four cardinal steps, in the order every movement offers them.
 *
 * <p><b>Load-bearing.</b> A\* breaks cost ties by the order neighbours were discovered, so this
 * order is what makes a path reproducible rather than merely optimal. A test pins a golden path
 * against it.
 *
 * <p>Exposed as accessors rather than as an {@code int[][]} constant on {@link IMovementType}
 * deliberately. An array on a public interface is a mutable global, and this is a plugin API:
 * a movement from another jar could reorder it and silently break the determinism every built-in
 * movement depends on.
 */
public final class Cardinals {

    /** North, east, south, west as {@code {dx, dz}}. */
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private Cardinals() {
    }

    /** @return how many cardinal steps there are */
    public static int count() {
        return STEPS.length;
    }

    /**
     * @param index a step index, from 0 to {@link #count()} minus one
     * @return that step's X offset
     */
    public static int dx(int index) {
        return STEPS[index][0];
    }

    /**
     * @param index a step index, from 0 to {@link #count()} minus one
     * @return that step's Z offset
     */
    public static int dz(int index) {
        return STEPS[index][1];
    }
}
```

- [ ] **Step 8: Write the module's `package-info`**

Create `core-movement/src/main/java/dev/continuo/movement/package-info.java`:

```java
/**
 * The published movement API.
 *
 * <p>A movement is a plugin, not an enum member. This package is what a movement compiles
 * against: the contract, the cost table, the standability predicates, and the registry that
 * filters and orders them. It deliberately does <b>not</b> expose the search — a movement has no
 * business knowing how A* works, and the dependency-direction check enforces that a movement
 * module cannot see {@code :core-pathfinder}.
 *
 * <h2>Stability</h2>
 *
 * <p><b>Not yet stable for out-of-tree authors.</b> Two known additions are coming, and both are
 * additive: M5 adds an executor to {@link dev.continuo.movement.IMovementType} as a default
 * method, and sub-project I widens {@link dev.continuo.movement.ExpansionContext} with node
 * state. Nothing here promises source compatibility before M5.
 *
 * <h2>Purity</h2>
 *
 * <p>Nothing in this module may reference {@code net.minecraft}, and the build fails if it does.
 * The movement API takes no capability from the platform SPI: the active set is decided by the
 * caller, which is what keeps M4 headless. See {@link dev.continuo.movement.Capability}.
 */
package dev.continuo.movement;
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :core-movement:test`
Expected: PASS, 9 tests.

- [ ] **Step 10: Verify the module's own build gates pass**

Run: `./gradlew :core-movement:check`
Expected: PASS, including `checkCorePurity`, `checkCoreBytecode` and `javadoc`.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core-movement
git commit -m "feat(c2): add :core-movement with Capability, CapabilitySet and Cardinals"
```

---

## Task 2: Relocate `MovementCosts` and `Standability`

Moves the two classes a movement author needs into the published module. **No behaviour changes and no signature changes** — `cheapestMove()` survives this task untouched, because deleting it requires the registry, which does not exist yet. Keeping the two apart is what makes this task independently reviewable.

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/MovementCosts.java` (moved)
- Create: `core-movement/src/main/java/dev/continuo/movement/Standability.java` (moved)
- Create: `core-movement/src/test/java/dev/continuo/movement/MovementCostsTest.java` (moved)
- Create: `core-movement/src/test/java/dev/continuo/movement/StandabilityTest.java` (moved)
- Delete: `core-pathfinder/src/main/java/dev/continuo/pathfinder/MovementCosts.java`
- Delete: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Standability.java`
- Delete: `core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementCostsTest.java`
- Delete: `core-pathfinder/src/test/java/dev/continuo/pathfinder/StandabilityTest.java`
- Modify: the ten `:core-pathfinder` files that import them (listed in Step 3)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `dev.continuo.movement.MovementCosts` with a new `JUMP_SURCHARGE` constant; `dev.continuo.movement.Standability` unchanged.

- [ ] **Step 1: Move the four files with git, preserving history**

```bash
git mv core-pathfinder/src/main/java/dev/continuo/pathfinder/MovementCosts.java \
       core-movement/src/main/java/dev/continuo/movement/MovementCosts.java
git mv core-pathfinder/src/main/java/dev/continuo/pathfinder/Standability.java \
       core-movement/src/main/java/dev/continuo/movement/Standability.java
git mv core-pathfinder/src/test/java/dev/continuo/pathfinder/MovementCostsTest.java \
       core-movement/src/test/java/dev/continuo/movement/MovementCostsTest.java
git mv core-pathfinder/src/test/java/dev/continuo/pathfinder/StandabilityTest.java \
       core-movement/src/test/java/dev/continuo/movement/StandabilityTest.java
```

- [ ] **Step 2: Change the package declaration in all four moved files**

In each of the four files, change the first line from `package dev.continuo.pathfinder;` to:

```java
package dev.continuo.movement;
```

`MovementCosts` and `MovementCostsTest` need no other edit. `Standability` needs none either — it already imports everything it uses from `dev.continuo.core`.

- [ ] **Step 3: Add `JUMP_SURCHARGE` to `MovementCosts`**

Parkour needs the jump surcharge that `ASCEND` currently folds in as a literal. Naming it changes no value. In `core-movement/src/main/java/dev/continuo/movement/MovementCosts.java`, replace the `ASCEND` declaration (currently `public static final double ASCEND = TRAVERSE + 2.9946;`) with:

```java
    /**
     * The ticks a jump adds on top of the horizontal crossing.
     *
     * <p>Named rather than folded into {@link #ASCEND} as a literal because more than one
     * movement pays it: any movement that leaves the ground clears a block on the same
     * simulated rise. The derivation and its citations are on {@link #ASCEND}.
     */
    public static final double JUMP_SURCHARGE = 2.9946;

    public static final double ASCEND = TRAVERSE + JUMP_SURCHARGE;
```

Keep `ASCEND`'s existing javadoc block immediately above it — move it down so it still documents `ASCEND`, not `JUMP_SURCHARGE`.

- [ ] **Step 4: Fix `StandabilityTest`'s `Pos` dependency**

`Pos` stays in `:core-pathfinder`, so the moved test cannot use `Pos.pack`. In `core-movement/src/test/java/dev/continuo/movement/StandabilityTest.java`, add a local key helper and use it everywhere `Pos.pack` appeared. Replace the `source` helper and the four `world.put(Pos.pack(...), ...)` call sites so they read:

```java
    /** A position key. Any injective encoding will do; this test only needs map lookups. */
    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    /** A map-backed source. Absent positions are air; nothing here needs a real fixture yet. */
    private static BlockSource source(final Map<String, BlockData> blocks) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(key(x, y, z));
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
```

Change the four `Map<Long, BlockData>` declarations to `Map<String, BlockData>`, the `new HashMap<Long, BlockData>()` calls to `new HashMap<String, BlockData>()`, and each `world.put(Pos.pack(a, b, c), X)` to `world.put(key(a, b, c), X)`.

- [ ] **Step 5: Add the import to every `:core-pathfinder` file that used them**

These ten files reference `MovementCosts` or `Standability` and now need imports. Add `import dev.continuo.movement.MovementCosts;` and/or `import dev.continuo.movement.Standability;` as each requires, in the existing import block, alphabetically after the `dev.continuo.core` imports:

| File | Needs |
|---|---|
| `main/.../TraverseMove.java` | both |
| `main/.../AscendMove.java` | both |
| `main/.../DescendMove.java` | both |
| `main/.../DiagonalMove.java` | both |
| `main/.../Goal.java` | `MovementCosts` (javadoc `{@link}` only — use the fully qualified name in the link instead of an import, since an unused import fails nothing but reads badly) |
| `main/.../GoalBlock.java` | `MovementCosts` |
| `main/.../GoalXZ.java` | `MovementCosts` |
| `main/.../package-info.java` | fully qualify both in `{@link}`s |
| `test/.../AStarPathfinderTest.java` | `MovementCosts` |
| `test/.../GoalTest.java` | `MovementCosts` |
| `test/.../AscendMoveTest.java`, `DescendMoveTest.java`, `DiagonalMoveTest.java`, `TraverseMoveTest.java` | both, as each uses |
| `test/.../PathfinderAcceptanceTest.java` | `MovementCosts` |

In `Goal.java` and `package-info.java`, javadoc `{@link}` targets must resolve or `-Xwerror` fails the build. Use `{@link dev.continuo.movement.MovementCosts#cheapestMove()}` form there.

- [ ] **Step 6: Declare the dependency**

`core-pathfinder/build.gradle.kts` already has `api(project(":core"))`. Add below it:

```kotlin
    api(project(":core-movement"))
```

`api` rather than `implementation` because `:core-pathfinder`'s own public signatures reference these types.

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --rerun-tasks`
Expected: PASS, 301 tests, 0 failures. **The test count must not change** — this task moves tests, it does not add or remove any. A different count means a test file was lost in the move.

- [ ] **Step 8: Verify the relocation changed no behaviour**

Run: `git diff --stat HEAD`
Expected: the four moved files show as renames; no `:core-pathfinder` file shows more than added imports. Any change to a cost value or a predicate in this task is a defect — **report it, do not fix it by adjusting a test.**

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(c2): move MovementCosts and Standability into :core-movement"
```

---

## Task 3: `IMovementType`, `ExpansionContext`, and `MoveSink`

Publishes the contract. Nothing implements it yet, which is deliberate: this task is reviewable purely as an interface design.

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/MoveSink.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/ExpansionContext.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/MutableExpansionContext.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/IMovementType.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/MutableExpansionContextTest.java`

**Interfaces:**
- Consumes: `Capability` (Task 1).
- Produces: `MoveSink.offer(int, int, int, double)`; `ExpansionContext.world()/x()/y()/z()`; `MutableExpansionContext(BlockSource)` with `moveTo(int, int, int)`; `IMovementType.id()`, `requires()`, `minCostPerAxisStep()`, `expand(ExpansionContext, MoveSink)`.

- [ ] **Step 1: Write the failing test**

Create `core-movement/src/test/java/dev/continuo/movement/MutableExpansionContextTest.java`:

```java
package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MutableExpansionContextTest {

    private static final BlockSource WORLD = new BlockSource() {
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
            return 256;
        }
    };

    @Test
    void itReportsThePositionItWasMovedTo() {
        MutableExpansionContext ctx = new MutableExpansionContext(WORLD);
        ctx.moveTo(3, 64, -7);

        assertSame(WORLD, ctx.world());
        assertEquals(3, ctx.x());
        assertEquals(64, ctx.y());
        assertEquals(-7, ctx.z());
    }

    @Test
    void oneInstanceIsReusedAcrossPositions() {
        MutableExpansionContext ctx = new MutableExpansionContext(WORLD);
        ctx.moveTo(0, 0, 0);
        ctx.moveTo(1, 2, 3);

        assertEquals(1, ctx.x());
        assertEquals(2, ctx.y());
        assertEquals(3, ctx.z());
    }

    @Test
    void aNullWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new MutableExpansionContext(null);
            }
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MutableExpansionContextTest'`
Expected: FAIL — compilation error, `MutableExpansionContext` does not exist.

- [ ] **Step 3: Write `MoveSink`**

Create `core-movement/src/main/java/dev/continuo/movement/MoveSink.java`:

```java
package dev.continuo.movement;

/**
 * Receives the neighbours a movement generates.
 *
 * <p>A sink rather than a returned collection, which buys two things: no collection is allocated
 * per expansion, and a movement can offer a neighbour the moment it finds one instead of building
 * a list to hand back.
 */
public interface MoveSink {

    /**
     * @param x the neighbour's X
     * @param y the neighbour's Y
     * @param z the neighbour's Z
     * @param cost the cost of getting there from the position being expanded, in ticks; must be
     *             positive, and must respect the movement's declared
     *             {@link IMovementType#minCostPerAxisStep()}
     */
    void offer(int x, int y, int z, double cost);
}
```

- [ ] **Step 4: Write `ExpansionContext`**

Create `core-movement/src/main/java/dev/continuo/movement/ExpansionContext.java`:

```java
package dev.continuo.movement;

import dev.continuo.core.BlockSource;

/**
 * The position a movement is being asked to expand from, and the world to read.
 *
 * <p><b>MUST NOT be retained past the {@link IMovementType#expand} call that received it.</b>
 * The search passes one instance per search and moves it between positions, so a movement that
 * stashes it will later read coordinates belonging to a different node. This is a documented
 * contract rather than an enforced one — like the SPI's rule 1, there is no assertion to write
 * against a caller's own misuse.
 *
 * <p>An interface rather than four parameters because this is where node state arrives at
 * sub-project I. Widening a context is additive; changing every published movement's signature
 * is not.
 */
public interface ExpansionContext {

    /** @return the world to read; never {@code null} */
    BlockSource world();

    /** @return the X of the position being expanded */
    int x();

    /** @return the Y of the position being expanded */
    int y();

    /** @return the Z of the position being expanded */
    int z();
}
```

- [ ] **Step 5: Write `MutableExpansionContext`**

Create `core-movement/src/main/java/dev/continuo/movement/MutableExpansionContext.java`:

```java
package dev.continuo.movement;

import dev.continuo.core.BlockSource;

/**
 * The one {@link ExpansionContext} implementation: created once per search and moved between
 * positions.
 *
 * <p>One allocation per search rather than one per expansion. It is public because more than one
 * module needs to build one — the search, and {@link MovementContract} when it audits a
 * movement — not because callers of a movement are expected to implement the interface
 * themselves.
 */
public final class MutableExpansionContext implements ExpansionContext {

    private final BlockSource world;
    private int x;
    private int y;
    private int z;

    /**
     * @param world the world to read; never {@code null}
     * @throws IllegalArgumentException if {@code world} is {@code null}
     */
    public MutableExpansionContext(BlockSource world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        this.world = world;
    }

    /**
     * Points this context at another position.
     *
     * @param x the X to expand from
     * @param y the Y to expand from
     * @param z the Z to expand from
     */
    public void moveTo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public BlockSource world() {
        return world;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }
}
```

- [ ] **Step 6: Write `IMovementType`**

Create `core-movement/src/main/java/dev/continuo/movement/IMovementType.java`:

```java
package dev.continuo.movement;

import java.util.Set;

/**
 * One kind of movement: what it needs, what it costs, and which neighbours it reaches.
 *
 * <p>A movement is a plugin. It may live in any module or jar that depends on this one, and
 * {@link MovementRegistry#discover()} finds it through {@link java.util.ServiceLoader}. A
 * discovered implementation therefore needs a <b>public no-argument constructor</b>; without one
 * the failure surfaces at runtime in a consumer's build rather than at compile time here.
 *
 * <p><b>Neighbours must be offered in a fixed order.</b> The search breaks cost ties by the order
 * neighbours were discovered, so expansion order is what makes a path reproducible rather than
 * merely optimal. Use {@link Cardinals} for cardinal movements and the search's determinism
 * guarantee carries over unchanged.
 */
public interface IMovementType {

    /**
     * A stable identifier, dotted and lower case — {@code "walk.traverse"},
     * {@code "mod.jetpack.fly"}.
     *
     * <p>Not decoration. It is the key a registry rejects duplicate registrations on, and the
     * key discovery sorts by so that classpath order cannot leak into a search.
     *
     * @return the identifier; never {@code null} or empty, and constant for the instance's life
     */
    String id();

    /**
     * What the caller must grant before this movement is used.
     *
     * @return the required capabilities; never {@code null}, empty for a movement that is always
     *         available
     */
    Set<Capability> requires();

    /**
     * A lower bound on what one <em>axis step</em> of this movement costs.
     *
     * <p><b>Get this wrong and the search silently stops returning shortest paths.</b> The
     * heuristic is a Chebyshev distance times the smallest value any active movement declares
     * here, so one movement can shrink the heuristic by this value times the number of blocks it
     * travels along its longest axis. Declaring too high a figure makes the heuristic
     * overestimate, which costs admissibility with no test failing anywhere else.
     *
     * <p>Concretely, the contract is: <b>the smallest
     * {@code cost / max(|dx|, |dy|, |dz|)} of any neighbour this movement can ever offer.</b>
     * For a movement that travels one block along each axis this is simply its cheapest cost. For
     * one that spans further — a fall of three blocks, a jump across two — divide.
     *
     * <p>It is a declaration, so it is checked rather than trusted:
     * {@link MovementContract#violations(IMovementType)} audits it against real expansions.
     *
     * @return the lower bound, in ticks; must be positive
     */
    double minCostPerAxisStep();

    /**
     * Offers every neighbour reachable from the context's position.
     *
     * @param ctx where to expand from; never {@code null}, and MUST NOT be retained past this
     *            call
     * @param sink receives each reachable neighbour, in a fixed order; never {@code null}
     */
    void expand(ExpansionContext ctx, MoveSink sink);
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core-movement:test`
Expected: PASS, 12 tests.

- [ ] **Step 8: Commit**

```bash
git add core-movement
git commit -m "feat(c2): publish IMovementType, ExpansionContext and MoveSink"
```

---

## Task 4: The registry, and the derived multiplier

The heart of C2. `ActiveMovements` binds a filtered set to its multiplier so the two cannot drift apart.

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/ActiveMovements.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/IMovementRegistry.java`
- Create: `core-movement/src/main/java/dev/continuo/movement/MovementRegistry.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/MovementRegistryTest.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/FakeMovement.java`

**Interfaces:**
- Consumes: `IMovementType`, `Capability`, `CapabilitySet` (Tasks 1, 3).
- Produces: `IMovementRegistry.register(IMovementType)`, `IMovementRegistry.activeFor(CapabilitySet)`; `ActiveMovements.movements()`, `ActiveMovements.cheapestAxisStep()`; `MovementRegistry()` no-arg constructor.

- [ ] **Step 1: Write the test double**

Create `core-movement/src/test/java/dev/continuo/movement/FakeMovement.java`:

```java
package dev.continuo.movement;

import java.util.EnumSet;
import java.util.Set;

/** A movement with declared numbers and a scripted single offer, for registry tests. */
final class FakeMovement implements IMovementType {

    private final String id;
    private final Set<Capability> requires;
    private final double minCostPerAxisStep;
    private final int spanX;
    private final double cost;

    FakeMovement(String id, double minCostPerAxisStep, Capability... requires) {
        this(id, minCostPerAxisStep, 1, minCostPerAxisStep, requires);
    }

    FakeMovement(String id, double minCostPerAxisStep, int spanX, double cost,
                 Capability... requires) {
        this.id = id;
        this.minCostPerAxisStep = minCostPerAxisStep;
        this.spanX = spanX;
        this.cost = cost;
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (int i = 0; i < requires.length; i++) {
            set.add(requires[i]);
        }
        this.requires = set;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return requires;
    }

    @Override
    public double minCostPerAxisStep() {
        return minCostPerAxisStep;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + spanX, ctx.y(), ctx.z(), cost);
    }
}
```

- [ ] **Step 2: Write the failing registry tests**

Create `core-movement/src/test/java/dev/continuo/movement/MovementRegistryTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementRegistryTest {

    private static List<String> idsOf(ActiveMovements active) {
        List<String> ids = new java.util.ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        return ids;
    }

    @Test
    void aMovementRequiringNothingIsActiveForACallerGrantingNothing() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));

        assertEquals(Arrays.asList("a.free"), idsOf(registry.activeFor(CapabilitySet.none())));
    }

    @Test
    void aMovementIsInactiveWhenItsCapabilityIsNotGranted() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));
        registry.register(new FakeMovement("b.gated", 9.0, Capability.PARKOUR));

        assertEquals(Arrays.asList("a.free"), idsOf(registry.activeFor(CapabilitySet.none())),
            "a movement whose requirement is not granted must be filtered out entirely");
        assertEquals(Arrays.asList("a.free", "b.gated"),
            idsOf(registry.activeFor(CapabilitySet.of(Capability.PARKOUR))));
    }

    @Test
    void iterationOrderIsRegistrationOrderNotIdOrder() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("z.last", 3.0));
        registry.register(new FakeMovement("a.first", 4.0));

        assertEquals(Arrays.asList("z.last", "a.first"),
            idsOf(registry.activeFor(CapabilitySet.none())),
            "A* breaks ties by discovery order, so registration order is load-bearing and must "
                + "not be re-sorted");
    }

    @Test
    void theMultiplierIsTheSmallestDeclaredCostPerAxisStep() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.cheap", 3.5636));
        registry.register(new FakeMovement("b.dear", 6.5582));

        assertEquals(3.5636, registry.activeFor(CapabilitySet.none()).cheapestAxisStep(), 1.0e-9);
    }

    @Test
    void aWideCheapMovementLowersTheMultiplierForEveryone() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.walk", 3.5636));
        registry.register(new FakeMovement("b.glide", 1.2, 4, 4.8, Capability.PARKOUR));

        assertEquals(3.5636, registry.activeFor(CapabilitySet.none()).cheapestAxisStep(), 1.0e-9,
            "while it is filtered out it must not affect the multiplier");
        assertEquals(1.2,
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).cheapestAxisStep(), 1.0e-9,
            "once active, the cheapest axis step is its axis step, or the heuristic "
                + "overestimates and A* stops returning shortest paths");
    }

    @Test
    void aDuplicateIdIsRejected() {
        final MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.dup", 3.0));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    registry.register(new FakeMovement("a.dup", 4.0));
                }
            });
        assertEquals(true, thrown.getMessage().contains("a.dup"));
    }

    @Test
    void anEmptyActiveSetIsRejectedRatherThanGivenAnUndefinedMultiplier() {
        final MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.gated", 3.0, Capability.PARKOUR));

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                registry.activeFor(CapabilitySet.none());
            }
        });
    }

    @Test
    void theActiveListCannotBeMutated() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("a.free", 3.0));
        final List<IMovementType> movements = registry.activeFor(CapabilitySet.none()).movements();

        assertThrows(UnsupportedOperationException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    movements.clear();
                }
            });
    }

    @Test
    void aNonPositiveDeclaredCostIsRejectedAtRegistration() {
        final MovementRegistry registry = new MovementRegistry();

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                registry.register(new FakeMovement("a.free", 0.0));
            }
        });
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementRegistryTest'`
Expected: FAIL — compilation error, `MovementRegistry` and `ActiveMovements` do not exist.

- [ ] **Step 4: Write `ActiveMovements`**

Create `core-movement/src/main/java/dev/continuo/movement/ActiveMovements.java`:

```java
package dev.continuo.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The movements a search may use, and the heuristic multiplier they imply.
 *
 * <p><b>The two travel together on purpose.</b> The failure this type exists to prevent is a
 * movement set and a multiplier drifting apart — a search filtering by one set while scaling its
 * heuristic by another set's minimum silently stops returning shortest paths. A type that cannot
 * hand out one without the other makes that unrepresentable.
 *
 * <p>Immutable. The multiplier is computed once, at construction.
 */
public final class ActiveMovements {

    private final List<IMovementType> movements;
    private final double cheapestAxisStep;

    /**
     * @param movements the active movements, in the order the search must expand them; copied
     * @throws IllegalStateException if empty — a search with no movements has no multiplier, and
     *                               returning an arbitrary one would hide the mistake
     */
    ActiveMovements(List<IMovementType> movements) {
        if (movements.isEmpty()) {
            throw new IllegalStateException(
                "no movement is active for these capabilities; a search with no movements has no "
                    + "cheapest axis step and could not be admissible");
        }
        this.movements = Collections.unmodifiableList(new ArrayList<IMovementType>(movements));

        double cheapest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.movements.size(); i++) {
            double declared = this.movements.get(i).minCostPerAxisStep();
            if (declared < cheapest) {
                cheapest = declared;
            }
        }
        this.cheapestAxisStep = cheapest;
    }

    /** @return the active movements in expansion order, unmodifiable; never empty */
    public List<IMovementType> movements() {
        return movements;
    }

    /**
     * The heuristic's multiplier: the smallest cost any active movement can charge for one axis
     * step.
     *
     * <p><b>This is what makes A* admissible, and it is now structural rather than numeric.</b>
     * Because it is a minimum over exactly the movements the search will use, every movement
     * satisfies {@code cost >= axisSpan * cheapestAxisStep} by definition. C1 could only assert
     * that as a checked property of a closed cost table; adding a cheap wide movement used to
     * break admissibility silently and now merely loosens the heuristic.
     *
     * @return the multiplier, in ticks per axis step; always positive
     */
    public double cheapestAxisStep() {
        return cheapestAxisStep;
    }
}
```

- [ ] **Step 5: Write `IMovementRegistry`**

Create `core-movement/src/main/java/dev/continuo/movement/IMovementRegistry.java`:

```java
package dev.continuo.movement;

/**
 * Holds the known movements and decides which of them a search may use.
 *
 * <p>Replaces the fixed array C1's search iterated. Adding a movement is a registration, or a
 * jar on the classpath, rather than an edit to the search.
 */
public interface IMovementRegistry {

    /**
     * @param type the movement to add; never {@code null}
     * @throws IllegalArgumentException if {@code type} is {@code null}, its {@link
     *         IMovementType#id()} is null, empty or already registered, or its
     *         {@link IMovementType#minCostPerAxisStep()} is not positive
     */
    void register(IMovementType type);

    /**
     * @param caps what the caller grants; never {@code null}
     * @return the movements whose requirements are met, in registration order, bound to the
     *         heuristic multiplier they imply; never {@code null}
     * @throws IllegalStateException if no movement is active
     */
    ActiveMovements activeFor(CapabilitySet caps);
}
```

- [ ] **Step 6: Write `MovementRegistry` (discovery comes in Task 5)**

Create `core-movement/src/main/java/dev/continuo/movement/MovementRegistry.java`:

```java
package dev.continuo.movement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The standard registry: insertion-ordered, duplicate-rejecting, capability-filtering.
 *
 * <p>Empty when constructed. Nothing is discovered implicitly — see
 * {@link #discover()} — so a caller always knows exactly which movements a search can use.
 */
public final class MovementRegistry implements IMovementRegistry {

    private final List<IMovementType> registered = new ArrayList<IMovementType>();
    private final Set<String> ids = new HashSet<String>();

    @Override
    public void register(IMovementType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        String id = type.id();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(
                "a movement must have a non-empty id; " + type.getClass().getName() + " has none");
        }
        if (!ids.add(id)) {
            throw new IllegalArgumentException("a movement with id " + id + " is already"
                + " registered; two movements answering to one id would make the registry's"
                + " deduplication and its discovery order both undefined");
        }
        double declared = type.minCostPerAxisStep();
        if (!(declared > 0.0)) {
            throw new IllegalArgumentException("movement " + id + " declares a"
                + " minCostPerAxisStep of " + declared + "; it must be positive, or it would drag"
                + " the heuristic's multiplier to zero and turn A* into an exhaustive search");
        }
        registered.add(type);
    }

    @Override
    public ActiveMovements activeFor(CapabilitySet caps) {
        if (caps == null) {
            throw new IllegalArgumentException("caps must not be null; use CapabilitySet.none()");
        }
        List<IMovementType> active = new ArrayList<IMovementType>(registered.size());
        for (int i = 0; i < registered.size(); i++) {
            IMovementType type = registered.get(i);
            if (caps.grants(type.requires())) {
                active.add(type);
            }
        }
        return new ActiveMovements(active);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementRegistryTest'`
Expected: PASS, 9 tests.

- [ ] **Step 8: Mutation-prove the capability filter**

Temporarily change `activeFor` so the filter is ignored — replace `if (caps.grants(type.requires())) {` with `if (true) {`.

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementRegistryTest'`
Expected: FAIL on `aMovementIsInactiveWhenItsCapabilityIsNotGranted`, `aWideCheapMovementLowersTheMultiplierForEveryone` and `anEmptyActiveSetIsRejectedRatherThanGivenAnUndefinedMultiplier`. **Paste the actual failure output into your report.** Then revert the mutation.

- [ ] **Step 9: Mutation-prove the multiplier is a minimum, not a first or last**

Temporarily change `ActiveMovements`'s loop to take the *largest* declared value: replace `if (declared < cheapest)` with `if (declared > cheapest)` and initialise `cheapest` to `Double.NEGATIVE_INFINITY`.

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementRegistryTest'`
Expected: FAIL on `theMultiplierIsTheSmallestDeclaredCostPerAxisStep` and `aWideCheapMovementLowersTheMultiplierForEveryone`. **Paste the output.** Then revert.

- [ ] **Step 10: Verify no mutation is left on disk**

Run: `git diff --stat`
Expected: only the files this task creates appear; `ActiveMovements.java` and `MovementRegistry.java` match what Steps 4 and 6 specify.

- [ ] **Step 11: Commit**

```bash
git add core-movement
git commit -m "feat(c2): add the movement registry and derive the heuristic multiplier"
```

---

## Task 5: `ServiceLoader` discovery

**Files:**
- Modify: `core-movement/src/main/java/dev/continuo/movement/MovementRegistry.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/MovementDiscoveryTest.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/DiscoverableMovement.java`
- Test: `core-movement/src/test/resources/META-INF/services/dev.continuo.movement.IMovementType`

**Interfaces:**
- Consumes: `MovementRegistry` (Task 4).
- Produces: `MovementRegistry.discover()`; package-private `MovementRegistry.registerAllSorted(Iterable<IMovementType>)`.

- [ ] **Step 1: Write the discoverable test movement and its service file**

Create `core-movement/src/test/java/dev/continuo/movement/DiscoverableMovement.java`:

```java
package dev.continuo.movement;

/**
 * A movement discovered through the test source set's own {@code META-INF/services} file, so
 * that discovery is exercised end to end rather than simulated.
 */
public final class DiscoverableMovement implements IMovementType {

    /** Public and no-argument, which is what {@link java.util.ServiceLoader} requires. */
    public DiscoverableMovement() {
    }

    @Override
    public String id() {
        return "test.discovered";
    }

    @Override
    public java.util.Set<Capability> requires() {
        return java.util.EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return 7.0;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        sink.offer(ctx.x() + 1, ctx.y(), ctx.z(), 7.0);
    }
}
```

Create `core-movement/src/test/resources/META-INF/services/dev.continuo.movement.IMovementType` containing exactly one line:

```
dev.continuo.movement.DiscoverableMovement
```

- [ ] **Step 2: Write the failing tests**

Create `core-movement/src/test/java/dev/continuo/movement/MovementDiscoveryTest.java`:

```java
package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementDiscoveryTest {

    private static List<String> idsOf(ActiveMovements active) {
        List<String> ids = new ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        return ids;
    }

    @Test
    void aMovementOnTheClasspathIsFound() {
        MovementRegistry registry = new MovementRegistry();
        registry.discover();

        assertTrue(idsOf(registry.activeFor(CapabilitySet.none())).contains("test.discovered"),
            "the META-INF/services entry in this module's test resources must be picked up");
    }

    @Test
    void aFreshRegistryDiscoversNothingUntilAsked() {
        MovementRegistry registry = new MovementRegistry();

        assertEquals(Arrays.asList("a.only"), idsOf(
            registryWith(registry, new FakeMovement("a.only", 3.0))
                .activeFor(CapabilitySet.none())),
            "discovery must never be implicit, or a caller cannot know what a search will use");
    }

    @Test
    void discoveredMovementsAreSortedByIdWhateverOrderTheLoaderYieldsThem() {
        MovementRegistry forward = new MovementRegistry();
        forward.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("a.first", 3.0),
            new FakeMovement("m.middle", 4.0),
            new FakeMovement("z.last", 5.0)));

        MovementRegistry reversed = new MovementRegistry();
        reversed.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("z.last", 5.0),
            new FakeMovement("m.middle", 4.0),
            new FakeMovement("a.first", 3.0)));

        List<String> expected = Arrays.asList("a.first", "m.middle", "z.last");
        assertEquals(expected, idsOf(forward.activeFor(CapabilitySet.none())));
        assertEquals(expected, idsOf(reversed.activeFor(CapabilitySet.none())),
            "ServiceLoader's iteration order is unspecified and follows classpath order, so "
                + "without a sort the search's tie-breaking would vary by environment while "
                + "every test stayed green");
    }

    @Test
    void discoveryAppendsAfterWhatWasRegisteredExplicitly() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("z.explicit", 3.0));
        registry.registerAllSorted(Arrays.<IMovementType>asList(
            new FakeMovement("a.discovered", 4.0)));

        assertEquals(Arrays.asList("z.explicit", "a.discovered"),
            idsOf(registry.activeFor(CapabilitySet.none())),
            "built-ins keep C1's expansion order; discovered movements land after them");
    }

    private static MovementRegistry registryWith(MovementRegistry registry, IMovementType type) {
        registry.register(type);
        return registry;
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementDiscoveryTest'`
Expected: FAIL — compilation error, `discover()` and `registerAllSorted(...)` do not exist.

- [ ] **Step 4: Add discovery to `MovementRegistry`**

Add these imports to `MovementRegistry.java`:

```java
import java.util.Collections;
import java.util.Comparator;
import java.util.ServiceLoader;
```

Add these two methods after `register`:

```java
    /**
     * Finds every {@link IMovementType} on the classpath and registers it.
     *
     * <p><b>Sorted by {@link IMovementType#id()} before registering, and that is not
     * cosmetic.</b> {@link ServiceLoader}'s iteration order is unspecified — in practice it
     * follows classpath order, which varies by environment. The search breaks cost ties by the
     * order neighbours were discovered, so feeding an unspecified order into it would make paths
     * depend on the classpath while every test stayed green wherever it happened to run.
     *
     * <p>Additive: whatever was registered explicitly keeps its position, and discovered
     * movements land after it. That is what lets the built-in movements keep C1's expansion
     * order exactly.
     *
     * @throws IllegalArgumentException if a discovered movement duplicates a registered id, or
     *         declares a non-positive {@link IMovementType#minCostPerAxisStep()}
     */
    public void discover() {
        List<IMovementType> found = new ArrayList<IMovementType>();
        for (IMovementType type : ServiceLoader.load(IMovementType.class)) {
            found.add(type);
        }
        registerAllSorted(found);
    }

    /**
     * Registers a batch in {@code id} order. The seam {@link #discover()} is tested through: a
     * test can hand it a deliberately reversed batch, which no real {@link ServiceLoader} can be
     * made to produce on demand.
     *
     * @param found the movements to register; never {@code null}
     */
    void registerAllSorted(Iterable<IMovementType> found) {
        List<IMovementType> sorted = new ArrayList<IMovementType>();
        for (IMovementType type : found) {
            sorted.add(type);
        }
        Collections.sort(sorted, new Comparator<IMovementType>() {
            @Override
            public int compare(IMovementType left, IMovementType right) {
                return left.id().compareTo(right.id());
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            register(sorted.get(i));
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core-movement:test`
Expected: PASS, 25 tests.

- [ ] **Step 6: Mutation-prove the sort**

Temporarily delete the `Collections.sort(...)` call from `registerAllSorted`.

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementDiscoveryTest'`
Expected: FAIL on `discoveredMovementsAreSortedByIdWhateverOrderTheLoaderYieldsThem`, with the reversed registry returning `[z.last, m.middle, a.first]`. **Paste the output.** Then revert.

- [ ] **Step 7: Verify no mutation is left on disk**

Run: `git diff --stat`
Expected: `MovementRegistry.java` shows only the Step 4 additions.

- [ ] **Step 8: Commit**

```bash
git add core-movement
git commit -m "feat(c2): discover movements by ServiceLoader, sorted by id"
```

---

## Task 6: `MovementContract` — the declaration made checkable

**Files:**
- Create: `core-movement/src/main/java/dev/continuo/movement/MovementContract.java`
- Test: `core-movement/src/test/java/dev/continuo/movement/MovementContractTest.java`

**Interfaces:**
- Consumes: `IMovementType`, `MutableExpansionContext`, `MoveSink` (Tasks 3, 4).
- Produces: `MovementContract.violations(IMovementType)` returning `List<String>`.

- [ ] **Step 1: Write the failing tests**

Create `core-movement/src/test/java/dev/continuo/movement/MovementContractTest.java`:

```java
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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementContractTest'`
Expected: FAIL — compilation error, `MovementContract` does not exist.

- [ ] **Step 3: Write `MovementContract`**

Create `core-movement/src/main/java/dev/continuo/movement/MovementContract.java`:

```java
package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Audits a movement's {@link IMovementType#minCostPerAxisStep()} against what it actually offers.
 *
 * <p>That figure is a declaration, and a wrong one is silently fatal: it scales the search's
 * heuristic, so declaring too high a value costs admissibility with no other test failing. This
 * turns the declaration into a checked claim, for every movement rather than only the built-in
 * ones.
 *
 * <p>Returns violations rather than asserting them, which keeps JUnit off a production compile
 * classpath while letting any module's tests call it. A movement module runs this over its own
 * movement; {@code :core-pathfinder} runs it over the four built-ins.
 */
public final class MovementContract {

    /** Enough worlds to hit each movement's preconditions from several directions. */
    private static final int WORLDS = 200;

    /** A cube big enough for a movement of any plausible span to have room to offer. */
    private static final int EXTENT = 6;

    private MovementContract() {
    }

    /**
     * @param type the movement to audit; never {@code null}
     * @return a single message describing the first neighbour whose cost falls below the declared
     *         figure, or an empty list when the declaration holds everywhere this could check.
     *         <b>One counterexample, not all of them</b> — the same declaration is wrong at every
     *         position a movement can offer from, so collecting them all would bury the one that
     *         matters under hundreds of copies, and the fix is identical either way
     */
    public static List<String> violations(IMovementType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        final double declared = type.minCostPerAxisStep();
        final List<String> violations = new ArrayList<String>();

        for (int seed = 0; seed < WORLDS && violations.isEmpty(); seed++) {
            final BlockSource world = randomWorld(seed);
            MutableExpansionContext ctx = new MutableExpansionContext(world);

            for (int y = -EXTENT + 2; y <= EXTENT - 2 && violations.isEmpty(); y++) {
                ctx.moveTo(0, y, 0);
                final int originY = y;
                type.expand(ctx, new MoveSink() {
                    @Override
                    public void offer(int nx, int ny, int nz, double cost) {
                        if (!violations.isEmpty()) {
                            return;
                        }
                        // The context always sits at x = 0, z = 0, so nx and nz are already
                        // offsets from the origin; only Y needs subtracting.
                        int span = Math.max(Math.abs(nx),
                            Math.max(Math.abs(ny - originY), Math.abs(nz)));
                        if (span == 0) {
                            violations.add(type.id() + " offered its own position, which is not"
                                + " a move");
                            return;
                        }
                        double perStep = cost / span;
                        if (perStep < declared - 1.0e-9) {
                            violations.add(type.id() + " declares minCostPerAxisStep " + declared
                                + " but offered (" + nx + ", " + ny + ", " + nz + ") from (0, "
                                + originY + ", 0) for " + cost + " across " + span
                                + " axis steps, which is " + perStep + " per step; the heuristic"
                                + " would overestimate and A* would stop returning shortest"
                                + " paths");
                        }
                    }
                });
            }
        }
        return violations;
    }

    /**
     * A cube of randomly chosen blocks, seeded so a violation is always reproducible.
     *
     * <p>Random rather than hand-written text art because this audit wants breadth, not
     * legibility: it has to reach whatever combination of preconditions lets a movement offer its
     * cheapest edge. C1's Dijkstra oracle established the same approach over 400 seeded worlds.
     */
    private static BlockSource randomWorld(int seed) {
        final Random random = new Random(seed);
        final BlockData[] palette = {
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.SLAB_TOP, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.THIN_LAYER, 0.0625, Fluid.NONE,
                EnumSet.noneOf(BlockTag.class))
        };
        final int side = 2 * EXTENT + 1;
        final BlockData[] cells = new BlockData[side * side * side];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = palette[random.nextInt(palette.length)];
        }
        final BlockData air = palette[0];

        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                if (x < -EXTENT || x > EXTENT || y < -EXTENT || y > EXTENT
                    || z < -EXTENT || z > EXTENT) {
                    return air;
                }
                int index = ((x + EXTENT) * side + (y + EXTENT)) * side + (z + EXTENT);
                return cells[index];
            }

            @Override
            public int minY() {
                return -EXTENT;
            }

            @Override
            public int maxY() {
                return EXTENT;
            }
        };
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementContractTest'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Mutation-prove the audit can fail**

Temporarily change the comparison in `violations` from `if (perStep < declared - 1.0e-9)` to `if (false)`.

Run: `./gradlew :core-movement:test --tests 'dev.continuo.movement.MovementContractTest'`
Expected: FAIL on `aWideMovementDeclaringItsWholeCostRatherThanItsPerStepCostIsCaught` and `theViolationNamesTheOfferThatBrokeTheDeclaration`. **Paste the output.** Then revert.

- [ ] **Step 6: Verify no mutation is left, then commit**

Run: `git diff --stat` — `MovementContract.java` must match Step 3.

```bash
git add core-movement
git commit -m "feat(c2): make minCostPerAxisStep a checked claim, not a declaration"
```

---

## Task 7: Convert the four built-in movements to `IMovementType`

Changes `expand`'s signature, so A\* must adapt in the same task. A\* still iterates a fixed array here — the registry arrives in Task 8. Keeping those apart is what makes each independently reviewable.

**Files:**
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/TraverseMove.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AscendMove.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/DescendMove.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/DiagonalMove.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java:36-38,111-139`
- Delete: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Move.java`
- Delete: `core-pathfinder/src/main/java/dev/continuo/pathfinder/MoveSink.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/RecordingSink.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/{Traverse,Ascend,Descend,Diagonal}MoveTest.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/BuiltInMovementContractTest.java`

**Interfaces:**
- Consumes: `IMovementType`, `ExpansionContext`, `MutableExpansionContext`, `MoveSink`, `Cardinals`, `MovementContract` (Tasks 1, 3, 6).
- Produces: the four movements as `IMovementType` with ids `walk.traverse`, `walk.ascend`, `walk.descend`, `walk.diagonal`.

- [ ] **Step 1: Write the failing contract test for the built-ins**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/BuiltInMovementContractTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltInMovementContractTest {

    @Test
    void everyBuiltInMovementDeclaresItsCostPerAxisStepHonestly() {
        IMovementType[] movements = {
            new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
        };

        for (int i = 0; i < movements.length; i++) {
            List<String> violations = MovementContract.violations(movements[i]);
            assertEquals(java.util.Collections.<String>emptyList(), violations,
                movements[i].id() + " violated its own declaration");
        }
    }

    @Test
    void descendDeclaresItsWorstRatioNotItsCheapestCost() {
        assertEquals(
            (dev.continuo.movement.MovementCosts.TRAVERSE
                + dev.continuo.movement.MovementCosts.fallTicks(
                    dev.continuo.movement.MovementCosts.MAX_SAFE_FALL))
                / dev.continuo.movement.MovementCosts.MAX_SAFE_FALL,
            new DescendMove().minCostPerAxisStep(), 1.0e-9,
            "a one-block descend is cheaper in absolute terms but spans one axis step; the "
                + "binding ratio is the deepest fall, and declaring the cheap one would push the "
                + "heuristic's multiplier up and cost admissibility");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.BuiltInMovementContractTest'`
Expected: FAIL — compilation error, the movements do not implement `IMovementType` and have no `id()`.

- [ ] **Step 3: Rewrite `TraverseMove`**

Replace `core-pathfinder/src/main/java/dev/continuo/pathfinder/TraverseMove.java` entirely:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.Capability;
import dev.continuo.movement.Cardinals;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

import java.util.EnumSet;
import java.util.Set;

/** Walking one block to a cardinal neighbour at the same height. */
final class TraverseMove implements IMovementType {

    @Override
    public String id() {
        return "walk.traverse";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.TRAVERSE;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int y = ctx.y();
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = ctx.x() + Cardinals.dx(i);
            int nz = ctx.z() + Cardinals.dz(i);
            if (Standability.standable(ctx.world(), nx, y, nz)) {
                sink.offer(nx, y, nz, MovementCosts.TRAVERSE);
            }
        }
    }
}
```

- [ ] **Step 4: Rewrite `AscendMove`**

Keep its existing class javadoc verbatim; replace the body:

```java
final class AscendMove implements IMovementType {

    @Override
    public String id() {
        return "walk.ascend";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.ASCEND;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        if (!Standability.passable(ctx.world().at(x, y + 2, z))) {
            return;
        }
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = x + Cardinals.dx(i);
            int nz = z + Cardinals.dz(i);
            if (Standability.standable(ctx.world(), nx, y + 1, nz)) {
                sink.offer(nx, y + 1, nz, MovementCosts.ASCEND);
            }
        }
    }
}
```

Use the same seven imports as `TraverseMove`.

- [ ] **Step 5: Rewrite `DescendMove`**

Keep its existing class javadoc verbatim, add the paragraph below to it, and replace the body:

```java
final class DescendMove implements IMovementType {

    /**
     * The deepest fall gives the worst cost per axis step, because a fall accelerates: its
     * marginal cost per block falls away while the heuristic's credit per block does not.
     * Computed rather than written as a literal, so that re-deriving {@code MAX_SAFE_FALL} or
     * {@code fallTicks} cannot leave a stale figure behind — which is the whole reason the
     * search derives its multiplier instead of trusting a constant.
     */
    private static final double MIN_COST_PER_AXIS_STEP = worstRatio();

    private static double worstRatio() {
        double worst = Double.POSITIVE_INFINITY;
        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            double ratio = (MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop)) / drop;
            if (ratio < worst) {
                worst = ratio;
            }
        }
        return worst;
    }

    @Override
    public String id() {
        return "walk.descend";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MIN_COST_PER_AXIS_STEP;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        for (int i = 0; i < Cardinals.count(); i++) {
            int nx = x + Cardinals.dx(i);
            int nz = z + Cardinals.dz(i);

            if (!Standability.passable(ctx.world().at(nx, y, nz))
                || !Standability.passable(ctx.world().at(nx, y + 1, nz))) {
                continue;
            }

            for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
                int landingY = y - drop;
                if (Standability.standable(ctx.world(), nx, landingY, nz)) {
                    sink.offer(nx, landingY, nz,
                        MovementCosts.TRAVERSE + MovementCosts.fallTicks(drop));
                    break;
                }
                if (!Standability.passable(ctx.world().at(nx, landingY, nz))) {
                    break;
                }
            }
        }
    }
}
```

- [ ] **Step 6: Rewrite `DiagonalMove`**

Keep its existing class javadoc and its private `DIAGONALS` array and `clear` helper verbatim; change the declaration to `final class DiagonalMove implements IMovementType`, add the three new methods, and change `expand`:

```java
    @Override
    public String id() {
        return "walk.diagonal";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.DIAGONAL;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        for (int i = 0; i < DIAGONALS.length; i++) {
            int nx = x + DIAGONALS[i][0];
            int nz = z + DIAGONALS[i][1];

            if (!Standability.standable(ctx.world(), nx, y, nz)) {
                continue;
            }
            if (!clear(ctx.world(), nx, y, z) || !clear(ctx.world(), x, y, nz)) {
                continue;
            }
            sink.offer(nx, y, nz, MovementCosts.DIAGONAL);
        }
    }
```

- [ ] **Step 7: Delete the superseded interfaces**

```bash
git rm core-pathfinder/src/main/java/dev/continuo/pathfinder/Move.java
git rm core-pathfinder/src/main/java/dev/continuo/pathfinder/MoveSink.java
```

- [ ] **Step 8: Adapt A\* to the new signature, still over a fixed array**

In `AStarPathfinder.java`, add `import dev.continuo.movement.ExpansionContext;`, `import dev.continuo.movement.IMovementType;`, `import dev.continuo.movement.MoveSink;` and `import dev.continuo.movement.MutableExpansionContext;`, then change the `MOVES` field (`:36-38`) to:

```java
    private static final IMovementType[] MOVES = {
        new TraverseMove(), new AscendMove(), new DescendMove(), new DiagonalMove()
    };
```

Immediately before the `while (!open.isEmpty())` loop, add:

```java
        final MutableExpansionContext ctx = new MutableExpansionContext(world);
```

and replace the expansion loop at the end of the body (`:137-139`) with:

```java
            ctx.moveTo(cx, cy, cz);
            for (int i = 0; i < MOVES.length; i++) {
                MOVES[i].expand(ctx, sink);
            }
```

The anonymous `MoveSink` at `:111` now implements the imported `dev.continuo.movement.MoveSink`; no change to its body is needed.

- [ ] **Step 9: Update `RecordingSink` and the four movement tests**

In `RecordingSink.java`, add `import dev.continuo.movement.MoveSink;`.

In each of `TraverseMoveTest`, `AscendMoveTest`, `DescendMoveTest`, `DiagonalMoveTest`, every call of the form `move.expand(world, x, y, z, sink)` becomes:

```java
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(x, y, z);
        move.expand(ctx, sink);
```

Add `import dev.continuo.movement.MutableExpansionContext;` to each. Change nothing else — **no assertion in these four files may change.** If one does, that is a behaviour change in a task that must not have one: report it with the output.

- [ ] **Step 10: Run the full build**

Run: `./gradlew build --rerun-tasks`
Expected: PASS. Test count is 301 plus 2 from `BuiltInMovementContractTest` = **303**, 0 failures. **Every C1 assertion must still hold**, including `theMovementIterationOrderIsPinnedSoAReorderingCannotPassUnnoticed` — the golden path is unchanged because `Cardinals` preserves C1's order and `MOVES` preserves C1's sequence.

- [ ] **Step 11: Mutation-prove that `Cardinals` order is still what pins the golden path**

Temporarily reverse `Cardinals.STEPS` to `{{-1, 0}, {0, 1}, {1, 0}, {0, -1}}`.

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.AStarPathfinderTest'`
Expected: FAIL on `theMovementIterationOrderIsPinnedSoAReorderingCannotPassUnnoticed`, returning the mirror-image route. **Paste the output.** This proves the guard survived the relocation. Then revert.

- [ ] **Step 12: Verify no mutation is left, then commit**

Run: `git diff --stat` — `Cardinals.java` must be unmodified relative to Task 1.

```bash
git add -A
git commit -m "feat(c2): the four built-in movements implement the published IMovementType"
```

---

## Task 8: Wire A\* to the registry and derive the heuristic

The breaking change. After this, the multiplier is per-search and `cheapestMove()` is gone.

**Files:**
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/AStarPathfinder.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/Goal.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalBlock.java`
- Modify: `core-pathfinder/src/main/java/dev/continuo/pathfinder/GoalXZ.java`
- Modify: `core-movement/src/main/java/dev/continuo/movement/MovementCosts.java:219-253`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/GoalTest.java`
- Test: `core-pathfinder/src/test/java/dev/continuo/pathfinder/DefaultRegistryTest.java`

**Interfaces:**
- Consumes: `IMovementRegistry`, `MovementRegistry`, `ActiveMovements`, `CapabilitySet` (Tasks 1, 4, 5).
- Produces: `AStarPathfinder(int, IMovementRegistry)`; `AStarPathfinder.findPath(BlockSource, int, int, int, Goal, CapabilitySet)`; `Goal.heuristic(int, int, int, double)`.

- [ ] **Step 1: Write the failing test for the default registry**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/DefaultRegistryTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultRegistryTest {

    @Test
    void theDefaultRegistryHoldsC1sFourMovementsInC1sOrder() {
        ActiveMovements active =
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none());

        List<String> ids = new ArrayList<String>();
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }
        assertEquals(
            Arrays.asList("walk.traverse", "walk.ascend", "walk.descend", "walk.diagonal"),
            ids,
            "A* breaks ties by discovery order, so C1's expansion order must survive verbatim");
    }

    @Test
    void theMultiplierOverC1sMovementsIsWhatC1sConstantWas() {
        assertEquals(MovementCosts.TRAVERSE,
            AStarPathfinder.defaultRegistry().activeFor(CapabilitySet.none()).cheapestAxisStep(),
            1.0e-9,
            "traverse is the cheapest axis step, so deriving the multiplier must reproduce the "
                + "figure C1 hard-coded — otherwise every C1 search result would change");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests 'dev.continuo.pathfinder.DefaultRegistryTest'`
Expected: FAIL — compilation error, `AStarPathfinder.defaultRegistry()` does not exist.

- [ ] **Step 3: Change `Goal` and its two implementations**

In `Goal.java`, change the `heuristic` declaration to:

```java
    /**
     * @param x candidate X
     * @param y candidate Y
     * @param z candidate Z
     * @param cheapestAxisStep the cheapest cost of one axis step over the movements this search
     *                         may use, from
     *                         {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}
     * @return a never-overestimating estimate of the remaining cost, in ticks
     */
    double heuristic(int x, int y, int z, double cheapestAxisStep);
```

and replace the class javadoc's second paragraph with:

```java
 * <p><b>The heuristic must never overestimate</b> the true remaining cost, or A* stops
 * guaranteeing a shortest path. Both implementations here multiply a Chebyshev distance by the
 * multiplier the search supplies, which is a minimum over exactly the movements that search may
 * use. That is what makes the guarantee structural: every movement satisfies
 * {@code cost(m) >= axisSpan(m) × cheapestAxisStep} by the definition of a minimum. C1 could only
 * assert it as a checked numeric property of a closed cost table — see
 * {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}.
```

In `GoalBlock.java`, change the method to:

```java
    @Override
    public double heuristic(int px, int py, int pz, double cheapestAxisStep) {
        int moves = Math.max(Math.abs(x - px), Math.max(Math.abs(y - py), Math.abs(z - pz)));
        return moves * cheapestAxisStep;
    }
```

and replace its class javadoc's admissibility paragraph with:

```java
 * <p>The heuristic is {@code cheapestAxisStep × max(|dx|, |dy|, |dz|)}, where the multiplier is a
 * minimum over the movements the search may use. One movement can close at most its own axis span
 * of that Chebyshev gap, and by the definition of the minimum it pays at least that many cheapest
 * axis steps for it — so the estimate cannot exceed the true remaining cost.
 *
 * <p>Taking the maximum rather than the sum is what makes a diagonal — which closes X and Z
 * together — free of double-counting.
```

In `GoalXZ.java`:

```java
    @Override
    public double heuristic(int px, int py, int pz, double cheapestAxisStep) {
        int moves = Math.max(Math.abs(x - px), Math.abs(z - pz));
        return moves * cheapestAxisStep;
    }
```

Remove the now-unused `MovementCosts` import from both goal classes.

- [ ] **Step 4: Delete `MovementCosts.cheapestMove()`**

In `core-movement/src/main/java/dev/continuo/movement/MovementCosts.java`, delete the whole `cheapestMove()` method and its javadoc block (`:219-253`). Then, in the class javadoc, replace the sentence beginning *"And {@link #cheapestMove()} multiplies the search's heuristic"* with:

```java
 * <p>The heuristic's multiplier is no longer a constant here. It is derived per search, as a
 * minimum over the active movement set — see
 * {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}. A static lower bound over a
 * set that is no longer static was C1's most dangerous single line, and keeping it as a second
 * source of truth would be worse than removing it.
```

- [ ] **Step 5: Rewire `AStarPathfinder`**

Add `import dev.continuo.movement.ActiveMovements;`, `import dev.continuo.movement.CapabilitySet;`, `import dev.continuo.movement.IMovementRegistry;`, `import dev.continuo.movement.MovementRegistry;`.

Replace the `MOVES` field with a registry factory and an instance field:

```java
    private final IMovementRegistry registry;

    /**
     * The registry a pathfinder uses when given none: C1's four movements, in C1's order, plus
     * whatever {@link MovementRegistry#discover()} finds on the classpath.
     *
     * <p>The order is load-bearing. A* breaks cost ties by the order neighbours were discovered,
     * so registering these four in any other sequence would change which of two equal-cost paths
     * comes back.
     *
     * @return a fresh registry; never {@code null}
     */
    static MovementRegistry defaultRegistry() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new TraverseMove());
        registry.register(new AscendMove());
        registry.register(new DescendMove());
        registry.register(new DiagonalMove());
        registry.discover();
        return registry;
    }
```

Change the constructors:

```java
    /** Creates a pathfinder with {@link #DEFAULT_NODE_BUDGET} and {@link #defaultRegistry()}. */
    public AStarPathfinder() {
        this(DEFAULT_NODE_BUDGET);
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @throws IllegalArgumentException if the budget is not positive
     */
    public AStarPathfinder(int nodeBudget) {
        this(nodeBudget, defaultRegistry());
    }

    /**
     * @param nodeBudget the most nodes that may be expanded before giving up; must be positive
     * @param registry the movements this pathfinder may use; never {@code null}
     * @throws IllegalArgumentException if the budget is not positive or the registry is null
     */
    public AStarPathfinder(int nodeBudget, IMovementRegistry registry) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.nodeBudget = nodeBudget;
        this.registry = registry;
    }
```

Add the overload and change the existing `findPath` signature:

```java
    /**
     * Searches with no capabilities granted, so only movements that require none are used.
     *
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal) {
        return findPath(world, startX, startY, startZ, goal, CapabilitySet.none());
    }

    /**
     * @param world the world to read; never {@code null}
     * @param startX the starting X
     * @param startY the starting Y
     * @param startZ the starting Z
     * @param goal what to reach; never {@code null}
     * @param caps what the caller grants; never {@code null}
     * @return the result; never {@code null}
     */
    public PathResult findPath(BlockSource world, int startX, int startY, int startZ, Goal goal,
                               CapabilitySet caps) {
```

Inside the body, after the null checks, add:

```java
        final ActiveMovements active = registry.activeFor(caps);
        final double cheapestAxisStep = active.cheapestAxisStep();
        final List<IMovementType> moves = active.movements();
```

Then change the three `goal.heuristic(...)` calls to pass `cheapestAxisStep`, and the expansion loop to iterate `moves`:

```java
            for (int i = 0; i < moves.size(); i++) {
                moves.get(i).expand(ctx, sink);
            }
```

- [ ] **Step 6: Update `GoalTest`**

Every `heuristic(...)` call gains a multiplier argument. Replace the two tests that referenced the deleted constant:

```java
    @Test
    void theHeuristicCountsTheFewestPossibleMovesNotTheDistanceWalked() {
        Goal goal = new GoalBlock(3, 64, 3);

        assertEquals(3 * MovementCosts.TRAVERSE,
            goal.heuristic(0, 64, 0, MovementCosts.TRAVERSE), 1.0e-9,
            "a diagonal covers X and Z at once, so three moves suffice, not six");
    }

    @Test
    void verticalDistanceCountsWhenItExceedsHorizontal() {
        Goal goal = new GoalBlock(0, 74, 0);

        assertEquals(10 * MovementCosts.TRAVERSE,
            goal.heuristic(0, 64, 0, MovementCosts.TRAVERSE), 1.0e-9,
            "ten levels need at least ten axis steps");
    }

    @Test
    void aLooserMultiplierGivesALooserButStillAdmissibleEstimate() {
        Goal goal = new GoalBlock(4, 64, 0);

        assertTrue(goal.heuristic(0, 64, 0, 1.0) < goal.heuristic(0, 64, 0, 3.5636),
            "a cheap wide movement lowering the multiplier must loosen the estimate, never "
                + "raise it above the true cost");
    }
```

Add the remaining multiplier argument (`MovementCosts.TRAVERSE`) to the other five `heuristic` calls in the file, and add `import dev.continuo.movement.MovementCosts;`.

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --rerun-tasks`
Expected: PASS. 303 tests plus 2 from `DefaultRegistryTest` plus 1 new `GoalTest` case = **306**, 0 failures.

**Every C1 path and cost assertion must be unchanged.** That is the whole claim of spec §6.3, and it is now checked three ways: the multiplier reproduces `TRAVERSE`, registration reproduces `MOVES` order, and the `findPath` overload defaults to no capabilities. If any C1 assertion moves, **report it with the output — do not adjust the test.**

- [ ] **Step 8: Mutation-prove the multiplier ignores nothing**

This is the guard the spec singles out as most likely to be worthless. Temporarily change `ActiveMovements`'s constructor to ignore axis span by using each movement's *cheapest single cost* — simulate it by changing `DescendMove.minCostPerAxisStep()` to return `MovementCosts.TRAVERSE + MovementCosts.fallTicks(1)`, which is the plausible-looking wrong answer.

Run: `./gradlew :core-pathfinder:test :core-movement:test`
Expected: FAIL on `BuiltInMovementContractTest.everyBuiltInMovementDeclaresItsCostPerAxisStepHonestly` and on `descendDeclaresItsWorstRatioNotItsCheapestCost`. **Paste the output.**

If `AStarPathfinderTest`'s optimality/oracle test does **not** also fail, record that explicitly in your report as a gap: the oracle is not sized to separate the two implementations, which is exactly the failure C1 hit when its optimality test passed against the very bug it was written for. Do not resize it in this task — report it and it becomes Task 10's business.

Then revert.

- [ ] **Step 9: Verify no mutation is left, then commit**

Run: `git diff --stat` — `DescendMove.java` must match Task 7 Step 5.

```bash
git add -A
git commit -m "feat(c2): A* takes a registry and derives its heuristic multiplier per search"
```

---

## Task 9: The `:movement-parkour` module

The deliverable. A movement in its own module, unable to compile against the pathfinder.

**Files:**
- Create: `movement-parkour/build.gradle.kts`
- Create: `movement-parkour/src/main/java/dev/continuo/movement/parkour/ParkourMove.java`
- Create: `movement-parkour/src/main/java/dev/continuo/movement/parkour/package-info.java`
- Create: `movement-parkour/src/main/resources/META-INF/services/dev.continuo.movement.IMovementType`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Test: `movement-parkour/src/test/java/dev/continuo/movement/parkour/ParkourMoveTest.java`

**Interfaces:**
- Consumes: `IMovementType`, `Capability`, `Cardinals`, `MovementCosts`, `Standability`, `MutableExpansionContext`, `MovementContract` (Tasks 1–6).
- Produces: `ParkourMove` with id `walk.parkour`, requiring `Capability.PARKOUR`.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add:

```kotlin
include("movement-parkour")
```

In root `build.gradle.kts`, add to `allowedProjectDependencies`:

```kotlin
    ":movement-parkour" to setOf(":core", ":core-movement"),
```

**`:core-pathfinder` is deliberately absent. That absence is the seam.**

Create `movement-parkour/build.gradle.kts`:

```kotlin
plugins {
    id("continuo-pure-module")
}

dependencies {
    // Deliberately NOT :core-pathfinder. A movement must be writable without access to the
    // search, and checkDependencyDirection fails the build if that ever stops being true.
    api(project(":core-movement"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    // Test-scoped only, so the seam above still holds: the end-to-end test needs a real A* to
    // prove a discovered movement is actually used by a search.
    testImplementation(project(":core-pathfinder"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 2: Verify the dependency direction check accepts it**

Run: `./gradlew checkDependencyDirection`
Expected: PASS.

- [ ] **Step 3: Write the failing tests**

Create `movement-parkour/src/test/java/dev/continuo/movement/parkour/ParkourMoveTest.java`:

```java
package dev.continuo.movement.parkour;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.Capability;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementContract;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourMoveTest {

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** Offers recorded as "x,y,z@cost", so order and content are both assertable. */
    private static List<String> offers(BlockSource world, int x, int y, int z) {
        final List<String> recorded = new ArrayList<String>();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(x, y, z);
        new ParkourMove().expand(ctx, new MoveSink() {
            @Override
            public void offer(int nx, int ny, int nz, double cost) {
                recorded.add(nx + "," + ny + "," + nz + "@" + cost);
            }
        });
        return recorded;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static BlockSource world(final Map<String, BlockData> blocks) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(key(x, y, z));
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

    /** Floor at y=63 on both sides of a one-block gap at x=1, standing at (0,64,0). */
    private static Map<String, BlockData> gapWorld() {
        Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);
        blocks.put(key(2, 63, 0), STONE);
        return blocks;
    }

    @Test
    void itJumpsAOneBlockGapToTheLandingBeyond() {
        assertEquals(
            java.util.Collections.singletonList("2,64,0@" + (2 * MovementCosts.TRAVERSE
                + MovementCosts.JUMP_SURCHARGE)),
            offers(world(gapWorld()), 0, 64, 0));
    }

    @Test
    void itDoesNotOfferAJumpWhereTheGapIsWalkable() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 63, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "traverse already reaches the far side in two steps; a parkour edge here would be a "
                + "duplicate at a worse cost, which would make the search prefer jumping over "
                + "walking for no reason");
    }

    @Test
    void itDoesNotOfferAJumpThroughAWall() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 64, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "the player's feet pass through the gap column");
    }

    @Test
    void itDoesNotOfferAJumpWhenTheGapColumnIsBlockedAtHeadHeight() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 65, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "the player's head passes through the gap column too");
    }

    @Test
    void itDoesNotOfferAJumpWithoutHeadroomToJumpInto() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(0, 66, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "a ceiling at y+2 stops the jump before it starts, exactly as it does for ascend");
    }

    @Test
    void itDoesNotOfferAJumpWithNothingToLandOn() {
        Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0));
    }

    @Test
    void itDeclaresItsCostPerAxisStepHonestly() {
        assertEquals(java.util.Collections.<String>emptyList(),
            MovementContract.violations(new ParkourMove()));
    }

    @Test
    void itSpansTwoAxisStepsSoItDeclaresHalfItsCost() {
        double cost = 2 * MovementCosts.TRAVERSE + MovementCosts.JUMP_SURCHARGE;

        assertEquals(cost / 2.0, new ParkourMove().minCostPerAxisStep(), 1.0e-9);
        assertTrue(new ParkourMove().minCostPerAxisStep() > MovementCosts.TRAVERSE,
            "parkour must not become the cheapest axis step, or it would loosen the heuristic "
                + "for every search including ones that cannot use it");
    }

    @Test
    void itRequiresItsCapability() {
        assertEquals(EnumSet.of(Capability.PARKOUR), new ParkourMove().requires());
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :movement-parkour:test`
Expected: FAIL — compilation error, `ParkourMove` does not exist.

- [ ] **Step 5: Write `ParkourMove`**

Create `movement-parkour/src/main/java/dev/continuo/movement/parkour/ParkourMove.java`:

```java
package dev.continuo.movement.parkour;

import dev.continuo.movement.Capability;
import dev.continuo.movement.Cardinals;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Sprint-jumping a one-block gap to the block beyond, on the level.
 *
 * <p><b>The gap must not be standable.</b> If it were, {@code walk.traverse} already reaches the
 * far side in two cheaper steps, and offering a parkour edge as well would add a duplicate at a
 * worse cost — which makes the search prefer jumping to walking for no reason.
 *
 * <p>One block and the same height only. A sprint jump clears more, but every further block is a
 * claim about momentum that nothing in this codebase can check and M5 has not measured.
 *
 * <p><b>This movement spans two axis steps, and that is why it exists.</b> It is the first
 * movement to exercise the search's per-axis-step admissibility condition non-trivially:
 * declaring its whole cost rather than its per-step cost would raise the heuristic's multiplier
 * and silently stop A* returning shortest paths.
 */
public final class ParkourMove implements IMovementType {

    /**
     * Two horizontal blocks at the sprint figure, plus the jump surcharge.
     *
     * <p><b>Declared, not derived.</b> Adding the surcharge rather than overlapping it is an
     * upper bound — the rise and the crossing really do happen together — and that is the same
     * bound, taken for the same stated reason, that {@link MovementCosts#ASCEND} takes. Only M5
     * can measure the truth. The bound errs toward over-costing, whose failure mode is a quality
     * loss rather than a wrong path.
     */
    public static final double COST = 2 * MovementCosts.TRAVERSE + MovementCosts.JUMP_SURCHARGE;

    /** Two blocks along one axis, so half the cost. */
    private static final double MIN_COST_PER_AXIS_STEP = COST / 2.0;

    private static final Set<Capability> REQUIRES =
        Collections.unmodifiableSet(EnumSet.of(Capability.PARKOUR));

    /** Public and no-argument, which is what {@link java.util.ServiceLoader} requires. */
    public ParkourMove() {
    }

    @Override
    public String id() {
        return "walk.parkour";
    }

    @Override
    public Set<Capability> requires() {
        return REQUIRES;
    }

    @Override
    public double minCostPerAxisStep() {
        return MIN_COST_PER_AXIS_STEP;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();

        if (!Standability.passable(ctx.world().at(x, y + 2, z))) {
            return;
        }

        for (int i = 0; i < Cardinals.count(); i++) {
            int dx = Cardinals.dx(i);
            int dz = Cardinals.dz(i);
            int gapX = x + dx;
            int gapZ = z + dz;

            if (!Standability.passable(ctx.world().at(gapX, y, gapZ))
                || !Standability.passable(ctx.world().at(gapX, y + 1, gapZ))) {
                continue;
            }
            if (Standability.standable(ctx.world(), gapX, y, gapZ)) {
                continue;
            }

            int landX = x + 2 * dx;
            int landZ = z + 2 * dz;
            if (Standability.standable(ctx.world(), landX, y, landZ)) {
                sink.offer(landX, y, landZ, COST);
            }
        }
    }
}
```

- [ ] **Step 6: Write the service file and the package doc**

Create `movement-parkour/src/main/resources/META-INF/services/dev.continuo.movement.IMovementType` containing exactly one line:

```
dev.continuo.movement.parkour.ParkourMove
```

Create `movement-parkour/src/main/java/dev/continuo/movement/parkour/package-info.java`:

```java
/**
 * A parkour jump, as a movement plugin.
 *
 * <p><b>This module is the evidence that the movement seam works.</b> It depends on
 * {@code :core-movement} and not on {@code :core-pathfinder}, so it is written with no access to
 * the search's internals, and {@code checkDependencyDirection} fails the build if that ever
 * changes. It is found at runtime through {@code META-INF/services}, so nothing in the core names
 * it.
 */
package dev.continuo.movement.parkour;
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :movement-parkour:test`
Expected: PASS, 9 tests.

- [ ] **Step 8: Mutation-prove the two preconditions that guard against a duplicate edge**

First, temporarily delete the `if (Standability.standable(ctx.world(), gapX, y, gapZ)) { continue; }` block.

Run: `./gradlew :movement-parkour:test --tests '*ParkourMoveTest'`
Expected: FAIL on `itDoesNotOfferAJumpWhereTheGapIsWalkable`. **Paste the output.** Revert.

Then temporarily delete the `if (!Standability.passable(ctx.world().at(x, y + 2, z))) { return; }` block.

Run: `./gradlew :movement-parkour:test --tests '*ParkourMoveTest'`
Expected: FAIL on `itDoesNotOfferAJumpWithoutHeadroomToJumpInto`. **Paste the output.** Revert.

- [ ] **Step 9: Verify no mutation is left, then commit**

Run: `git diff --stat` — `ParkourMove.java` must match Step 5.

```bash
git add -A
git commit -m "feat(c2): add :movement-parkour, discovered by ServiceLoader"
```

---

## Task 10: End-to-end — the seam, the gate, and the oracle

Proves the three claims C2 exists to make: a discovered movement is actually used by a search, the capability gate turns it off, and admissibility survives.

**Files:**
- Test: `movement-parkour/src/test/java/dev/continuo/movement/parkour/ParkourPathfindingTest.java`
- Modify (only if Task 8 Step 8 reported the oracle as under-sized): `core-pathfinder/src/test/java/dev/continuo/pathfinder/AStarPathfinderTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–9.
- Produces: nothing new.

- [ ] **Step 1: Read Task 8's report before writing anything**

If Task 8 Step 8 recorded that the Dijkstra oracle did **not** fail against a span-ignoring multiplier, that gap is this task's first job. A regression guard that cannot be shown to fail on the broken code is not a guard — C1 spent a whole round learning this. Size the oracle up (more seeded worlds, taller terrain so descend's `k = 3` actually occurs) until it separates the two implementations, and record the failing output. Only then continue.

- [ ] **Step 2: Write the failing end-to-end test**

Create `movement-parkour/src/test/java/dev/continuo/movement/parkour/ParkourPathfindingTest.java`:

```java
package dev.continuo.movement.parkour;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementRegistry;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourPathfindingTest {

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    /**
     * A causeway one block wide from x=0 to x=4 at y=64, with the floor missing under x=2 and
     * nothing but void either side — so the only route to the far end is a parkour jump.
     */
    private static BlockSource island() {
        final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);
        blocks.put(key(1, 63, 0), STONE);
        blocks.put(key(3, 63, 0), STONE);
        blocks.put(key(4, 63, 0), STONE);

        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(key(x, y, z));
                return found == null ? AIR : found;
            }

            @Override
            public int minY() {
                return 60;
            }

            @Override
            public int maxY() {
                return 70;
            }
        };
    }

    @Test
    void aDiscoveredMovementIsFoundWithoutAnybodyNamingIt() {
        MovementRegistry registry = new MovementRegistry();
        registry.discover();

        List<String> ids = new ArrayList<String>();
        ActiveMovements active = registry.activeFor(CapabilitySet.of(Capability.PARKOUR));
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }

        assertTrue(ids.contains("walk.parkour"),
            "ServiceLoader must find ParkourMove through META-INF/services; found " + ids);
    }

    @Test
    void aSearchCrossesTheGapWhenParkourIsGranted() {
        PathResult result = new AStarPathfinder().findPath(island(), 0, 64, 0,
            new GoalBlock(4, 64, 0), CapabilitySet.of(Capability.PARKOUR));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(4, result.path().get(result.path().size() - 1).x());
        assertTrue(result.cost() >= ParkourMove.COST,
            "the route has to pay for at least one jump");
    }

    @Test
    void theSameSearchFindsNothingWhenParkourIsNotGranted() {
        PathResult result =
            new AStarPathfinder().findPath(island(), 0, 64, 0, new GoalBlock(4, 64, 0));

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "without the capability the gap is impassable, and this is what proves the gate is "
                + "load-bearing rather than decorative");
    }

    @Test
    void grantingParkourDoesNotChangeTheMultiplierForTheBuiltIns() {
        MovementRegistry registry = AStarPathfinder.publicDefaultRegistry();

        assertEquals(
            registry.activeFor(CapabilitySet.none()).cheapestAxisStep(),
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).cheapestAxisStep(),
            1.0e-9,
            "parkour costs more per axis step than traverse, so turning it on must not loosen "
                + "the heuristic — if this ever changes, every search gets slower");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :movement-parkour:test --tests '*ParkourPathfindingTest'`
Expected: FAIL — `AStarPathfinder.publicDefaultRegistry()` does not exist (`defaultRegistry()` is package-private and this test is in another package).

- [ ] **Step 4: Make the default registry reachable from another package**

In `AStarPathfinder.java`, add a public accessor beside the package-private factory:

```java
    /**
     * The registry {@link #AStarPathfinder()} would use.
     *
     * <p>Public so that a movement module's tests can assert what granting a capability does to
     * the heuristic's multiplier without reaching into this package.
     *
     * @return a fresh registry; never {@code null}
     */
    public static MovementRegistry publicDefaultRegistry() {
        return defaultRegistry();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :movement-parkour:test`
Expected: PASS, 13 tests.

- [ ] **Step 6: Mutation-prove the capability gate end to end**

Temporarily change `MovementRegistry.activeFor` so it ignores `requires()` (replace the `if (caps.grants(...))` condition with `if (true)`).

Run: `./gradlew :movement-parkour:test --tests '*ParkourPathfindingTest'`
Expected: FAIL on `theSameSearchFindsNothingWhenParkourIsNotGranted` — the search crosses the gap without permission. **Paste the output.** This is the guard that proves the gate is load-bearing over a world where it changes the answer. Revert.

- [ ] **Step 7: Full cold build**

Run: `./gradlew build --rerun-tasks`
Expected: PASS. Report the exact task count, test count and failure count.

- [ ] **Step 8: Verify the promises the spec made checkable**

```bash
git diff --stat ed569e4 -- platform/ adapters/
```

Expected: **empty.** A non-empty diff means C2 broke its "no SPI type, neither adapter" promise (spec §1.2, done criterion 6).

```bash
./gradlew checkDependencyDirection
grep -n "movement-parkour" build.gradle.kts
```

Expected: PASS, and `:movement-parkour` allowed only `:core` and `:core-movement`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "test(c2): prove the seam, the capability gate and admissibility end to end"
```

---

## Self-Review

**Spec coverage.** Every section maps to a task: §3 module layout → Tasks 1, 9; §4.1–4.2 → Task 3; §4.3 → Task 1; §4.4 → Task 3; §5.1 → Task 4; §5.2 → Task 5; §5.3 → Tasks 4 and 8; §5.4 → Task 5; §6.1 → Task 2; §6.2 → Task 8; §6.3 → Task 8 Step 7 and Task 10; §7 → Task 9; §8 → Task 6; §9's mutation table → Task 4 Steps 8–9, Task 5 Step 6, Task 6 Step 5, Task 7 Step 11, Task 8 Step 8, Task 9 Step 8, Task 10 Step 6; §10 done criteria → Task 10 Steps 7–8.

**Two spec requirements deliberately re-shaped**, both recorded at the top of this plan: `findPath` gains an overload rather than a changed signature, and `CARDINALS` becomes a `Cardinals` accessor class rather than an interface constant.

**One gap the plan cannot close on its own.** Spec §9's first mutation row requires the Dijkstra oracle to fail against a span-ignoring multiplier. Whether C1's oracle is *already* sized to do that is unknown until Task 8 Step 8 runs it. The plan handles this honestly: Task 8 Step 8 says to report the gap rather than paper over it, and Task 10 Step 1 makes closing it the first job of the final task. Do not let a green suite at Task 8 be read as proof the guard works.
