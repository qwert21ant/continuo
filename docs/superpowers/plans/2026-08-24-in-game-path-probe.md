# In-Game Path Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner stand in a running Minecraft world, mark a destination, press a key, and get A\* run against the live world with the route rendered as text art they can read and paste back into the test suite as a fixture.

**Architecture:** C1's text-art renderer is promoted from `:core-pathfinder`'s test sources into its published API and generalized from a fixture world to any `BlockSource`, sharing one legend with the fixture parser so the round trip cannot drift. A new `PathProbe` in `:runtime` runs the search against the live `BlockLookup` the core already owns and renders the result over a padded, clamped bounding box. Both adapters gain two keybinds and call it. No SPI type is added and nothing moves the player.

**Tech Stack:** Java 8 bytecode (machine-checked — no `var`, no records, no `List.of`, no text blocks, no switch expressions, no lambdas in main source; house style is anonymous classes), Gradle with `continuo-pure-module`, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-24-in-game-path-probe-design.md`

## Global Constraints

- **Java 8 bytecode, machine-checked.** No `var`, records, `List.of`, text blocks, or switch expressions. **No lambdas in main source** — house style is anonymous inner classes. Lambdas are acceptable in adapter code only where the surrounding adapter already uses them (the 1.21.11 adapter does; the 1.7.10 adapter does not and cannot).
- **Javadoc is build-failing** in pure modules (`-Xdoclint:all,-missing -Xwerror`). A `{@link}` to a type in a module you do not depend on breaks the build. Use `{@code}` when unsure.
- **`GRADLE_USER_HOME` is already `C:\GradleHome`.** Never set, export or override it.
- **Never run `./gradlew clean`** — it destroys the 1.7.10 decompiled sources at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`. Use `./gradlew build --rerun-tasks` for a from-cold guarantee.
- **A new module or dependency must be registered in `allowedProjectDependencies`** in the root `build.gradle.kts`, or `checkDependencyDirection` fails the whole build. The checked configurations are `api`, `implementation`, `compileOnly`, `runtimeOnly`, `compileOnlyApi` — test-scoped dependencies are deliberately exempt.
- **Adapters have no tests and cannot get any.** Their correctness is checked by compiling and by the owner running the game.
- **Never count tests after a filtered run.** `--tests 'X'` leaves only that class's results in `build/test-results/`, so a later count reads low. Count only after a full `build --rerun-tasks`.
- **CI and the remote are off-limits.** Do not push, do not touch `.github/workflows/ci.yml`.
- **If a guard cannot be shown to fail on broken code, report that.** A negative result is more valuable than a test that merely looks like one. Report discrepancies with this plan rather than adjusting to them.

---

## File Structure

| File | Responsibility |
|---|---|
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/BlockLegend.java` | **Create.** The canonical character↔`BlockData` mapping, both directions, published |
| `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathRenderer.java` | **Create** (moved from test). Renders any `BlockSource` plus a `PathResult` as text art |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java` | **Delete.** Moved to main |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java` | **Delete.** Replaced by `BlockLegend` |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureRenderer.java` | **Create.** Test-side delegate rendering a `FixtureWorld` through the published form |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/BlockLegendTest.java` | **Create.** Pins both directions and the `?` fallback |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java` | **Modify.** Legend source and marker constants |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java` | **Modify.** One constant reference |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java` | **Modify.** Call the delegate; add the two-forms-agree guard |
| `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java` | **Modify.** One call site |
| `runtime/src/main/java/dev/continuo/runtime/ProbeBounds.java` | **Create.** The padded, clamped render box |
| `runtime/src/main/java/dev/continuo/runtime/ProbeReport.java` | **Create.** What a probe run returns |
| `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` | **Create.** Mark a goal, run the search, assemble the report |
| `runtime/src/test/java/dev/continuo/runtime/ProbeWorld.java` | **Create.** A settable `BlockSource` double |
| `runtime/src/test/java/dev/continuo/runtime/ProbeBoundsTest.java` | **Create.** |
| `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` | **Create.** |
| `runtime/build.gradle.kts` | **Modify.** Three new dependencies |
| `build.gradle.kts` | **Modify.** `:runtime`'s allowlist entry |
| `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java` | **Modify.** Two keybinds, one poll |
| `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java` | **Modify.** Two keybinds, one poll |

---

### Task 1: `BlockLegend` — one legend, published

Spec §4.2. `FixtureBlocks` is deleted rather than kept as an alias holder: two legends that agree today drift silently the first time either gains a shape, and the round trip in P5 depends on them being identical. Deleting it makes that impossible by construction.

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/BlockLegend.java`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/BlockLegendTest.java`
- Delete: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java` (still in test sources at this point)
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `public final class BlockLegend` in `dev.continuo.pathfinder` with `public static final char UNMAPPED = '?'`; public `BlockData` constants `AIR`, `STONE`, `BOTTOM_SLAB`, `TOP_SLAB`, `STAIR`, `CARPET`, `PARTIAL_FLOOR`, `FENCE`, `UNKNOWN`, `WATER`, `LAVA`; `public static Map<Character, BlockData> legend()`; `public static char characterFor(BlockData data)`.

- [ ] **Step 1: Write the failing test**

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/BlockLegendTest.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLegendTest {

    @Test
    void theLegendMapsCharactersToBlocks() {
        assertEquals(BlockLegend.AIR, BlockLegend.legend().get(Character.valueOf('.')));
        assertEquals(BlockLegend.STONE, BlockLegend.legend().get(Character.valueOf('#')));
        assertEquals(BlockLegend.CARPET, BlockLegend.legend().get(Character.valueOf('c')));
    }

    @Test
    void everyLegendEntryRoundTripsBackToItsOwnCharacter() {
        // This is the property the whole render-and-paste-back pipeline rests on. If the
        // forward and reverse directions ever disagree for one entry, a rendered map re-parses
        // as different terrain and the pasted fixture poses a different routing question than
        // the one that was captured - silently, because both halves still look well formed.
        for (java.util.Map.Entry<Character, BlockData> entry : BlockLegend.legend().entrySet()) {
            assertEquals(entry.getKey().charValue(), BlockLegend.characterFor(entry.getValue()),
                "legend character " + entry.getKey() + " does not come back from its block");
        }
    }

    @Test
    void aBlockOutsideTheLegendRendersAsUnmapped() {
        // Spec 4.3: a live world produces BlockData the legend has no character for. It must
        // render as something rather than crash, and '?' is what re-parses as UNKNOWN.
        BlockData offLegend = new BlockData(BlockShape.PARTIAL, 0.5625, Fluid.NONE,
            EnumSet.noneOf(BlockTag.class));

        assertEquals('?', BlockLegend.characterFor(offLegend));
        assertEquals(BlockLegend.UNMAPPED, BlockLegend.characterFor(offLegend));
    }

    @Test
    void theLegendCannotBeModifiedByACaller() {
        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                BlockLegend.legend().put(Character.valueOf('x'), BlockLegend.STONE);
            }
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests '*BlockLegendTest*'`
Expected: FAIL to compile — `cannot find symbol: class BlockLegend`.

