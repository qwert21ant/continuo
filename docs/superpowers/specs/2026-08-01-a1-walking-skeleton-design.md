# Continuo A1 — Walking Skeleton (Fabric 1.21.11)

**Date:** 2026-08-01
**Status:** Approved
**Milestone:** M1
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md)
**Architecture:** [`mc-automation-architecture.md`](../../../mc-automation-architecture.md)

---

## 1. Purpose

Prove the core/game boundary exists, end to end, with the least possible code on either
side of it.

A bot walks forward for a fixed 40 ticks on Fabric 1.21.11 — roughly 8–9 blocks at vanilla
walking speed — driven by a Java 8 core jar that contains no reference to Minecraft.
Nothing more. The value of this milestone is the *seam*, not the
behaviour — every subsequent sub-project is built on the SPI shape and the build invariants
established here.

A1 deliberately does not read the world. Block abstraction is the hardest part of the SPI
and the part M2 (Forge 1.7.10) will teach the most about; designing it now means designing
it around 1.21-shaped assumptions and throwing the result away.

**SPI v0 is designed as if 1.7.10 already existed.** No `BlockState`-shaped types, no
assumptions about registry names, chunk formats, or modern API idioms leaking into the
interfaces. This is cheapest to apply now and hardest to remember now.

---

## 2. Scope

**In scope**

- Gradle multi-module skeleton, Kotlin DSL
- `platform` — SPI v0 (four interfaces, three enums)
- `core` — `ContinuoCore`, a tick-counting walk
- `adapters/adapter-fabric-1.21.11` — keybind, tick hook, `IActuator` implementation
- `buildSrc` — three build-failing invariant checks
- Headless core unit test
- CI

**Out of scope**

World reading, rotation control, goals, pathfinding, config, chat commands, GUI, bridge,
mixins, and the Forge 1.7.10 adapter (M2).

---

## 3. SPI v0

```java
// platform/ — zero net.minecraft, Java 8 source level

public interface IActuator {
    void setInput(Input input, boolean pressed);
}

public enum Input { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT }

public interface IPlatformInfo {
    String gameVersion();   // "1.21.11"
    Loader loader();        // FABRIC
}

public enum Loader { FABRIC, FORGE, NEOFORGE }

/** Game -> core. Core implements this; the adapter calls it. */
public interface IGameEvents {
    void onClientTick(TickPhase phase);
}

public enum TickPhase { PRE, POST }

/** What the adapter hands the core at startup. */
public interface IPlatformContext {
    IActuator actuator();
    IPlatformInfo info();
}
```

`IPlatformInfo` has no consumer in A1. It is included anyway: it costs nothing, it
establishes the metadata direction that capability negotiation needs later, and it gives
the smoke check something to print that proves which adapter is actually loaded.

`TickPhase` carries `PRE` and `POST` although A1 only uses `PRE`, so the `onClientTick`
signature does not churn when both phases are needed.

---

## 4. Core

One class.

```java
public final class ContinuoCore implements IGameEvents {
    void start(IPlatformContext ctx);
    void stop();
    void requestWalk();
    public void onClientTick(TickPhase phase);
}
```

State: a tick counter and a walking flag.

Tick numbering, stated precisely because the tests assert on it: ticks are counted from the
first `onClientTick` **after** `requestWalk()`, numbered 1 upward. The core sets `FORWARD`
pressed while handling tick 1, holds it through tick 40, and releases it while handling
tick 41. The walk therefore spans 40 ticks of held input.

**No static singleton.** The adapter constructs `ContinuoCore` and holds it. The core has
no global state and no knowledge of its owner, which is precisely why the headless test can
construct one with a fake `IPlatformContext`.

**The adapter never decides anything.** It translates a keypress into a method call and a
method call into a `KeyBinding`. If A1 ends with a conditional in the adapter that is not
pure translation, the SPI is already wrong, and day one is when that is worth knowing.

### Control flow

```
keybind pressed  --> adapter --> core.requestWalk()
client tick      --> adapter --> core.onClientTick(PRE)
                                    |
                                    v
                       core --> IActuator.setInput(FORWARD, true/false)
                                    |
                                    v
                   adapter --> KeyBinding.setPressed(...)   <-- only MC code in the loop
```

### Behavioural decisions

**Re-triggering mid-walk is ignored.** Not restarted, not queued. Simplest rule, and it is
testable. Anything cleverer is speculative until there is a real command queue at M6.

**Leaving the world mid-walk must not strand an input.** `KeyBinding.setPressed(true)`
outlives the walk if the player disconnects at tick 20, leaving the client holding W. The
fix stays inside the existing shape: `stop()` releases all inputs and resets state, and the
adapter calls it on world unload and on mod shutdown. No new SPI interface — `onWorldChange`
arrives at M3 when the core has state worth invalidating. But the input-release guarantee
has to exist now, because a bot that jams movement keys is the kind of bug that makes the
whole harness untrustworthy.

---

## 5. Module layout and build

