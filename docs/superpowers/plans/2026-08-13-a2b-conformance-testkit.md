# A2b Conformance Testkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the version-independent conformance machinery out of both Minecraft adapters into a new `:runtime` module, and build a `:platform-testkit` conformance suite that asserts global rules 2 and 3, the `onClientTick` contract, and the click drain offline — the three behaviours both smoke checklists disclaim.

**Architecture:** A new seam interface `CoreApi` in `:core` (extending the existing `IGameEvents`) lets a recording fake stand in for `ContinuoCore`. A new pure module `:runtime` holds `AdapterRuntime`, a transcription of the state machine currently duplicated in `ContinuoFabricMod` and `ContinuoForgeMod`, expressed against an opaque level object, an opaque player object, a boolean click poll, and a log interface. A new pure module `:platform-testkit` holds the recording fakes and an abstract JUnit contract-test class; `:runtime`'s tests are its first subject. Both adapters shrink to keybind registration plus four forwarding calls.

**Tech Stack:** Java 8 source and bytecode (enforced), Gradle Kotlin DSL, JUnit 5 (`junit_version=5.11.4` in `gradle.properties`), the existing `continuo-pure-module` convention plugin.

**Spec:** [`docs/superpowers/specs/2026-08-13-a2b-conformance-testkit-design.md`](../specs/2026-08-13-a2b-conformance-testkit-design.md)

## Global Constraints

Every task's requirements implicitly include this section.

- **No type, method, signature, or enum constant may be added, removed, or altered in `dev.continuo.platform`.** This is the spec's hardest constraint. `platform/src/main/java/dev/continuo/platform/package-info.java` is edited in Task 10 for documentation only.
- **`:core`, `:runtime` and `:platform-testkit` compile with `options.release = 8`** via the `continuo-pure-module` convention. `checkCoreBytecode` fails the build on any class above major version 52. No `var`, no `List.of`, no records, no switch expressions, no text blocks. Lambdas and method references are Java 8 and are permitted.
- **`checkCorePurity` fails on any bytecode reference to `net/minecraft`, `net.minecraft`, `net/fabricmc`, `net.fabricmc`, `net/minecraftforge`, `net.minecraftforge`, `cpw/mods`, or `cpw.mods`** in `:core`, `:runtime` and `:platform-testkit`. Level and player are passed as `java.lang.Object`.
- **The `continuo-pure-module` convention runs Javadoc with `-Xdoclint:all,-missing -Xwerror`.** An unresolvable `{@link}` fails the build. Use `{@code}` for cross-module references rather than `{@link}`.
- **Module dependency direction is declared in exactly one place**, `allowedProjectDependencies` in the root `build.gradle.kts`. Adding a module without registering it there fails `checkDependencyDirection`.
- **These three log strings are asserted by the smoke checklists and MUST survive verbatim:** `Continuo core started on {} / {}`, `Continuo walk requested`, `Continuo stopping: client level changed`.
- **`./gradlew clean` fails intermittently on Windows** with `Unable to delete directory ...\build`, caused by a stale daemon holding a RetroFuturaGradle jar open. Fix: `./gradlew --stop`, wait a few seconds, retry. It is never the change under test.
- **Documentation-only changes must be verified with `--rerun-tasks`.** Javadoc edits do not change the ABI, so `:platform:javadoc` and test tasks report `UP-TO-DATE` and a green build means nothing.
- **`GRADLE_USER_HOME` is `C:\GradleHome`,** not `~/.gradle`.
- **Do not touch `.github/workflows/ci.yml`,** add a git remote, or push. Standing instruction from the owner.
- **Do not run `git add -A`.** Stage the exact files each step names.
- **If a step's commit message claims a verification you did not actually run, rewrite the message** to say what you observed. This has gone wrong twice on this project.
- **If the plan specifies code that does not compile or contradicts what you find in the source, stop and escalate.** Do not improvise a fix and do not edit plan-verbatim code silently. This has caught two real plan errors on this project.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `core/src/main/java/dev/continuo/core/CoreApi.java` | The seam: everything an adapter runtime calls on the core |
| `platform-testkit/build.gradle.kts` | Testkit module build |
| `platform-testkit/src/main/java/dev/continuo/testkit/package-info.java` | What a green suite does and does not mean |
| `platform-testkit/src/main/java/dev/continuo/testkit/RecordingCore.java` | `CoreApi` fake recording an ordered event list, programmable to throw |
| `platform-testkit/src/main/java/dev/continuo/testkit/AdapterUnderTest.java` | What the suite needs to be able to do to any subject |
| `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java` | The abstract conformance suite |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java` | Moved from `core/src/test` |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java` | Moved from `core/src/test` |
| `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformInfo.java` | Moved from `core/src/test` |
| `runtime/build.gradle.kts` | Runtime module build |
| `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java` | The extracted state machine |
| `runtime/src/main/java/dev/continuo/runtime/ClickSource.java` | Boolean click poll |
| `runtime/src/main/java/dev/continuo/runtime/RuntimeLog.java` | Two-method log abstraction |
| `runtime/src/main/java/dev/continuo/runtime/package-info.java` | Module purpose and the rules it discharges |
| `runtime/src/test/java/dev/continuo/runtime/AdapterRuntimeConformanceTest.java` | The suite's first subject |
| `runtime/src/test/java/dev/continuo/runtime/QueuedClicks.java` | Test-only `ClickSource` with a settable queue |
| `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/Slf4jRuntimeLog.java` | `RuntimeLog` over SLF4J |
| `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/Log4jRuntimeLog.java` | `RuntimeLog` over log4j2 |

**Modified:** `settings.gradle.kts`, root `build.gradle.kts`, `core/build.gradle.kts`, `core/src/main/java/dev/continuo/core/ContinuoCore.java`, `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`, both adapter `build.gradle.kts` and mod classes, `platform/src/main/java/dev/continuo/platform/package-info.java`, `docs/superpowers/specs/2026-08-11-spi-behavioural-contract-design.md`, `docs/superpowers/specs/2026-08-01-mc-automation-roadmap-design.md`, both smoke checklists.

**Deleted:** `core/src/test/java/dev/continuo/core/fakes/` (three files, moved).

---

## A deliberate gap: re-entrancy

The spec's §5.2 lists "No re-entrant delivery" among the `onClientTick` cases. **It is not implemented, by decision.** Re-entrancy is a property of the adapter's event source — whether the game can call `tickStart` again from inside a core call — and `AdapterRuntime` cannot prevent its own caller from re-entering it. Adding a re-entrancy guard would exceed the three behaviour changes §4.2 permits.

Task 4 records this in `AdapterConformanceTest`'s class javadoc alongside rules 1 and 4, as a stated gap rather than a silent one. Do not add a guard to `AdapterRuntime` to close it.

---

## Task 1: The `CoreApi` seam