- [ ] **Step 3: Create `BlockLegend` in main sources**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/BlockLegend.java`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical character-to-block mapping shared by the renderer and the fixture parser.
 *
 * <p>Values are chosen to match what B1's audit actually recorded, so a fixture exercises the
 * real numbers rather than round ones. {@link #PARTIAL_FLOOR} is farmland's 1.21.11 value and
 * {@link #CARPET} is carpet's; both are the version-divergent cases the predicates are designed
 * to reconcile.
 *
 * <p><b>One definition, deliberately, and it is structural rather than tidy.</b> The renderer
 * writes characters and the fixture parser reads them, and a rendered map is only pasteable back
 * as a fixture while those two agree. Two mappings that agree today would drift the first time
 * either side gained a block shape, and the drift would be silent: the map would still look well
 * formed and would simply re-parse as different terrain. Sharing one definition removes that
 * failure mode rather than testing for it.
 *
 * <p><b>{@link #characterFor} conflates two different things, and that is the documented
 * behaviour.</b> A block the legend has no character for renders as {@link #UNMAPPED}, which is
 * also {@link #UNKNOWN}'s own character. A live world produces such blocks routinely — see the
 * limits recorded on {@link PathRenderer}.
 */
public final class BlockLegend {

    /**
     * The character for a block the legend does not name.
     *
     * <p>It is {@link #UNKNOWN}'s character too, so a map cannot distinguish "unreadable" from
     * "not in this legend" once written. Both re-parse as {@code UNKNOWN}, which is impassable.
     */
    public static final char UNMAPPED = '?';

    /** Empty space. */
    public static final BlockData AIR = plain(BlockShape.AIR, 0.0);

    /** A full cube: stone, dirt, leaves, and most of a world. */
    public static final BlockData STONE = plain(BlockShape.FULL, 1.0);

    /** A slab occupying the lower half. */
    public static final BlockData BOTTOM_SLAB = plain(BlockShape.SLAB_BOTTOM, 0.5);

    /** A slab occupying the upper half, so its collision top is a whole block up. */
    public static final BlockData TOP_SLAB = plain(BlockShape.SLAB_TOP, 1.0);

    /** A stair. */
    public static final BlockData STAIR = plain(BlockShape.STAIR, 1.0);

    /** Carpet's measured height. */
    public static final BlockData CARPET = plain(BlockShape.THIN_LAYER, 0.0625);

    /** Farmland's measured height. */
    public static final BlockData PARTIAL_FLOOR = plain(BlockShape.PARTIAL, 0.9375);

    /** A fence: collision above the cube, so neither passable nor a floor. */
    public static final BlockData FENCE = plain(BlockShape.FENCE, 1.5);

    /** Unreadable. */
    public static final BlockData UNKNOWN = BlockData.UNKNOWN;

    /** No collision, occupied by water. */
    public static final BlockData WATER =
        new BlockData(BlockShape.AIR, 0.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));

    /** No collision, occupied by lava, and refused on the tag. */
    public static final BlockData LAVA =
        new BlockData(BlockShape.AIR, 0.0, Fluid.LAVA, EnumSet.of(BlockTag.AVOID));

    private static final Map<Character, BlockData> LEGEND = buildLegend();
    private static final Map<BlockData, Character> REVERSE = buildReverse();

    private BlockLegend() {
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
     * The reverse direction, built once. First character wins, so a block reachable by two
     * characters renders as the one declared first in {@link #buildLegend}.
     */
    private static Map<BlockData, Character> buildReverse() {
        Map<BlockData, Character> reverse = new HashMap<BlockData, Character>();
        for (Map.Entry<Character, BlockData> entry : LEGEND.entrySet()) {
            if (!reverse.containsKey(entry.getValue())) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reverse);
    }

    /**
     * @return the character-to-block legend, unmodifiable, in a stable iteration order so that
     *         the reverse lookup is deterministic
     */
    public static Map<Character, BlockData> legend() {
        return LEGEND;
    }

    /**
     * @param data the block to render; never {@code null}
     * @return its character, or {@link #UNMAPPED} if the legend does not name it
     */
    public static char characterFor(BlockData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Character ch = REVERSE.get(data);
        return ch == null ? UNMAPPED : ch.charValue();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-pathfinder:test --tests '*BlockLegendTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Delete `FixtureBlocks` and repoint its four call sites**

Delete `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java`.

In `FixtureWorld.java`, replace the two references:
- `FixtureBlocks.legend()` becomes `BlockLegend.legend()`
- `FixtureBlocks.AIR` becomes `BlockLegend.AIR`

In `FixtureWorldTest.java`, replace `FixtureBlocks.STONE` with `BlockLegend.STONE`.

In `PathRenderer.java` (still in test sources), replace `FixtureBlocks.legend()` with `BlockLegend.legend()`.

In `PathRendererTest.java`, replace `FixtureBlocks.CARPET` with `BlockLegend.CARPET` and `FixtureBlocks.AIR` with `BlockLegend.AIR`.

- [ ] **Step 6: Run the whole module's tests**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS. Every pre-existing test still green — this task changed no behaviour, only where the legend lives.

- [ ] **Step 7: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/BlockLegend.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/BlockLegendTest.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureBlocks.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorldTest.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java
git commit -m "feat(probe): publish the fixture legend as BlockLegend

The renderer writes characters and the fixture parser reads them, and a rendered
map is only pasteable back as a fixture while the two agree. FixtureBlocks is
deleted rather than kept as an alias holder so there is one definition and the
two cannot drift.

No behaviour change: same eleven values, same characters, same iteration order."
```

---

### Task 2: `PathRenderer` moves to main and takes a `BlockSource`

Spec §4.1 and §4.3. The signature takes six inclusive bounds — **including `maxY`**, which differs from `BlockSource.maxY()`'s exclusive contract. The `FixtureWorld` delegate is what absorbs that difference.

**Files:**
- Create: `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathRenderer.java`
- Delete: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java`
- Create: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureRenderer.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java`
- Modify: `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java:113`

**Interfaces:**
- Consumes: `BlockLegend.characterFor(BlockData)` and `BlockLegend.UNMAPPED` from Task 1.
- Produces: `public final class PathRenderer` with `public static final char START = 'S'`, `GOAL = 'G'`, `PATH = '*'`, `EXPANDED = '+'`, and
  `public static String render(BlockSource world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Pos start, Pos goal, PathResult result)`.
  Test-side `FixtureRenderer.render(FixtureWorld, PathResult)` returns the same `String`.

**Why the delegate is not called `PathRenderer`:** a test-source class with the same package and name as a main-source class shadows it on the test compile classpath. The two forms must have different names.

- [ ] **Step 1: Write the failing test**

Add to `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java`:

```java
    /**
     * The two forms must agree, and the reason is the one parameter whose meaning is not
     * obvious. BlockSource.maxY() is one past the top, while FixtureWorld's X and Z bounds are
     * inclusive. The published form takes all six inclusive so a caller reading the signature
     * cannot be caught by the split, which means the delegate has to pass maxY() - 1. Get that
     * off by one and the map silently loses or gains its top layer.
     */
    @Test
    void theBlockSourceFormAgreesWithTheFixtureForm() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        String viaFixture = FixtureRenderer.render(world, result);
        String viaBlockSource = PathRenderer.render(world,
            world.minX(), world.minY(), world.minZ(),
            world.maxX(), world.maxY() - 1, world.maxZ(),
            world.start(), world.goal(), result);

        assertEquals(viaFixture, viaBlockSource);
    }

    /**
     * The top layer is present, which is what an off-by-one in the delegate would silently
     * remove. FLAT declares y=64, y=65 and y=66, so the render must carry all three.
     */
    @Test
    void theRenderCarriesEveryLayerOfTheWorldIncludingTheTopmost() {
        FixtureWorld world = FixtureWorld.parse(FLAT);
        PathResult result = new AStarPathfinder().findPath(world, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        String rendered = FixtureRenderer.render(world, result);

        assertTrue(rendered.contains("--- y=64"), rendered);
        assertTrue(rendered.contains("--- y=65"), rendered);
        assertTrue(rendered.contains("--- y=66"),
            "the topmost layer is the one an inclusive/exclusive slip drops\n" + rendered);
    }

    /**
     * Spec 4.3, pinned rather than merely documented: a live world produces blocks the legend
     * does not name, and the whole in-game probe rests on those rendering as something readable
     * instead of throwing. The paste-back consequence is the second half - '?' comes back as
     * UNKNOWN, which is impassable, so a pasted fixture can be stricter than the world it came
     * from.
     */
    @Test
    void aBlockOutsideTheLegendRendersAsQuestionMarkAndReParsesAsUnknown() {
        final BlockData offLegend = new BlockData(BlockShape.PARTIAL, 0.5625, Fluid.NONE,
            EnumSet.noneOf(BlockTag.class));
        FixtureWorld world = FixtureWorld.parse(FLAT);
        BlockSource patched = new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                return x == 2 && y == 66 && z == 0 ? offLegend : world.at(x, y, z);
            }

            @Override
            public int minY() {
                return world.minY();
            }

            @Override
            public int maxY() {
                return world.maxY();
            }
        };
        PathResult result = new AStarPathfinder().findPath(patched, 0, 65, 0,
            new GoalBlock(4, 65, 0));

        String rendered = PathRenderer.render(patched,
            world.minX(), world.minY(), world.minZ(),
            world.maxX(), world.maxY() - 1, world.maxZ(),
            new Pos(0, 65, 0), new Pos(4, 65, 0), result);

        String terrain = rendered.substring(0, rendered.indexOf("// "));
        assertTrue(terrain.indexOf(BlockLegend.UNMAPPED) >= 0,
            "the off-legend block must render as '?'\n" + rendered);

        FixtureWorld reparsed = FixtureWorld.parse(rendered);
        assertEquals(BlockLegend.UNKNOWN, reparsed.at(2, 66, 0),
            "and '?' comes back as UNKNOWN, not as the block it actually was - so a pasted"
                + " fixture is stricter than the world it was captured from");
    }
```

