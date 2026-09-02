# D1 — SPI player state and look actuation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the core a way to read where the player is and a way to point it, and settle the
edge- vs level-triggered actuation question deferred from M1 — so that D2 can build a path executor.

**Architecture:** One new SPI interface (`IPlayerView`, pulled, on the `IBlockView` pattern), one new
method on `IActuator` (`setLook`), one rewritten global rule, and `PathProbe` as the only consumer.
No new module, no new dependency, no mixin, no coremod, no new `IGameEvents` method.

**Tech Stack:** Java 8 core / Java 21 Fabric adapter, Gradle 9.6.1, JUnit 5, Fabric Loom 1.17.17,
RetroFuturaGradle for Forge 1.7.10.

**Spec:** [`docs/superpowers/specs/2026-09-02-d1-spi-player-state-design.md`](../specs/2026-09-02-d1-spi-player-state-design.md)

## Global Constraints

These bind every task. Read them before starting any one of them.

- **Report discrepancies; do not adjust.** If a brief here does not match what you find in the
  repository, **stop and report it**. A plan that does not match reality is a bug in the plan, not a
  puzzle for you to solve. Nine defects in the C5 spec and plan were caught this way.
- **Java 8 language level, and no lambdas in main source**, for `platform`, `core`, `core-pathfinder`,
  `core-movement`, `movement-parkour`, `runtime`, `platform-testkit`, and
  `adapters/adapter-forge-1.7.10`. Use anonymous classes. `adapters/adapter-fabric-1.21.11` is Java
  21 and its existing code uses lambdas; match what is already in the file you are editing.
- **Gate on `./gradlew build`, never `./gradlew :test`.** Javadoc is build-failing and `:test` does
  not run it, so a green `:test` can hide a broken build.
- **Never run `./gradlew clean`.** It destroys the decompiled 1.7.10 sources under
  `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/`, which take a long time to regenerate and
  which several tasks here need for verification.
- **`GRADLE_USER_HOME` is `C:\GradleHome`**, not `~/.gradle`.
- **Do not run filtered Gradle test invocations when counting tests.** They corrupt the XML counts.
  Count after a full run by summing all `TEST-*.xml`.
- **Adapters have no tests and cannot get any.** Review is their only gate. Every Minecraft API claim
  must be checked against the decompiled sources on disk — 1.7.10 under
  `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`, 1.21.11 under
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/sources`.
- **There is no unused-import linter** — no checkstyle, spotless, PMD or error-prone, and javac does
  not warn. If you remove the last use of an import, remove the import by hand.
- **CI and the remote are off-limits.** Do not touch `.github/workflows/ci.yml`, do not push, and do
  not `git pull` — `master` is local-only.
- **Commit with explicit paths.** Never `git add -A`.

---

## File Structure

**Created**

| file | responsibility |
|---|---|
| `platform/src/main/java/dev/continuo/platform/IPlayerView.java` | the six facts the core may read about the player |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakePlayerView.java` | a settable `IPlayerView` for headless tests |
| `platform-testkit/src/test/java/dev/continuo/testkit/FakePlatformContextTest.java` | pins the fourth accessor's same-instance clause |
| `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlayerView.java` | 1.21.11 translation |
| `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlayerView.java` | 1.7.10 translation — **the `boundingBox.minY` file** |

**Modified**

| file | change |
|---|---|
| `platform/src/main/java/dev/continuo/platform/IPlatformContext.java` | fourth accessor `player()` |
| `platform/src/main/java/dev/continuo/platform/IActuator.java` | `setLook`; rule 4 deferral paragraph rewritten |
| `platform/src/main/java/dev/continuo/platform/IGameEvents.java` | M5 forward reference removed |
| `platform/src/main/java/dev/continuo/platform/package-info.java` | global rule 4 rewritten |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java` | `player()` |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java` | records `setLook` calls |
| `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java` | rule 4 javadoc note |
| `core/src/main/java/dev/continuo/core/ContinuoCore.java` | level-triggered walk |
| `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java` | two tests rewritten, two added |
| `runtime/src/main/java/dev/continuo/runtime/PathProbe.java` | start from `IPlayerView`; two self-checks |
| `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java` | tests for both |
| both `*PlatformContext.java` | `player()` |
| both `*Actuator.java` | `setLook` |
| both `Continuo*Mod.java` | probe wiring |

---

## Task 1: `IPlayerView` and the fourth context accessor

**Files:**
- Create: `platform/src/main/java/dev/continuo/platform/IPlayerView.java`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/FakePlayerView.java`
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlayerView.java`
- Create: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlayerView.java`
- Modify: `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlatformContext.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlatformContext.java`
- Test: `platform-testkit/src/test/java/dev/continuo/testkit/FakePlatformContextTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `dev.continuo.platform.IPlayerView` with `double x()`, `double y()`, `double z()`,
  `float yaw()`, `float pitch()`, `boolean onGround()`. `IPlatformContext.player()` returning it.
  `dev.continuo.testkit.FakePlayerView` with a `set(double x, double y, double z, float yaw,
  float pitch, boolean onGround)` mutator, and `FakePlatformContext.fakePlayerView()`.

**Why this task is one unit:** adding a method to `IPlatformContext` breaks all three of its
implementers at compile time. They must move together or the build is red between tasks.

- [ ] **Step 1: Write the failing test**

Create `platform-testkit/src/test/java/dev/continuo/testkit/FakePlatformContextTest.java`:

```java
package dev.continuo.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins both of {@code IPlatformContext}'s accessor clauses for the accessor D1 adds: MUST NOT
 * return {@code null}, and MUST return the same instance every call.
 *
 * <p>Together they are what lets the core cache what an accessor returns. Both have always been
 * stated and neither was asserted; the fourth accessor is a cheap place to start.
 *
 * <p><b>The null assertions are load-bearing, not belt-and-braces.</b> {@code assertSame(null, null)}
 * passes, so a same-instance test alone is satisfied by an accessor that returns {@code null} every
 * time — it would pin one clause while appearing to pin two.
 */
class FakePlatformContextTest {

    @Test
    void playerReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        // Asserted before assertSame, not after: assertSame(null, null) passes, so without this
        // the test would be satisfied by an accessor that returns null every time -- pinning one
        // of IPlatformContext's two clauses while appearing to pin both.
        assertNotNull(ctx.player());
        assertSame(ctx.player(), ctx.player());
        assertSame(ctx.fakePlayerView(), ctx.player());
    }

    @Test
    void everyAccessorReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertNotNull(ctx.actuator());
        assertNotNull(ctx.info());
        assertNotNull(ctx.blocks());
        assertSame(ctx.actuator(), ctx.actuator());
        assertSame(ctx.info(), ctx.info());
        assertSame(ctx.blocks(), ctx.blocks());
    }

    @Test
    void theFakePlayerViewReportsWhatWasSet() {
        FakePlatformContext ctx = new FakePlatformContext();

        ctx.fakePlayerView().set(1.5, 64.0, -2.25, 90.0f, -12.5f, true);

        assertEquals(1.5, ctx.player().x());
        assertEquals(64.0, ctx.player().y());
        assertEquals(-2.25, ctx.player().z());
        assertEquals(90.0f, ctx.player().yaw());
        assertEquals(-12.5f, ctx.player().pitch());
        assertTrue(ctx.player().onGround());
    }

    @Test
    void theFakePlayerViewStartsAtTheOrigin() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertEquals(0.0, ctx.player().x());
        assertEquals(0.0, ctx.player().y());
        assertEquals(0.0, ctx.player().z());
        assertEquals(0.0f, ctx.player().yaw());
        assertEquals(0.0f, ctx.player().pitch());
        assertFalse(ctx.player().onGround());
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails for the right reason**

Run: `./gradlew :platform-testkit:test`
Expected: **compilation failure** — `player()` and `fakePlayerView()` do not exist. A compile
failure is the correct "red" here; there is nothing to assert against yet.

- [ ] **Step 3: Create `IPlayerView`**

Create `platform/src/main/java/dev/continuo/platform/IPlayerView.java`:

```java
package dev.continuo.platform;

