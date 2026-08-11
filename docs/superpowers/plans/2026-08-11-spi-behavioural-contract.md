# SPI Behavioural Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write the SPI's behavioural semantics into the `platform` module's javadoc, and make the one existing adapter conform to them.

**Architecture:** Four cross-cutting rules live once, numbered, in `package-info.java`; per-type javadoc states only type-specific semantics and cites the rules by number. The Fabric adapter then gains an in-world tick guard, `POST` phase delivery, and fault handling with world-load recovery. No SPI signature changes, no new types, no core behaviour changes.

**Tech Stack:** Java 8 bytecode (platform, core) on a Java 21 toolchain, Gradle 9.6.1 via `./gradlew`, JUnit 5, Fabric Loom 1.17.17 with official Mojang mappings.

**Spec:** `docs/superpowers/specs/2026-08-11-spi-behavioural-contract-design.md`

## Global Constraints

- **Use `./gradlew`, never `gradle`.** There is no `gradle` on PATH.
- **No `net.minecraft` in `platform` or `core`.** Enforced by `checkCorePurity`.
- **Java 8 bytecode (major 52) in `platform` and `core`.** Enforced by `checkCoreBytecode`. No records, sealed types, pattern switches, or `var` in these modules.
- **No new types in `dev.continuo.platform`, and no signature changes.** The package's own documentation states that every type added is a future version-compatibility problem.
- **Do not touch `.github/workflows/ci.yml`, do not add a git remote, do not push.** Standing instruction from the owner.
- **Do not decide edge- vs level-triggered actuation.** Deferred to M5. Rule 4 documents the hazard; it does not resolve it.
- **RFC 2119 keywords** (MUST, MUST NOT, MAY) are used deliberately throughout the javadoc. Keep them.
- **Rule numbering is load-bearing.** M2's `platform-testkit` will mirror it one case per rule. Do not renumber.

## A note on testing in this plan

Most of this plan is not test-driven, and pretending otherwise would produce fake tests. Be aware of the honest situation before you start:

- **The core does not change.** `ContinuoCore.onClientTick` already ignores `POST`, and `ContinuoCoreTest.ignoresPostPhaseTicks` already asserts it.
- **Nothing in the core violates the new contract**, so there is no red-green cycle available there. Task 4 adds one test that is expected to **pass on first run** — it pins a newly-stated rule against future change. The plan says so explicitly rather than staging a fake failure.
- **The adapter has zero automated tests** and cannot get them without Minecraft on the classpath. Task 5's verification is compilation plus the manual smoke checklist in Task 6.
- **Global rule 3 (faults) ships unverified.** This is a known, accepted gap recorded in the spec §7. Do not add a deliberate throw to the tree to test it, and do not claim the checklist covers it.

---

### Task 1: Global rules in `package-info`

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/package-info.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the four numbered global rules that Tasks 2, 3 and 5 cite by number. Rule numbering is fixed here: 1 Threading, 2 Lifecycle, 3 Faults, 4 Input persistence.

- [ ] **Step 1: Replace the file contents**

The existing three-sentence package doc is kept verbatim as the opening paragraph; the rules are appended.