**Files:**
- Create: `core/src/main/java/dev/continuo/core/CoreApi.java`
- Modify: `core/src/main/java/dev/continuo/core/ContinuoCore.java:15`
- Test: `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `dev.continuo.core.CoreApi`, a `public interface` extending `dev.continuo.platform.IGameEvents` and declaring `void start(IPlatformContext context)` and `void stop()`. `ContinuoCore implements CoreApi`. Tasks 3–9 depend on this type.

- [ ] **Step 1: Write the failing test**

Append to `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`, inside the class, before the closing brace:

```java
    /**
     * Pins the A2b seam: an adapter runtime holds the core through {@link CoreApi} so a
     * recording fake can be substituted. If this stops compiling, the seam is gone and
     * {@code platform-testkit} can no longer observe anything.
     */
    @Test
    void continuoCoreIsUsableThroughTheCoreApiSeam() {
        CoreApi seam = new ContinuoCore();
        seam.start(new FakePlatformContext());
        seam.onClientTick(TickPhase.PRE);
        seam.stop();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.continuo.core.ContinuoCoreTest"`
Expected: FAIL at compilation with `cannot find symbol: class CoreApi`.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/main/java/dev/continuo/core/CoreApi.java`:

```java
package dev.continuo.core;

import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlatformContext;

/**
 * Everything an adapter runtime calls on the core.
 *
 * <p>This is the A2b injection seam. It exists so that a conformance suite can substitute a
 * recording implementation for {@link ContinuoCore} and observe an adapter runtime's
 * behaviour without a running game. It deliberately lives here rather than in
 * {@code dev.continuo.platform}: that package is the contract between the core and every
 * Minecraft version, and a testing concern must not become a permanent obligation on every
 * future adapter.
 *
 * <p>{@code start} and {@code stop} are the methods global rules 2 and 3 bind, and neither is
 * declared on any type in {@code dev.continuo.platform}. A suite encoding those rules has to
 * name a core-side type; this is that type.
 *
 * <p>Deliberately absent: {@code requestWalk}. It is bot behaviour, not conformance. An
 * adapter runtime dispatches a consumed click to a supplied {@code Runnable} instead, so the
 * runtime never learns what a walk is.
 */
public interface CoreApi extends IGameEvents {

    /**
     * Called once per adapter lifetime, before any other method on this interface.
     *
     * @param context everything the adapter hands the core at startup; never {@code null}
     */
    void start(IPlatformContext context);

    /** Releases any held input and resets state. Idempotent; leaves the core reusable. */
    void stop();
}
```

- [ ] **Step 4: Declare the implementation**

In `core/src/main/java/dev/continuo/core/ContinuoCore.java`, change line 15 from:

```java
public final class ContinuoCore implements IGameEvents {
```

to:

```java
public final class ContinuoCore implements CoreApi {
```

Then add `@Override` to `start` (line 29) and `stop` (line 45) — `onClientTick` already has one. The `import dev.continuo.platform.IGameEvents;` on line 3 becomes unused; remove it. `import dev.continuo.platform.IPlatformContext;` stays.

- [ ] **Step 5: Run the full core suite**

Run: `./gradlew :core:build`
Expected: PASS, 16 tests. All 15 pre-existing tests must still pass with no edits to them.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/continuo/core/CoreApi.java core/src/main/java/dev/continuo/core/ContinuoCore.java core/src/test/java/dev/continuo/core/ContinuoCoreTest.java
git commit -m "feat(core): add the CoreApi seam for the A2b conformance suite"
```

---

## Task 2: The `:platform-testkit` module and the moved fakes

**Files:**
- Create: `platform-testkit/build.gradle.kts`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformInfo.java`
- Delete: `core/src/test/java/dev/continuo/core/fakes/` (all three files)
- Modify: `settings.gradle.kts`, root `build.gradle.kts`, `core/build.gradle.kts`, `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`

**Interfaces:**
- Consumes: `dev.continuo.core.CoreApi` (Task 1).
- Produces: `dev.continuo.testkit.FakeActuator` with `List<FakeActuator.Call> calls()`, `int callCount()`, `void clear()`, and public fields `Call.input` / `Call.pressed`; `dev.continuo.testkit.FakePlatformContext` with `FakeActuator fakeActuator()`; `dev.continuo.testkit.FakePlatformInfo(String, Loader)`. Tasks 4–7 use `FakePlatformContext`.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include("core")`, add:

```kotlin
include("platform-testkit")
```

In the root `build.gradle.kts`, inside `allowedProjectDependencies`, after the `":core"` entry, add:

```kotlin
    ":platform-testkit" to setOf(":platform", ":core"),
```

- [ ] **Step 2: Create the module build script**

Create `platform-testkit/build.gradle.kts`:

```kotlin
plugins {
    id("continuo-pure-module")
}

// JUnit is an `api` dependency, not `testImplementation`: AdapterConformanceTest is a
// production type of this module — consumers extend it from their own test source sets —
// so JUnit is on this module's MAIN compile classpath, and must be on the consumer's too.
dependencies {
    api(project(":platform"))
    api(project(":core"))

    val junitVersion = project.property("junit_version") as String
    api("org.junit.jupiter:junit-jupiter:$junitVersion")
}
```

- [ ] **Step 3: Move the three fakes**

Create `platform-testkit/src/main/java/dev/continuo/testkit/FakeActuator.java` with the exact contents of `core/src/test/java/dev/continuo/core/fakes/FakeActuator.java`, changing only the package declaration to `package dev.continuo.testkit;` and making the `Call` constructor `public` (it was package-private and now has consumers outside its package — though nothing constructs one directly today, leaving it package-private makes the class unusable from another package if a future test needs to):

```java
package dev.continuo.testkit;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;

import java.util.ArrayList;
import java.util.List;

/** Records every actuator call so tests can assert on exact call sequences. */
public final class FakeActuator implements IActuator {

    public static final class Call {
        public final Input input;
        public final boolean pressed;

        public Call(Input input, boolean pressed) {
            this.input = input;
            this.pressed = pressed;
        }

        @Override
        public String toString() {
            return input + "=" + pressed;
        }
    }

    private final List<Call> calls = new ArrayList<Call>();

    @Override
    public void setInput(Input input, boolean pressed) {
        calls.add(new Call(input, pressed));
    }

    public List<Call> calls() {
        return calls;
    }

    public int callCount() {
        return calls.size();
    }

    public void clear() {
        calls.clear();
    }
}
```

Create `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformContext.java` — identical to the original but for the package line:

```java
package dev.continuo.testkit;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformContext implements IPlatformContext {

    private final FakeActuator actuator = new FakeActuator();
    private final IPlatformInfo info = new FakePlatformInfo("0.0-test", Loader.FABRIC);

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }

    public FakeActuator fakeActuator() {
        return actuator;
    }
}
```

Create `platform-testkit/src/main/java/dev/continuo/testkit/FakePlatformInfo.java` — identical to the original but for the package line:

```java
package dev.continuo.testkit;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformInfo implements IPlatformInfo {

    private final String gameVersion;
    private final Loader loader;

    public FakePlatformInfo(String gameVersion, Loader loader) {
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public Loader loader() {
        return loader;
    }
}
```

Then delete the originals:

```bash
git rm core/src/test/java/dev/continuo/core/fakes/FakeActuator.java core/src/test/java/dev/continuo/core/fakes/FakePlatformContext.java core/src/test/java/dev/continuo/core/fakes/FakePlatformInfo.java
```

- [ ] **Step 4: Re-point `:core`'s tests**

In `core/build.gradle.kts`, add to the `dependencies` block after the two JUnit lines:

```kotlin
    testImplementation(project(":platform-testkit"))
```

In `core/src/test/java/dev/continuo/core/ContinuoCoreTest.java`, replace lines 3–4:

```java
import dev.continuo.core.fakes.FakeActuator;
import dev.continuo.core.fakes.FakePlatformContext;
```

with:

```java
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlatformContext;
```

**No other line of that file changes.** If any assertion needs editing, the fakes were altered during the move and that is a defect — stop and escalate.

- [ ] **Step 5: Run the build**

Run: `./gradlew :core:build :platform-testkit:build`
Expected: PASS, 16 core tests. `checkDependencyDirection`, `checkCorePurity`, `checkCoreBytecode` and `javadoc` all green on the new module.

There is no dependency cycle: `:platform-testkit:main` depends on `:core:main`, and `:core:test` depends on `:platform-testkit:main`. Different source sets; the task graph is acyclic.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/build.gradle.kts core/src/test/java/dev/continuo/core/ContinuoCoreTest.java platform-testkit/
git commit -m "refactor(testkit): add :platform-testkit and move the core test fakes into it"
```

---

## Task 3: `RecordingCore` and `AdapterUnderTest`

**Files:**
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/RecordingCore.java`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterUnderTest.java`
- Test: `platform-testkit/src/test/java/dev/continuo/testkit/RecordingCoreTest.java`

**Interfaces:**
- Consumes: `dev.continuo.core.CoreApi` (Task 1).
- Produces: `RecordingCore` with `List<RecordingCore.Event> events()`, `int count(Event)`, `void clear()`, `IPlatformContext context()`, and `failOnStart/failOnStop/failOnPre/failOnPost(RuntimeException)` plus `stopFailing()`; the nested `enum Event { START, STOP, TICK_PRE, TICK_POST }`. `AdapterUnderTest` as specified below. Tasks 4–7 use both.

`:platform-testkit` needs a test source set for this task. Add to `platform-testkit/build.gradle.kts`:

```kotlin
dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

(JUnit itself is already on the test compile classpath via the `api` dependency added in Task 2.)

- [ ] **Step 1: Write the failing test**

Create `platform-testkit/src/test/java/dev/continuo/testkit/RecordingCoreTest.java`:

```java
package dev.continuo.testkit;

import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingCoreTest {

    @Test
    void recordsEveryCallInOrder() {
        RecordingCore core = new RecordingCore();
        FakePlatformContext ctx = new FakePlatformContext();

        core.start(ctx);
        core.onClientTick(TickPhase.PRE);
        core.onClientTick(TickPhase.POST);
        core.stop();

        assertEquals(
            Arrays.asList(
                RecordingCore.Event.START,
                RecordingCore.Event.TICK_PRE,
                RecordingCore.Event.TICK_POST,
                RecordingCore.Event.STOP),
            core.events());
        assertSame(ctx, core.context());
    }

    @Test
    void recordsTheCallBeforeThrowing() {
        RecordingCore core = new RecordingCore();
        core.start(new FakePlatformContext());
        final RuntimeException boom = new RuntimeException("boom");
        core.failOnPre(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            core.onClientTick(TickPhase.PRE));

        assertSame(boom, thrown);
        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE),
            "the call must be recorded before the throw, or fault assertions cannot see it");
    }

    @Test
    void stopFailingClearsEveryProgrammedFailure() {
        RecordingCore core = new RecordingCore();
        core.failOnStart(new RuntimeException("a"));
        core.failOnStop(new RuntimeException("b"));
        core.failOnPre(new RuntimeException("c"));
        core.failOnPost(new RuntimeException("d"));

        core.stopFailing();

        core.start(new FakePlatformContext());
        core.onClientTick(TickPhase.PRE);
        core.onClientTick(TickPhase.POST);
        core.stop();

        assertEquals(4, core.events().size());
    }

    @Test
    void clearResetsTheEventListButNotTheContext() {
        RecordingCore core = new RecordingCore();
        FakePlatformContext ctx = new FakePlatformContext();
        core.start(ctx);

        core.clear();

        assertEquals(0, core.events().size());
        assertSame(ctx, core.context());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :platform-testkit:test`
Expected: FAIL at compilation with `cannot find symbol: class RecordingCore`.

- [ ] **Step 3: Write the implementation**

Create `platform-testkit/src/main/java/dev/continuo/testkit/RecordingCore.java`:

```java
package dev.continuo.testkit;

import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.TickPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CoreApi} that records every call in order and can be programmed to throw from any
 * of them.
 *
 * <p>The programmable throwing is what makes global rule 3 testable at all. A2a could not
 * exercise rule 3 because doing so required a deliberate throw, and a deliberate throw is not
 * something to leave in shipped adapter code. Here it is the point.
 *
 * <p>Every call is recorded <em>before</em> any programmed failure is thrown, so an assertion
 * can still see that the call happened.
 */
public final class RecordingCore implements CoreApi {

    /** One observed call, in the order it arrived. */
    public enum Event { START, STOP, TICK_PRE, TICK_POST }

    private final List<Event> events = new ArrayList<Event>();

    private IPlatformContext context;
    private RuntimeException startFailure;
    private RuntimeException stopFailure;
    private RuntimeException preFailure;
    private RuntimeException postFailure;

    @Override
    public void start(IPlatformContext context) {
        events.add(Event.START);
        this.context = context;
        if (startFailure != null) {
            throw startFailure;
        }
    }

    @Override
    public void stop() {
        events.add(Event.STOP);
        if (stopFailure != null) {
            throw stopFailure;
        }
    }

    @Override
    public void onClientTick(TickPhase phase) {
        events.add(phase == TickPhase.PRE ? Event.TICK_PRE : Event.TICK_POST);
        if (phase == TickPhase.PRE && preFailure != null) {
            throw preFailure;
        }
        if (phase == TickPhase.POST && postFailure != null) {
            throw postFailure;
        }
    }

    /** Every call so far, oldest first. Live, not a copy. */
    public List<Event> events() {
        return events;
    }

    /** How many times {@code event} has been observed. */
    public int count(Event event) {
        int total = 0;
        for (Event seen : events) {
            if (seen == event) {
                total++;
            }
        }
        return total;
    }

    /** Discards the recorded events. Programmed failures and the context are unaffected. */
    public void clear() {
        events.clear();
    }

    /** The context passed to the most recent {@code start} call, or {@code null}. */
    public IPlatformContext context() {
        return context;
    }

    public void failOnStart(RuntimeException failure) {
        startFailure = failure;
    }

    public void failOnStop(RuntimeException failure) {
        stopFailure = failure;
    }

    public void failOnPre(RuntimeException failure) {
        preFailure = failure;
    }

    public void failOnPost(RuntimeException failure) {
        postFailure = failure;
    }

    /** Clears every programmed failure. */
    public void stopFailing() {
        startFailure = null;
        stopFailure = null;
        preFailure = null;
        postFailure = null;
    }
}
```

Create `platform-testkit/src/main/java/dev/continuo/testkit/AdapterUnderTest.java`:

```java
package dev.continuo.testkit;

import dev.continuo.platform.IPlatformContext;

/**
 * Everything the conformance suite needs to be able to do to a subject.
 *
 * <p>This is the reusability boundary, and its limit is worth stating plainly: a suite that
 * runs without Minecraft cannot test an adapter that binds directly to Minecraft. "Reusable
 * by any adapter" means any adapter that can be driven through this interface — which is
 * every adapter routing its conformance obligations through a version-independent object, and
 * is not every conceivable adapter.
 *
 * <p>Level and player are {@code Object} deliberately. The suite compares levels by identity
 * and null-checks players, which is exactly what the contract's level-identity condition
 * requires and all it requires.
 */
public interface AdapterUnderTest {

    /** Drives the subject's startup. */
    void start(IPlatformContext context);

    /** Drives the game's tick-start hook. */
    void tickStart(Object level, Object player);

    /** Drives the game's tick-end hook. */
    void tickEnd(Object level, Object player);

    /** Drives a main-thread client-stopping event. */
    void clientStopping();

    /** Queues {@code count} unconsumed clicks on the subject's click source. */
    void queueClick(int count);

    /** How many queued clicks the subject has dispatched to its click handler. */
    int clicksHandled();

    /**
     * Makes the {@code number}-th click dispatch (1-based, counted from now) throw.
     *
     * @param number  which dispatch throws; 1 is the next one
     * @param failure what it throws
     */
    void failClickNumber(int number, RuntimeException failure);

    /** The recording core this subject was built with. */
    RecordingCore core();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :platform-testkit:build`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add platform-testkit/
git commit -m "feat(testkit): add RecordingCore and the AdapterUnderTest subject interface"
```

---

## Task 4: `:runtime` module and global rule 2 (lifecycle)

**Files:**
- Create: `runtime/build.gradle.kts`, `runtime/src/main/java/dev/continuo/runtime/{AdapterRuntime,ClickSource,RuntimeLog,package-info}.java`
- Create: `runtime/src/test/java/dev/continuo/runtime/{AdapterRuntimeConformanceTest,QueuedClicks}.java`
- Create: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`
- Modify: `settings.gradle.kts`, root `build.gradle.kts`

**Interfaces:**
- Consumes: `CoreApi` (Task 1), `FakePlatformContext` (Task 2), `RecordingCore` and `AdapterUnderTest` (Task 3).
- Produces: `dev.continuo.runtime.AdapterRuntime` with the constructor `AdapterRuntime(CoreApi core, RuntimeLog log, ClickSource clicks, Runnable onClick)` and methods `void start(IPlatformContext)`, `void tickStart(Object, Object)`, `void tickEnd(Object, Object)`, `void clientStopping()`; `dev.continuo.runtime.ClickSource` with `boolean consumeClick()`; `dev.continuo.runtime.RuntimeLog` with `void info(String)` and `void error(String, Throwable)`; `dev.continuo.testkit.AdapterConformanceTest` with `protected abstract AdapterUnderTest newSubject(RecordingCore core)`. Tasks 5–9 depend on all of these.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include("platform-testkit")`, add:

```kotlin
include("runtime")
```

In the root `build.gradle.kts`, inside `allowedProjectDependencies`, after the `":platform-testkit"` entry, add:

```kotlin
    ":runtime" to setOf(":platform", ":core"),
```

Create `runtime/build.gradle.kts`:

```kotlin
plugins {
    id("continuo-pure-module")
}

// :core is `api`, not `implementation`: AdapterRuntime's public constructor takes a CoreApi,
// so every consumer needs that type on its own compile classpath.
dependencies {
    api(project(":platform"))
    api(project(":core"))

    val junitVersion = project.property("junit_version") as String
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(project(":platform-testkit"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 2: Write the failing tests**

Create `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`:

```java
package dev.continuo.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance suite. Extend it and return a subject from {@link #newSubject}.
 *
 * <p>Organised by the global rule numbering in {@code dev.continuo.platform}'s
 * {@code package-info}. <b>That numbering is load-bearing and must not change</b>; the
 * javadoc states that conformance tests are expected to mirror it.
 *
 * <h2>Rules with no cases, and why</h2>
 *
 * <p><b>Rule 1 (Threading)</b> — "no implementation may block" is unfalsifiable as a test.
 * The package javadoc says so; this suite records the gap rather than leaving it silent.
 *
 * <p><b>Rule 4 (Input persistence)</b> — a hazard statement, not an obligation. That
 * {@code setInput}'s effect may not persist is precisely what the SPI declines to require
 * either side to handle before M5.
 *
 * <p><b>{@code onClientTick}'s "MUST NOT be delivered re-entrantly"</b> — a property of the
 * adapter's event source, not of anything this suite can drive. A runtime cannot stop its own
 * caller from re-entering it, and adding a guard would exceed what A2b's extraction permits.
 *
 * <h2>What a green run does not mean</h2>
 *
 * <p>See this package's {@code package-info}. In short: it says nothing about whether an
 * adapter passes the correct level or player object, whether {@code setInput} moves the
 * player, whether {@code PRE} genuinely precedes the game's input read, or whether the tick
 * source is a tick rather than a frame. Those remain the smoke checklists' job.
 */
public abstract class AdapterConformanceTest {

    /** A subject wired to {@code core}. Called once per test. */
    protected abstract AdapterUnderTest newSubject(RecordingCore core);

    protected RecordingCore core;
    protected AdapterUnderTest subject;

    /** Two distinct, non-null stand-ins for client level instances. */
    protected static final Object LEVEL_A = new Object();
    protected static final Object LEVEL_B = new Object();
    /** A non-null stand-in for a local player. */
    protected static final Object PLAYER = new Object();

    @BeforeEach
    void createSubject() {
        core = new RecordingCore();
        subject = newSubject(core);
    }

    /** Starts the subject and clears the recorded {@code START}, for tests that don't assert on it. */
    protected void startAndClear() {
        subject.start(new FakePlatformContext());
        core.clear();
    }

    /** Brings the subject into a loaded world with the fault (if any) cleared, then clears events. */
    protected void enterWorld(Object level) {
        subject.tickStart(level, PLAYER);
        subject.tickEnd(level, PLAYER);
        core.clear();
    }

    // ---- Global rule 2 — Lifecycle ----

    @Test
    void startCallsCoreStartExactlyOnce() {
        FakePlatformContext ctx = new FakePlatformContext();

        subject.start(ctx);

        assertEquals(1, core.count(RecordingCore.Event.START));
        assertSame(ctx, core.context());
    }

    @Test
    void startIsTheFirstCoreCall() {
        subject.start(new FakePlatformContext());
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(RecordingCore.Event.START, core.events().get(0));
    }

    @Test
    void noCoreCallsHappenBeforeStart() {
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.clientStopping();

        assertEquals(0, core.events().size(), "a tick before start() must reach no core method");
    }

    @Test
    void secondStartIsRejected() {
        subject.start(new FakePlatformContext());

        assertThrows(IllegalStateException.class, () -> subject.start(new FakePlatformContext()));
        assertEquals(1, core.count(RecordingCore.Event.START));
    }

    @Test
    void stopsOnTransitionFromNullToNonNullLevel() {
        startAndClear();

        subject.tickStart(LEVEL_A, PLAYER);

        assertTrue(core.count(RecordingCore.Event.STOP) >= 1,
            "a world load must call stop(), which clears state left by a stop() that threw");
    }

    @Test
    void stopsOnTransitionToNullLevel() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(null, null);

        assertEquals(1, core.count(RecordingCore.Event.STOP));
    }

    @Test
    void stopsOnTransitionBetweenTwoNonNullLevels() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_B, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a dimension change replaces the level without ending the session and IS a world unload");
    }

    @Test
    void doesNotStopWhenTheLevelIsUnchanged() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.STOP));
    }

    @Test
    void clientStoppingCallsStop() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.clientStopping();

        assertEquals(1, core.count(RecordingCore.Event.STOP));
    }
}
```

Create `runtime/src/test/java/dev/continuo/runtime/QueuedClicks.java`:

```java
package dev.continuo.runtime;

/** A {@link ClickSource} backed by a settable count, mirroring how a keybind queues clicks. */
final class QueuedClicks implements ClickSource {

    private int queued;

    void queue(int count) {
        queued += count;
    }

    @Override
    public boolean consumeClick() {
        if (queued <= 0) {
            return false;
        }
        queued--;
        return true;
    }
}
```

Create `runtime/src/test/java/dev/continuo/runtime/AdapterRuntimeConformanceTest.java`:

```java
package dev.continuo.runtime;

import dev.continuo.platform.IPlatformContext;
import dev.continuo.testkit.AdapterConformanceTest;
import dev.continuo.testkit.AdapterUnderTest;
import dev.continuo.testkit.RecordingCore;

/** Runs the conformance suite against {@link AdapterRuntime}, its first subject. */
class AdapterRuntimeConformanceTest extends AdapterConformanceTest {

    @Override
    protected AdapterUnderTest newSubject(RecordingCore core) {
        return new RuntimeSubject(core);
    }

    private static final class RuntimeSubject implements AdapterUnderTest {

        private final RecordingCore core;
        private final QueuedClicks clicks = new QueuedClicks();
        private final AdapterRuntime runtime;

        private int handled;
        private int failAtClick;
        private RuntimeException clickFailure;

        RuntimeSubject(RecordingCore core) {
            this.core = core;
            this.runtime = new AdapterRuntime(
                core,
                new DiscardingLog(),
                clicks,
                new Runnable() {
                    @Override
                    public void run() {
                        handled++;
                        if (clickFailure != null && handled == failAtClick) {
                            throw clickFailure;
                        }
                    }
                });
        }

        @Override
        public void start(IPlatformContext context) {
            runtime.start(context);
        }

        @Override
        public void tickStart(Object level, Object player) {
            runtime.tickStart(level, player);
        }

        @Override
        public void tickEnd(Object level, Object player) {
            runtime.tickEnd(level, player);
        }

        @Override
        public void clientStopping() {
            runtime.clientStopping();
        }

        @Override
        public void queueClick(int count) {
            clicks.queue(count);
        }

        @Override
        public int clicksHandled() {
            return handled;
        }

        @Override
        public void failClickNumber(int number, RuntimeException failure) {
            this.failAtClick = handled + number;
            this.clickFailure = failure;
        }

        @Override
        public RecordingCore core() {
            return core;
        }
    }

    /** The suite asserts on core calls, never on log output. */
    private static final class DiscardingLog implements RuntimeLog {
        @Override
        public void info(String message) {
        }

        @Override
        public void error(String message, Throwable thrown) {
        }
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :runtime:test`
Expected: FAIL at compilation with `cannot find symbol: class AdapterRuntime`.

- [ ] **Step 4: Write the minimal implementation**

Create `runtime/src/main/java/dev/continuo/runtime/ClickSource.java`:

```java
package dev.continuo.runtime;

/**
 * A poll that consumes one queued keybind click per call.
 *
 * <p>Both target versions already satisfy this shape — 1.21.11's
 * {@code KeyMapping.consumeClick()} and 1.7.10's {@code KeyBinding.isPressed()} — so an
 * adapter supplies a one-line implementation. The runtime sees only the boolean, which is why
 * the differing method names stop being a divergence risk.
 */
public interface ClickSource {

    /** @return {@code true} if a queued click was consumed; {@code false} when none remain */
    boolean consumeClick();
}
```

Create `runtime/src/main/java/dev/continuo/runtime/RuntimeLog.java`:

```java
package dev.continuo.runtime;

/**
 * The runtime's log, abstracted because 1.7.10 predates SLF4J in Minecraft and logs through
 * log4j2.
 *
 * <p>Global rules 2 and 3 log through this, so both versions emit byte-identical text. The
 * smoke checklists assert on those strings, so this strengthens them.
 */
public interface RuntimeLog {

    void info(String message);

    void error(String message, Throwable thrown);
}
```

Create `runtime/src/main/java/dev/continuo/runtime/package-info.java`:

```java
/**
 * The adapter's side of the platform contract, expressed once for every Minecraft version.
 *
 * <p>Before A2b this machinery existed twice, in {@code ContinuoFabricMod} and
 * {@code ContinuoForgeMod}, as version-independent Java that happened to be written twice.
 * It touches the game through four things only: an identity-compared level object, a
 * null-checked player object, a boolean click poll, and a logger. None of them is a game
 * type, which is why the three behaviours both smoke checklists disclaim — global rule 3
 * fault handling, the click drain, and PRE/POST pairing — became testable offline the moment
 * the machinery moved out of classes that import {@code net.minecraft}.
 *
 * <p>This package holds no bot behaviour. What Continuo decides to do is {@code dev.continuo.core}'s
 * subject; how a host discharges the four global rules is this one's.
 */
package dev.continuo.runtime;
```

Create `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java` with the lifecycle half only — faults, the tick window and the drain arrive in Tasks 5–7:

```java
package dev.continuo.runtime;

import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;

/**
 * Discharges the adapter-side obligations of the four global rules documented in
 * {@code dev.continuo.platform}'s {@code package-info}.
 *
 * <p>An adapter constructs one of these, forwards four calls to it, and holds no conformance
 * state of its own.
 */
public final class AdapterRuntime {

    private final CoreApi core;
    private final RuntimeLog log;
    private final ClickSource clicks;
    private final Runnable onClick;

    private boolean started;

    /**
     * The client level instance last seen by {@link #tickStart}, compared by identity. Holding
     * it does not leak an unloaded world: it is overwritten with the current level the moment
     * a change is detected, so it only ever names the level that is loaded now, or
     * {@code null}.
     */
    private Object lastLevel;

    /**
     * @param core    the core to drive
     * @param log     where rule 2 and rule 3 messages go
     * @param clicks  the keybind click poll
     * @param onClick run once per consumed click, inside the rule 3 guard
     */
    public AdapterRuntime(CoreApi core, RuntimeLog log, ClickSource clicks, Runnable onClick) {
        if (core == null || log == null || clicks == null || onClick == null) {
            throw new IllegalArgumentException("no constructor argument may be null");
        }
        this.core = core;
        this.log = log;
        this.clicks = clicks;
        this.onClick = onClick;
    }

    /**
     * Discharges global rule 2's "exactly once, before any other core method".
     *
     * <p>Rule 2 binds adapters, not the core. {@code ContinuoCore} still tolerates a second
     * {@code start} and a test pins that; this guard is the adapter-side obligation, and an
     * adapter MUST NOT rely on the core's leniency.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start(IPlatformContext context) {
        if (started) {
            throw new IllegalStateException("start(IPlatformContext) has already been called");
        }
        started = true;
        core.start(context);
    }

    /** Call from the game's tick-start hook. */
    public void tickStart(Object level, Object player) {
        if (!started) {
            return;
        }
        updateLevel(level);
    }

    /** Call from the game's tick-end hook. */
    public void tickEnd(Object level, Object player) {
        if (!started) {
            return;
        }
    }

    /**
     * Call from a main-thread client-stopping event.
     *
     * <p>Global rule 2 makes this MUST-where-available: an adapter on a platform with no such
     * event, as Forge 1.7.10 has none, simply never calls this and is conformant by omission.
     */
    public void clientStopping() {
        if (!started) {
            return;
        }
        log.info("Continuo stopping: client shutting down");
        core.stop();
    }

    /**
     * Global rule 2's world-unload trigger, stated as one observable condition: the client
     * level instance being replaced or becoming {@code null}. A dimension change replaces it
     * without ending the session and counts.
     */
    private void updateLevel(Object level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;

        log.info("Continuo stopping: client level changed");
        // Not redundant on a world load: in the ordinary case the core was already stopped by
        // the transition to null and this is a no-op, but if that earlier stop() threw, this
        // is what clears the stale state.
        core.stop();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :runtime:build`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts runtime/ platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java
git commit -m "feat(runtime): add :runtime with AdapterRuntime lifecycle and the rule 2 conformance cases"
```

---

## Task 5: Global rule 3 (faults)

**Files:**
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java`

**Interfaces:**
- Consumes: everything from Task 4.
- Produces: no new public types. `AdapterRuntime` gains a private `faulted` flag and a private `guarded(Runnable)`.

- [ ] **Step 1: Write the failing tests**

First add the two imports these cases and Task 6's need, to `AdapterConformanceTest`:

```java
import java.util.Arrays;
```

```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
```

Then append to `AdapterConformanceTest`, before the closing brace:

```java
    // ---- Global rule 3 — Faults ----

    @Test
    void aThrowingPreStopsTheCore() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a faulting core must be stopped so it cannot leave a movement key held");
    }

    @Test
    void nothingPropagatesOutOfTickStart() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));

        // A bot bug must never crash the user's game.
        assertDoesNotThrow(() -> subject.tickStart(LEVEL_A, PLAYER));
    }

    @Test
    void nothingPropagatesOutOfTickEnd() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.tickStart(LEVEL_A, PLAYER);
        core.failOnPost(new RuntimeException("core bug"));

        assertDoesNotThrow(() -> subject.tickEnd(LEVEL_A, PLAYER));
    }

    @Test
    void noTicksAreDeliveredWhileFaulted() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();
        core.clear();

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void aThrowingStopInsideTheFaultHandlerStillLeavesTheRuntimeFaulted() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        core.failOnStop(new RuntimeException("stop is broken too"));

        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();
        core.clear();

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the fault handler must not be able to fault its way out of the faulted state");
    }

    @Test
    void theFaultClearsOnTheNextWorldLoadAndTicksResume() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();

        subject.tickStart(null, null);
        core.clear();
        subject.tickStart(LEVEL_B, PLAYER);
        subject.tickEnd(LEVEL_B, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE),
            "the next world load clears the fault and reopens the tick window");
        assertEquals(1, core.count(RecordingCore.Event.TICK_POST));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :runtime:test`
Expected: FAIL. `aThrowingPreStopsTheCore` fails because no `PRE` is delivered at all yet (Task 6 adds delivery), and `nothingPropagatesOutOfTickStart` passes vacuously.

**This is expected and is why Tasks 5 and 6 land together in one green build.** Note the failure list, implement Step 3, then proceed to Task 6 — the suite is not green again until Task 6 Step 4.

- [ ] **Step 3: Add the fault machinery**

In `AdapterRuntime`, add the field after `private Object lastLevel;`:

```java
    /**
     * Set when a core call throws, per global rule 3. While set, no ticks are delivered.
     * Cleared on the next world load.
     */
    private boolean faulted;
```

Replace the body of `updateLevel` with:

```java
    private void updateLevel(Object level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;

        // Clear the fault BEFORE stopping, never after. If stop() throws, guarded() sets
        // faulted again and it must stay set — clearing afterwards would let the fault handler
        // swallow its own fault, which rule 3 forbids.
        if (level != null && faulted) {
            log.info("Continuo fault cleared by world load");
            faulted = false;
        }

        log.info("Continuo stopping: client level changed");
        // Not redundant on a world load: in the ordinary case the core was already stopped by
        // the transition to null and this is a no-op, but if that earlier stop() threw, this
        // is what clears the stale state.
        guarded(new Runnable() {
            @Override
            public void run() {
                core.stop();
            }
        });
    }
```

Replace `clientStopping`'s `core.stop();` with:

```java
        guarded(new Runnable() {
            @Override
            public void run() {
                core.stop();
            }
        });
```

Add at the end of the class:

```java
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
            log.error("Continuo core faulted; no further ticks until the next world load", thrown);
            try {
                core.stop();
            } catch (Throwable stopFailure) {
                log.error("Continuo core.stop() also failed while handling a fault", stopFailure);
            }
        }
    }
```

- [ ] **Step 4: Do not commit yet**

The suite is red until Task 6, so there is nothing green to commit. **Tasks 5 and 6 are one
deliverable and are executed as a single unit**; proceed directly to Task 6 and commit there.

---

## Task 6: The tick window and PRE/POST pairing

**Files:**
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java`

**Interfaces:**
- Consumes: everything from Tasks 4–5.
- Produces: no new public types. `AdapterRuntime` gains a private `preDelivered` latch and a private static `inWorld(Object, Object)`.

- [ ] **Step 1: Write the failing tests**

Append to `AdapterConformanceTest`, before the closing brace:

```java
    // ---- IGameEvents.onClientTick — tick window and phase pairing ----

    @Test
    void preIsDeliveredBeforePostWithinATick() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(
            Arrays.asList(RecordingCore.Event.TICK_PRE, RecordingCore.Event.TICK_POST),
            core.events());
    }

    @Test
    void noTicksAreDeliveredWithNoLevel() {
        startAndClear();

        subject.tickStart(null, null);
        subject.tickEnd(null, null);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void noTicksAreDeliveredWithNoLocalPlayer() {
        startAndClear();

        subject.tickStart(LEVEL_A, null);
        subject.tickEnd(LEVEL_A, null);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the tick window requires a world AND a local player");
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void postIsNeverDeliveredWithoutASameTickPre() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_POST),
            "the exception never runs this way: POST without a same-tick PRE is never conformant");
    }

    @Test
    void theLatchDoesNotWedgeAcrossTicks() {
        startAndClear();
        enterWorld(LEVEL_A);

        // A PRE whose POST is suppressed by the window closing mid-tick.
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(null, null);
        core.clear();

        // The next tick's END must not fire a POST left over from the previous tick's PRE.
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void unpairedPreIsPermittedOnlyWhenASuppressingConditionHeld() {
        startAndClear();
        enterWorld(LEVEL_A);

        // A dimension change or disconnect processed inside the game's own tick closes the
        // window between the two halves of one tick.
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(null, null);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST),
            "the contract permits an unpaired PRE exactly when the window closed or the "
                + "runtime faulted before POST was due; here the window closed");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :runtime:test`
Expected: FAIL — no phase is delivered at all yet, so every case asserting a `TICK_PRE` fails, including the ones added in Task 5.

- [ ] **Step 3: Add the window and the latch**

In `AdapterRuntime`, add the field after `faulted`:

```java
    /**
     * Set when {@code PRE} is delivered for the current tick; cleared the moment
     * {@link #tickEnd} next runs, whether or not it goes on to deliver {@code POST}. The
     * window and the fault state are re-read independently by each phase, so either can change
     * between the two halves of one tick — a mid-tick dimension change, or a disconnect
     * processed inside the game's own tick. This latch is what stops {@code POST} from ever
     * firing without a same-tick {@code PRE}. It cannot wedge across ticks: it is
     * unconditionally cleared on every {@link #tickEnd} call, so a {@code PRE} that loses its
     * {@code POST} mid-tick never leaves the latch set for the next one.
     */
    private boolean preDelivered;
```

Replace `tickStart`'s body with:

```java
    public void tickStart(Object level, Object player) {
        if (!started) {
            return;
        }
        updateLevel(level);
        if (!inWorld(level, player)) {
            return;
        }
        if (faulted) {
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                core.onClientTick(TickPhase.PRE);
                preDelivered = true;
            }
        });
    }
```

Replace `tickEnd`'s body with:

```java
    public void tickEnd(Object level, Object player) {
        if (!started) {
            return;
        }
        boolean deliverPost = preDelivered;
        preDelivered = false;
        if (!deliverPost || !inWorld(level, player) || faulted) {
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                core.onClientTick(TickPhase.POST);
            }
        });
    }