/**
 * Reads the local player's position and facing from the live game.
 *
 * <p>Note the direction: the adapter implements this and the core calls it — the same direction as
 * {@link IBlockView}, and the opposite of {@link IGameEvents}.
 *
 * <p><b>Call window.</b> Every method here MUST only be called while
 * {@link IGameEvents#onClientTick}'s delivery window is open — a world loaded and a local player
 * present. Outside that window the behaviour is unspecified. This deliberately reuses that existing
 * condition rather than stating a new one, so there is nothing extra for an adapter to evaluate or
 * get wrong.
 *
 * <p><b>Values describe the current tick and MUST NOT be cached across ticks.</b> A core that wants
 * the previous tick's position stores it itself.
 *
 * <p>Subject to all four global rules in this package's documentation, in particular rule 1: these
 * are main-thread calls and no implementation may block.
 *
 * <p><b>The field budget is deliberately six.</b> Velocity, eye height, bounding-box dimensions and
 * fluid state are all natively available on both target versions and are all deliberately absent,
 * because nothing consumes them yet. This package's documentation says every type added here is a
 * future version-compatibility problem; the same applies to every method. Widening this interface
 * when a real consumer appears is a two-file change.
 */
public interface IPlayerView {

    /**
     * The player's X, in world coordinates.
     *
     * @return the X coordinate of the centre of the player's collision box
     */
    double x();

    /**
     * The player's Y, in world coordinates — <b>the feet</b>.
     *
     * <p><b>Defined as the bottom of the player's collision box</b>, not as "the player's Y", which
     * is ambiguous and resolves differently on the two target versions.
     *
     * <p><b>Caveat — 1.7.10's obvious field is the wrong one.</b> On 1.7.10 {@code Entity.posY} is
     * the <i>stance</i>, 1.62 blocks above the feet: {@code setPosition} computes the collision box
     * as {@code minY = posY - yOffset + ySize} with {@code EntityPlayer.yOffset = 1.62F}, and the
     * client's own movement packet sends {@code boundingBox.minY} as its feet and {@code posY} as
     * its stance. A conformant 1.7.10 implementation therefore returns
     * {@code boundingBox.minY}, <b>not {@code posY}</b>. On 1.21.11 {@code getY()} is already the
     * feet and needs no adjustment.
     *
     * <p>Getting this wrong is a silent 1.62-block error on exactly one version, with nothing in the
     * build able to catch it.
     *
     * @return the Y coordinate of the bottom of the player's collision box
     */
    double y();

    /**
     * The player's Z, in world coordinates.
     *
     * @return the Z coordinate of the centre of the player's collision box
     */
    double z();

    /**
     * Which way the player is facing, in degrees.
     *
     * <p><b>The convention is a fact about both target versions, not one this SPI invents.</b> Both
     * derive movement direction from yaw by identical arithmetic — {@code Δ = (−sin yaw, cos yaw)}
     * for forward motion — so on both:
     *
     * <ul>
     *   <li>{@code 0} faces <b>+Z</b>
     *   <li>{@code 90} faces <b>−X</b>
     *   <li>{@code 180} faces <b>−Z</b>
     *   <li>{@code 270} faces <b>+X</b>
     * </ul>
     *
     * <p><b>Not normalised.</b> This returns whatever the platform currently holds, which may be any
     * finite value, including one outside {@code [-180, 180)}. A core needing a normalised angle
     * normalises it; requiring the adapter to do so would be arithmetic in an adapter for no gain.
     *
     * @return the yaw in degrees, unnormalised
     */
    float yaw();

    /**
     * How far up or down the player is looking, in degrees.
     *
     * <p>{@code -90} is straight up, {@code 0} is the horizon, {@code +90} is straight down, on both
     * target versions.
     *
     * @return the pitch in degrees
     */
    float pitch();

    /**
     * Whether the platform considers the player to be standing on something.
     *
     * <p>Reported, not computed: both target versions maintain their own ground flag and this
     * returns it. The two have not been audited against each other to the depth {@link #y()} has —
     * if they are found to disagree about, say, standing on a fence, that is a per-version note
     * rather than a reason for either adapter to start deciding.
     *
     * @return the platform's own ground flag
     */
    boolean onGround();
}
```

- [ ] **Step 4: Add the accessor to `IPlatformContext`**

In `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`, change the class javadoc
line that reads:

```java
 * <p>Valid for the adapter's entire lifetime. All three accessors MUST NOT return {@code null},
```

to:

```java
 * <p>Valid for the adapter's entire lifetime. All four accessors MUST NOT return {@code null},
```

and add this method after `blocks()`:

```java
    /**
     * The player reader for this platform.
     *
     * <p>Like {@link #blocks()}, the returned instance is valid for the adapter's lifetime and
     * internally reads whichever local player is current, so the core may cache it — but its
     * <em>methods</em> may only be called while {@link IGameEvents#onClientTick}'s delivery window
     * is open. See {@link IPlayerView}.
     *
     * @return the player view; never {@code null}, and the same instance on every call
     */
    IPlayerView player();
```

- [ ] **Step 5: Create `FakePlayerView` and wire it into `FakePlatformContext`**

Create `platform-testkit/src/main/java/dev/continuo/testkit/FakePlayerView.java`:

```java
package dev.continuo.testkit;

import dev.continuo.platform.IPlayerView;

/** A settable {@link IPlayerView}. Starts at the origin, facing {@code +Z}, not on the ground. */
public final class FakePlayerView implements IPlayerView {

    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean onGround;

    /** Sets every field at once, so a test cannot leave a stale half-state behind. */
    public void set(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public boolean onGround() {
        return onGround;
    }
}
```

In `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java`, add the import
`dev.continuo.platform.IPlayerView`, the field, the accessor and the getter:

```java
    private final FakePlayerView playerView = new FakePlayerView();
```

```java
    @Override
    public IPlayerView player() {
        return playerView;
    }

    public FakePlayerView fakePlayerView() {
        return playerView;
    }
```

- [ ] **Step 6: Create `FabricPlayerView`**

Create
`adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlayerView.java`:

```java
package dev.continuo.adapter.fabric;

import dev.continuo.platform.IPlayerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Translates the local player's state into the SPI's vocabulary.
 *
 * <p>Pure translation: a method maps to an accessor. No decision is made here. If this class ever
 * grows a conditional that changes behaviour rather than resolving a name or guarding a null, that
 * logic belongs in the core.
 *
 * <p>{@code getY()} is already the bottom of the collision box on this version, so
 * {@link IPlayerView#y()}'s feet definition needs no adjustment here. The 1.7.10 adapter is where
 * that costs something.
 */
final class FabricPlayerView implements IPlayerView {

    private final Minecraft minecraft;

    FabricPlayerView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public double x() {
        return player().getX();
    }

    @Override
    public double y() {
        return player().getY();
    }

    @Override
    public double z() {
        return player().getZ();
    }

    @Override
    public float yaw() {
        return player().getYRot();
    }

    @Override
    public float pitch() {
        return player().getXRot();
    }

    @Override
    public boolean onGround() {
        return player().onGround();
    }

    /**
     * The one guard in this class. {@link IPlayerView}'s call window excludes the case where this
     * throws, so reaching it is a caller's contract violation rather than a state to handle.
     */
    private LocalPlayer player() {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException(
                "IPlayerView was read outside onClientTick's delivery window: no local player");
        }
        return player;
    }
}
```

- [ ] **Step 7: Create `ForgePlayerView` — the riskiest file in D1**

Create
`adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlayerView.java`:

```java
package dev.continuo.adapter.forge;

import dev.continuo.platform.IPlayerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;

/**
 * Translates the local player's state into the SPI's vocabulary.
 *
 * <p>Pure translation: a method maps to a field. No decision is made here. If this class ever grows
 * a conditional that changes behaviour rather than resolving a name or guarding a null, that logic
 * belongs in the core.
 */
final class ForgePlayerView implements IPlayerView {

    private final Minecraft minecraft;

    ForgePlayerView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public double x() {
        return player().posX;
    }

    /**
     * <b>{@code boundingBox.minY}, deliberately, and never {@code posY}.</b>
     *
     * <p>{@link IPlayerView#y()} is defined as the bottom of the collision box. On this version
     * {@code posY} is the <i>stance</i> — 1.62 blocks higher — because {@code Entity.setPosition}
     * computes {@code minY = posY - yOffset + ySize} and {@code EntityPlayer.yOffset} is
     * {@code 1.62F}. The game's own network code settles which is which: {@code EntityClientPlayerMP}
     * sends {@code (posX, boundingBox.minY, posY, posZ)} and the protocol reads those as
     * X / feetY / stance / Z.
     *
     * <p>Using {@code posY} here would put every search start 1.62 blocks above the ground, inside
     * the player's own head, on this version only — and no test in this repository can catch it.
     */
    @Override
    public double y() {
        return player().boundingBox.minY;
    }

    @Override
    public double z() {
        return player().posZ;
    }

    @Override
    public float yaw() {
        return player().rotationYaw;
    }

    @Override
    public float pitch() {
        return player().rotationPitch;
    }

    @Override
    public boolean onGround() {
        return player().onGround;
    }

