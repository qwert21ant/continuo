# A2b — Injection seam, `platform-testkit`, SPI v1

**Date:** 2026-08-13
**Status:** Approved
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) — milestone M2, sub-project A2b
**Predecessor:** [`2026-08-12-a2a-legacy-adapter-design.md`](2026-08-12-a2a-legacy-adapter-design.md)
**Contract:** `platform/src/main/java/dev/continuo/platform/package-info.java` — **normative**

The second half of M2. A2a built the 1.7.10 adapter and evaluated the gate; A2b closes the
three items the roadmap retargeted from M2 — the injection seam, the conformance suite, and
the SPI v1 revision — and is the gate's own precondition for anything beyond M2 starting.

---

## 1. Goal and done criteria

**Deliverable:** the three behaviours both smoke checklists explicitly disclaim — global rule
3 fault handling, the click drain, and PRE/POST pairing — become ordinary offline JUnit
assertions, and the mechanism that makes that possible is reusable by any future adapter.

**Done when all six hold:**

1. `./gradlew clean build` is green with both adapters present.
2. `:platform-testkit` exists and `:runtime` passes its conformance suite.
3. `docs/smoke-checklist-a1.md` passes against a real 1.21.11 client after the Fabric
   conversion.
4. `docs/smoke-checklist-a2.md` passes against a real 1.7.10 client after the Forge
   conversion.
5. The SPI v1 documentation revision in §6 is committed and the javadoc check is green.
6. The roadmap records A2b as closed, the same way it records A2a.

**Explicitly not in A2b:** any change to a type, method, signature, or enum constant in
`dev.continuo.platform`; any movement on the M5 edge- vs level-triggered actuation decision;
the three cosmetic items A2a's final review triaged as "can ship" (the `0.1.0` vs
`0.1.0-SNAPSHOT` mismatch, the missing Fabric lang file, the `<ul>` style mismatch between
`package-info` and `IGameEvents`); and anything touching `.github/workflows/ci.yml`.

---

## 2. The finding this spec rests on

The two adapters are not merely "mechanically identical", which is how A2a's gate evidence
described them. **The conformance machinery in them is version-independent Java that happens
to have been written twice.**

`faulted`, `preDelivered`, `lastLevel`, `updateLevel`, `guarded`, `drainClicks`, and the
`inWorld` tick window touch Minecraft through exactly four things:

- an opaque client level object, compared only by identity;
- an opaque local player object, only ever null-checked;
- a "consume one queued click" poll returning a boolean;
- a logger.

Nothing else. `ContinuoFabricMod` and `ContinuoForgeMod` differ in that machinery only by
lambda-versus-anonymous-class syntax and SLF4J versus log4j2.

This matters because the three unverified behaviours live entirely inside that machinery.
They require Minecraft to be *running* only because they are currently written inside classes
that import `net.minecraft`. Move the machinery to where it does not, and they become
testable with no game on the classpath.

Two enabling facts, confirmed against the build rather than assumed:

- `continuo-pure-module` sets `options.release = 8` and `checkCoreBytecode` fails on any class
  above major version 52. Anything placed in a pure module is loadable by the 1.7.10 adapter.
- `checkCorePurity` scans bytecode for `net/minecraft`, `net/fabricmc`, `net/minecraftforge`
  and `cpw/mods` in both slash and dot forms. An `Object`-typed level parameter passes it, and
  the check will catch any later attempt to smuggle a real type in.

### 2.1 The injection seam dissolves rather than gets solved

The roadmap poses the seam as: both adapters hard-code `new ContinuoCore()` into a `final`
field of a `public final class`, so no recording implementation can be substituted and the
testkit cannot observe an adapter at all.

That framing assumes the thing worth observing is an adapter. Once the machinery lives in a
version-independent class, the thing worth observing is that class, which the testkit
constructs directly. Each adapter's `new ContinuoCore()` becomes an uninteresting choice of
which core to run in production.