```

Add after `updateLevel`:

```java
    /**
     * The tick window from {@code IGameEvents.onClientTick}: ticks are delivered only while a
     * world is loaded and a local player exists. Global rule 2 is lifecycle only and does not
     * state this window.
     *
     * <p>It lives here rather than in each adapter so that two conformant adapters cannot
     * drift on it — the same reason global rule 2's unload trigger is stated as one observable
     * condition.
     */
    private static boolean inWorld(Object level, Object player) {
        return level != null && player != null;
    }
```

Add the import `import dev.continuo.platform.TickPhase;` after the existing `IPlatformContext` import.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :runtime:build`
Expected: PASS, 21 tests. Every case from Tasks 4, 5 and 6 is green.

- [ ] **Step 5: Commit**

```bash
git add platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java
git commit -m "feat(runtime): add rule 3 fault handling, the tick window and PRE/POST pairing"
```

The commit covers Tasks 5 and 6 together because rule 3's cases cannot go green without tick delivery. If your run showed a different pass count than 21, put the number you observed in the commit body rather than this one.

---

## Task 7: The click drain

**Files:**
- Modify: `platform-testkit/src/main/java/dev/continuo/testkit/AdapterConformanceTest.java`
- Modify: `runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java`

**Interfaces:**
- Consumes: everything from Tasks 4–6.
- Produces: no new public types. `AdapterRuntime` gains a private `drainClicks()` and dispatches consumed clicks to the constructor's `onClick`.