    /**
     * The one guard in this class. {@link IPlayerView}'s call window excludes the case where this
     * throws, so reaching it is a caller's contract violation rather than a state to handle.
     */
    private EntityClientPlayerMP player() {
        EntityClientPlayerMP player = minecraft.thePlayer;
        if (player == null) {
            throw new IllegalStateException(
                "IPlayerView was read outside onClientTick's delivery window: no local player");
        }
        return player;
    }
}
```

- [ ] **Step 8: Wire both platform contexts**

In `FabricPlatformContext.java`: add `import dev.continuo.platform.IPlayerView;`, the field
`private final IPlayerView playerView;`, `this.playerView = new FabricPlayerView(minecraft);` in the
constructor, and:

```java
    @Override
    public IPlayerView player() {
        return playerView;
    }
```

Make the identical change in `ForgePlatformContext.java`, constructing `new ForgePlayerView(minecraft)`.

- [ ] **Step 9: Verify the Minecraft API claims against the decompiled sources**

Adapters have no tests; this step is their gate. Confirm each of these, and **report rather than
adjust** if any does not hold:

```bash
# 1.7.10 — public fields on Entity
grep -n 'public double posX\|public float rotationYaw\|public float rotationPitch\|public boolean onGround' \
  adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java/net/minecraft/entity/Entity.java
# 1.7.10 — the bounding box is public, and yOffset is 1.62
grep -n 'public AxisAlignedBB boundingBox' \
  adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java/net/minecraft/entity/Entity.java
grep -n 'this.yOffset = 1.62F' \
  adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java/net/minecraft/entity/player/EntityPlayer.java
# 1.7.10 — Minecraft.thePlayer's declared type
grep -n 'public EntityClientPlayerMP thePlayer\|thePlayer;' \
  adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java
```

For 1.21.11, confirm `getX/getY/getZ` (`Entity.java:3699/3715/3735`), `getYRot/getXRot`
(`:3823/:3840`) and `onGround()` (`:696`) under
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/sources/net/minecraft/world/entity/Entity.java`,
and that `Minecraft.player` is a `LocalPlayer`.

- [ ] **Step 10: Run the test and confirm it passes**

Run: `./gradlew :platform-testkit:test`
Expected: PASS, 4 new tests.

- [ ] **Step 11: Build everything, including both adapters**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. This is the step that proves the two adapters still compile against the
widened `IPlatformContext`.

- [ ] **Step 12: Audit the two new adapter files**

Per B1 §6.2: count the conditionals in `FabricPlayerView` and `ForgePlayerView`. **Expected: exactly
one each** — the null guard in `player()`. If either has more, or if any conditional decides
something rather than resolving a name or guarding a null, stop and report it.

- [ ] **Step 13: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/IPlayerView.java \
        platform/src/main/java/dev/continuo/platform/IPlatformContext.java \
        platform-testkit/src/main/java/dev/continuo/testkit/FakePlayerView.java \
        platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java \
        platform-testkit/src/test/java/dev/continuo/testkit/FakePlatformContextTest.java \
        adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlayerView.java \
        adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricPlatformContext.java \
        adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlayerView.java \
        adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgePlatformContext.java
git commit -m "feat(d1): the core can read where the player is

IPlayerView joins the SPI on IBlockView's pattern -- adapter implements, core
calls, same tick window, same instance for the adapter's lifetime. Six methods:
position, yaw, pitch, and the ground flag.

y() is defined as the bottom of the collision box, which is the whole reason the
interface needs a contract rather than just a signature. 1.7.10's posY is the
stance, 1.62 blocks higher; the feet are boundingBox.minY, and the client's own
movement packet is what settles it. ForgePlayerView returns boundingBox.minY and
says why on the line."
```

---

## Task 2: `IActuator.setLook`

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/IActuator.java`
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricActuator.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgeActuator.java`
- Test: `platform-testkit/src/test/java/dev/continuo/testkit/FakeActuatorTest.java` (create)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `IActuator.setLook(float yaw, float pitch)`. `FakeActuator.LookCall` with public final
  `float yaw` and `float pitch`; `FakeActuator.lookCalls()` returning `List<LookCall>`;
  `FakeActuator.clear()` now clears both lists.

**Why this task is one unit:** adding a method to `IActuator` breaks all three implementers at
compile time.

- [ ] **Step 1: Write the failing test**

Create `platform-testkit/src/test/java/dev/continuo/testkit/FakeActuatorTest.java`:

```java
package dev.continuo.testkit;

import dev.continuo.platform.Input;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the recording fake, which is the only observer any core-side test of actuation has. */
class FakeActuatorTest {

    @Test
    void recordsLookCallsInOrder() {
        FakeActuator actuator = new FakeActuator();

        actuator.setLook(90.0f, -12.5f);
        actuator.setLook(-45.0f, 0.0f);

        assertEquals(2, actuator.lookCalls().size());
        assertEquals(90.0f, actuator.lookCalls().get(0).yaw);
        assertEquals(-12.5f, actuator.lookCalls().get(0).pitch);
        assertEquals(-45.0f, actuator.lookCalls().get(1).yaw);
        assertEquals(0.0f, actuator.lookCalls().get(1).pitch);
    }

    @Test
    void looksAndInputsAreRecordedSeparately() {
        FakeActuator actuator = new FakeActuator();

        actuator.setInput(Input.FORWARD, true);
        actuator.setLook(1.0f, 2.0f);

        assertEquals(1, actuator.callCount(), "a look must not count as an input call");
        assertEquals(1, actuator.lookCalls().size());
    }