This is the same shape as A2a's resolution of `IActuator`'s unbound-key clause — *dissolved,
not chosen*. It is recorded here so a future session does not read the absence of a
substitution mechanism in the adapters as an oversight.

The roadmap's binding constraint is satisfied directly: **no type is added to
`dev.continuo.platform`.** The seam is one interface in `core`.

---

## 3. Module layout

Two new modules, both using the existing `continuo-pure-module` convention, which brings the
Java 8 bytecode ceiling, the purity scan, and the `-Xwerror` javadoc check with them.

```
:platform          unchanged — no new types
:core              + dev.continuo.core.CoreApi          the seam
:runtime           dev.continuo.runtime.*               the extracted machinery
:platform-testkit  dev.continuo.testkit.*               the conformance suite
```

`allowedProjectDependencies` in the root `build.gradle.kts` gains:

```kotlin
":runtime"          to setOf(":platform", ":core"),
":platform-testkit" to setOf(":platform", ":core"),
":adapters:adapter-fabric-1.21.11" to setOf(":platform", ":core", ":runtime"),
":adapters:adapter-forge-1.7.10"   to setOf(":platform", ":core", ":runtime"),
```

`:runtime`'s tests consume `:platform-testkit`, and `:core`'s tests consume it too (§5.1).
Both are test-scoped, which that map already exempts by design — the exemption's existing
comment says a module's tests may need fixtures from another module without that implying a
production direction, which is exactly this case.

**`:runtime` is a separate module rather than a package inside `:core`** because the two
answer different questions. `:core` is the bot: what Continuo decides to do. `:runtime` is
the adapter's side of the SPI contract: how any host discharges the four global rules. The
testkit asserts against the second without needing the first, and keeping the boundary real
means the dependency-direction check enforces it rather than convention doing so.

### 3.1 The seam

```java
package dev.continuo.core;

/** Everything an adapter runtime calls on the core. */
public interface CoreApi extends IGameEvents {
    void start(IPlatformContext context);
    void stop();
}
```

`ContinuoCore implements CoreApi`. All three methods already exist with these exact
signatures, so this is a declaration change and nothing more.

**`requestWalk()` is deliberately not on `CoreApi`.** It is bot behaviour, not conformance.
The runtime dispatches each drained click to an adapter-supplied `Runnable`, so the runtime
never learns what a walk is and the testkit's recording core has no walk-shaped method to
stub. `requestWalk` stays on `ContinuoCore`, the adapters keep calling it, and it remains
covered by global rule 3 because the runtime invokes the handler inside the same guard.

`:platform-testkit` depends on `:core` for this interface. That is not a layering smell: the
roadmap already records that rules 2 and 3 bind `start` and `stop`, which live on
`ContinuoCore` and on no type in `dev.continuo.platform`. A suite encoding those rules must
name a core-side type.

---

## 4. `AdapterRuntime`

```java
package dev.continuo.runtime;

public final class AdapterRuntime {
    public AdapterRuntime(CoreApi core, RuntimeLog log, ClickSource clicks, Runnable onClick);

    public void start(IPlatformContext context);
    public void tickStart(Object level, Object player);
    public void tickEnd(Object level, Object player);
    public void clientStopping();
}
```

Four public methods, all on the client main thread, none referencing a game type. Two support
interfaces, both in `:runtime`:

```java
public interface ClickSource { boolean consumeClick(); }
public interface RuntimeLog  { void info(String message); void error(String message, Throwable thrown); }
```

`ClickSource.consumeClick()` returns `true` if a queued click was consumed and `false` when
the queue is empty — the contract both `KeyMapping.consumeClick()` and
`KeyBinding.isPressed()` already satisfy. Fabric supplies `walkKey::consumeClick`; Forge an
anonymous class over `walkKey.isPressed()`. The differing method names stop being a
divergence risk because the runtime sees only the boolean.

### 4.1 Behaviour is preserved, statement for statement