```java
/**
 * Continuo platform SPI.
 *
 * <p>The contract between the pure core and any Minecraft version. Nothing in this package
 * may reference {@code net.minecraft}, and nothing may assume a Minecraft version newer
 * than 1.7.10. Every type added here is a future version-compatibility problem, so keep
 * the surface minimal.
 *
 * <h2>Global rules</h2>
 *
 * <p>These four rules are cross-cutting: they bind every type in this package, in both
 * directions. Per-type documentation cites them by number. Conformance tests are expected
 * to mirror this numbering, one case per rule. The keywords MUST, MUST NOT and MAY carry
 * their RFC 2119 meanings.
 *
 * <h3>Rule 1 — Threading</h3>
 *
 * <p>Every method in this package, in both directions, is called on the client main
 * thread. No implementation may block. No method may be called from any other thread.
 * Both target versions are single-threaded at the tick level; this rule exists to forbid
 * an adapter from routing calls through an asynchronous event bus.
 *
 * <h3>Rule 2 — Lifecycle</h3>
 *
 * <p>The core's {@code start} method is called exactly once per adapter lifetime, before
 * any other core method. The core's {@code stop} method may be called any number of times,
 * is idempotent, and leaves the core reusable — no second {@code start} follows it. An
 * adapter MUST call {@code stop} on world unload, on disconnect, and on client shutdown.
 *
 * <p>This rule binds adapters, not the core. The current core happens to tolerate a second
 * {@code start} call, and a test pins that behaviour, but an adapter MUST NOT rely on it.
 *
 * <h3>Rule 3 — Faults</h3>
 *
 * <p>If a core method throws, the adapter MUST catch it, log it with a stack trace, call
 * the core's {@code stop} method to release any held input, and deliver no further ticks
 * until the next world load clears the fault. A core fault MUST NOT propagate into the
 * game's tick loop. If the {@code stop} call inside the fault handler itself throws, the
 * adapter MUST log that too and still enter the faulted state: the handler must not be able
 * to fault.
 *
 * <p>A bot bug must never crash the user's game, and a half-dead core must never leave a
 * movement key held. Recovery is tied to world load because that is the same event that
 * opens the tick window under {@link dev.continuo.platform.IGameEvents#onClientTick} — one
 * event, one state transition, no separate recovery machinery.
 *
 * <h3>Rule 4 — Input persistence is not guaranteed</h3>
 *
 * <p>State set through {@link dev.continuo.platform.IActuator#setInput} may be cleared by
 * the platform at any time without notice. Any screen opening does this on both target
 * versions ({@code KeyMapping.releaseAll}; 1.7.10's {@code KeyBinding.unPressAllKeys}), as
 * does the user physically tapping the key. This SPI requires neither edge- nor
 * level-triggered actuation from core or adapter.
 *
 * <p>Resolving this is deferred to milestone M5, whose per-tick position resync will make
 * re-assertion of held inputs a special case of the same reconciliation loop. Until then
 * every adapter MUST behave identically here, so that M5 can change them all in one move.
 * The current core's assumption that a single {@code setInput(FORWARD, true)} persists for
 * forty ticks is documented-as-unguaranteed by this rule, not fixed by it.
 */
package dev.continuo.platform;
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :platform:compileJava`
Expected: `BUILD SUCCESSFUL`.

Note: `package-info.java` emits no class file for a package with no annotations, so a successful compile here proves the syntax parses and nothing more. The link targets are checked in Task 7 if you run it.

- [ ] **Step 3: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/package-info.java
git commit -m "docs(platform): add the SPI's four global rules to package-info"
```

---

### Task 2: The tick contract — `IGameEvents` and `TickPhase`

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/IGameEvents.java`
- Modify: `platform/src/main/java/dev/continuo/platform/TickPhase.java`

**Interfaces:**
- Consumes: the rule numbering from Task 1.
- Produces: the "both phases MUST be delivered" and "in-world only" requirements that Task 5 implements and Task 6 smoke-tests.

These two types are one task because the phase-ordering obligation is stated in both and must not drift between them.

- [ ] **Step 1: Replace `IGameEvents.java`**

```java
package dev.continuo.platform;

/**
 * Events flowing from the game into the core.
 *
 * <p>Note the direction: the core implements this and the adapter calls it. The adapter
 * holds the core, never the reverse.
 *
 * <p>Subject to all four global rules in this package's documentation — in particular rule
 * 3, which makes the adapter, not the core, responsible for anything these methods throw.
 */
public interface IGameEvents {

    /**
     * Called once per client <em>tick</em>, per phase.
     *
     * <p>Adapter obligations:
     *
     * <ul>
     *   <li><b>Tick, not frame.</b> An adapter MUST NOT bind this to a frame or render
     *       event. The nominal rate is twenty calls per second per phase; it may be lower
     *       when the game cannot keep up, and MUST NOT be higher.
     *   <li><b>{@link TickPhase#PRE} MUST fire before the game reads input for that
     *       tick.</b> The consequence is the part worth stating: {@link IActuator#setInput}
     *       called during {@code PRE} of tick <i>N</i> affects the player's movement on
     *       tick <i>N</i>. This is why a forty-tick walk yields forty ticks of travel and
     *       not thirty-nine.
     *   <li><b>{@link TickPhase#POST} MUST fire after the game has finished processing that
     *       tick's logic</b>, and after {@code PRE} for the same tick.
     *   <li><b>Both phases MUST be delivered.</b> An adapter that delivers only {@code PRE}
     *       is not conformant, even though the current core ignores {@code POST}. Optional
     *       phase delivery would mean the core could never use {@code POST} without a
     *       capability check, which is exactly the cross-adapter divergence this contract
     *       exists to prevent.
     *   <li><b>Delivered only while a world is loaded and a local player exists.</b> An
     *       adapter MUST NOT call this at the main menu, on a loading screen, or at any
     *       other time when there is no local player.
     *   <li><b>MUST NOT be delivered re-entrantly.</b> One call must return before the next
     *       begins.
     * </ul>
     *
     * @param phase which side of the game's own tick processing this call is on
     */
    void onClientTick(TickPhase phase);
}
```