    @Test
    void clearEmptiesBothRecordings() {
        FakeActuator actuator = new FakeActuator();
        actuator.setInput(Input.FORWARD, true);
        actuator.setLook(1.0f, 2.0f);

        actuator.clear();

        assertEquals(0, actuator.callCount());
        assertEquals(0, actuator.lookCalls().size(),
            "clear() must reset looks too, or a test that clears between phases sees stale ones");
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails for the right reason**

Run: `./gradlew :platform-testkit:test`
Expected: **compilation failure** — `setLook` and `lookCalls` do not exist.

- [ ] **Step 3: Add `setLook` to `IActuator`**

In `platform/src/main/java/dev/continuo/platform/IActuator.java`, add after `setInput`:

```java
    /**
     * Points the player in a direction.
     *
     * <p>Absolute, not relative: this sets the facing rather than turning by an amount. Deltas
     * compound rounding across ticks and go wrong the moment the server rewrites rotation, which it
     * does — a position correction calls the platform's own set-position-and-rotation path and
     * overwrites yaw and pitch along with the position.
     *
     * <p>Yaw and pitch are combined into one call because both target versions apply rotation as one
     * write, and because a caller changing only its heading passes {@link IPlayerView#pitch()}
     * straight back.
     *
     * <p><b>Conventions are {@link IPlayerView#yaw()}'s and {@link IPlayerView#pitch()}'s</b>, which
     * are facts about both target versions rather than inventions of this SPI: yaw {@code 0} faces
     * {@code +Z}, {@code 90} faces {@code -X}; pitch {@code -90} is straight up and {@code +90}
     * straight down.
     *
     * <p><b>Takes effect at the game's next input read</b>, and therefore on the current tick when
     * called from {@link TickPhase#PRE} — the same timing {@link #setInput} has.
     *
     * <p><b>No round trip is guaranteed.</b> {@link IPlayerView#yaw()} is not required to return what
     * was last passed here: the user's mouse writes rotation too, and so does the server. This is
     * global rule 4's principle applied to rotation rather than to key state.
     *
     * <p>The core MUST pass a {@code pitch} in {@code [-90, 90]}; adapter behaviour outside that
     * range is unspecified, exactly as it is for a {@code null} {@link Input}. Neither target version
     * clamps on a direct field write, and an out-of-range pitch is both a rendering defect and the
     * loudest signal a server-side plausibility check can read. {@code yaw} may be any finite value:
     * both versions' movement arithmetic is periodic in it.
     *
     * <p><b>Adapter obligation.</b> An implementation MUST also write the platform's
     * previous-rotation field — {@code prevRotationYaw}/{@code prevRotationPitch} on 1.7.10,
     * {@code yRotO}/{@code xRotO} on 1.21.11 — so the local camera does not visibly interpolate
     * across the change. Both games snapshot the previous rotation once per tick and their own
     * set-rotation paths write it; an adapter that skips it leaves the renderer interpolating from a
     * stale angle. Cosmetic rather than behavioural, and stated as an obligation only so that one
     * adapter cannot quietly do it while the other does not.
     *
     * @param yaw   the heading in degrees; any finite value
     * @param pitch the elevation in degrees; MUST be in {@code [-90, 90]}
     */
    void setLook(float yaw, float pitch);
```

- [ ] **Step 4: Record looks in `FakeActuator`**

In `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java`, add:

```java
    /** One recorded {@code setLook} call. */
    public static final class LookCall {
        public final float yaw;
        public final float pitch;

        public LookCall(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public String toString() {
            return "look(" + yaw + ", " + pitch + ")";
        }
    }

    private final List<LookCall> lookCalls = new ArrayList<LookCall>();

    @Override
    public void setLook(float yaw, float pitch) {
        lookCalls.add(new LookCall(yaw, pitch));
    }

    public List<LookCall> lookCalls() {
        return lookCalls;
    }
```

and extend the existing `clear()` so it reads:

```java
    public void clear() {
        calls.clear();
        lookCalls.clear();
    }
```

- [ ] **Step 5: Implement `setLook` on both adapters**

In `FabricActuator.java`, add:

```java
    @Override
    public void setLook(float yaw, float pitch) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
        // Written alongside the rotation itself, per IActuator#setLook's adapter obligation: the
        // game snapshots the previous rotation once per tick, so a write landing after that
        // snapshot would leave the camera interpolating from a stale angle.
        player.yRotO = yaw;
        player.xRotO = pitch;
    }
```

with `import net.minecraft.client.player.LocalPlayer;` added.

In `ForgeActuator.java`, add:

```java
    @Override
    public void setLook(float yaw, float pitch) {
        EntityClientPlayerMP player = minecraft.thePlayer;
        if (player == null) {
            return;
        }
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        // Written alongside the rotation itself, per IActuator#setLook's adapter obligation, and
        // matching what this version's own setPositionAndRotation does.
        player.prevRotationYaw = yaw;
        player.prevRotationPitch = pitch;
    }
```

with `import net.minecraft.client.entity.EntityClientPlayerMP;` added.

> **Note the asymmetry with `IPlayerView`, which is deliberate.** `IPlayerView`'s null guard throws,
> because reading outside the call window is a contract violation by the caller. `setLook` returns
> quietly, because it matches `setInput`'s existing behaviour of never throwing for a well-formed
> call — a bot faulting the tick loop over a race with a disconnect is exactly what global rule 3
> exists to prevent. If you think this asymmetry is wrong, report it rather than unifying it.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `./gradlew :platform-testkit:test`
Expected: PASS, 3 new tests.

- [ ] **Step 7: Verify the Minecraft API claims**

```bash
# 1.21.11 — the setters and the prev-rotation fields
SRC=$(echo .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/sources)
grep -n 'public void setYRot(\|public void setXRot(\|public float yRotO\|public float xRotO' \
  $SRC/net/minecraft/world/entity/Entity.java
# 1.7.10 — the writable fields
grep -n 'public float rotationYaw\|public float rotationPitch\|public float prevRotationYaw\|public float prevRotationPitch' \
  adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java/net/minecraft/entity/Entity.java
```

Expected: all six present and public. Report rather than adjust if any is not.

- [ ] **Step 8: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — both adapters compile against the widened `IActuator`.

- [ ] **Step 9: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/IActuator.java \
        platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java \
        platform-testkit/src/test/java/dev/continuo/testkit/FakeActuatorTest.java \
        adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/FabricActuator.java \
        adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ForgeActuator.java
git commit -m "feat(d1): the core can point the player

IActuator gains setLook(yaw, pitch): absolute, combined, one method, no new
types. Movement input is interpreted relative to facing on both versions by
identical arithmetic, so without this a bot can press FORWARD and cannot steer.

Both adapters also write the previous-rotation field, which the contract states
as an obligation rather than leaving to preference -- one adapter doing it and
the other not is the kind of divergence this SPI exists to stop."
```

---

## Task 3: Global rule 4 resolved, and the core obeys it

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/package-info.java`
- Modify: `platform/src/main/java/dev/continuo/platform/IActuator.java` (javadoc only)
- Modify: `platform/src/main/java/dev/continuo/platform/IGameEvents.java` (javadoc only)
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java` (javadoc only)
- Modify: `core/src/main/java/dev/continuo/core/ContinuoCore.java:77-90`
- Test: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`

**Interfaces:**
- Consumes: `FakeActuator.callCount()`, `FakeActuator.calls()`, `FakeActuator.clear()` from Task 2.
- Produces: nothing new for later tasks. `ContinuoCore`'s public API is unchanged.

**Pre-flight — this task breaks two existing tests, and one must be rewritten rather than deleted.**
`holdsForwardForFortyTicksThenReleasesOnTickFortyOne` and `ignoresRequestWalkWhileAlreadyWalking`
both assert on call *counts* that only hold under edge-triggering. Deleting the second would delete
the only coverage of "a re-request does not restart the walk", which is a real guarantee that
survives the change. It is rewritten to assert that guarantee directly.

- [ ] **Step 1: Write the failing tests**

In `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`, **replace** the two existing tests
named `holdsForwardForFortyTicksThenReleasesOnTickFortyOne` and
`ignoresRequestWalkWhileAlreadyWalking` with these five, and add the two helpers:

```java
    /** How many of the recorded calls were presses. */
    private int pressCount() {
        int n = 0;
        for (FakeActuator.Call call : actuator.calls()) {
            if (call.pressed) {
                n++;
            }
        }
        return n;
    }

    private FakeActuator.Call lastCall() {
        assertTrue(actuator.callCount() > 0, "expected at least one actuator call");
        return actuator.calls().get(actuator.callCount() - 1);
    }

    /**
     * Global rule 4's whole content, as one assertion. The core re-states its desired input every
     * tick rather than pressing once and trusting the press to persist — which both target versions
     * break whenever a screen opens.
     */
    @Test
    void reAssertsForwardOnEveryTickOfTheWalk() {
        core.requestWalk();
        tick(40);

        assertEquals(40, actuator.callCount(), "level-triggered: one call per tick, not one in total");
        assertEquals(40, pressCount());
        for (FakeActuator.Call call : actuator.calls()) {
            assertEquals(Input.FORWARD, call.input);
            assertTrue(call.pressed);
        }
    }

    @Test
    void releasesExactlyOnceOnTickFortyOne() {
        core.requestWalk();
        tick(40);
        assertEquals(40, actuator.callCount(), "guard: the walk must still be running at tick 40");

        tick(1);

        assertEquals(41, actuator.callCount());
        assertEquals(Input.FORWARD, lastCall().input);
        assertEquals(false, lastCall().pressed);
        assertEquals(40, pressCount(), "exactly one release, and no extra press on tick 41");
    }

    /**
     * Global rule 4's second clause. An idle core writes nothing at all — it must not hold every
     * input at {@code false} every tick, which would fight the user's own keyboard whenever the bot
     * is not running.
     *
     * <p>Nothing asserted this before D1, because before D1 nothing could have violated it.
     */
    @Test
    void writesNothingWhileIdle() {
        tick(20);
        assertEquals(0, actuator.callCount(), "before any walk is requested");

        core.requestWalk();
        tick(41);
        actuator.clear();
        tick(20);

        assertEquals(0, actuator.callCount(), "after the walk has finished");
    }

    /**
     * The same clause on the path that reaches idleness through {@code stop()} rather than through
     * the walk running out. Level-triggering makes this newly worth pinning: a core that re-stated
     * its inputs unconditionally would keep writing here, where before D1 there was no per-tick
     * write that could.
     */
    @Test
    void writesNothingOnTicksAfterStop() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        tick(20);

        assertEquals(0, actuator.callCount(), "stop() ends the walk, and an idle core is silent");
    }

    /**
     * Re-requesting mid-walk is still ignored. Asserted through the walk's *length* rather than
     * through a call count, because under level-triggering every tick produces a call and a count
     * can no longer distinguish "ignored" from "restarted".
     */
    @Test
    void reRequestingMidWalkDoesNotRestartOrExtendIt() {
        core.requestWalk();
        tick(10);

        core.requestWalk();
        tick(30);

        assertEquals(40, pressCount(), "40 held ticks in total, so the walk was not restarted");
        assertTrue(lastCall().pressed, "guard: still walking at tick 40");

        tick(1);

        assertEquals(false, lastCall().pressed,
            "released on tick 41, so the re-request did not extend the walk to tick 51");
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :core:test --tests '*ContinuoCoreTest*'`
Expected: FAIL. `reAssertsForwardOnEveryTickOfTheWalk` fails with `expected: <40> but was: <1>`;
`releasesExactlyOnceOnTickFortyOne` fails on its guard; `reRequestingMidWalkDoesNotRestartOrExtendIt`
fails on `pressCount()`. **`writesNothingWhileIdle` and `writesNothingOnTicksAfterStop` should pass
already** — they describe behaviour the edge-triggered core also has, and they are here to stop the
conversion from breaking it.

> If either idle-silence test fails at this point, stop and report it: it means the existing core
> does something this plan did not predict, and the conversion would be papering over it.

- [ ] **Step 3: Convert `ContinuoCore` to level-triggered**

In `core/src/main/java/dev/continuo/core/ContinuoCore.java`, replace the body of `onClientTick`
(currently lines 77–90) with:

```java
    /**
     * Holds {@code FORWARD} for {@link #WALK_TICKS} ticks, re-asserting it every tick.
     *
     * <p><b>Level-triggered, per global rule 4.</b> The desired input is re-stated on every tick of
     * the walk rather than pressed once at the start: both target versions clear held key state
     * whenever a screen opens ({@code KeyMapping.releaseAll}; 1.7.10's
     * {@code KeyBinding.unPressAllKeys}), and an edge-triggered core never learns that it happened.
     * The walk would silently truncate and present as a wrong distance.
     *
     * <p><b>Nothing is written while idle</b> — not even a release. A core that held every input at
     * {@code false} every tick would fight the user's own keyboard whenever the bot is not running.
     */
    @Override
    public void onClientTick(TickPhase phase) {
        if (phase != TickPhase.PRE || !walking) {
            return;
        }
        tick++;
        if (tick > WALK_TICKS) {
            context.actuator().setInput(Input.FORWARD, false);
            walking = false;
            tick = 0;
            return;
        }
        context.actuator().setInput(Input.FORWARD, true);
    }
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :core:test --tests '*ContinuoCoreTest*'`
Expected: PASS, all of them — including the untouched `pressesForwardOnFirstTickAfterRequest`,
`doesNothingAfterTheWalkCompletes`, `neverTouchesAnyInputOtherThanForward`,
`stopReleasesForwardMidWalk`, `canWalkAgainAfterStop` and `startTwiceReplacesContext`.

> If any test other than the four you touched now fails, stop and report it. The conversion is meant
> to preserve every behaviour except the call count.

- [ ] **Step 5: Rewrite global rule 4**

In `platform/src/main/java/dev/continuo/platform/package-info.java`, replace the whole
`<h3>Rule 4 — Input persistence is not guaranteed</h3>` section (currently lines 98–110) with:

```java
 * <h3>Rule 4 — Actuation is level-triggered</h3>
 *
 * <p>State set through {@link dev.continuo.platform.IActuator#setInput} may be cleared by the
 * platform at any time without notice. Any screen opening does this on both target versions
 * ({@code KeyMapping.releaseAll}; 1.7.10's {@code KeyBinding.unPressAllKeys}), as does the user
 * physically tapping the key.
 *
 * <p><b>Resolved in M5/D1: the core absorbs this, and adapters owe nothing.</b>
 *
 * <ul>
 *   <li><b>While it is driving, the core states its full desired input set every tick.</b> It does
 *       not track what it has already pressed and does not rely on any previous {@code setInput}
 *       call persisting. The worst case after a screen opens is one lost tick, which self-heals.
 *   <li><b>While it is idle, the core writes nothing at all.</b> It MUST NOT hold every input at
 *       {@code false} each tick; that would fight the user's own keyboard whenever the bot is not
 *       running.
 *   <li><b>On {@code stop} the core releases what it holds, once.</b>
 *   <li><b>An adapter owes nothing new.</b> {@code setInput} remains idempotent, and remains
 *       documented as clearable at any time. No adapter is required to re-assert anything.
 * </ul>
 *
 * <p>Level rather than edge was chosen for three reasons. It needs no SPI addition, where an
 * edge-triggered core would need either a read-back of held state or a notification that state was
 * cleared. The two models produce byte-identical network output, since re-asserting is a field write
 * inside the client and the server sees only the resulting movement packets — so neither is more
 * plausible than the other. And it makes a path executor a pure function of its path and the
 * player's state, recomputed each tick, rather than a state machine remembering what it has already
 * sent.
 *
 * <p>The same reasoning covers {@link dev.continuo.platform.IActuator#setLook}: a core driving the
 * player rewrites its desired facing every tick, which is what makes a server's rotation correction
 * self-healing rather than a case to detect.
 */
```

- [ ] **Step 6: Remove the two forward references to M5**

In `platform/src/main/java/dev/continuo/platform/IActuator.java`, in `setInput`'s javadoc, replace
the sentence:

```
 * side to re-assert a held input, and does not require either side to rely on one
 * persisting. Whether actuation is edge- or level-triggered is deferred to milestone M5;
 * see global rule 4 for that deferral.
```

with:

```
 * side to re-assert a held input. Global rule 4 resolves what the core does about it: while
 * driving, the core re-states its full desired input set every tick, so an adapter is never
 * required to re-assert anything.
```

In `platform/src/main/java/dev/continuo/platform/IGameEvents.java`, in `onClientTick`'s javadoc,
replace:

```
 *       ground. What happens to a held input across those screens is global rule 4's
 *       subject and is deferred to M5; this note only records that the tick count and the
 *       distance are not the same quantity.
```

with:

```
 *       ground. What happens to a held input across those screens is global rule 4's
 *       subject, and is resolved there; this note only records that the tick count and the
 *       distance are not the same quantity.
```

In `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`, replace the
rule 4 entry in the "Rules with no cases, and why" javadoc:

```
 * <p><b>Rule 4 (Input persistence)</b> — a hazard statement, not an obligation. That
 * {@code setInput}'s effect may not persist is precisely what the SPI declines to require
 * either side to handle before M5.
```

with:

```
 * <p><b>Rule 4 (Actuation is level-triggered)</b> — an obligation on the <em>core</em>, not on an
 * adapter. The rule requires the core to re-state its desired input set every tick and requires
 * nothing of an adapter, so this suite — which drives adapters — has nothing to assert. The
 * obligation is asserted against the core, in {@code ContinuoCoreTest}.
```

- [ ] **Step 7: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Javadoc is build-failing, so this is also what checks the rewritten
javadoc's `{@link}` targets resolve.

- [ ] **Step 8: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/package-info.java \
        platform/src/main/java/dev/continuo/platform/IActuator.java \
        platform/src/main/java/dev/continuo/platform/IGameEvents.java \
        platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java \
        core/src/main/java/dev/continuo/core/ContinuoCore.java \
        core/src/test/java/dev/continuo/core/ContinuoCoreTest.java
git commit -m "feat(d1): actuation is level-triggered, and the core obeys it

M1's review wanted edge- vs level-triggered decided before two adapters existed.
The roadmap deferred it to M5 on the reasoning that a core reconciling against
real position every tick has effectively answered it. It resolves to level.

It needs no SPI addition, where edge-triggering needs a read-back or an event.
Both models produce byte-identical network output, so neither is more plausible.
And it makes a path executor a pure function of path and player state rather
than a state machine -- which is the argument that actually decides it, and it
belongs to D2.

The obligation binds the core alone, so both adapters are unchanged. Verified
against source that re-writing input state every tick cannot fabricate a queued
click on either version: setDown writes only isDown, and 1.7.10's pressed and
pressTime are separate fields. That matters because the walk and probe keys are
driven off exactly those click counters.

ContinuoCore's 40-tick walk is converted, so the rule ships with something
obeying it: 40 presses where there was one. Two tests asserted call counts that
only held under edge-triggering. One is rewritten to assert the walk's length
instead, because 'a re-request does not restart the walk' is a real guarantee
that survives the change and a call count can no longer express it."
```

---

## Task 4: The probe takes its start from `IPlayerView`, and checks the standing invariant

**Files:**
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java:140-184`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java:200-230`
- Test: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`

**Interfaces:**
- Consumes: `IPlayerView` and `FakePlayerView` from Task 1.
- Produces: `PathProbe.start(BlockSource world, IPlayerView player)` returning `ProbeReport` (null
  when the run started). `PathProbe.markGoal(IPlayerView player)`. The existing
  `start(BlockSource, int, int, int)` and `run(BlockSource, int, int, int)` are **unchanged** and
  remain the primitives — C5 made `run` equal to `begin` plus one unbounded slice on purpose, and
  that structure is not disturbed here.

> **Task 5 changes `start`'s signature again**, to add an `IActuator`. That is expected; it is
> called out here so it does not read as a surprise there.

- [ ] **Step 1: Write the failing test**

Add to `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`. Read the existing file first
for its fixture conventions — `ProbeWorld` has a stone floor at `FLOOR_Y = 63` with the walkable
layer at `WALK_Y = 64`, so a player standing on the floor has `y() == 64.0`.

```java
    @Test
    void takesItsStartFromThePlayerViewAndFloorsIt() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();
        player.set(2.75, 64.0, -3.25, 0.0f, 0.0f, true);
        probe.markGoal(5, ProbeWorld.WALK_Y, 0);

        assertNull(probe.start(world, player), "the run must start");
        ProbeReport report = drainToReport(probe);

        assertEquals(PathOutcome.FOUND, report.outcome(), "guard: the route must exist");
        assertTrue(report.summary().contains("(2, 64, -4)"),
            "the start must be the floored player position, not a rounded one: " + report.summary());
    }

    @Test
    void reportsThePlayerStateItRead() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();
        player.set(0.5, 64.0, 0.5, 90.0f, -12.5f, true);
        probe.markGoal(4, ProbeWorld.WALK_Y, 0);

        probe.start(world, player);
        ProbeReport report = drainToReport(probe);

        assertTrue(report.summary().contains("player 0.50 64.00 0.50"),
            "position must be reported: " + report.summary());
        assertTrue(report.summary().contains("yaw 90.0"),
            "yaw must be reported: " + report.summary());
        assertTrue(report.summary().contains("onGround true"),
            "the ground flag must be reported: " + report.summary());
    }

    /**
     * The check that catches a 1.62-block {@code y()} error on 1.7.10. Under that bug the sampled
     * block sits about a block above the player's head, and anywhere a player can stand has
     * headroom, so it is air essentially always.
     */
    @Test
    void noticesWhenTheBlockBelowTheFeetCannotSupportTheStandingPlayer() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();
        // A whole block too high: on ground, but with air beneath the reported feet.
        player.set(0.5, ProbeWorld.WALK_Y + 1, 0.5, 0.0f, 0.0f, true);
        probe.markGoal(4, ProbeWorld.WALK_Y, 0);

        probe.start(world, player);
        ProbeReport report = drainToReport(probe);

        assertTrue(report.summary().contains("onGround is true but the block below the feet"),
            "the standing invariant must fire: " + report.summary());
    }

    @Test
    void doesNotNoticeAnythingWhenThePlayerIsStandingOnTheFloor() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);
        probe.markGoal(4, ProbeWorld.WALK_Y, 0);

        probe.start(world, player);
        ProbeReport report = drainToReport(probe);

        assertEquals(PathOutcome.FOUND, report.outcome(), "guard: the route must exist");
        assertFalse(report.summary().contains("onGround is true but the block below the feet"),
            "no notice on plain ground: " + report.summary());
    }

    @Test
    void doesNotCheckTheStandingInvariantWhileAirborne() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y + 3, 0.5, 0.0f, 0.0f, false);
        probe.markGoal(4, ProbeWorld.WALK_Y, 0);

        probe.start(world, player);
        ProbeReport report = drainToReport(probe);

        assertFalse(report.summary().contains("onGround is true but the block below the feet"),
            "a falling player stands on nothing and that is not a defect: " + report.summary());
    }

    @Test
    void markGoalCanBeTakenFromThePlayerView() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        PathProbe probe = new PathProbe();

        player.set(4.9, ProbeWorld.WALK_Y, -0.1, 0.0f, 0.0f, true);
        probe.markGoal(player);
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);

        probe.start(world, player);
        ProbeReport report = drainToReport(probe);

        assertEquals(PathOutcome.FOUND, report.outcome());
        assertTrue(report.summary().contains("(4, 64, -1)"),
            "the goal must be the floored marked position: " + report.summary());
    }
```

Add this helper to the test class if one does not already exist — check first, and reuse the
existing one if it does:

```java
    /** Spends slices until the run finishes, and fails rather than looping forever. */
    private static ProbeReport drainToReport(PathProbe probe) {
        for (int i = 0; i < 10000; i++) {
            ProbeReport report = probe.advance();
            if (report != null) {
                return report;
            }
        }
        throw new AssertionError("the run did not finish within 10000 slices");
    }
```

Imports the new tests need: `dev.continuo.platform.IPlayerView`, `dev.continuo.testkit.FakePlayerView`,
`dev.continuo.pathfinder.PathOutcome`, and `assertNull` / `assertFalse` from
`org.junit.jupiter.api.Assertions`.

- [ ] **Step 2: Confirm `runtime`'s test source set can see `platform-testkit`**

Run: `grep -n 'testkit\|testImplementation' runtime/build.gradle.kts`
Expected: a `testImplementation(project(":platform-testkit"))` or equivalent — `AdapterRuntimeConformanceTest`
extends `AdapterConformanceTest`, so it must already be there. **If it is not, stop and report it**
rather than adding a dependency: a new module dependency is a change this plan did not budget for.

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: **compilation failure** — `start(BlockSource, IPlayerView)` and `markGoal(IPlayerView)` do
not exist.

- [ ] **Step 4: Add the player-driven entry points to `PathProbe`**

In `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`, add the imports
`dev.continuo.core.BlockData`, `dev.continuo.movement.Standability` and
`dev.continuo.platform.IPlayerView`, add a field beside the other `active*` fields:

```java
    /**
     * The standing-invariant notice for the run in flight, or {@code null} if it held.
     *
     * <p>Computed at {@link #start} because that is the only tick whose player state produced the
     * search's start position. Cleared everywhere {@link #activeSnapshot} is.
     */
    private String activeStandingNotice;
```

add it to the clearing block in **all three** of `run`'s `finally`, `advance`'s `finally` and
`cancel()` — the probe's rule is that every field cleared in one is cleared in all three, with no
exceptions — and add these methods:

```java
    /**
     * Records the player's current block position as the goal. Replaces any previous mark.
     *
     * @param player where the player is; never {@code null}
     */
    public void markGoal(IPlayerView player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        markGoal(floor(player.x()), floor(player.y()), floor(player.z()));
    }

    /**
     * Begins a sliced run from where the player is standing.
     *
     * <p>The start comes from {@link IPlayerView} rather than from coordinates an adapter computed,
     * which is what makes the SPI's feet definition load-bearing: an adapter reporting the stance
     * instead of the feet on 1.7.10 would start every search 1.62 blocks above the ground. The
     * standing invariant recorded here is what surfaces that.
     *
     * @param world  the world to read; never {@code null}
     * @param player where the player is; never {@code null}
     * @return a report if the run could not be started, or {@code null} if it was
     */
    public ProbeReport start(BlockSource world, IPlayerView player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        int px = floor(player.x());
        int py = floor(player.y());
        int pz = floor(player.z());
        ProbeReport refused = start(world, px, py, pz);
        if (refused != null) {
            return refused;
        }
        activePlayerState = "player " + fmt2(player.x()) + " " + fmt2(player.y()) + " "
            + fmt2(player.z()) + ", yaw " + fmt(player.yaw()) + ", pitch " + fmt(player.pitch())
            + ", onGround " + player.onGround();
        activeStandingNotice = standingNotice(world, player, px, py, pz);
        return null;
    }

    /**
     * The standing invariant: a player the platform reports as on the ground should have something
     * under its feet.
     *
     * <p><b>This is what catches a feet-versus-stance error in an adapter.</b> With {@code y()}
     * reporting 1.62 blocks too high, the sampled block sits about a block above the player's head,
     * and anywhere a player can stand has headroom — so it is air essentially always.
     *
     * <p>A notice rather than an assertion, because it has legitimate failures: standing on a slab
     * or stair edge, on a fence post, straddling a block boundary, in a boat, on a ladder, or in
     * fluid. On plain flat ground a notice is a real defect. The list is a first draft and the right
     * response to finding another case is to extend it, not to weaken the check.
     *
     * @return the notice, or {@code null} if the invariant held or does not apply
     */
    private static String standingNotice(BlockSource world, IPlayerView player,
                                         int px, int py, int pz) {
        if (!player.onGround()) {
            return null;
        }
        BlockData below = world.at(px, py - 1, pz);
        if (Standability.supports(below)) {
            return null;
        }
        return "onGround is true but the block below the feet at " + px + "," + (py - 1) + ","
            + pz + " is " + below.shape() + ", which cannot support a standing player."
            + " On plain ground this means IPlayerView.y() is not the bottom of the collision box"
            + " -- on 1.7.10 that is posY (the stance, 1.62 blocks high) where it should be"
            + " boundingBox.minY. Legitimate on a slab or stair edge, a fence post, a block"
            + " boundary, a boat, a ladder or in fluid";
    }

    /** Floor toward negative infinity, so a negative coordinate lands in the block it is inside. */
    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** Two decimal places, in {@code Locale.ROOT}, for the same reason {@link #fmt} is. */
    private static String fmt2(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(value));
    }
```

Add the `activePlayerState` field beside `activeStandingNotice`, cleared in the same three places:

```java
    /** What {@link #start} read from {@link IPlayerView}, for the report. */
    private String activePlayerState;
```

- [ ] **Step 5: Append both to the report**

In `PathProbe.report(...)`, after the `sliced ... worst ...` clause and before the divergence notice,
add:

```java
        if (activePlayerState != null) {
            summary.append(", ").append(activePlayerState);
        }
        if (activeStandingNotice != null) {
            append(summary, map, activeStandingNotice);
        }
```

> `append(summary, map, ...)` puts the notice in both the summary and the map, which is what every
> other probe notice does. Read the existing notices around it and match them.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: PASS, 6 new tests, and every pre-existing `PathProbeTest` test still green — `run` and the
integer `start` were not touched.

- [ ] **Step 7: Wire both adapters**

In `ContinuoFabricMod.java`, in the probe poll block, replace the `BlockPos at = ...` start-position
reads with `context.player()`:

```java
            if (mark) {
                probe.markGoal(context.player());
                LOGGER.info("Continuo: path goal marked at {} {} {}",
                    context.player().x(), context.player().y(), context.player().z());
            }
            if (path) {
                ProbeReport refused = probe.start(core.blocks(), context.player());
                if (refused != null) {
                    LOGGER.info(refused.summary());
                }
            }
```

The `BlockPos at = client.player.blockPosition();` line in **that block only** becomes unused —
remove it. **The block-dump poll above it keeps its own read**; that is a separate feature and out of
scope. Check whether `net.minecraft.core.BlockPos` is still imported for the dump block before
removing the import — there is no unused-import linter, so this must be checked by hand.

Make the equivalent change in `ContinuoForgeMod.pollProbeKeys`, replacing the `px`/`py`/`pz`
computation with `context.player()`. **The `MathHelper.floor_double(client.thePlayer.boundingBox.minY)`
line disappears here**, and its long explanatory comment moves with it — the same reasoning now
lives in `ForgePlayerView.y()`'s javadoc and in `IPlayerView.y()`'s contract, which is the point of
D1. Confirm `MathHelper` is still used by `pollDumpKey` before removing its import.

> **Both mods already have the context in scope**, so no plumbing is needed: `ContinuoForgeMod` holds
> it as a field (`private ForgePlatformContext context;`, `:74`), and in `ContinuoFabricMod` it is an
> effectively-final local that the tick lambda already captures — the existing comment on that lambda
> says so. `client` is still needed in both for the game directory and the null-player check. Report
> it if the shape is not what this brief assumes.

- [ ] **Step 8: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java \
        adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java \
        adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java
git commit -m "feat(d1): the probe reads the player through the SPI, and checks it

PathProbe takes its start position and its goal mark from IPlayerView instead of
from coordinates each adapter computed for itself. That collapses knowledge that
was duplicated in two untestable files -- Forge floored boundingBox.minY, Fabric
floored blockPosition() -- into the one contract that now states the rule.

It also runs the standing invariant: a player the platform reports as on the
ground should have something under its feet. That is the only check in the
project that can catch a feet-versus-stance error in an adapter, because no
headless test can instantiate one. A notice rather than an assertion, since
slabs, fences, boats and ladders fail it legitimately -- but on plain ground a
notice is a real defect."
```

---

## Task 5: The probe faces the goal, and checks the look round trip

**Files:**
- Modify: `runtime/src/main/java/dev/continuo/runtime/PathProbe.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java`
- Test: `runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java`

**Interfaces:**
- Consumes: `IActuator.setLook` and `FakeActuator.lookCalls()` from Task 2;
  `PathProbe.start(BlockSource, IPlayerView)` from Task 4.
- Produces: `PathProbe.start(BlockSource world, IPlayerView player, IActuator actuator)` — **Task 4's
  two-argument `start` is replaced, not overloaded.** `PathProbe.advance(IPlayerView player)` —
  likewise replaces the no-argument `advance()`.
- `PathProbe.yawToward(double fromX, double fromZ, int toX, int toZ)` is package-private and static,
  so it can be tested directly.

- [ ] **Step 1: Write the failing test**

Add to `PathProbeTest`:

```java
    @Test
    void yawTowardUsesTheConventionBothVersionsShare() {
        // 0 faces +Z, 90 faces -X, 180 faces -Z, -90 faces +X.
        assertEquals(0.0f, PathProbe.yawToward(0.5, 0.5, 0, 10), 0.001f);
        assertEquals(90.0f, PathProbe.yawToward(0.5, 0.5, -10, 0), 0.001f);
        assertEquals(-90.0f, PathProbe.yawToward(0.5, 0.5, 10, 0), 0.001f);
        // Due north comes back as -180 rather than +180: atan2(+0.0, -z) is +pi, and yawToward
        // deliberately does not normalise -- IPlayerView.yaw() is documented as unnormalised and
        // setLook accepts any finite yaw, so inventing a normalisation here would impose a
        // requirement the SPI declines to make. The two are the same heading, which the next
        // assertion is what actually pins.
        assertEquals(-180.0f, PathProbe.yawToward(0.5, 0.5, 0, -10), 0.001f);
    }

    @Test
    void theLookCheckTreatsPlusAndMinus180AsTheSameHeading() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        FakeActuator actuator = new FakeActuator();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);
        probe.markGoal(0, ProbeWorld.WALK_Y, -6);