- [ ] **Step 1: Write the failing tests**

Append to `AdapterConformanceTest`, before the closing brace:

```java
    // ---- The click drain ----

    @Test
    void aQueuedClickIsDispatchedInsideTheTickWindow() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.queueClick(2);

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(2, subject.clicksHandled());
    }

    @Test
    void aClickQueuedOutOfWorldIsDiscarded() {
        startAndClear();
        subject.queueClick(3);

        subject.tickStart(null, null);
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, subject.clicksHandled(),
            "a title-screen keypress must not fire the instant the next world loads");
    }

    @Test
    void aClickQueuedWhileFaultedIsDiscarded() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();

        subject.queueClick(3);
        subject.tickStart(LEVEL_A, PLAYER);

        // The faulted tick returns before the dispatch loop, so a zero count here would hold
        // whether the click was discarded or merely left sitting in the queue. Only clearing
        // the fault tells the two apart: the world load to LEVEL_B reopens the dispatch loop,
        // and a click the drain failed to discard is handled on that tick.
        subject.tickStart(LEVEL_B, PLAYER);

        assertEquals(0, subject.clicksHandled(),
            "a click must not survive to be replayed once the fault clears");
    }

    @Test
    void clicksStillQueuedWhenTheHandlerThrowsAreDiscarded() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.queueClick(4);
        subject.failClickNumber(2, new RuntimeException("handler bug"));

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(2, subject.clicksHandled(),
            "the loop aborts on the second click and the remaining two are drained, not handled");
        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a throw from the click handler faults exactly as a throw from the core does");
        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the fault aborts the tick before PRE is reached");

        // Those three are all decided inside the guard, before the trailing drain runs, so
        // they cannot tell a discarded click from one still queued. Clearing the fault with a
        // world load reopens the dispatch loop: the two clicks the aborted loop never reached
        // are handled here, and the count reaches 4, unless they were drained.
        subject.tickStart(LEVEL_B, PLAYER);

        assertEquals(2, subject.clicksHandled(),
            "the clicks left queued by the aborted loop must not leak into a later tick");
    }

    @Test
    void clicksAreNotDrainedBetweenPreAndPost() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.queueClick(1);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(1, subject.clicksHandled(),
            "a keypress made between the two halves of a tick must survive to the next one");
    }
```