- [ ] **Step 2: Replace `TickPhase.java`**

```java
package dev.continuo.platform;

/**
 * Which side of the game's own tick processing a callback is running on.
 *
 * <p>Adapters MUST deliver both constants, and MUST deliver {@link #PRE} before
 * {@link #POST} for the same tick. See {@link IGameEvents#onClientTick} for the full
 * obligation.
 */
public enum TickPhase {

    /**
     * Before the game reads input for this tick. An {@link IActuator#setInput} call made
     * during this phase takes effect on this same tick.
     */
    PRE,

    /**
     * After the game has finished processing this tick's logic. No core behaviour uses this
     * phase yet; adapters MUST deliver it regardless, so that it is available without a
     * capability check when the core does.
     */
    POST
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :platform:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/IGameEvents.java platform/src/main/java/dev/continuo/platform/TickPhase.java
git commit -m "docs(platform): specify onClientTick tick, phase and window semantics"
```

---

### Task 3: The remaining five types

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/IActuator.java`
- Modify: `platform/src/main/java/dev/continuo/platform/Input.java`
- Modify: `platform/src/main/java/dev/continuo/platform/IPlatformContext.java`
- Modify: `platform/src/main/java/dev/continuo/platform/IPlatformInfo.java`
- Modify: `platform/src/main/java/dev/continuo/platform/Loader.java`

**Interfaces:**
- Consumes: rule numbering from Task 1; the `PRE` same-tick guarantee from Task 2.
- Produces: nothing later tasks depend on.

Grouped as one task because each is a few lines of javadoc with no interdependency; a reviewer evaluates them together.

- [ ] **Step 1: Replace `IActuator.java`**

```java
package dev.continuo.platform;

/**
 * The single channel through which the core influences the game.
 *
 * <p>Implemented by adapters. Every effect the core has on the world passes through here,
 * which is what makes the core testable and what will later make input humanization a
 * single seam rather than a cross-cutting concern.
 */
public interface IActuator {

    /**
     * Sets a movement input to pressed or released.
     *
     * <p>Takes effect at the game's next input read, and therefore on the current tick when
     * called from {@link TickPhase#PRE}.
     *
     * <p>Idempotent: setting an already-pressed input to pressed is a no-op from the game's
     * perspective.
     *
     * <p>The effect does not necessarily persist. Global rule 4 in this package's
     * documentation applies: the platform may clear input state at any time without notice,
     * and neither core nor adapter may assume a held input survives to the next tick.
     *
     * <p>Adapters MUST support every {@link Input} constant; throwing for a valid constant
     * is a conformance failure. The core MUST NOT pass {@code null}, and adapter behaviour
     * on {@code null} is unspecified.
     *
     * @param input   which movement input to change; never {@code null}
     * @param pressed {@code true} to press, {@code false} to release
     */
    void setInput(Input input, boolean pressed);
}
```

- [ ] **Step 2: Replace the class javadoc in `Input.java`**

Only the javadoc block changes; the seven constants stay exactly as they are.

```java
/**
 * Abstract movement inputs, named for intent rather than for any keyboard layout or
 * Minecraft version's internal naming.
 *
 * <p>Adapters MUST map every constant here to a real game input. Adding a constant is a
 * breaking change for every adapter.
 */
```

- [ ] **Step 3: Replace `IPlatformContext.java`**

```java
package dev.continuo.platform;