A2b is an extraction, not a redesign. `updateLevel`'s identity check, the fault-clear-before-
`stop` ordering, the `preDelivered` latch cleared unconditionally on every tick end,
`drainClicks` on all three tick-start paths and not on the tick-end path, and `guarded`'s
catch-then-`stop`-then-catch-again all move across unchanged.

**If the runtime's behaviour differs from either adapter's current behaviour in any way not
listed in §4.2, that is a bug in the extraction, not a design decision.**

### 4.2 The three changes that are genuine

Stated here so they are not smuggled in under "extraction".

1. **The tick window moves into the runtime.** Adapters pass `level` and `player`; the runtime
   computes `level != null && player != null`. Today each adapter computes it separately. This
   removes an axis on which two conformant adapters could drift — the same move A2a made when
   it restated the unload trigger as level identity rather than as per-platform events.

2. **Global rule 2 and rule 3 log messages move into the runtime**, so both versions emit
   byte-identical text. The checklists assert on these strings, so this strengthens them
   rather than threatening them. The one message that stays in adapter code is
   `Continuo walk requested`, because it lives inside the click handler and the runtime does
   not know what a walk is: one duplicated line per adapter, still asserted by both
   checklists. `Continuo core started on {} / {}` also stays in the adapter, since it reads
   `context.info()` at startup.

   The three strings both checklists assert — `Continuo core started on … / …`,
   `Continuo walk requested`, and `Continuo stopping: client level changed` — all survive
   verbatim. **No checklist step text needs rewriting for this sub-project.**

3. **Ticks before `start()` are ignored, and a second `start()` throws
   `IllegalStateException`.** Today a tick before `start` is a latent `NullPointerException`
   inside the core, and nothing enforces rule 2's "exactly once". This is the runtime
   discharging an obligation the contract already states, and it gives the testkit something
   to assert.

   This does **not** change the core. `ContinuoCore` still tolerates a second `start`, and
   `startTwiceReplacesContext` — the existing test pinning that behaviour — stays exactly as
   it is. Rule 2's own wording already draws this distinction: the rule binds adapters, not
   the core, and an adapter MUST NOT rely on the core's tolerance.

---

## 5. `platform-testkit`

```java
package dev.continuo.testkit;

public interface AdapterUnderTest {
    void start(IPlatformContext context);
    void tickStart(Object level, Object player);
    void tickEnd(Object level, Object player);
    void clientStopping();
    void queueClick(int count);
    RecordingCore core();
}

public abstract class AdapterConformanceTest {
    protected abstract AdapterUnderTest newSubject(RecordingCore core);
    // the cases in §5.2
}
```

`RecordingCore implements CoreApi` and records `start`, `stop`, and `onClientTick(phase)` as
one ordered event list, so ordering assertions are as cheap as counting ones. It can be
programmed to throw from any of the three, which is what makes global rule 3 testable at all
— A2a could not exercise rule 3 because doing so needed a deliberate throw, and a deliberate
throw is not something to leave in shipped adapter code. In a recording fake it is the point.

`:runtime`'s test source set contains a subclass of `AdapterConformanceTest` a few lines long;
that is the suite's first subject. A future adapter — M9's, or a third party's — either
delegates to `AdapterRuntime` and inherits conformance, or supplies its own
`AdapterUnderTest`.

**The genericity claim, stated honestly.** A suite that runs without Minecraft cannot test an
adapter that binds directly to Minecraft. "Reusable by any adapter" therefore means: any
adapter that can be driven through `AdapterUnderTest`. That is a real and useful class — it
includes every adapter that routes its conformance obligations through a version-independent
object, which is what this design makes the cheap path — but it is not every conceivable
adapter, and the testkit's own documentation must say so rather than implying otherwise.

### 5.1 The core fakes move

`FakeActuator`, `FakePlatformContext`, and `FakePlatformInfo` move from `core/src/test` into
`:platform-testkit`'s main source set, and `:core`'s tests depend on the testkit rather than
carrying their own copies. Duplicating a recording actuator into the testkit would guarantee
the two drift.