Add these imports to `PathRendererTest.java`:

```java
import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.Fluid;
import dev.continuo.core.BlockTag;

import java.util.EnumSet;
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-pathfinder:test --tests '*PathRendererTest*'`
Expected: FAIL to compile — `cannot find symbol: class FixtureRenderer`, and `render(...)` with ten arguments does not exist.

- [ ] **Step 3: Create the main-source `PathRenderer`**

Create `core-pathfinder/src/main/java/dev/continuo/pathfinder/PathRenderer.java`. This is the existing test-source implementation with `FixtureWorld` replaced by `BlockSource` plus explicit bounds and markers, and the legend lookup replaced by `BlockLegend.characterFor`:

```java
package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Renders a world and a search result as text art, in the same format fixtures are written in.
 *
 * <p>ASCII rather than an image, deliberately. The people and agents who debug this read test
 * output as text; a PNG written to the build directory would be invisible to every one of them.
 *
 * <p><b>The output round-trips, with two stated limits.</b> Overlay characters read back as air
 * when parsed, so a rendered failure can be pasted straight into a test as a new fixture. Terrain
 * <em>not covered by an overlay</em> and <em>named by the legend</em> survives exactly.
 *
 * <p><b>An overlay replaces the terrain character rather than accompanying it</b>, so a passable
 * non-air block underneath one — a carpet, a snow layer — re-parses as air. Nothing is stacked or
 * escaped to avoid this, deliberately: dropping the overlay wherever the terrain is non-air would
 * lose the path marker exactly where the terrain is interesting, which is worse for the debugging
 * this class exists to serve.
 *
 * <p>The practical consequence is mild rather than absent. Every block that can sit under an
 * overlay is passable and non-supporting by construction — the search only walks where it can
 * stand — and air is passable and non-supporting too, so a pasted-back fixture still poses the
 * same routing question and still reproduces the failure. What it loses is the record of which
 * passable block was there.
 *
 * <p><b>The second limit belongs to live worlds and is sharper.</b> A block whose classification
 * {@link BlockLegend} does not name renders as {@link BlockLegend#UNMAPPED}, and that character
 * re-parses as {@code BlockData.UNKNOWN}, which is impassable. So a map captured from a running
 * game can be <em>stricter</em> than the world it came from: a {@code ?} that was really a
 * passable block becomes a wall, and the pasted fixture may fail to reproduce the routing
 * question it was captured for. Ordinary terrain is unaffected — stone, dirt and leaves all
 * classify to the legend's full cube, and slabs, stairs, fences, water and lava are named — but
 * <b>a map with {@code ?} anywhere near the route needs checking before it is trusted as a
 * fixture.</b>
 *
 * <p>{@code PathRendererTest} pins all of this: what survives, and what does not.
 */
public final class PathRenderer {

    /** Where the search began. */
    public static final char START = 'S';

    /** What it was trying to reach. */
    public static final char GOAL = 'G';

    /** A position on the returned route. */
    public static final char PATH = '*';

    /** A position the search expanded without using. */
    public static final char EXPANDED = '+';

    private PathRenderer() {
    }

    /**
     * @param world the world that was searched; never {@code null}
     * @param minX the lowest X to draw, inclusive
     * @param minY the lowest Y to draw, inclusive
     * @param minZ the lowest Z to draw, inclusive
     * @param maxX the highest X to draw, <b>inclusive</b>
     * @param maxY the highest Y to draw, <b>inclusive</b> — note this differs from
     *             {@code BlockSource.maxY()}, which is one past the top. All six bounds here
     *             mean the same thing as each other, which is what a caller reading the
     *             signature will assume
     * @param maxZ the highest Z to draw, <b>inclusive</b>
     * @param start where the search began; never {@code null}
     * @param goal what it was trying to reach; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    public static String render(BlockSource world,
                                int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ,
                                Pos start, Pos goal, PathResult result) {
        if (world == null || start == null || goal == null || result == null) {
            throw new IllegalArgumentException("no argument may be null");
        }

        Set<Long> path = new HashSet<Long>();
        for (Pos pos : result.path()) {
            path.add(Long.valueOf(pos.packed()));
        }
        Set<Long> expanded = new HashSet<Long>();
        for (Pos pos : result.expanded()) {
            expanded.add(Long.valueOf(pos.packed()));
        }

        // A failed search has an empty path, so fall back to the caller's own start and goal.
        // Without this the one render that matters most — the failure you want to paste back in
        // as a regression fixture — loses the goal entirely and shows the start as an ordinary
        // expanded node, because the start is always in `expanded` whether or not it is in
        // `path`.
        Long startKey = Long.valueOf(
            (result.path().isEmpty() ? start : result.path().get(0)).packed());
        Long goalKey = Long.valueOf(
            (result.path().isEmpty() ? goal : result.path().get(result.path().size() - 1))
                .packed());

        StringBuilder out = new StringBuilder();
        out.append("origin: ").append(minX).append(',')
            .append(minY).append(',').append(minZ).append('\n');

        for (int y = minY; y <= maxY; y++) {
            out.append("--- y=").append(y).append('\n');
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    Long key = Long.valueOf(Pos.pack(x, y, z));
                    if (key.equals(startKey)) {
                        out.append(START);
                    } else if (key.equals(goalKey)) {
                        out.append(GOAL);
                    } else if (path.contains(key)) {
                        out.append(PATH);
                    } else if (expanded.contains(key)) {
                        out.append(EXPANDED);
                    } else {
                        out.append(BlockLegend.characterFor(world.at(x, y, z)));
                    }
                }
                out.append('\n');
            }
        }

        appendSummary(out, result);
        return out.toString();
    }

    private static void appendSummary(StringBuilder out, PathResult result) {
        out.append("// ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost()).append('\n');
    }
}
```

- [ ] **Step 4: Delete the test-source renderer and add the delegate**

Delete `core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java`.

Create `core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureRenderer.java`:

```java
package dev.continuo.pathfinder;

/**
 * Renders a {@link FixtureWorld} through the published {@link PathRenderer}.
 *
 * <p>It exists for one reason: {@code FixtureWorld} carries its own bounds and its own start and
 * goal, and every fixture test would otherwise repeat nine arguments. It also absorbs the one
 * asymmetry in the published signature — {@code BlockSource.maxY()} is one past the top, while
 * {@code PathRenderer}'s {@code maxY} is inclusive like its five neighbours.
 *
 * <p>It cannot be called {@code PathRenderer}: a test-source class sharing a package and name
 * with a main-source class shadows it on the test compile classpath, and the tests would then be
 * exercising a copy rather than the published implementation.
 */
final class FixtureRenderer {

    private FixtureRenderer() {
    }

    /**
     * @param world the fixture; never {@code null}
     * @param result the search result; never {@code null}
     * @return the rendering, ending in a newline
     */
    static String render(FixtureWorld world, PathResult result) {
        return PathRenderer.render(world,
            world.minX(), world.minY(), world.minZ(),
            world.maxX(), world.maxY() - 1, world.maxZ(),
            world.start(), world.goal(), result);
    }
}
```