/**
 * Everything the adapter hands the core at startup.
 *
 * <p>Bundled into one type so that adding a capability later changes one signature rather
 * than every call site.
 *
 * <p>Valid for the adapter's entire lifetime. Both accessors MUST NOT return {@code null},
 * and MUST return the same instance on every call — the core may therefore cache what they
 * return.
 */
public interface IPlatformContext {

    /**
     * The actuator for this platform.
     *
     * @return the actuator; never {@code null}, and the same instance on every call
     */
    IActuator actuator();

    /**
     * Metadata about this platform.
     *
     * @return the platform info; never {@code null}, and the same instance on every call
     */
    IPlatformInfo info();
}
```

- [ ] **Step 4: Replace `IPlatformInfo.java`**

```java
package dev.continuo.platform;

/**
 * Metadata about the platform the core is running on.
 *
 * <p>Has no consumer in A1. It is present because it costs nothing, it establishes the
 * direction capability negotiation will need later, and it gives the smoke check a way to
 * prove which adapter is actually loaded.
 */
public interface IPlatformInfo {

    /**
     * The game's release version as the loader reports it, for example {@code "1.21.11"} or
     * {@code "1.7.10"}.
     *
     * <p>This is <em>not</em> for feature detection. Branching core behaviour on a parsed
     * version string is what capability negotiation is for, and that does not exist yet.
     *
     * @return the game version, never {@code null}; {@code "unknown"} when it cannot be
     *         determined
     */
    String gameVersion();

    /**
     * The mod loader hosting this adapter.
     *
     * @return the loader; never {@code null}, and constant for the adapter's lifetime
     */
    Loader loader();
}
```

- [ ] **Step 5: Add a class javadoc to `Loader.java`**

`Loader.java` currently has no javadoc at all. Add this above `public enum Loader`, leaving the three constants unchanged.

```java
/**
 * The mod loaders Continuo can run under.
 *
 * <p>Adding a constant breaks exhaustive switches in adapters and is therefore a breaking
 * change to this package.
 */
```

- [ ] **Step 6: Verify the whole pure-module build is still green**

Run: `./gradlew :platform:build :core:build`
Expected: `BUILD SUCCESSFUL`, with `checkCorePurity` and `checkCoreBytecode` passing for both modules and 14 core tests passing.

- [ ] **Step 7: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/
git commit -m "docs(platform): specify actuator, context, info and enum semantics"
```

---

### Task 4: Pin the two core-side contract claims

**Files:**
- Modify: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`

**Interfaces:**
- Consumes: global rule 2 from Task 1.
- Produces: nothing later tasks depend on.

**Read this before you start.** Rule 2 states that `stop()` is idempotent. `ContinuoCore.stop()` already satisfies this — after the first call `walking` is false, so subsequent calls touch nothing. **The test you are about to add will pass the first time you run it.** That is expected and correct: it is a regression pin for a rule that was just written down, not a red-green cycle. Do not manufacture a failure, and do not skip the test on the grounds that it passes immediately. Run it and confirm it passes for the right reason.

- [ ] **Step 1: Add the idempotency test**

Insert after `stopWhenNotWalkingReleasesNothing` (currently ends at line 125).

```java
    /**
     * Global rule 2 states that {@code stop()} is idempotent. The core already satisfies
     * this; the test exists to pin it, so that a future change to {@code stop()} cannot
     * quietly break an adapter that calls it on both world unload and client shutdown.
     */
    @Test
    void stopIsIdempotent() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        core.stop();
        core.stop();

        assertEquals(0, actuator.callCount(), "repeated stop() must not touch the actuator");
    }
```

- [ ] **Step 2: Update the `startTwiceReplacesContext` javadoc**

Its current comment says the behaviour is "not a stated contract". Rule 2 now speaks to it, so the comment must point at the rule instead of contradicting it. Replace the existing javadoc block above `startTwiceReplacesContext` (currently lines 177-182) with:

```java
    /**
     * Pins current behaviour: calling {@code start()} a second time silently replaces the
     * context rather than throwing.
     *
     * <p>Global rule 2 says an adapter calls {@code start()} exactly once per lifetime. That
     * rule binds adapters, not the core, and this leniency is deliberately not promoted to a
     * guarantee — an adapter MUST NOT rely on it. If the core's behaviour here ever changes,
     * this test should change with it rather than be deleted silently.
     */