        probe.start(world, player, actuator);
        assertEquals(-180.0f, actuator.lookCalls().get(0).yaw, 0.001f, "guard: due north was asked for");
        // The platform reports the same heading with the opposite sign, which a raw float
        // comparison would call 360 degrees of error.
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 180.0f, 0.0f, true);
        ProbeReport report = drainToReport(probe, player);

        assertFalse(report.summary().contains("setLook did not take effect"),
            "+180 and -180 are one heading: " + report.summary());
    }

    @Test
    void facesTheGoalWhenTheRunStarts() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        FakeActuator actuator = new FakeActuator();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, -20.0f, true);
        probe.markGoal(0, ProbeWorld.WALK_Y, 6);

        probe.start(world, player, actuator);

        assertEquals(1, actuator.lookCalls().size(), "starting a run must point at the goal");
        assertEquals(0.0f, actuator.lookCalls().get(0).yaw, 0.001f, "the goal is at +Z");
        assertEquals(-20.0f, actuator.lookCalls().get(0).pitch,
            "pitch must be passed straight back, not invented");
    }

    @Test
    void doesNotTouchTheLookWhenThereIsNoGoalToFace() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        FakeActuator actuator = new FakeActuator();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);

        ProbeReport refused = probe.start(world, player, actuator);

        assertNotNull(refused, "guard: with no goal marked the run must be refused");
        assertEquals(0, actuator.lookCalls().size());
    }

    @Test
    void noticesWhenTheLookDidNotTakeEffect() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        FakeActuator actuator = new FakeActuator();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);
        probe.markGoal(0, ProbeWorld.WALK_Y, 6);

        probe.start(world, player, actuator);
        // The fake actuator writes nothing back, so the player still reports the old yaw. A real
        // adapter that failed to write the rotation field would look exactly like this.
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 137.0f, 0.0f, true);
        ProbeReport report = drainToReport(probe, player);

        assertTrue(report.summary().contains("setLook did not take effect"),
            "the round-trip check must fire: " + report.summary());
    }

    @Test
    void doesNotNoticeWhenTheLookDidTakeEffect() {
        ProbeWorld world = new ProbeWorld();
        FakePlayerView player = new FakePlayerView();
        FakeActuator actuator = new FakeActuator();
        PathProbe probe = new PathProbe();
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 0.0f, 0.0f, true);
        probe.markGoal(0, ProbeWorld.WALK_Y, 6);

        probe.start(world, player, actuator);
        // Simulate the adapter having written the rotation, wrapped by a full turn to prove the
        // comparison normalises rather than comparing raw floats.
        player.set(0.5, ProbeWorld.WALK_Y, 0.5, 360.0f, 0.0f, true);
        ProbeReport report = drainToReport(probe, player);

        assertEquals(PathOutcome.FOUND, report.outcome(), "guard: the route must exist");
        assertFalse(report.summary().contains("setLook did not take effect"),
            "360 and 0 are the same heading: " + report.summary());
    }