**Amended in place after the Task 7 review** (ruling in the SDD ledger; same convention as
`03f357f`). Two of the bodies above are not the ones this plan first carried. As originally
written, `aClickQueuedWhileFaultedIsDiscarded` and
`clicksStillQueuedWhenTheHandlerThrowsAreDiscarded` asserted nothing that depended on the drain:
the faulted branch returns before the dispatch loop, and the trailing drain runs after
`guarded()` has already decided every value the test read, so both stayed green with the
`drainClicks()` call they name deleted. Each now clears the fault with a world load to
`LEVEL_B` and re-checks the count, which is what actually observes the discard. Spec §5.2
requires those two discards to be *asserted*, so the original bodies were defective against
the spec rather than a plan-versus-review disagreement.

**Two further cases were added in the same fix round, under a `// ---- Coverage gaps found in
review ----` heading at the end of the class**, closing gaps the Tasks 5+6 review found:
`theFaultClearOrderingSurvivesAStopThatThrowsOnTheWorldLoad` pins `updateLevel`'s
clear-the-fault-*before*-`stop()` ordering, which no case had pinned, and
`postIsSuppressedWhenAFaultArrivesBetweenTheTwoHalvesOfATick` covers `tickEnd`'s `|| faulted`
term, which was deletable with no case failing. That is why the shipped suite is **28** cases
and not the 26 Step 4 below first expected.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :runtime:test`
Expected: FAIL — `clicksHandled()` is 0 everywhere because nothing consumes clicks yet.

- [ ] **Step 3: Add the drain**

In `AdapterRuntime`, replace `tickStart`'s body with:

```java
    public void tickStart(Object level, Object player) {
        if (!started) {
            return;
        }
        updateLevel(level);
        if (!inWorld(level, player)) {
            // Drain clicks made outside a world so a title-screen keypress cannot fire the
            // instant the next world loads.
            drainClicks();
            return;
        }
        if (faulted) {
            // Same invariant while faulted: a click must not survive to be replayed once the
            // fault clears on the next world load.
            drainClicks();
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                while (clicks.consumeClick()) {
                    onClick.run();
                }
                core.onClientTick(TickPhase.PRE);
                preDelivered = true;
            }
        });
        // If the block above ran to completion, the click source is already empty and this is
        // a no-op. If the handler threw partway through the loop, this discards whatever
        // clicks were still queued so they cannot leak into a tick after the fault clears.
        drainClicks();
    }