```

- [ ] **Step 3: Run the core tests**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 15 tests passing (14 existing plus `stopIsIdempotent`), none skipped.

- [ ] **Step 4: Confirm the new test passes for the right reason**

Temporarily change the new test's assertion from `0` to `1` and re-run `./gradlew :core:test`.
Expected: `stopIsIdempotent` FAILS with `expected: <1> but was: <0>`.

This proves the test actually executes and actually observes the actuator, rather than passing vacuously. **Revert the assertion back to `0`** and re-run to confirm green before committing. This step exists because M1 shipped two checks that passed while enforcing nothing.

- [ ] **Step 5: Commit**

```bash
git add core/src/test/java/dev/continuo/core/ContinuoCoreTest.java
git commit -m "test(core): pin stop() idempotency and align start-twice note with rule 2"
```

---

### Task 5: Fabric adapter conformance

**Files:**
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`

**Interfaces:**
- Consumes: global rules 2 and 3 from Task 1; the in-world and both-phases requirements from Task 2.
- Produces: the observable behaviours Task 6's checklist steps 9 and 10 verify.

All four conformance changes land in one task and one file. They are not split because the faulted flag is read by the same two tick handlers the guard and `POST` delivery modify — splitting would mean writing a throwaway intermediate version of the same method.

**Heads-up on build time:** this is the first task that compiles the adapter. Loom will download and remap Minecraft 1.21.11 on a cold cache, which takes several minutes and needs network. That is normal, not a hang.

- [ ] **Step 1: Replace `ContinuoFabricMod.java`**

The imports are unchanged from the current file. What changes: a `faulted` field, an `inWorld` guard, an `END_CLIENT_TICK` registration, a `guarded` helper, a `JOIN` registration, and `guarded` wrapping the two existing `stop()` call sites.

```java
package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.ContinuoCore;
import dev.continuo.platform.TickPhase;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring only. Translates a keypress into a core method call and a client tick into a core
 * tick. Every behavioural decision lives in {@link ContinuoCore}.
 *
 * <p>Implements the four global rules documented in the {@code dev.continuo.platform}
 * package. Two of them need machinery here: rule 2's tick window (the {@link #inWorld}
 * guard) and rule 3's fault handling ({@link #guarded} and {@link #faulted}).
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private final ContinuoCore core = new ContinuoCore();
    private KeyMapping walkKey;

    /**
     * Set when a core call throws, per global rule 3. While set, no ticks are delivered.
     * Cleared on the next world load.
     */
    private boolean faulted;

    @Override
    public void onInitializeClient() {
        // 1.21.11 replaced the old String-literal keybind category with a registered
        // KeyMapping.Category keyed by an Identifier. This is a name/type correction only;
        // the category still exists purely to label the controls screen entry.
        KeyMapping.Category category =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("continuo", "main"));

        walkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.walk",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            category
        ));

        FabricPlatformContext context = new FabricPlatformContext(Minecraft.getInstance());
        core.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!inWorld(client)) {
                // Drain clicks made outside a world so a title-screen keypress cannot fire
                // a walk the instant the next world loads.
                while (walkKey.consumeClick()) {
                    // discarded deliberately
                }
                return;
            }
            if (faulted) {
                return;
            }
            guarded(() -> {
                while (walkKey.consumeClick()) {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
                core.onClientTick(TickPhase.PRE);
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!inWorld(client) || faulted) {
                return;
            }
            guarded(() -> core.onClientTick(TickPhase.POST));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (faulted) {
                LOGGER.info("Continuo fault cleared by world load");
                faulted = false;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Continuo stopping: disconnected");
            guarded(core::stop);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("Continuo stopping: client shutting down");
            guarded(core::stop);
        });
    }

    /**
     * Global rule 2's tick window: ticks are delivered only while a world is loaded and a
     * local player exists.
     */
    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    /**
     * Runs a core call under global rule 3. Nothing the core throws reaches the game's tick
     * loop, the core is stopped so it cannot leave a movement key held, and no further ticks
     * are delivered until the next world load.
     */
    private void guarded(Runnable coreCall) {
        try {
            coreCall.run();
        } catch (Throwable thrown) {
            // Set before stopping: if stop() throws too, the faulted state must still hold.
            faulted = true;
            LOGGER.error("Continuo core faulted; no further ticks until the next world load", thrown);
            try {
                core.stop();
            } catch (Throwable stopFailure) {
                LOGGER.error("Continuo core.stop() also failed while handling a fault", stopFailure);
            }
        }
    }
}
```