```

Replace the `drainToReport(PathProbe)` helper from Task 4 with:

```java
    private static ProbeReport drainToReport(PathProbe probe, IPlayerView player) {
        for (int i = 0; i < 10000; i++) {
            ProbeReport report = probe.advance(player);
            if (report != null) {
                return report;
            }
        }
        throw new AssertionError("the run did not finish within 10000 slices");
    }
```

and update Task 4's six tests to call it with their `player` — they all have one in scope.

Additional imports these tests need beyond Task 4's: `dev.continuo.platform.IActuator`,
`dev.continuo.testkit.FakeActuator`, and `assertNotNull` from `org.junit.jupiter.api.Assertions`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: **compilation failure** — the three-argument `start`, `advance(IPlayerView)` and
`yawToward` do not exist.

- [ ] **Step 3: Implement in `PathProbe`**

Add `import dev.continuo.platform.IActuator;`, two fields cleared in **all three** of `run`'s
`finally`, `advance`'s `finally` and `cancel()`:

```java
    /**
     * The look {@link #start} asked for, checked on the next {@link #advance}. {@code null} once
     * checked, or when no look was set.
     *
     * <p>Holding an {@code IActuator} or an {@code IPlayerView} across ticks would be safe — both
     * are adapter-lifetime instances by {@code IPlatformContext}'s contract, so neither pins a level
     * the way a {@code BlockSource} does. They are still not held: the player is passed to
     * {@link #advance} each tick instead, so the only thing that survives a tick boundary here is
     * two floats and a string.
     */
    private Float pendingYaw;
    private String activeLookNotice;