No dependency cycle results: `:platform-testkit:main` depends on `:core:main`, and
`:core:test` depends on `:platform-testkit:main`. Those are different source sets and the task
graph is acyclic. The 15 existing core tests must pass unchanged apart from their imports —
if any assertion needs editing, the fakes were changed and that is a defect.

### 5.2 What the suite asserts

Organised by the existing rule numbering. **The numbering is load-bearing and must not be
renumbered**; the package javadoc says conformance tests are expected to mirror it.

**Rule 1 — Threading.** No cases. "No implementation may block" is unfalsifiable as a test, as
the package javadoc already states. The testkit says so in its own documentation rather than
leaving a silent gap that reads like an omission.

**Rule 2 — Lifecycle.**
- `start` is called exactly once, and before any other core method.
- `stop` is called on a transition to `null`.
- `stop` is called on a transition between two different non-`null` levels.
- `stop` is called on a transition from `null` to non-`null`.
- `stop` is not called when the level is unchanged across ticks.
- `clientStopping()` calls `stop`.
- A second `start` is rejected.

**Rule 3 — Faults.**
- A throwing `onClientTick(PRE)` results in `stop` being called and no further ticks delivered.
- A `stop` that throws *inside* the fault handler still leaves the runtime faulted — the
  handler cannot fault.
- The fault clears on the next world load and ticks resume.
- Nothing propagates out of `tickStart` or `tickEnd`.
- A throw from the click handler faults identically.

**Rule 4 — Input persistence.** No cases. It is a hazard statement, not an obligation, and
`setInput`'s effect not persisting is precisely what the SPI declines to require either side
to handle until M5.

**`IGameEvents.onClientTick`'s own contract.** Not a numbered global rule, but the richest
area for the suite:
- `PRE` is delivered before `POST` within a tick.
- `POST` is never delivered without a same-tick `PRE`.
- No delivery outside the tick window.
- No re-entrant delivery.
- An unpaired `PRE` is handled as the javadoc explicitly instructs: the case asserts that one
  of the two suppressing conditions held — window closed, or faulted — rather than failing
  outright. The javadoc's sentence "A recording `IGameEvents` that observes `PRE` without
  `POST` MUST therefore assert the suppressing condition rather than fail outright" was written
  for this suite; this is where it is honoured.

**The click drain.**
- A click queued while out of world is discarded.
- A click queued while faulted is discarded.
- Clicks still queued when the handler throws part-way through the loop are discarded.
- Clicks are *not* drained between `PRE` and `POST`, so a keypress made between the two halves
  of a tick survives to the next one.

That is roughly twenty cases, and it covers all three behaviours both checklists disclaim.

### 5.3 What a green suite still does not mean

Written into `:platform-testkit`'s own `package-info.java`, not only into this spec, because
a future session will meet the code before it meets the document. A green suite does **not**
show:

- that an adapter passes the correct level or player object — a Forge adapter reading the
  wrong field would pass every case;
- that `IActuator.setInput` moves the player;
- that `PRE` genuinely precedes the game's own input read for that tick;
- that the tick source is a tick and not a frame;
- anything about rule 1 or rule 4.

Those remain smoke-checklist territory. **The testkit and the checklists are complements, and
neither subsumes the other.** Both checklists gain a pointer to the testkit, and the testkit
documentation gains a pointer back, so the division is discoverable from either end.

---

## 6. SPI v1 revision

A documentation pass. **No SPI type, method, signature, or enum constant changes** — the M2
gate did not trip, and A2b found nothing that reopens it.

- Reconcile `2026-08-11-spi-behavioural-contract-design.md` §4.1 entries 1–3, which still
  describe questions A2a settled as open, carrying only a pointer note. A2a left them
  deliberately for this pass rather than patching them piecemeal.
- Update the `package-info.java` paragraph that says conformance tests "are expected to be
  organised by this numbering, though not every rule reduces to a test" — a testkit now
  exists, so it names it, and states which rules have no cases and why.