- [ ] **Step 2: Compile the adapter**

Run: `./gradlew :adapters:adapter-fabric-1.21.11:compileJava`
Expected: `BUILD SUCCESSFUL`.

If `client.level` or `client.player` does not resolve, check the mapping — this module uses `loom.officialMojangMappings()`, so the fields are `level` and `player`, not the Yarn `world`/`player`. If `ClientPlayConnectionEvents.JOIN`'s lambda arity is wrong, its signature is `(ClientPacketListener handler, PacketSender sender, Minecraft client)`.

- [ ] **Step 3: Run the full build**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`. 15 core tests pass. `checkCorePurity`, `checkCoreBytecode` and `checkDependencyDirection` all pass. Deprecation warnings about "incompatible with Gradle 10" are pre-existing and expected — ignore them.

- [ ] **Step 4: Commit**

```bash
git add adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java
git commit -m "fix(fabric): conform to the SPI tick window, phase and fault rules"
```

---

### Task 6: Smoke checklist — one correction, two additions

**Files:**
- Modify: `docs/smoke-checklist-a1.md`

**Interfaces:**
- Consumes: the adapter behaviour from Task 5.
- Produces: the manual verification that Task 5 has no automated coverage.

The correction matters more than the additions. Step 5's existing diagnostic becomes actively misleading the moment `POST` delivery lands, and would send a future debugger after a non-bug.

- [ ] **Step 1: Correct step 5's doubled-distance diagnostic**

Find this text under step 5:

```
   - Roughly double (~17 blocks) or roughly half (~4 blocks) means the tick hook is firing at
     the wrong rate (e.g. registered on both client tick phases, or on server tick instead of
     client tick).
```

Replace it with:

```
   - Roughly double (~17 blocks) or roughly half (~4 blocks) means the core is acting on the
     wrong number of ticks. Note that the adapter is *deliberately* registered on both client
     tick phases (START_CLIENT_TICK -> PRE, END_CLIENT_TICK -> POST) and that is correct and
     required by the SPI contract. The bug to look for is the core acting on POST as well as
     PRE, or the hook being on server tick instead of client tick.
```

- [ ] **Step 2: Add steps 9 and 10**

Append after step 8, before the closing "Record the result" paragraph.

```
9. **Title-screen keypress.** From the main menu, before loading any world, press `K` five
   or six times. Then load the world from step 2.
   *Observe:* the log must **not** contain `Continuo walk requested` from those presses, and
   the player must not start walking on its own at any point after the world loads.
   *Why this matters:* the SPI contract delivers ticks only while a world is loaded with a
   local player, and the adapter drains clicks made outside a world so they cannot fire on
   join. A walk starting the moment you spawn means the drain is missing; a
   `Continuo walk requested` line logged at the title screen means the in-world guard is
   missing.

10. **Leave a singleplayer world mid-walk.** Press `K`, and while the bot is still moving
    choose "Save and Quit to Title". Stay at the title screen this time rather than
    rejoining.
    *Observe:* the log must contain `Continuo stopping: disconnected`.
    *Why this matters:* global rule 2 requires `stop()` on world unload, and the adapter
    relies on `DISCONNECT` alone to cover it. Step 8 checks the symptom after a rejoin; this
    step checks the cause directly, in the singleplayer case that the design flagged as
    verify-don't-assume. If the line is absent, `DISCONNECT` does not fire on singleplayer
    exit and the adapter needs a separate world-unload hook.