```

Replace the two-argument `start` from Task 4 with:

```java
    public ProbeReport start(BlockSource world, IPlayerView player, IActuator actuator) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        if (actuator == null) {
            throw new IllegalArgumentException("actuator must not be null");
        }
        int px = floor(player.x());
        int py = floor(player.y());
        int pz = floor(player.z());
        Pos target = goal;
        ProbeReport refused = start(world, px, py, pz);
        if (refused != null) {
            return refused;
        }
        activePlayerState = "player " + fmt2(player.x()) + " " + fmt2(player.y()) + " "
            + fmt2(player.z()) + ", yaw " + fmt(player.yaw()) + ", pitch " + fmt(player.pitch())
            + ", onGround " + player.onGround();
        activeStandingNotice = standingNotice(world, player, px, py, pz);
        float yaw = yawToward(player.x(), player.z(), target.x(), target.z());
        actuator.setLook(yaw, player.pitch());
        pendingYaw = Float.valueOf(yaw);
        return null;
    }
```

> `Pos target = goal;` is read **before** the delegating `start`, because that call captures the goal
> into `activeGoal` and a later read of the mutable `goal` field could see a different mark. This is
> the same hazard `activeGoal`'s own javadoc describes.

Replace `advance()` with:

```java
    public ProbeReport advance(IPlayerView player) {
        if (active == null) {
            return null;
        }
        if (pendingYaw != null) {
            if (player == null) {
                throw new IllegalArgumentException("player must not be null while a run is in flight");
            }
            activeLookNotice = lookNotice(pendingYaw.floatValue(), player.yaw());
            pendingYaw = null;
        }
        // ... the existing body, unchanged from here down
    }