```

Add after `inWorld`:

```java
    /**
     * Discards any queued clicks without dispatching them. Called on all three tick-start
     * paths — out of world, faulted, and after a delivered {@code PRE} that may have aborted
     * mid-loop.
     *
     * <p>Deliberately not called from {@link #tickEnd}: clicks are consumed only in the
     * tick-start path, so that tick's queue was already dealt with, and draining again would
     * swallow a keypress the user makes between the two halves of a tick.
     */
    private void drainClicks() {
        while (clicks.consumeClick()) {
            // discarded deliberately
        }
    }
```

- [ ] **Step 4: Run the whole build**

Run: `./gradlew build`
Expected: PASS. 16 core tests, 4 testkit tests, 26 runtime conformance tests — 28 as shipped, after the two coverage-gap cases noted under Step 1. Both adapters still compile against the unchanged `ContinuoCore`.

- [ ] **Step 5: Write the testkit's `package-info`**

Create `platform-testkit/src/main/java/dev/continuo/testkit/package-info.java`:

```java
/**
 * The conformance suite for the platform contract.
 *
 * <p>Extend {@code AdapterConformanceTest} and return a subject from {@code newSubject}. The
 * cases are organised by the global rule numbering in {@code dev.continuo.platform}'s
 * {@code package-info}, which is load-bearing and must not change.
 *
 * <h2>What a green run does not mean</h2>
 *
 * <p>This is the part a future session most needs, because a green suite is easy to overread.
 * A green run does <b>not</b> show:
 *
 * <ul>
 *   <li>that an adapter passes the correct level or player object — an adapter reading the
 *       wrong field passes every case here;
 *   <li>that {@code IActuator.setInput} moves the player;
 *   <li>that {@code PRE} genuinely precedes the game's own input read for that tick;
 *   <li>that the tick source is a tick and not a frame;
 *   <li>anything about global rule 1 or global rule 4, neither of which reduces to a test.
 * </ul>
 *
 * <p>Those remain the job of {@code docs/smoke-checklist-a1.md} and
 * {@code docs/smoke-checklist-a2.md}. This suite covers the shared logic; the checklists cover
 * the platform binding. <b>Neither subsumes the other, and a green run of either is not
 * evidence about the other's subject.</b>
 */
package dev.continuo.testkit;
```

- [ ] **Step 6: Commit**

```bash
git add platform-testkit/ runtime/src/main/java/dev/continuo/runtime/AdapterRuntime.java
git commit -m "feat(runtime): add the click drain and document what a green suite does not mean"
```

---

## Task 8: Convert the Fabric adapter

**Files:**
- Create: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/Slf4jRuntimeLog.java`
- Modify: `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/ContinuoFabricMod.java`
- Modify: `adapters/adapter-fabric-1.21.11/build.gradle.kts:26-27`
- Modify: root `build.gradle.kts`

**Interfaces:**
- Consumes: `AdapterRuntime`, `RuntimeLog`, `ClickSource` (Task 4).
- Produces: nothing later tasks depend on.

**There is no automated test for this task.** The adapter cannot be executed without a running client — the same reason A2a stated that TDD does not apply to adapter modules. Its verification is the smoke checklist in Step 5. Do not invent a unit test for it.

- [ ] **Step 1: Allow the dependency**

In the root `build.gradle.kts`, change the Fabric entry to:

```kotlin
    ":adapters:adapter-fabric-1.21.11" to setOf(":platform", ":core", ":runtime"),
```

In `adapters/adapter-fabric-1.21.11/build.gradle.kts`, after `implementation(project(":core"))`, add:

```kotlin
    implementation(project(":runtime"))
```

- [ ] **Step 2: Add the log adapter**

Create `adapters/adapter-fabric-1.21.11/src/main/java/dev/continuo/adapter/fabric/Slf4jRuntimeLog.java`:

```java
package dev.continuo.adapter.fabric;

import dev.continuo.runtime.RuntimeLog;
import org.slf4j.Logger;

/** Bridges {@link RuntimeLog} to the SLF4J logger 1.21.11 ships. */
final class Slf4jRuntimeLog implements RuntimeLog {

    private final Logger logger;

    Slf4jRuntimeLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        logger.error(message, thrown);
    }
}
```