```

- [ ] **Step 3: Record the known coverage gap**

Append this paragraph immediately before the final "Record the result (pass/fail) of each step individually" line.

```
**Not covered by this checklist:** global rule 3 (fault handling). Exercising it requires
deliberately making the core throw, which is not something to leave in the tree. Rule 3 is
implemented and knowingly unverified until M2's `platform-testkit` covers it. Do not record
this checklist as evidence that fault handling works.
```

- [ ] **Step 4: Read the whole file back**

Run: `git diff docs/smoke-checklist-a1.md`
Expected: three changes present — the step 5 replacement, the steps 9-10 addition, and the coverage-gap paragraph. (The latter two may appear as one hunk since they are adjacent.) Confirm the step numbering runs 1 through 10 with no duplicates and no gaps, and that the coverage-gap paragraph sits before the final "Record the result" line rather than after it.

- [ ] **Step 5: Commit**

```bash
git add docs/smoke-checklist-a1.md
git commit -m "docs(smoke): correct the both-phases diagnostic and cover the tick window"
```

---

### Task 7: Machine-check the javadoc — OPTIONAL, BEYOND THE APPROVED SPEC

**Files:**
- Modify: `buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts`

**Interfaces:**
- Consumes: all javadoc from Tasks 1-3.
- Produces: nothing later tasks depend on.

**This task is not in the approved spec.** It was added because this plan writes a large volume of javadoc containing roughly twenty `{@link}` cross-references, and nothing in the build currently checks that any of them resolve — a broken link is invisible until someone generates docs, which nobody does. It is deliberately last so it can be dropped without affecting anything else. **Cut this task if you do not want the scope widened.**

It is consistent with the project's standing preference for machine-checked invariants over documented ones, and with its rule that a new check must be proven to fire before it is trusted.

- [ ] **Step 1: Add the javadoc check to the convention plugin**

Append to `buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts`, after the existing `checkCoreBytecode` registration and before the final `tasks.named("check")` block.

```kotlin
// Nothing else in the build reads the javadoc, so a broken {@link} in the SPI's behavioural
// contract would be invisible. -Xwerror promotes doclint warnings to failures; -missing is
// excluded because not every member is documented and requiring that is a separate argument.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Xwerror", true)
    }
}
```

Then change the existing final block from:

```kotlin
tasks.named("check") {
    dependsOn(checkCorePurity, checkCoreBytecode)
}
```

to:

```kotlin
tasks.named("check") {
    dependsOn(checkCorePurity, checkCoreBytecode, tasks.named("javadoc"))
}
```

- [ ] **Step 2: Verify the javadoc currently passes**

Run: `./gradlew :platform:javadoc :core:javadoc`
Expected: `BUILD SUCCESSFUL`.

If it fails, the failures are real — fix the offending javadoc in `platform/` or `core/` rather than weakening the doclint options. The most likely cause is a `{@link}` to a type or member that does not exist.

- [ ] **Step 3: Prove the check actually fires**

A check that has never failed is not known to work. M1 shipped two that passed vacuously.

Temporarily add this line to the class javadoc of `platform/src/main/java/dev/continuo/platform/IActuator.java`:

```java
 * <p>Deliberate breakage to prove the check fires: {@link NoSuchTypeExistsHere}.
```

Run: `./gradlew :platform:javadoc`
Expected: **FAILURE**, with an error naming `NoSuchTypeExistsHere` — something like `error: reference not found`.

If it passes, the check is not working. Do not proceed; investigate before continuing.

- [ ] **Step 4: Revert the deliberate breakage**

Remove the line added in step 3.

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`, with `javadoc` now running as part of `check` for both `platform` and `core`.

Confirm `git status` shows `buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts` as the only modified file — if `IActuator.java` still appears, the breakage was not fully reverted.

- [ ] **Step 5: Commit**

```bash
git add buildSrc/src/main/kotlin/continuo-pure-module.gradle.kts
git commit -m "build: fail the build on unresolvable javadoc references"
```

---

## Done when

- [ ] All four global rules and all seven types carry their contract javadoc.
- [ ] `./gradlew clean build` is green: 15 core tests, three (or four, with Task 7) invariant checks.
- [ ] The Fabric adapter delivers both tick phases, only in-world, and cannot propagate a core fault.
- [ ] The smoke checklist's step 5 diagnostic no longer contradicts the adapter, and steps 9-10 exist.
- [ ] The rule 3 coverage gap is recorded in the checklist rather than glossed.
- [ ] The manual smoke checklist has been run by the owner and passed. **This is the only evidence that Task 5 works** — do not claim the adapter conforms on the strength of a successful compile.