```

Add:

```java
    /**
     * The yaw that points from a position toward a block's centre, in the convention both target
     * versions share: {@code 0} faces {@code +Z}, {@code 90} faces {@code -X}. Both derive forward
     * motion as {@code (-sin yaw, cos yaw)}, which inverts to {@code yaw = -atan2(dx, dz)}.
     *
     * <p>Package-private so it can be tested against that convention directly, rather than only
     * through a search.
     */
    static float yawToward(double fromX, double fromZ, int toX, int toZ) {
        double dx = (toX + 0.5) - fromX;
        double dz = (toZ + 0.5) - fromZ;
        return (float) Math.toDegrees(-Math.atan2(dx, dz));
    }

    /**
     * Whether a {@code setLook} appears to have taken effect, as a notice rather than an assertion.
     *
     * <p>{@link IActuator#setLook} guarantees no round trip — the user's mouse and the server both
     * write rotation — so this cannot be asserted. But on a tick where nobody touched the mouse, a
     * mismatch means one of {@code setLook} and {@link IPlayerView#yaw()} is wrong, and those two
     * checking each other is the only leverage this project has on adapter code that no test can
     * reach.
     *
     * @return the notice, or {@code null} if the two agree
     */
    private static String lookNotice(float asked, float got) {
        float difference = Math.abs(wrapDegrees(asked - got));
        if (difference <= LOOK_TOLERANCE_DEGREES) {
            return null;
        }
        return "setLook did not take effect: asked for yaw " + fmt(asked) + " and the next tick"
            + " reported " + fmt(got) + " (" + fmt(difference) + " degrees apart)."
            + " Either IActuator.setLook is not writing the rotation field or IPlayerView.yaw() is"
            + " not reading it. Expected after moving the mouse or a server position correction,"
            + " which also rewrites rotation; a defect otherwise";
    }

    /** Degrees folded into {@code [-180, 180)}, so 359 and -1 are one degree apart. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
```

with the constant beside `SLICE_NODES`:

```java
    /**
     * How far the reported yaw may drift from the requested one before the round-trip check fires.
     *
     * <p>One degree. The write is a float field assignment on both versions, so an adapter that
     * works is exact; the tolerance exists for the float round trip through {@code Math.toDegrees}
     * and for a mouse that moved a pixel between the two ticks, not to absorb a real failure — the
     * failure this catches is a wrong field or no write at all, which is degrees or tens of degrees
     * out.
     */
    static final float LOOK_TOLERANCE_DEGREES = 1.0f;
```

Append the notice in `report(...)`, beside the standing notice:

```java
        if (activeLookNotice != null) {
            append(summary, map, activeLookNotice);
        }
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :runtime:test --tests '*PathProbeTest*'`
Expected: PASS — 6 new tests plus Task 4's six, all green.

- [ ] **Step 5: Wire both adapters**

In both mods, pass the actuator to `start` and the player to `advance`:

```java
            if (path) {
                ProbeReport refused = probe.start(core.blocks(), context.player(), context.actuator());
                if (refused != null) {
                    LOGGER.info(refused.summary());
                }
            }
            ProbeReport report = probe.advance(context.player());
```

> **`advance` is called on every tick, including ticks with no run in flight** — that is why it
> returns immediately when `active == null`, and the ordering matters: the `active == null` check
> comes first, so passing a player is harmless when nothing is running. Keep `advance` outside the
> `if (path)` block exactly where it is today.

- [ ] **Step 6: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Full-suite count**

Run: `./gradlew build --rerun-tasks`
Then count by summing every `TEST-*.xml` — do not use a filtered run, which corrupts the counts.
Record the before (513) and after totals in the commit message.

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/dev/continuo/runtime/PathProbe.java \
        runtime/src/test/java/dev/continuo/runtime/PathProbeTest.java \
        adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java \
        adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java
git commit -m "feat(d1): the probe faces its goal, and the two SPI halves check each other

Pressing the path key now turns the player toward the marked goal, which is
setLook's whole in-game done criterion and needs no instrumentation to read.

It also checks the round trip: setLook writes on one tick, IPlayerView.yaw()
reads on the next, and a mismatch means one of the two new SPI halves is wrong.
A notice rather than an assertion, because the contract genuinely permits
divergence -- the mouse and the server both write rotation -- but the two
halves checking each other is the only leverage this project has on adapter
code no test can instantiate."
```

---

## After the last task

**Do not claim D1 is done on a green build.** Criterion 7 of the spec is in-game and cannot be met
headlessly. Hand back to the owner with:

1. The full-suite count, before and after, from an unfiltered `build --rerun-tasks`.
2. The adapter audit numbers from Task 1 Step 12.
3. **A request for one in-game run on each version**, checking, per spec §9.2 and §9.3:
   - the path key turns the player to face the marked goal;
   - no `onGround is true but the block below the feet` notice while standing on flat ground;
   - no `setLook did not take effect` notice on a tick where the mouse was still;
   - the 40-tick walk still travels ≈8 blocks — the M1/M2 smoke item, re-run because Task 3 changed
     the code under it;
   - the reported `player x y z` matches the F3 readout.
4. The mutation list from spec §10.2 for the final review, **executed rather than read**. Mutation 4
   — `ForgePlayerView.y()` returning `posY` — is the one that matters: no headless test can kill it,
   and running it in a client is the only way to learn whether the standing invariant actually works.
   Spec §14 says that check has never been run and its value is an argument rather than a
   measurement; this is what converts it.

Predicted kills in §10.2 are **hypotheses**, not predictions — they were wrong in 3 of 6 in C4 and
3 of 7 in C5. A surviving mutant is a finding, and the three cases (unpinned guarantee, equivalent
mutant, correct as-is) need different responses.