- [ ] **Step 3: Rewrite the mod class**

Replace the entire contents of `ContinuoFabricMod.java` with:

```java
package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring only. Registers the keybind, builds the platform context, and forwards the game's
 * events to {@link AdapterRuntime}.
 *
 * <p>This class holds no conformance state. The four global rules documented in the
 * {@code dev.continuo.platform} package, and {@code IGameEvents.onClientTick}'s tick-window
 * and phase-ordering contract, are all discharged by {@code AdapterRuntime} — one
 * implementation shared with the 1.7.10 adapter, so the two cannot diverge and M5 can change
 * both in one move.
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private KeyMapping walkKey;
    private AdapterRuntime runtime;

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

        final ContinuoCore core = new ContinuoCore();
        FabricPlatformContext context = new FabricPlatformContext(Minecraft.getInstance());

        runtime = new AdapterRuntime(
            core,
            new Slf4jRuntimeLog(LOGGER),
            walkKey::consumeClick,
            () -> {
                LOGGER.info("Continuo walk requested");
                core.requestWalk();
            });
        runtime.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );

        ClientTickEvents.START_CLIENT_TICK.register(client ->
            runtime.tickStart(client.level, client.player));

        ClientTickEvents.END_CLIENT_TICK.register(client ->
            runtime.tickEnd(client.level, client.player));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runtime.clientStopping());
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS, including `checkDependencyDirection`.

- [ ] **Step 5: Commit, stating plainly that the checklist has not been run**

The smoke checklist is an owner action on a real client and cannot happen inside this session. Commit the conversion with a message that says so. **Do not write any claim about walk distance, portal behaviour, or checklist steps passing** — you have not observed any of it.

```bash
git add build.gradle.kts adapters/adapter-fabric-1.21.11/
git commit -F - <<'MSG'
refactor(fabric): delegate conformance to AdapterRuntime

The adapter now registers the keybind, builds the context, and forwards
four calls. All conformance state moves to the shared AdapterRuntime.

NOT YET VERIFIED: docs/smoke-checklist-a1.md has not been run against a
real 1.21.11 client for this change. `./gradlew build` is green, which
covers compilation only — no adapter behaviour is exercised by any
automated test, by design.
MSG
```

- [ ] **Step 6: Report the outstanding checklist to the controller**

State in your report that `docs/smoke-checklist-a1.md` is outstanding and must be run by the owner against a real 1.21.11 client, including the portal step, expecting 8–9 blocks of displacement. The controller surfaces this to the owner.

If the owner later reports a failure, the conversion is a behaviour-preserving extraction, so a failure means the extraction is wrong — it is not to be patched by adjusting the adapter.

---

## Task 9: Convert the Forge adapter

**Files:**
- Create: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/Log4jRuntimeLog.java`
- Modify: `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/ContinuoForgeMod.java`
- Modify: `adapters/adapter-forge-1.7.10/build.gradle.kts:45-46`
- Modify: root `build.gradle.kts`

**Interfaces:**
- Consumes: `AdapterRuntime`, `RuntimeLog`, `ClickSource` (Task 4).
- Produces: nothing later tasks depend on.

**No automated test, for the same reason as Task 8.**

- [ ] **Step 1: Allow the dependency**

In the root `build.gradle.kts`, change the Forge entry to:

```kotlin
    ":adapters:adapter-forge-1.7.10" to setOf(":platform", ":core", ":runtime"),
```

In `adapters/adapter-forge-1.7.10/build.gradle.kts`, after `implementation(project(":core"))`, add:

```kotlin
    implementation(project(":runtime"))
```

- [ ] **Step 2: Add the log adapter**

Create `adapters/adapter-forge-1.7.10/src/main/java/dev/continuo/adapter/forge/Log4jRuntimeLog.java`:

```java
package dev.continuo.adapter.forge;

import dev.continuo.runtime.RuntimeLog;
import org.apache.logging.log4j.Logger;

/**
 * Bridges {@link RuntimeLog} to log4j2. 1.7.10 predates SLF4J in Minecraft, so this adapter
 * logs through what the game ships. A logging-API difference only — the messages themselves
 * come from the shared runtime and are identical on both versions.
 */
final class Log4jRuntimeLog implements RuntimeLog {

    private final Logger logger;

    Log4jRuntimeLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        logger.error(message, thrown);
    }
}
```

- [ ] **Step 3: Rewrite the mod class**

Replace the entire contents of `ContinuoForgeMod.java` with:

```java
package dev.continuo.adapter.forge;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import dev.continuo.runtime.ClickSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

/**
 * Wiring only. Registers the keybind, builds the platform context, and forwards the game's
 * events to {@link AdapterRuntime}.
 *
 * <p>This class holds no conformance state. The four global rules documented in the
 * {@code dev.continuo.platform} package, and {@code IGameEvents.onClientTick}'s tick-window
 * and phase-ordering contract, are all discharged by {@code AdapterRuntime} — one
 * implementation shared with the 1.21.11 adapter, so the two cannot diverge and M5 can change
 * both in one move.
 *
 * <p>There is no {@code clientStopping()} call here. Forge 1.7.10 exposes no main-thread
 * client-stopping event; the customary JVM shutdown hook runs off the main thread and would
 * collide with global rule 1. Rule 2 makes that clause MUST-where-available, so this adapter
 * is conformant by omission.
 */
// clientSideOnly is deliberately omitted: the FML build this module actually compiles
// against (the decompiled cpw.mods.fml.common.Mod in build/rfg/minecraft-src, which is
// what compileJava resolves, not a newer binary) has no such element on @Mod. The
// attribute was added to FML for MC 1.8+ and never backported to 1.7.10, confirmed by
// direct inspection of the decompiled annotation. This is metadata only (server-side load
// exclusion); acceptableRemoteVersions = "*" below covers the same purpose.
@Mod(
    modid = ContinuoForgeMod.MOD_ID,
    name = "Continuo",
    version = "0.1.0",
    acceptableRemoteVersions = "*"
)
public final class ContinuoForgeMod {

    public static final String MOD_ID = "continuo";

    private static final Logger LOGGER = LogManager.getLogger("continuo");

    private KeyBinding walkKey;
    private AdapterRuntime runtime;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        walkKey = new KeyBinding("key.continuo.walk", Keyboard.KEY_K, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(walkKey);

        final ContinuoCore core = new ContinuoCore();
        ForgePlatformContext context = new ForgePlatformContext(Minecraft.getMinecraft());

        runtime = new AdapterRuntime(
            core,
            new Log4jRuntimeLog(LOGGER),
            new ClickSource() {
                @Override
                public boolean consumeClick() {
                    return walkKey.isPressed();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
            });
        runtime.start(context);

        // TickEvent.ClientTickEvent is posted on the FML bus, not the Forge event bus.
        FMLCommonHandler.instance().bus().register(this);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            runtime.tickStart(client.theWorld, client.thePlayer);
        } else {
            runtime.tickEnd(client.theWorld, client.thePlayer);
        }
    }
}
```

Note `walkKey` is read from an anonymous inner class, so it must not be reassigned after `init`. It is a field assigned once; that compiles under Java 8 without `final` because the anonymous class captures `this`, not the local.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS. If `clean` was needed and failed with `Unable to delete directory`, run `./gradlew --stop`, wait, and retry — that is the known stale-daemon issue, not this change.

- [ ] **Step 5: Commit, stating plainly that the checklist has not been run**

Same as Task 8: the checklist is an owner action on a real client. **Do not write any claim about walk distance, the portal step, or the unbound-key step** — you have not observed any of it.

```bash
git add build.gradle.kts adapters/adapter-forge-1.7.10/
git commit -F - <<'MSG'
refactor(forge): delegate conformance to AdapterRuntime

The adapter now registers the keybind, builds the context, and forwards
four calls. All conformance state moves to the shared AdapterRuntime,
which is the same code the 1.21.11 adapter runs.

No clientStopping() call: 1.7.10 exposes no main-thread client-stopping
event, and global rule 2 makes that clause MUST-where-available.

NOT YET VERIFIED: docs/smoke-checklist-a2.md has not been run against a
real 1.7.10 client for this change. `./gradlew build` is green, which
covers compilation only.
MSG
```

- [ ] **Step 6: Report the outstanding checklist to the controller**

State in your report that `docs/smoke-checklist-a2.md` is outstanding and must be run by the owner against a real 1.7.10 client, including the portal step and the unbound-Forward-key step. Note for the owner that the usual `UnsatisfiedLinkError: org.lwjgl.openal.AL10...` sound noise is by design, whereas an `IllegalAccessError` would mean the access transformer stopped taking effect and is a real failure.

---

## Task 10: The SPI v1 documentation revision

**Files:**
- Modify: `platform/src/main/java/dev/continuo/platform/package-info.java:11-16`
- Modify: `docs/superpowers/specs/2026-08-11-spi-behavioural-contract-design.md:125-148`
- Modify: `docs/superpowers/specs/2026-08-01-mc-automation-roadmap-design.md`
- Modify: `docs/smoke-checklist-a1.md`, `docs/smoke-checklist-a2.md`

**Interfaces:** none. Documentation only. **No SPI type, method, signature or enum constant changes.**