- [ ] **Step 5: Repoint `FixtureWorld`'s marker constants and the existing call sites**

In `FixtureWorld.java`, replace the four marker constant declarations so there is one definition of each character:

```java
    static final char START = PathRenderer.START;
    static final char GOAL = PathRenderer.GOAL;
    static final char PATH = PathRenderer.PATH;
    static final char EXPANDED = PathRenderer.EXPANDED;
```

In `PathRendererTest.java`, replace every `PathRenderer.render(world, result)` with `FixtureRenderer.render(world, result)`. There are eight such call sites across the existing tests; the three tests added in Step 1 already call the right form.

In `PathfinderAcceptanceTest.java:113`, replace `PathRenderer.render(world, result)` with `FixtureRenderer.render(world, result)`.

- [ ] **Step 6: Run the module's tests**

Run: `./gradlew :core-pathfinder:test`
Expected: PASS. Every pre-existing renderer test green through the delegate, plus the three new ones.

- [ ] **Step 7: Prove the inclusivity guard fails on broken code**

Temporarily change `FixtureRenderer.render` to pass `world.maxY()` instead of `world.maxY() - 1`.

Run: `./gradlew :core-pathfinder:test --tests '*PathRendererTest*'`
Expected: FAIL. Record which tests fail and paste the output. If nothing fails, **say so** — it means the guard does not witness the off-by-one and the test needs rethinking, which is more valuable to report than a green run.

Revert the change.

- [ ] **Step 8: Commit**

```bash
git add core-pathfinder/src/main/java/dev/continuo/pathfinder/PathRenderer.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRenderer.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureRenderer.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/FixtureWorld.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathRendererTest.java \
        core-pathfinder/src/test/java/dev/continuo/pathfinder/PathfinderAcceptanceTest.java
git commit -m "feat(probe): publish PathRenderer over any BlockSource

Moves the renderer from test sources into the published API and generalizes it
from FixtureWorld to BlockSource plus six inclusive bounds and explicit start and
goal markers. FixtureRenderer keeps the fixture call sites to two arguments and
absorbs the maxY inclusive/exclusive difference.

The delegate cannot share the renderer's name: a test-source class in the same
package would shadow the main one and the suite would test a copy.

Spec 4.3's live-world limit is now pinned rather than only documented - an
off-legend block renders '?' and re-parses as UNKNOWN, so a captured map can be
stricter than the world it came from."
```

---

### Task 3: `:runtime` gains the pathfinder, and `ProbeBounds`

Spec §3 and §5.5. The dependency wiring lands here because this is the first task that needs it. `ProbeBounds` is a pure function over coordinates and is fully testable on its own.

**Files:**
- Modify: `runtime/build.gradle.kts`
- Modify: `build.gradle.kts` (root, `allowedProjectDependencies`)
- Create: `runtime/src/main/java/dev/continuo/runtime/ProbeBounds.java`
- Create: `runtime/src/test/java/dev/continuo/runtime/ProbeBoundsTest.java`

**Interfaces:**
- Consumes: `Pos` from `:core-pathfinder`, `BlockSource` from `:core`.
- Produces: package-private `final class ProbeBounds` in `dev.continuo.runtime` with `static final int PADDING = 2`, `static final int MAX_EXTENT = 64`, final package-visible fields `minX`, `minY`, `minZ`, `maxX`, `maxY`, `maxZ`, `clamped`, and `static ProbeBounds around(BlockSource world, Pos start, Pos goal, List<Pos> path)`.

- [ ] **Step 1: Wire the dependencies**

In `runtime/build.gradle.kts`, add to the `dependencies` block, after the existing `api(project(":core"))`:

```kotlin
    // The probe's public API names PathOutcome, so :core-pathfinder is `api` rather than
    // `implementation`. :core-movement is named directly for CapabilitySet and Capability, not
    // only reached through the pathfinder, so it is declared rather than left transitive.
    api(project(":core-pathfinder"))
    api(project(":core-movement"))

    // Discovered by ServiceLoader at runtime, never compiled against. Without this nothing puts
    // the parkour movement on the game's classpath - the adapters depend on :runtime and never
    // on the movement modules - and the probe would request Capability.PARKOUR from a registry
    // with nothing to grant it. Every search would silently run the four built-ins and look
    // exactly like one that exercised parkour.
    runtimeOnly(project(":movement-parkour"))
```

In the root `build.gradle.kts`, replace `:runtime`'s allowlist entry:

```kotlin
    ":runtime" to setOf(":platform", ":core", ":core-movement", ":core-pathfinder",
        ":movement-parkour"),
```

- [ ] **Step 2: Write the failing test**

Create `runtime/src/test/java/dev/continuo/runtime/ProbeBoundsTest.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeBoundsTest {

    /** A world with generous Y limits, so only the explicit clamps are under test here. */
    private static BlockSource world(final int minY, final int maxY) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                return BlockData.UNKNOWN;
            }

            @Override
            public int minY() {
                return minY;
            }

            @Override
            public int maxY() {
                return maxY;
            }
        };
    }

    @Test
    void theBoxCoversStartAndGoalWithPadding() {
        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(10, 64, 10), new Pos(14, 64, 12), Collections.<Pos>emptyList());

        assertEquals(10 - ProbeBounds.PADDING, bounds.minX);
        assertEquals(14 + ProbeBounds.PADDING, bounds.maxX);
        assertEquals(10 - ProbeBounds.PADDING, bounds.minZ);
        assertEquals(12 + ProbeBounds.PADDING, bounds.maxZ);
        assertFalse(bounds.clamped, "a small box is not clamped");
    }

    @Test
    void thePathIsIncludedEvenWhereItLeavesTheStartToGoalBox() {
        // A route that detours well outside the straight line between start and goal. Bounding
        // only start and goal would draw a map with the interesting half of the route missing,
        // which is exactly the case worth looking at.
        List<Pos> path = Arrays.asList(new Pos(10, 64, 10), new Pos(10, 64, 30),
            new Pos(14, 64, 12));

        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(10, 64, 10), new Pos(14, 64, 12), path);

        assertEquals(30 + ProbeBounds.PADDING, bounds.maxZ);
    }

    @Test
    void anAxisLongerThanTheMaximumExtentIsClampedAndSaysSo() {
        ProbeBounds bounds = ProbeBounds.around(world(0, 256),
            new Pos(0, 64, 0), new Pos(500, 64, 0), Collections.<Pos>emptyList());

        assertEquals(ProbeBounds.MAX_EXTENT, bounds.maxX - bounds.minX + 1,
            "the X axis is reduced to the maximum extent");
        assertTrue(bounds.clamped,
            "and the caller is told, because a silently truncated map looks like a search that "
                + "stopped for no reason");
    }

    @Test
    void theBoxNeverLeavesTheWorldsOwnYLimits() {
        // maxY is one past the top, per BlockSource. The box's maxY is inclusive, so the
        // highest legal layer is maxY() - 1.
        ProbeBounds bounds = ProbeBounds.around(world(60, 70),
            new Pos(0, 61, 0), new Pos(4, 69, 0), Collections.<Pos>emptyList());

        assertTrue(bounds.minY >= 60, "minY was " + bounds.minY);
        assertTrue(bounds.maxY <= 69, "maxY was " + bounds.maxY);
    }

    @Test
    void aBoxThatOnlyFitsBecauseOfTheWorldsYLimitsIsNotReportedAsClamped() {
        // Clamping to the world's own limits is ordinary, not truncation: there is no terrain
        // beyond them to lose. Only MAX_EXTENT reduction means the reader is missing something.
        ProbeBounds bounds = ProbeBounds.around(world(60, 70),
            new Pos(0, 61, 0), new Pos(4, 69, 0), new ArrayList<Pos>());

        assertFalse(bounds.clamped);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :runtime:test --tests '*ProbeBoundsTest*'`