- Record the client-shutdown soft spot as standing.

### 6.1 The client-shutdown clause stands

Global rule 2's client-shutdown obligation is capability-conditional: `stop` MUST be called
where the platform exposes a main-thread client-stopping event, and MAY be omitted where none
exists. A2a's gate write-up flags this as the soft spot in the "not a shape difference"
verdict, because `IGameEvents` states the anti-capability-check principle in absolute terms.

A2b does not re-open it, and the runtime does not resolve it in either direction. Forge simply
never calls `AdapterRuntime.clientStopping()`, because 1.7.10 has no main-thread event to call
it from; the conditional has moved from adapter code into an uncalled method, which is not an
argument for or against it. The revision records this explicitly so that a future reader does
not mistake the runtime's existence for the soft spot having been addressed. The next occasion
to press on it is M3's SPI audit, which the roadmap already schedules.

---

## 7. Testing strategy

**This is the first genuinely TDD-able work since M1.** The conformance cases are written
before `AdapterRuntime` exists and drive its construction. That inverts A2a's position, where
the spec had to state plainly that TDD did not apply because the adapters were untestable
without Minecraft on the classpath — a statement that had to be repeated to stop reviewers
flagging its absence and implementers inventing fake tests.

TDD applies to `:runtime` and `:platform-testkit`. It still does **not** apply to the adapter
modules: after conversion they are keybind registration, context construction, and four
forwarding calls, none of which can be executed without a running client. Their verification
is the checklists, as before.

**Automated.** `./gradlew clean build` — 15 core tests, the conformance suite, and the
existing purity, bytecode, dependency-direction and javadoc checks. Any step whose change is
documentation-only must use `--rerun-tasks`: javadoc edits do not change the ABI, so
`:platform:javadoc` and the test tasks silently report `UP-TO-DATE` and a green build means
nothing.

**Manual.** Both checklists on both clients, **re-run after each adapter is converted rather
than once at the end**, so a failure points at one adapter instead of two.

---

## 8. Sequencing

1. `CoreApi` in `:core`; `ContinuoCore implements CoreApi`. Build stays green; no behaviour
   change.
2. `:platform-testkit` module. Fakes moved in, `RecordingCore`, `AdapterUnderTest`, and the
   conformance cases written — failing, with no subject yet. `:core`'s tests re-pointed at the
   moved fakes and still green.
3. `:runtime` module. `AdapterRuntime` built against the suite until it passes.
4. Fabric adapter converted to delegate. **Owner re-runs `docs/smoke-checklist-a1.md`.**
5. Forge adapter converted to delegate. **Owner re-runs `docs/smoke-checklist-a2.md`.**
6. SPI v1 documentation revision (§6); roadmap closes A2b.
7. Whole-branch review on the most capable model, **reading files the diff does not include**.
   The last three sub-projects each ended with a whole-branch review finding issues that every
   per-task review had missed, and the single most valuable one was in a file the branch never
   touched.

Steps 4 and 5 each block on an owner action. Nothing after step 5 depends on the checklist
results, so step 6 can proceed in parallel with them if the owner prefers.

---

## 9. Carried-forward ledger

**Closed by A2b:** the injection seam (dissolved, §2.1), `platform-testkit` (§5), the SPI v1
revision (§6). With these closed, the gate's condition that nothing beyond M2 starts until the
v1 revision is settled is satisfied, and M3 may begin.

**Still open, untouched:**

- **The M5 actuation decision.** Edge- versus level-triggered remains undecided. Extraction
  makes it cheaper — both adapters' behaviour now lives in one file — but neither adapter may
  start re-asserting held inputs before M5 says so.
- **CI.** `.github/workflows/ci.yml` has never run, there is no remote, and its "Set up JDK 21"
  comment is stale against `toolchainVersion=25`. Untouched by standing instruction.
- **The client-shutdown soft spot** (§6.1), which stands and is re-asked at M3's SPI audit.
- **The three cosmetic items** A2a's final review triaged as "can ship".