- [ ] **Step 1: Update the global-rules preamble**

In `platform/src/main/java/dev/continuo/platform/package-info.java`, replace lines 11–16:

```
 * <p>These four rules are cross-cutting: they bind every type in this package, in both
 * directions. Per-type documentation cites them by number. Conformance tests are expected to
 * be organised by this numbering, though not every rule reduces to a test — rule 1's "no
 * implementation may block" and rule 4's "may be cleared at any time" have no assertion to
 * write, and rules 2 and 3 bind methods that are not on any type in this package. The
 * keywords MUST, MUST NOT and MAY carry their RFC 2119 meanings.
```

with:

```
 * <p>These four rules are cross-cutting: they bind every type in this package, in both
 * directions. Per-type documentation cites them by number. The conformance suite in
 * {@code platform-testkit} is organised by this numbering, so <b>the numbering is
 * load-bearing and must not change</b>. Not every rule reduces to a test: rule 1's "no
 * implementation may block" and rule 4's "may be cleared at any time" have no assertion to
 * write, and the suite records those gaps in its own documentation rather than leaving them
 * silent. Rules 2 and 3 bind {@code start} and {@code stop}, which are declared on no type in
 * this package, so the suite asserts them against the core-side interface that does declare
 * them. The keywords MUST, MUST NOT and MAY carry their RFC 2119 meanings.
```

Use `{@code platform-testkit}`, not `{@link}` — the module is not on the javadoc classpath and `-Xwerror` would fail the build on an unresolvable reference.

- [ ] **Step 2: Verify the javadoc check**

Run: `./gradlew :platform:javadoc --rerun-tasks`
Expected: PASS. `--rerun-tasks` is mandatory here: a javadoc-only edit does not change the ABI, so without it the task reports `UP-TO-DATE` and proves nothing.

- [ ] **Step 3: Rewrite the contract spec's §4.1 entries 1–3**

In `docs/superpowers/specs/2026-08-11-spi-behavioural-contract-design.md`, replace the bold note at lines 127–131 with:

```markdown
**Caveats 1–3 were settled in A2a, and this section was rewritten to match in A2b's SPI v1
revision. The normative text is `dev.continuo.platform`'s `package-info` javadoc;
`2026-08-12-a2a-legacy-adapter-design.md` records the reasoning.**
```

Replace entries 1, 2 and 3 (lines 137–148) with:

```markdown
1. **Rule 2, world unload — settled: a dimension change IS a world unload.** The trigger is
   stated as an observable condition rather than as per-platform events: an adapter MUST call
   `stop()` on each of three client level-instance transitions — to `null`, between two
   different non-`null` instances, and from `null` to non-`null`. Both adapters now evaluate
   that condition through the same `AdapterRuntime`, so they cannot diverge on walking through
   a portal. Verified in-game on both versions 2026-08-13.
2. **Rule 2, client shutdown on 1.7.10 — settled: MUST-where-available.** `stop()` MUST be
   called on client shutdown where the platform exposes a main-thread client-stopping event,
   and MAY be omitted where none exists. Forge 1.7.10 exposes none and is conformant by
   omission: `stop()`'s effects cannot outlive the process, so the obligation is hygiene rather
   than a defended failure mode, and rule 1 stays exception-free. **This remains the softest
   point in the M2 gate verdict.** A2b did not resolve it and the runtime does not bear on it
   — Forge simply never calls `AdapterRuntime.clientStopping()`, which moves the conditional
   from adapter code into an uncalled method and is an argument neither way. Re-asked at M3's
   SPI audit. See §6.1 of `2026-08-13-a2b-conformance-testkit-design.md`.
3. **§5's `setInput` clauses on 1.7.10 — settled: the unbound-key clause was deleted.** Both
   adapters address the key binding per instance rather than by keycode, and movement reads
   that field rather than polling the keyboard, so an unbound key is not a failure mode on the
   route either adapter takes. The clause was dissolved rather than satisfied. Confirmed
   in-game 2026-08-13 with the vanilla Forward key set to NONE.
```

Leave entries 4 and 5 exactly as they are.

- [ ] **Step 4: Cross-reference the checklists**

In both `docs/smoke-checklist-a1.md` and `docs/smoke-checklist-a2.md`, find the paragraph disclaiming rule 3, the click drain and PRE/POST pairing, and append:

```markdown
Those three are covered by the `platform-testkit` conformance suite, added in A2b, which runs
offline against the shared `AdapterRuntime`. **The suite and this checklist are complements.**
A green suite says nothing about whether this adapter passes the correct level or player
object, whether `setInput` moves the player, or whether `PRE` precedes the game's input read —
that is what the steps below are for. Neither is evidence about the other's subject.
```

- [ ] **Step 5: Close A2b out in the roadmap**

In `docs/superpowers/specs/2026-08-01-mc-automation-roadmap-design.md`:

Replace the `A2b — remaining` bullet (around line 117) with:

```markdown
- **A2b — ✅ DONE.** The injection seam, `platform-testkit`, and the SPI v1 revision. Spec:
  [`2026-08-13-a2b-conformance-testkit-design.md`](2026-08-13-a2b-conformance-testkit-design.md).
  The seam **dissolved rather than got solved**: extracting both adapters' shared conformance
  machinery into `:runtime` made the object worth observing the runtime, which the testkit
  constructs directly, so no substitution mechanism inside an adapter is needed and no type was
  added to `dev.continuo.platform`. Rule 3 fault handling, the click drain and PRE/POST pairing
  are now offline assertions. The SPI v1 revision was a documentation pass: **no SPI type,
  method, signature or enum constant changed.**
```

In the "Still open — retargeted from M2 to A2b" block (around lines 182–196), change the heading to `**Closed by A2b — ✅**` and mark each of the three bullets resolved, keeping their text so the reasoning survives.

Add to the "Known unverified, by design" paragraph (around line 258):

```markdown
**Superseded by A2b for the shared logic.** Global rule 3, the click drain and PRE/POST pairing
are now asserted offline by `platform-testkit` against `AdapterRuntime`, which both adapters
delegate to. What remains unverified by anything automated is the platform binding itself:
whether each adapter passes the correct level and player objects, and whether `setInput` moves
the player. A green smoke run still does not cover the three behaviours, and a green suite
still does not cover the binding.
```

- [ ] **Step 6: Verify the whole build with reruns**

Run: `./gradlew build --rerun-tasks`
Expected: PASS. Documentation-only edits otherwise report `UP-TO-DATE` and prove nothing.

- [ ] **Step 7: Commit**

```bash
git add platform/src/main/java/dev/continuo/platform/package-info.java docs/
git commit -m "docs(spi): settle the v1 revision and close out A2b"
```

---

## After the last task

**Budget a whole-branch review on the most capable model**, and instruct it to read files the diff does not include. The last three sub-projects each ended with a whole-branch review that found issues every per-task review had missed — 6 after 7 reviews, 6 after 10, 6 after 8 — and the single most valuable finding was in a file the branch never touched. State "read-only, no file mutation" in the dispatch.

Specific things worth pointing that review at:

- `AdapterRuntime` against the pre-A2b `ContinuoFabricMod` and `ContinuoForgeMod` at commit `4122d4d`, statement by statement. The spec's claim is that behaviour is preserved except for the three changes in §4.2; that claim is checkable by diff and nobody will have checked it end to end.
- `core/src/main/java/dev/continuo/core/ContinuoCore.java` and `platform/src/main/java/dev/continuo/platform/*.java` — javadoc that may still describe adapters as holding conformance state they no longer hold.
- Whether any conformance case passes vacuously. `nothingPropagatesOutOfTickStart` in particular asserts nothing explicit; confirm it is red when the guard is removed.

---

## Self-Review Notes

Checked against the spec: §2.1 (seam dissolves) → Task 1 plus the roadmap edit in Task 10 Step 5. §3 (module layout) → Tasks 2 and 4 Step 1. §3.1 (`CoreApi`, no `requestWalk`) → Task 1. §4 (API) → Task 4. §4.1 (behaviour preserved) → Tasks 5–7 plus the review pointer above. §4.2 (three genuine changes) → tick window in Task 6, log messages in Tasks 8–9, start guard in Task 4. §5 (testkit) → Tasks 2, 3, 4. §5.1 (fakes move) → Task 2. §5.2 (cases) → Tasks 4–7, less the re-entrancy case, which is documented as a deliberate gap above and in `AdapterConformanceTest`'s javadoc. §5.3 (what green does not mean) → Task 7 Step 5 and Task 10 Step 4. §6 (SPI v1) → Task 10. §7 (testing strategy) → the no-automated-test notes in Tasks 8 and 9. §8 (sequencing) → task order.

Type consistency: `CoreApi` (Task 1) is used identically in Tasks 3, 4, 8, 9. `RecordingCore.Event` constants `START`/`STOP`/`TICK_PRE`/`TICK_POST` are used unchanged in Tasks 4–7. `AdapterUnderTest`'s seven methods (Task 3) are implemented exactly once, in Task 4's `RuntimeSubject`, and called only with those names in Tasks 4–7. `ClickSource.consumeClick()` and `RuntimeLog.info/error` (Task 4) are implemented in Tasks 4, 8 and 9 with matching signatures.