```
continuo/
├── settings.gradle.kts
├── build.gradle.kts
├── buildSrc/                          <- convention plugins + the three invariant checks
├── platform/                          <- SPI. java-library, --release 8
├── core/                              <- ContinuoCore. java-library, --release 8, -> platform
└── adapters/
    └── adapter-fabric-1.21.11/        <- unimined, Java 21, -> platform + core
```

Three modules plus `buildSrc`. The architecture doc's full module tree (`core-math`,
`core-world`, `core-pathfinder`, `core-engine`, `core-api`, `movements/*`, `scripts/*`,
`bridge/*`) is created as code arrives to justify each one. Empty modules slow the build
and obscure the repo.

**Gradle Kotlin DSL. unimined as the loader plugin**, selected against M2's requirements
rather than M1's — a modern-only plugin chosen here means redoing the build a milestone
later. Mojang mappings for 1.21.11.

`platform` and `core` are plain `java-library`; no loader plugin touches them. That is the
structural expression of the architecture's central claim.

**No mixins in A1.** Fabric API provides `ClientTickEvents.END_CLIENT_TICK` and
`KeyBindingHelper` directly; Forge 1.7.10 provides `TickEvent.ClientTickEvent` and
`ClientRegistry.registerKeyBinding` on its event bus. Both milestones' hooks are plain
event subscriptions. Mixins — and UniMixins on 1.7.10, which is the fiddly part — are
deferred until something needs them, probably M3 for block access. This removes the most
environment-sensitive tooling from the milestone whose job is proving the environment works.

---

## 6. Invariant checks

Three checks in `buildSrc`, wired into `check` so plain `./gradlew build` enforces them.

| Check | Asserts |
|---|---|
| `checkCorePurity` | No `net/minecraft` reference in the constant pool of any compiled `platform`/`core` class, **and** no Minecraft artifact in their declared dependencies |
| `checkDependencyDirection` | The project dependency graph matches an allowlist declared in exactly one place |
| `checkCoreBytecode` | Every class file in `platform`/`core` has major version <= 52 (Java 8) |

`checkCorePurity` does both a bytecode scan and a dependency scan deliberately: a
transitive Minecraft dependency would slip past a dependency-only check.

**The checks are themselves tested as part of A1's definition of done.** Each is
deliberately violated on a scratch branch, confirmed to fail with a useful message, then
reverted. A check that silently passes because its class scanner has a path bug is worse
than no check, because it will be trusted for years. `checkCoreBytecode` matters most here:
it has no real consumer until M2, so A1 is the only opportunity to confirm it works before
the project depends on it.

Rationale for machine-checking rather than documenting: with most code written by agents, an
architectural rule that lives only in a document erodes. A rule that breaks the build cannot.

---

## 7. Testing

### Automated

One test class, and it is the template every later core test follows.

`FakeActuator` records `(tick, input, pressed)` tuples. `FakePlatformContext` supplies it
alongside a `FakePlatformInfo("0.0-test", FABRIC)`. The test constructs a `ContinuoCore`,
calls `requestWalk()`, pumps `onClientTick(PRE)` 45 times, and asserts:

1. `FORWARD` is set pressed exactly once, while handling tick 1
2. `FORWARD` is set released exactly once, while handling tick 41
3. no other `Input` value is ever touched
4. ticks 42–45 produce no further actuator calls

Plus: `requestWalk()` during an active walk produces no additional actuator calls;
`stop()` mid-walk releases `FORWARD` and resets; and both `requestWalk()` and `stop()`
throw `IllegalStateException` when called before `start()` — one lifecycle contract, not
two.

Assertion 3 must also assert that at least one call was recorded. Without that, a dead
core produces zero calls, the loop runs zero times, and the test passes while proving
nothing. This test class is the template every later core test copies, so the weakness
would propagate.

No Minecraft, milliseconds to run. That this is *possible* is the entire architectural
claim of the project, demonstrated on day one.

### Manual smoke (Fabric 1.21.11)

Superflat creative world, survival-speed walking (not flying). Note coordinates, press the
keybind, observe the bot walk forward and stop on its own. Confirm the displacement is
8–9 blocks along the facing axis — 40 ticks at vanilla walking speed is ≈8.6 blocks, so
anything in that band passes and a wildly different number means the tick hook is firing at
the wrong rate. Confirm the log line reports `1.21.11 / FABRIC` sourced from
`IPlatformInfo`.

### CI

GitHub Actions: build all modules, run core tests, run the three invariant checks, on every
push.

---

## 8. Done criteria

1. `./gradlew build` green — core tests pass, all three invariant checks pass
2. Each invariant check verified to fail when deliberately violated, then reverted
3. Manual smoke on Fabric 1.21.11 passes as described in §7
4. Disconnecting mid-walk leaves no input stuck
5. CI runs criterion 1 on every push

---

## 9. Next step

M2 — `adapter-forge-1.7.10` against this same SPI, and the SPI v1 revision that follows
from what 1.7.10 teaches.