Expected: FAIL to compile — `cannot find symbol: class ProbeBounds`. If it instead fails with `package dev.continuo.pathfinder does not exist`, Step 1's wiring did not take.

- [ ] **Step 4: Implement `ProbeBounds`**

Create `runtime/src/main/java/dev/continuo/runtime/ProbeBounds.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.Pos;

import java.util.List;

/**
 * The region a probe run draws.
 *
 * <p>The box covers the start, the goal and every position on the returned route, padded so the
 * terrain immediately around them is visible, then clamped so a distant goal cannot ask for an
 * enormous file. A map is one character per position per Y layer, so an unclamped box a few
 * hundred blocks on a side is hundreds of megabytes.
 *
 * <p><b>{@link #clamped} means the reader is missing something, and only that.</b> Reducing an
 * axis to {@link #MAX_EXTENT} throws terrain away, so the output has to say so — a silently
 * truncated map looks like a search that stopped for no reason, and there is nothing in it to
 * tell the two apart. Clamping to the world's own Y limits is not the same thing and does not
 * set the flag: there is no terrain outside them to lose.
 */
final class ProbeBounds {

    /** Blocks of terrain drawn around the region of interest. */
    static final int PADDING = 2;

    /** The most blocks any one axis may span. */
    static final int MAX_EXTENT = 64;

    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;

    /** Whether an axis was reduced to {@link #MAX_EXTENT}, throwing terrain away. */
    final boolean clamped;

    private ProbeBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        boolean clamped) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.clamped = clamped;
    }

    /**
     * @param world the world being drawn, for its Y limits; never {@code null}
     * @param start where the search began; never {@code null}
     * @param goal what it was trying to reach; never {@code null}
     * @param path the returned route, empty for a failed search; never {@code null}
     * @return the region to draw
     */
    static ProbeBounds around(BlockSource world, Pos start, Pos goal, List<Pos> path) {
        if (world == null || start == null || goal == null || path == null) {
            throw new IllegalArgumentException("no argument may be null");
        }

        int minX = Math.min(start.x(), goal.x());
        int maxX = Math.max(start.x(), goal.x());
        int minY = Math.min(start.y(), goal.y());
        int maxY = Math.max(start.y(), goal.y());
        int minZ = Math.min(start.z(), goal.z());
        int maxZ = Math.max(start.z(), goal.z());

        for (int i = 0; i < path.size(); i++) {
            Pos pos = path.get(i);
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minY = Math.min(minY, pos.y());
            maxY = Math.max(maxY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
        }

        minX -= PADDING;
        maxX += PADDING;
        minY -= PADDING;
        maxY += PADDING;
        minZ -= PADDING;
        maxZ += PADDING;

        boolean clamped = false;
        int[] x = clampAxis(minX, maxX);
        int[] y = clampAxis(minY, maxY);
        int[] z = clampAxis(minZ, maxZ);
        clamped = x[2] == 1 || y[2] == 1 || z[2] == 1;

        // The world's own limits come last, so they cannot be undone by the extent clamp. maxY()
        // is one past the top and this box's maxY is inclusive, hence the subtraction.
        int lowY = Math.max(y[0], world.minY());
        int highY = Math.min(y[1], world.maxY() - 1);
        if (highY < lowY) {
            highY = lowY;
        }

        return new ProbeBounds(x[0], lowY, z[0], x[1], highY, z[1], clamped);
    }

    /**
     * @return {@code {min, max, wasClamped}}, keeping the centre of the original span
     */
    private static int[] clampAxis(int min, int max) {
        int span = max - min + 1;
        if (span <= MAX_EXTENT) {
            return new int[] {min, max, 0};
        }
        int centre = min + span / 2;
        int half = MAX_EXTENT / 2;
        int newMin = centre - half;
        return new int[] {newMin, newMin + MAX_EXTENT - 1, 1};
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :runtime:test --tests '*ProbeBoundsTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Verify the dependency direction check accepts the new edges**

Run: `./gradlew checkDependencyDirection`
Expected: PASS. If it reports `:runtime` depends on something not allowed, Step 1's root `build.gradle.kts` edit is wrong or incomplete.

- [ ] **Step 7: Commit**

```bash
git add runtime/build.gradle.kts build.gradle.kts \
        runtime/src/main/java/dev/continuo/runtime/ProbeBounds.java \
        runtime/src/test/java/dev/continuo/runtime/ProbeBoundsTest.java
git commit -m "feat(probe): give :runtime the pathfinder, and bound the render box

:core-pathfinder and :core-movement are api dependencies; :movement-parkour is
runtimeOnly. Without the last one nothing puts the parkour movement on the game's
classpath - the adapters depend on :runtime and never on a movement module - and
the probe would request PARKOUR from a registry with nothing to grant it, running
the four built-ins while looking exactly like a search that exercised parkour.

ProbeBounds covers start, goal and route with padding, then clamps each axis to
64 blocks. It reports the clamp because a silently truncated map is
indistinguishable from a search that stopped for no reason."
```

---

### Task 4: `ProbeReport` and `PathProbe`

Spec §5.1, §5.2, §5.3. This is the deliverable the adapters call.

**Files:**
- Create: `runtime/src/main/java/dev/continuo/runtime/ProbeReport.java`
- Create: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Create: `runtime/src/test/java/dev/continuo/runtime/ProbeWorld.java`
- Create: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`

**Interfaces:**
- Consumes: `ProbeBounds.around(BlockSource, Pos, Pos, List<Pos>)` and its package-visible fields from Task 3; `PathRenderer.render(...)` from Task 2.
- Produces:
  - `public final class ProbeReport` with `public boolean ran()`, `public PathOutcome outcome()`, `public String summary()`, `public String map()`.
  - `public final class PathProbe` with `public PathProbe()`, `public PathProbe(int nodeBudget)`, `public static final int NODE_BUDGET = 10000`, `public void markGoal(int x, int y, int z)`, `public ProbeReport run(BlockSource world, int startX, int startY, int startZ)`.

- [ ] **Step 1: Write the test double**

Create `runtime/src/test/java/dev/continuo/runtime/ProbeWorld.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.BlockLegend;

import java.util.HashMap;
import java.util.Map;

/**
 * A settable world: a square stone floor at {@link #FLOOR_Y} with air above, plus whatever a
 * test puts on top of it.
 *
 * <p>Hand-built rather than parsed from text art, because {@code FixtureWorld} lives in
 * {@code :core-pathfinder}'s test sources and this module cannot see it. That is the seam
 * working rather than an inconvenience — the probe codes against {@code BlockSource} and so does
 * this.
 *
 * <p><b>The floor is finite, and that is load-bearing rather than tidy.</b> An unbounded floor
 * has no such thing as a blocked route: {@link #wallAcross} could span any number of blocks and
 * the search would simply walk around the end of it, so a test meaning to witness
 * {@code NO_PATH} would quietly witness {@code FOUND} by a longer road. {@link #RADIUS} gives
 * the world an edge for a wall to reach.
 */
final class ProbeWorld implements BlockSource {

    static final int FLOOR_Y = 63;
    static final int WALK_Y = 64;

    /** The floor spans {@code -RADIUS..RADIUS} on both horizontal axes, inclusive. */
    static final int RADIUS = 12;

    private final Map<Long, BlockData> overrides = new HashMap<Long, BlockData>();

    /** Puts a block at a position, replacing whatever the floor rule would give. */
    void put(int x, int y, int z, BlockData data) {
        overrides.put(Long.valueOf(key(x, y, z)), data);
    }

    /**
     * Builds a two-tall wall along the whole Z extent of the floor at one X, so it genuinely
     * separates the world rather than being something to walk around.
     */
    void wallAcross(int x) {
        for (int z = -RADIUS; z <= RADIUS; z++) {
            put(x, WALK_Y, z, BlockLegend.STONE);
            put(x, WALK_Y + 1, z, BlockLegend.STONE);
        }
    }

    @Override
    public BlockData at(int x, int y, int z) {
        BlockData override = overrides.get(Long.valueOf(key(x, y, z)));
        if (override != null) {
            return override;
        }
        if (x < -RADIUS || x > RADIUS || z < -RADIUS || z > RADIUS) {
            return BlockLegend.AIR;
        }
        return y == FLOOR_Y ? BlockLegend.STONE : BlockLegend.AIR;
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        return 128;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ (long) z;
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`:

```java
package dev.continuo.runtime;

import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathProbeTest {

    @Test
    void markingAGoalThenRunningFindsTheRoute() {
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.ran());
        assertEquals(PathOutcome.FOUND, report.outcome());
        assertTrue(report.summary().contains("FOUND"), report.summary());
        assertTrue(report.map().indexOf(PathRenderer.START) >= 0, report.map());
        assertTrue(report.map().indexOf(PathRenderer.GOAL) >= 0, report.map());
    }

    @Test
    void runningWithNoGoalMarkedIsReportedRatherThanThrown() {
        // The caller is inside the game loop. Global rule 3 makes a throw from there an adapter
        // fault, and "I forgot to press mark" is the most likely thing to happen in practice.
        ProbeReport report = new PathProbe().run(new ProbeWorld(), 0, ProbeWorld.WALK_Y, 0);

        assertFalse(report.ran());
        assertTrue(report.summary().contains("no goal marked"), report.summary());
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                report.map();
            }
        });
    }

    @Test
    void aWalledOffGoalReportsNoPathAndStillRendersTheMap() {
        // The case that most needs looking at, and the one a summary line cannot explain.
        // The wall spans the floor's whole Z extent: a partial wall on a finite floor is a
        // detour, not a barrier, and this test would then witness FOUND by a longer road while
        // still looking like it had witnessed NO_PATH.
        ProbeWorld world = new ProbeWorld();
        world.wallAcross(3);
        PathProbe probe = new PathProbe();
        probe.markGoal(6, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.NO_PATH, report.outcome());
        assertTrue(report.map().indexOf(PathRenderer.START) >= 0,
            "a failed render must still say where the search began\n" + report.map());
        assertTrue(report.map().indexOf(PathRenderer.GOAL) >= 0,
            "and where it was trying to get to\n" + report.map());
    }

    @Test
    void aTinyBudgetIsReportedAsBudgetExceededRatherThanAsNoPath() {
        // Distinguishing these two matters in-game more than it does in a fixture: one means
        // the world has no route and the other means the probe gave up, and the fix differs.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(3);
        probe.markGoal(40, ProbeWorld.WALK_Y, 40);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertEquals(PathOutcome.BUDGET_EXCEEDED, report.outcome());
        assertTrue(report.summary().contains("BUDGET_EXCEEDED"), report.summary());
    }

    @Test
    void aGoalBeyondTheRenderLimitProducesAMapThatSaysItWasClamped() {
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        ProbeReport report = probe.run(world, 0, ProbeWorld.WALK_Y, 0);

        assertTrue(report.map().contains("clamped"),
            "a truncated map must say so, or it reads as a search that stopped for no reason\n"
                + report.map());
        assertTrue(report.summary().contains("clamped"), report.summary());
    }

    @Test
    void theClampNoticeDoesNotBreakTheMapsHeader() {
        // The notice is appended as a "// " line rather than prepended, because the fixture
        // parser requires "origin:" on the first line and skips "//" lines. Prepending it would
        // make exactly the maps worth pasting back unparseable.
        ProbeWorld world = new ProbeWorld();
        PathProbe probe = new PathProbe(50);
        probe.markGoal(400, ProbeWorld.WALK_Y, 0);

        String map = probe.run(world, 0, ProbeWorld.WALK_Y, 0).map();

        assertTrue(map.startsWith("origin:"), map.substring(0, Math.min(80, map.length())));
    }

    @Test
    void theParkourMovementIsOnTheClasspathTheProbeSearchesWith() {
        // Spec 5.3. The probe requests Capability.PARKOUR, and a registry with nothing to grant
        // it behaves identically to one that exercises it - the same "reads as a pass, checked
        // nothing" shape MovementContract was fixed twice to close. The adapters reach parkour
        // only through :runtime's runtimeOnly dependency, so this guards the wiring that makes
        // the capability mean anything in-game.
        List<IMovementType> active = AStarPathfinder.defaultRegistry()
            .activeFor(CapabilitySet.of(Capability.PARKOUR)).movements();

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < active.size(); i++) {
            ids.add(active.get(i).id());
        }

        assertTrue(ids.contains("walk.parkour"),
            "walk.parkour is not in the set the probe searches with, so the probe would request"
                + " a capability nothing supplies and report success either way; got " + ids);
    }
}
```

Add these imports to `PathProbeTest.java`:

```java
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;

import java.util.ArrayList;
import java.util.List;
```

**This expression is verified, not guessed:** it is the same call `ParkourOptimalityTest.aStarMatchesDijkstraOverManyRandomWorldsWithParkourActive` already makes in `:movement-parkour`. `defaultRegistry()` returns a `MovementRegistry`; the enumeration goes through `activeFor(CapabilitySet).movements()`, which is exactly the set `PathProbe` searches with.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: FAIL to compile — `cannot find symbol: class PathProbe`.

- [ ] **Step 4: Implement `ProbeReport`**

Create `runtime/src/main/java/dev/continuo/runtime/ProbeReport.java`:

```java
package dev.continuo.runtime;

import dev.continuo.pathfinder.PathOutcome;

/**
 * What one probe run produced.
 *
 * <p>Two channels, because they answer different questions. {@link #summary()} is the one line
 * that goes to the log and tells you whether to bother looking; {@link #map()} is the text art
 * that tells you whether the route is sane.
 *
 * <p><b>A run that never happened is a first-class state, not an error.</b> Pressing the path key
 * before the mark key is the most likely thing to happen in practice, and the caller is inside
 * the game loop where a throw would be an adapter fault under global rule 3. {@link #ran()} is
 * false in that case and {@link #summary()} explains it; {@link #outcome()} and {@link #map()}
 * throw, because there is genuinely nothing for them to return and a placeholder would be worse
 * than a refusal.
 */
public final class ProbeReport {

    private final boolean ran;
    private final PathOutcome outcome;
    private final String summary;
    private final String map;

    private ProbeReport(boolean ran, PathOutcome outcome, String summary, String map) {
        this.ran = ran;
        this.outcome = outcome;
        this.summary = summary;
        this.map = map;
    }

    /** @param summary why no search happened; never {@code null} */
    static ProbeReport notRun(String summary) {
        return new ProbeReport(false, null, summary, null);
    }

    static ProbeReport of(PathOutcome outcome, String summary, String map) {
        return new ProbeReport(true, outcome, summary, map);
    }

    /** @return whether a search actually ran */
    public boolean ran() {
        return ran;
    }

    /**
     * @return what the search returned
     * @throws IllegalStateException if no search ran
     */
    public PathOutcome outcome() {
        if (!ran) {
            throw new IllegalStateException("no search ran: " + summary);
        }
        return outcome;
    }

    /** @return one line for the log; never {@code null} */
    public String summary() {
        return summary;
    }

    /**
     * @return the text-art map, ending in a newline
     * @throws IllegalStateException if no search ran
     */
    public String map() {
        if (!ran) {
            throw new IllegalStateException("no search ran: " + summary);
        }
        return map;
    }
}
```

- [ ] **Step 5: Implement `PathProbe`**

Create `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`:

```java
package dev.continuo.runtime;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathRenderer;
import dev.continuo.pathfinder.PathResult;
import dev.continuo.pathfinder.Pos;

/**
 * Runs A\u002A against a live world and renders the result, so a route can be looked at in a
 * running game.
 *
 * <p>Dev-only, like {@code BlockDumpWalker} beside it. Nothing calls it during normal operation.
 *
 * <p><b>Main thread only.</b> A live {@code BlockSource} inherits {@code IBlockView}'s delivery
 * window, and this reads through it synchronously. That is also why C3's {@code WorldSnapshot} is
 * not a prerequisite: a snapshot is what makes reads safe <em>off</em> the main thread, and
 * nothing here leaves it.
 *
 * <p>The mark-then-run shape is deliberate: it lets an owner walk to somewhere awkward, mark it,
 * walk back, and search across terrain they chose, without any new SPI surface for naming a
 * destination.
 */
public final class PathProbe {

    /**
     * The node budget a probe uses when none is given.
     *
     * <p>Far below {@code AStarPathfinder.DEFAULT_NODE_BUDGET}, and for a different reason. That
     * figure was chosen as far below anything that would hang a <em>test</em>; this one runs on
     * the client thread of a running game, where a hundred thousand expansions against live
     * block reads is a multi-second freeze. It is a stall guard, not a search-effort policy —
     * C4 owns the policy and this must not pretend to.
     */
    public static final int NODE_BUDGET = 10000;

    private final int nodeBudget;

    private Pos goal;

    /** Uses {@link #NODE_BUDGET}. */
    public PathProbe() {
        this(NODE_BUDGET);
    }

    /**
     * @param nodeBudget the search budget; must be positive
     */
    public PathProbe(int nodeBudget) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive, got " + nodeBudget);
        }
        this.nodeBudget = nodeBudget;
    }

    /**
     * Records where a later {@link #run} should path to. Replaces any previous mark.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     */
    public void markGoal(int x, int y, int z) {
        this.goal = new Pos(x, y, z);
    }

    /**
     * Searches from a position to the marked goal.
     *
     * @param world the world to read; never {@code null}
     * @param startX where the search begins
     * @param startY where the search begins
     * @param startZ where the search begins
     * @return the report; never {@code null}, and never throwing merely because no goal is
     *         marked
     */
    public ProbeReport run(BlockSource world, int startX, int startY, int startZ) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (goal == null) {
            return ProbeReport.notRun("Continuo path probe: no goal marked."
                + " Stand on the destination, press the mark key, then try again.");
        }

        Pos start = new Pos(startX, startY, startZ);
        PathResult result = new AStarPathfinder(nodeBudget).findPath(
            world, startX, startY, startZ,
            new GoalBlock(goal.x(), goal.y(), goal.z()),
            CapabilitySet.of(Capability.PARKOUR));

        ProbeBounds bounds = ProbeBounds.around(world, start, goal, result.path());
        StringBuilder map = new StringBuilder(PathRenderer.render(world,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            start, goal, result));

        StringBuilder summary = new StringBuilder();
        summary.append("Continuo path probe: ").append(result.outcome())
            .append(", ").append(result.path().size()).append(" steps")
            .append(", ").append(result.nodesExpanded()).append(" expanded")
            .append(", cost ").append(result.cost())
            .append(", ").append(start).append(" -> ").append(goal)
            .append(", budget ").append(nodeBudget);

        if (bounds.clamped) {
            String notice = "the map is clamped to " + ProbeBounds.MAX_EXTENT
                + " blocks per axis, so terrain outside it is not drawn";
            summary.append("; ").append(notice);
            // Appended as a comment line rather than prepended, because the fixture parser
            // requires "origin:" on the first line and skips "//" lines. Prepending it would
            // make exactly the maps worth pasting back unparseable.
            map.append("// ").append(notice).append('\n');
        }

        return ProbeReport.of(result.outcome(), summary.toString(), map.toString());
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 7: Prove the parkour-classpath guard fails on broken code**

Temporarily comment out `runtimeOnly(project(":movement-parkour"))` in `runtime/build.gradle.kts`.

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: FAIL on `theParkourMovementIsOnTheClasspathTheProbeSearchesWith`. **Paste the output.** This is the guard that stops the probe silently searching without the movement it claims to exercise. If it passes, the dependency is reaching the test classpath by some other route — **report that** rather than adjusting the test.

Restore the line.

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/ProbeReport.java \
        runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/ProbeWorld.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java
git commit -m "feat(probe): add PathProbe and ProbeReport

Mark a goal, run A* from wherever you stand, get a one-line verdict and a
text-art map. Runs synchronously on the main thread, which is why C3's
WorldSnapshot is not a prerequisite - a snapshot makes reads safe off-thread and
nothing here leaves it.

Running with no goal marked is a reported state rather than a throw: the caller
is inside the game loop, where global rule 3 makes a throw an adapter fault, and
pressing the keys out of order is the likeliest thing to happen.

The clamp notice is appended as a // line rather than prepended, so the maps most
worth pasting back as fixtures still parse."
```

---

### Task 5: 1.21.11 Fabric adapter wiring

Spec §6. Two keybinds, one poll, no judgment.

**Files:**
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`

**Interfaces:**
- Consumes: `PathProbe`, `ProbeReport` from Task 4; `ContinuoCore.blocks()` returning `BlockLookup`, which implements `BlockSource`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Promote the core to a field and add the probe**

The core is currently a local `final ContinuoCore core` inside `onInitializeClient`. The new poll needs it, so add fields beside the existing `walkKey` / `dumpKey` / `runtime`:

```java
    private KeyMapping markKey;
    private KeyMapping pathKey;
    private ContinuoCore core;
    private final PathProbe probe = new PathProbe();
```

and change the local declaration from `final ContinuoCore core = new ContinuoCore();` to `core = new ContinuoCore();`.

**No local alias is needed.** The existing walk lambda captures `core`, and once `core` is a field that reference compiles to `this.core` — fields have no effective-finality requirement. Only locals do. If the compiler disagrees, report that rather than working around it.

Add imports:

```java
import dev.continuo.runtime.PathProbe;
import dev.continuo.runtime.ProbeReport;
```

- [ ] **Step 2: Register the two keybinds**

After the existing `dumpKey` registration, add:

```java
        // Dev-only, like the dump key: mark a destination, then path to it from wherever you
        // stand. Unrelated to the four global rules, so polled separately below.
        markKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.mark",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            category
        ));
        pathKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.path",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            category
        ));
```

- [ ] **Step 3: Add the poll**

After the existing dump-key `END_CLIENT_TICK` registration, add:

```java
        // Polled on END for the same reason the dump is: the read reflects state after this
        // tick's core processing has settled. The core's own BlockLookup is used rather than a
        // fresh one, so the classification memo is shared and its level-transition lifecycle is
        // the one ContinuoCore.stop() already discharges.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            try {
                BlockPos at = client.player.blockPosition();
                if (markKey.consumeClick()) {
                    probe.markGoal(at.getX(), at.getY(), at.getZ());
                    LOGGER.info("Continuo: path goal marked at {} {} {}",
                        at.getX(), at.getY(), at.getZ());
                }
                if (pathKey.consumeClick()) {
                    ProbeReport report = probe.run(
                        core.blocks(), at.getX(), at.getY(), at.getZ());
                    LOGGER.info(report.summary());
                    if (report.ran()) {
                        Path out = client.gameDirectory.toPath()
                            .resolve("continuo-path-probe.txt");
                        Files.write(out, report.map().getBytes(StandardCharsets.UTF_8));
                        LOGGER.info("Continuo: wrote path probe map to {}", out);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Continuo: the path probe failed", e);
            }
        });
```

- [ ] **Step 4: Compile the adapter**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:build`
Expected: BUILD SUCCESSFUL. There are no tests here and cannot be — this step checks compilation only.

- [ ] **Step 5: Commit**

```bash
git add adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java
git commit -m "feat(probe): wire the path probe into the 1.21.11 adapter

H marks a goal at the player's feet, L paths to it from wherever they stand. Both
polled on END alongside the dump key, and both free in vanilla - Continuo already
claims K for the walk and J for the dump.

Reads through ContinuoCore.blocks() rather than a fresh BlockLookup, so the
classification memo is shared and the level-transition lifecycle is the one
ContinuoCore.stop() already discharges."
```

---

### Task 6: 1.7.10 Forge adapter wiring

Spec §6. Same behaviour, different API. **No lambdas** — this module compiles against a Java 6-era Forge and the surrounding code uses anonymous classes throughout.

**Files:**
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java`

**Interfaces:**
- Consumes: `PathProbe`, `ProbeReport` from Task 4.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add fields**

Beside the existing `walkKey`, `dumpKey`, `runtime`, `context`:

```java
    private KeyBinding markKey;
    private KeyBinding pathKey;
    private ContinuoCore core;
    private final PathProbe probe = new PathProbe();
```

Change `final ContinuoCore core = new ContinuoCore();` in `init` to `core = new ContinuoCore();`.

**No local alias is needed.** The existing anonymous `Runnable` calls `core.requestWalk()`; once `core` is a field, that compiles to `ContinuoForgeMod.this.core`. The effective-finality rule binds captured locals, not fields. If the compiler disagrees, report that rather than working around it.

Add imports:

```java
import dev.continuo.runtime.PathProbe;
import dev.continuo.runtime.ProbeReport;
```

- [ ] **Step 2: Register the two keybinds**

After the existing `dumpKey` registration in `init`:

```java
        // Dev-only, like the dump key: mark a destination, then path to it from wherever you
        // stand. H and L are free in vanilla and clear of Continuo's existing K and J.
        markKey = new KeyBinding("key.continuo.mark", Keyboard.KEY_H, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(markKey);
        pathKey = new KeyBinding("key.continuo.path", Keyboard.KEY_L, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(pathKey);
```

- [ ] **Step 3: Poll it from the tick handler**

In `onClientTick`, add a call beside the existing `pollDumpKey(client)` in the `else` branch:

```java
            pollDumpKey(client);
            pollProbeKeys(client);
```

Then add the method beside `pollDumpKey`:

```java
    /**
     * Polls the mark and path keys and, on a click, marks a goal or runs the probe.
     *
     * <p>Runs inside a tick callback, so the whole operation is wrapped in a single
     * {@code try}/{@code catch} for the same reason {@link #pollDumpKey} is: a dev tool that
     * kills the client on a bad region is worse than one that logs and carries on.
     *
     * <p>Reads through {@code ContinuoCore.blocks()} rather than a fresh {@code BlockLookup}, so
     * the classification memo is shared and its level-transition lifecycle is the one
     * {@code ContinuoCore.stop()} already discharges.
     */
    private void pollProbeKeys(Minecraft client) {
        if (client.thePlayer == null) {
            return;
        }
        try {
            int px = MathHelper.floor_double(client.thePlayer.posX);
            int py = MathHelper.floor_double(client.thePlayer.posY);
            int pz = MathHelper.floor_double(client.thePlayer.posZ);

            if (markKey.isPressed()) {
                probe.markGoal(px, py, pz);
                LOGGER.info("Continuo: path goal marked at " + px + " " + py + " " + pz);
            }
            if (pathKey.isPressed()) {
                ProbeReport report = probe.run(core.blocks(), px, py, pz);
                LOGGER.info(report.summary());
                if (report.ran()) {
                    File out = new File(client.mcDataDir, "continuo-path-probe.txt");
                    OutputStream stream = null;
                    try {
                        stream = new FileOutputStream(out);
                        stream.write(report.map().getBytes("UTF-8"));
                    } finally {
                        if (stream != null) {
                            try {
                                stream.close();
                            } catch (IOException ignored) {
                                // Already written or already failed; nothing useful to do.
                            }
                        }
                    }
                    LOGGER.info("Continuo: wrote path probe map to " + out.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Continuo: the path probe failed", e);
        }
    }
```

- [ ] **Step 4: Compile the adapter**

Run: `./gradlew :adapters:adapter-forge-1.7.10:build`
Expected: BUILD SUCCESSFUL. **Do not run `./gradlew clean`** — it destroys the decompiled 1.7.10 sources this module compiles against.

- [ ] **Step 5: Commit**

```bash
git add adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java
git commit -m "feat(probe): wire the path probe into the 1.7.10 adapter

Same behaviour as the 1.21.11 adapter, in anonymous-class style - this module
compiles against a decompiled Java 6-era Forge and the surrounding code uses no
lambdas.

The core is promoted from a local to a field so the new poll can reach it, with
the existing walk Runnable capturing an effectively-final local instead."
```

---

### Task 7: Whole-build verification against the spec's done criteria

No new code. This task exists because the done criteria demand a stated test count, and because a filtered run cannot produce one.

**Files:** none.

**Interfaces:** none.

- [ ] **Step 1: Full cold build**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL.

Do **not** use `./gradlew clean`. Do **not** run this after a filtered `--tests` run without `--rerun-tasks`, or the counts read low.

- [ ] **Step 2: Count the tests**

```bash
python -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob('*/build/test-results/test/*.xml')+glob.glob('*/*/build/test-results/test/*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print('tests',t,'failures',f,'errors',e,'skipped',s)"
```

Expected: 0 failures, 0 errors. The baseline before this plan is **361**; this plan adds 4 (`BlockLegendTest`) + 3 (`PathRendererTest`) + 5 (`ProbeBoundsTest`) + 7 (`PathProbeTest`) = **19**, for an expected **380**. If the number differs, say so and account for the difference rather than restating the expectation.

- [ ] **Step 3: Check the dependency direction explicitly**

Run: `./gradlew checkDependencyDirection`
Expected: PASS.

- [ ] **Step 4: Walk the spec's done criteria**

Open `docs/superpowers/specs/2026-08-24-in-game-path-probe-design.md` §8 and confirm each of criteria 1–5 against the tree. Criterion 6 — the owner running it in a real game — **cannot be discharged by an agent.** Report it as outstanding; do not claim the work is done.

- [ ] **Step 5: Report**

State the test count, the build result, and that criterion 6 is outstanding and belongs to the owner.

---

## Self-Review

**Spec coverage.** §1.1 in-scope items: the probe (Task 4), the renderer promotion (Tasks 1–2), the two keybinds per adapter (Tasks 5–6), the headless tests (Tasks 3–4). §3 module layout and the allowlist: Task 3. §4.1 signature and the `maxY` trap: Task 2. §4.2 one legend: Task 1. §4.3 the `?` degradation: Task 2 Step 1. §5.1 shape: Task 4. §5.2 budget: Task 4. §5.3 parkour and its classpath guard: Tasks 3 and 4. §5.4 threading: documented on `PathProbe`, no test — it is a claim about where the code runs, which no headless test can witness. §5.5 bounds: Task 3. §6 adapters: Tasks 5–6. §7 test table: every row has a task. §8 done criteria: Task 7.

**Gap found and closed.** The spec's §7 table lists "the existing renderer suite still passes through the delegating `FixtureWorld` form" — Task 2 Step 6 covers it, but only implicitly. Task 2 Step 5 now names the eight call sites explicitly so an implementer cannot half-convert them.

**Placeholder scan.** No TBD, no "add error handling", no "similar to Task N". Every code step carries the code. One API name was initially left for the implementer to confirm — `defaultRegistry().movements()` — and has since been verified against `ParkourOptimalityTest`, which makes the same call: the enumeration is `defaultRegistry().activeFor(CapabilitySet).movements()`, and the plan now carries that rather than a deferral.

**Type consistency.** `BlockLegend.characterFor(BlockData)` returns `char` — used as such in `PathRenderer` (Task 2) and asserted as such in Task 1. `ProbeBounds` fields are package-visible and read directly as `bounds.minX` in Task 4; declared that way in Task 3. `ProbeReport.of` and `notRun` are package-private static factories called only from `PathProbe`, same package. `PathProbe.run` takes `(BlockSource, int, int, int)` in Task 4 and is called with `core.blocks()` in Tasks 5–6, where `BlockLookup implements BlockSource`. `PathRenderer.render` takes ten arguments in Task 2 and is called with ten in Task 4.
