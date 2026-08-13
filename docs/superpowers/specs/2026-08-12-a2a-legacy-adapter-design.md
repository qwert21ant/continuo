# A2a — Legacy adapter, Forge 1.7.10

**Date:** 2026-08-12
**Status:** Approved
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) — milestone M2, sub-project A2
**Contract:** `platform/src/main/java/dev/continuo/platform/package-info.java` — **normative**

This is the first half of M2. The roadmap treats M2 as one milestone; it decomposes into two
sub-projects with different shapes, and this spec covers only the first.

| | |
|---|---|
| **A2a — this spec** | Second toolchain, `adapter-forge-1.7.10`, the contract questions the adapter cannot be written without, and manual verification on both versions |
| **A2b — its own spec, later** | The injection seam, `platform-testkit`, and the SPI v1 revision |

The split is not cosmetic. A2b's conformance suite must be written with two implementations
in front of it — the roadmap's own reasoning is that writing it against one adapter risks
encoding Fabric's accidents as the contract — so a spec for it today would be guessing at
what 1.7.10 teaches. A2a is what generates that evidence.

---

## 1. Goal and done criteria

**Deliverable:** the same unmodified core jar from M1 produces the same 40-tick walk on
Forge 1.7.10.

**Done when all five hold:**

1. `./gradlew clean build` is green with both adapters present, on a Java 25 daemon.
2. `docs/smoke-checklist-a2.md` passes against a real 1.7.10 client — 40-tick walk, 8–9
   blocks displacement along the facing axis.
3. The full A1 checklist re-runs green on Fabric after the level-identity change.
4. The new portal step passes on both versions.
5. The three contract edits in §5 are committed and the javadoc check is green.

**Explicitly not in A2a:** the injection seam, `platform-testkit`, the SPI v1 revision, and
any movement on the M5 edge- vs level-triggered actuation decision.

**The M2 gate is evaluated at the end of A2a, not acted on.** The roadmap's rule — *if the
two adapters require materially different SPI shapes, stop and redesign* — needs both
adapters to exist before it can be judged. A2a produces the judgement and writes it into the
roadmap as A2b's input. A2b is where the SPI is actually revised.

---

## 2. Build architecture

### One build, Java 25 daemon

RetroFuturaGradle 2.0.0 (2025-11-11) added Gradle 9 support and in the same change raised
its floor: **Java 25 is the minimum version required to run Gradle.** The latest release is
2.0.3 (2026-08-10). The last 1.x release is 1.4.9 (2025-10-30), and the 1.x line documents
Gradle 7.6–8.8 — it predates Gradle 9 entirely, so staying on the current Java 21 daemon
would mean pinning a plugin line with no Gradle 9 support.

The build therefore moves to a Java 25 daemon via
`./gradlew updateDaemonJvm --jvm-version=25`, which regenerates
`gradle/gradle-daemon-jvm.properties`. No Java 25 is installed in `C:\SDK`; the foojay
resolver already configured in `settings.gradle.kts` provisions it on first run. The
standing prohibition on `org.gradle.java.home` in `gradle.properties` is unaffected and
still applies.

Compile toolchains stay per-module and unchanged in kind:

| Module | Toolchain | Target |
|---|---|---|
| `platform`, `core` | 21 | `--release 8` |
| `adapters/adapter-fabric-1.21.11` | 21 | 21 |
| `adapters/adapter-forge-1.7.10` | **8** | 8 |

`C:\SDK\jdk-1.8` is present and is what makes the last row work without provisioning.

### Three consequences worth stating rather than discovering

**"The same unmodified core jar" becomes structurally true.** Under one build both adapters
consume the identical `:core` artifact. The roadmap's headline criterion for this milestone
stops being an assertion someone has to check and becomes a property of the build graph.
This is the single strongest argument for the one-build option and the thing the fallback
in §2.1 would cost.

**The existing invariants extend to the new module for free.** One entry in
`allowedProjectDependencies` (`":adapters:adapter-forge-1.7.10" to setOf(":platform",
":core")`) and `checkDependencyDirection` covers it. `checkCorePurity` and
`checkCoreBytecode` already run on `core` and are what make a shared jar meaningful rather
than merely convenient.

**Known non-goal: a distributable mod jar.** Neither adapter bundles `platform`/`core` into
a shippable artifact — Fabric would need `include()`, Forge would need shading. Both work
today only because `runClient` puts project dependencies on the dev classpath. A1 did not
solve this and A2a does not either. It is a packaging concern for whenever the project first
ships a jar to someone who is not running it from Gradle.

### 2.1 The risk, and the fallback

**Fabric Loom 1.17.17 on a Java 25 daemon is unverified.** Loom is moving that way — its
`fabric-loom-native` → Java FFM change merged 2026-07-10 and requires Java 25 — but
"moving that way" is not evidence about the pinned version.

So the first implementation task is a spike: bump the daemon, run the existing Fabric build
completely untouched. If Loom breaks, the spike fails cheaply and **work stops**. The
fallback is a separate Gradle build for the Forge adapter (its own wrapper on Gradle 8.x,
RFG 1.4.9, consuming `platform`/`core` as jars), which costs the shared-jar property above,
puts the new module outside the dependency-direction and purity checks, and turns "the same
unmodified core jar" into a claim needing a deliberate mechanism. That is a materially
different design and **requires re-approval before anything else proceeds.**

Also settled, so a later session does not re-open it: the roadmap's standing question of
whether unimined has cut a 1.4.2 release was re-checked on 2026-08-11 and it has not. RFG is
the toolchain. Do not re-litigate it in A2a.

---

## 3. The adapter module

`adapters/adapter-forge-1.7.10`, package `dev.continuo.adapter.forge`, four classes mirroring
the Fabric four one-for-one: `ContinuoForgeMod`, `ForgeActuator`, `ForgePlatformContext`,
`ForgePlatformInfo`.

### 3.1 Version equivalences

| Fabric 1.21.11 | Forge 1.7.10 |
|---|---|
| `ClientTickEvents.START_CLIENT_TICK` / `END_CLIENT_TICK` | `TickEvent.ClientTickEvent`, `phase == START` / `END` |
| `KeyBindingHelper.registerKeyBinding` | `ClientRegistry.registerKeyBinding` |
| `InputConstants.Type.KEYSYM`, `GLFW_KEY_K` | LWJGL2 `Keyboard.KEY_K` |
| `KeyMapping.Category` registered by `Identifier` | plain `String` category |
| `KeyMapping.consumeClick()` | `KeyBinding.isPressed()` |
| `KeyMapping.setDown(boolean)` | `keyBind*.pressed = value`, via access transformer |
| `FabricLoader` mod-container version lookup | `cpw.mods.fml.common.Loader.instance().getMinecraftModContainer().getVersion()` |
| `ClientLifecycleEvents.CLIENT_STOPPING` | *no equivalent — see §5.2* |

Two traps worth naming in advance: the FML package on 1.7.10 is `cpw.mods.fml`, not
`net.minecraftforge.fml`; and `cpw.mods.fml.common.Loader` collides by simple name with
`dev.continuo.platform.Loader`, so one of the two must be fully qualified at the use site.

### 3.2 Actuation

`ForgeActuator` is the same pure enum-to-target translation `FabricActuator` is, mapping
each `Input` constant to a `Minecraft.getMinecraft().gameSettings.keyBind*` field and setting
`pressed` directly.

**All seven `Input` constants map.** The dedicated sprint keybind was added in 1.7.2, so
`GameSettings.keyBindSprint` exists on 1.7.10. No change to the `Input` enum is required, and
the possibility that one was — which would have been genuine SPI surgery — is closed.

`KeyBinding.pressed` is private on 1.7.10. It is made accessible by an **access transformer**,
which RFG supports directly. The alternatives were rejected:

- *Reflection on the private field* would have to cope with the field having one name in the
  dev environment and its SRG name after reobfuscation, so the code must try both and fails
  at runtime rather than compile time — a defect class that appears only in production.
- *The public static `KeyBinding.setKeyBindState(int keyCode, boolean)`* addresses whichever
  binding currently holds a keycode rather than one the adapter chose, and silently does
  nothing when the key is unbound.

An access transformer is not a mixin, so this does not breach the roadmap's "UniMixins not
before M3" line. **The AT's SRG field name is determined from the dev environment during
implementation, not guessed here.**

**This dissolves the unbound-key clause rather than answering it.** The javadoc currently
requires a conformant adapter to surface an unbound key, while a neighbouring clause forbids
throwing for a valid `Input` constant — the carried-forward item asked M2 to pick a mechanism
reconciling the two. It does not need one. 1.7.10's `MovementInputFromOptions` reads
`keyBindForward.getIsKeyPressed()` rather than polling the keyboard, so a directly-set
`pressed` field moves the player *even when the key is unbound*. Fabric's `setDown` is
likewise per-instance. The failure mode exists only on the `setKeyBindState` route, which
this design does not take, so the correct resolution is to delete the clause. **Implementation
MUST confirm the unbound-key behaviour in the live dev environment before the clause is
deleted** — the deletion rests on it.

### 3.3 The level-identity watch

This is the core of the design, and it exists because settling rule 2 (§5.1) collapses three
separate mechanisms into one.

A single `lastLevel` field, compared at the top of the `PRE` handler before any other guard:

| Transition | Meaning | Action |
|---|---|---|
| non-null → null | world unload, disconnect, quit to title | `stop()` |
| non-null → different non-null | dimension change (now an unload) | `stop()` |
| null → non-null | world load | clear fault, then `stop()` |

The `stop()` on world load is not redundant and MUST NOT be dropped as such. In the ordinary
case the core was already stopped by the preceding transition to null and idempotency makes
it a no-op — but if that earlier `stop()` threw, the core enters the new level holding stale
state. This is the call that clears it.

One observable condition therefore discharges rule 2's world-unload trigger, rule 2's
disconnect trigger, and rule 3's fault recovery — in identical code on both adapters. That
identity is the point: the contract exists to stop the two adapters diverging, and a shared
observable condition cannot diverge the way two per-platform event choices can.

**Ordering inside a transition is load-bearing and easy to get backwards: clear the fault
first, then call `stop()` under `guarded`.** The other order lets a throwing `stop()` set
`faulted` via the guard, which the recovery line then wrongly clears — turning the fault
handler into something that can swallow its own fault, which rule 3 explicitly forbids.

The watch fires from the tick handler, which runs at the title screen on both platforms
(`ClientTickEvent` and `START_CLIENT_TICK` are client ticks, not level ticks), so a
transition to null is observed on the very next tick — within 50ms of the event that caused
it.

### 3.4 What this deletes from the Fabric adapter

`ClientPlayConnectionEvents.JOIN` and `ClientPlayConnectionEvents.DISCONNECT` are removed.
The watch covers both within one tick, and `core.stop()` is idempotent by contract, so
keeping them would mean two mechanisms for one obligation — the exact shape of drift this
design is trying to prevent. `ClientLifecycleEvents.CLIENT_STOPPING` stays; Forge has no
counterpart and, per §5.2, needs none.

Everything else in `ContinuoFabricMod` — the `inWorld` guard, `guarded`/`faulted`, the
`preDelivered` latch, `drainClicks` — is unchanged, and the Forge adapter reproduces it
structurally. Actuation stays mechanically identical across the two adapters, per the
roadmap's M5 carry-forward.

**One knock-on:** A1 checklist step 10 asserts the log line `Continuo stopping: disconnected`,
which this deletes. The step is rewritten to the new message, not dropped — it is still
checking the same cause.

---

## 4. Testing: where TDD does not apply

**A2a adds no automated tests.** This is stated so that a plan author does not invent some
and a reviewer does not flag the absence as a defect.

- `core` and `platform` gain no behaviour. The javadoc edits in §5 are documentation;
  `ContinuoCore` is untouched. The existing 15 core tests are the regression net and MUST
  stay green.
- The Forge adapter cannot be unit-tested for the same reason the Fabric adapter cannot:
  asserting anything about it requires Minecraft on the classpath, which `checkCorePurity`
  exists to forbid. Substituting a recording `IGameEvents` is precisely the injection seam,
  and that is A2b's.

The automated coverage A2a does deliver is the four build-failing checks extending to a
second adapter: `checkCorePurity`, `checkCoreBytecode`, `checkDependencyDirection`, and
doclint `-Xwerror` over the edited javadoc. The last is load-bearing here — three contract
edits with cross-references are exactly where a broken `{@link}` gets introduced.

### 4.1 Manual verification

`docs/smoke-checklist-a2.md`, mirroring A1's ten steps against 1.7.10, plus a new portal step
added to **both** checklists:

> Press `K` to start a walk and step into a nether portal before it finishes. After the
> dimension change completes, the player must not still be walking.

Then the full A1 checklist re-runs on Fabric. Not a targeted subset: the level-identity change
lands in `START_CLIENT_TICK`, the hot path every other step depends on, and a targeted re-run
of only the lifecycle steps is exactly the shape that misses a tick-handler regression. This
project has shipped checks before that passed without exercising the mechanism they named.

### 4.2 What the portal step buys, and what remains unverified

The dimension-change path is the one piece of previously-unverifiable behaviour A2a makes
manually observable, on both versions. That is a real narrowing of the unverified surface.

**Still unverified after A2a, unchanged from M1:** global rule 3 fault handling, PRE/POST
pairing, and the click drain. All three need a deliberate throw or a recording observer, both
of which are A2b's. `smoke-checklist-a2.md` carries the same explicit "do not record this as
evidence" disclaimers A1's does. **A green run of either checklist covers none of the three.**

---

## 5. Contract edits

Three edits to `dev/continuo/platform/package-info.java` and `IActuator.java`, all deletions
of open questions rather than additions. **Rule numbering (1 Threading, 2 Lifecycle, 3 Faults,
4 Input persistence) is untouched** — A2b's testkit is expected to mirror it.

### 5.1 Rule 2 — a dimension change is a world unload

The "Open question — what counts as a world unload" block is replaced by a settled trigger,
stated as an observable condition rather than as per-platform events: **an adapter MUST call
`stop()` on each of three client level-instance transitions — to `null` (world unload,
disconnect, or quit to title), between two different non-`null` instances (a dimension
change), and from `null` to non-`null` (a world load, ordinarily a no-op under idempotency,
but the call that clears stale state if a preceding `stop()` itself threw).**

The stricter reading wins on asymmetry of failure. Stopping too often is a visible, harmless
abort. Continuing across a portal is a silent wrong-distance bug, on a core whose state
describes a position in a level that no longer exists, with held input the platform has
already cleared behind the loading screen.

Expressing it as level identity rather than as "Fabric uses hook X, Forge uses hook Y" is
what makes it uniform, testable by A2b, and unable to drift.

### 5.2 Rule 2 — client shutdown becomes MUST-where-available

The "Caveat — client shutdown on 1.7.10" block is replaced by: `stop()` MUST be called on
world unload and on disconnect; on client shutdown it MUST be called where the platform
exposes a main-thread client-stopping event, and MAY be omitted where none exists.

The rationale is what makes this a resolution rather than a concession: **`stop()`'s only
observable effects — releasing held input and resetting in-memory core state — cannot outlive
the process.** On client shutdown the obligation is hygiene, not a defended failure mode. So
Fabric keeps `CLIENT_STOPPING` because it is free, and 1.7.10 is conformant by omission
rather than by a shutdown hook that would have to run off the main thread.

**Rule 1 stays exception-free.** That was the alternative's cost and it is not worth paying
for an obligation with no observable effect.

### 5.3 `IActuator` — the unbound-key clause is deleted

Per §3.2. The clause and its "MUST surface an unbound key" obligation are removed. The
1.7.10 caveat shrinks to a statement that `setKeyBindState` is non-conformant and that a
per-instance route is required, with the reason.

### 5.4 Minor

The contract spec's §4.1 says "four caveats were added"; five shipped. One word, and it is
adjacent to work already happening here.

---

## 6. Sequencing

Risk first. Steps 1 and 2 are where this milestone can fail; 3–7 are known work.

1. **Spike — Java 25 daemon.** Bump, run the existing Fabric build untouched. **If Loom
   breaks, stop and escalate** (§2.1).
2. **RFG module skeleton to `runClient`.** A 1.7.10 dev client launches with an empty mod.
   Second stopping point.
3. **Adapter classes and access transformer.** Confirm the AT's SRG field name and the
   unbound-key behaviour in the live dev environment.
4. **Contract edits** (§5).
5. **Fabric retrofit** to the level-identity watch; delete `JOIN` and `DISCONNECT`.
6. **Checklists.** Write `smoke-checklist-a2.md`, add the portal step to both, re-run A1 in
   full.
7. **Evaluate the M2 gate** and write the findings into the roadmap as A2b's input.

---

## 7. Carried-forward ledger

**Closed by A2a:**

| Item | Resolution |
|---|---|
| Is a dimension change a world unload? | Yes — §5.1, as a level-identity condition |
| Mechanism for `IActuator`'s unbound-key clause | Dissolved, not chosen — §3.2, §5.3 |
| Client shutdown on 1.7.10 vs rule 1 | MUST-where-available — §5.2 |
| Contract spec §4.1 "four caveats" | Corrected to five — §5.4 |
| Toolchain re-check (unimined 1.4.2?) | Re-checked 2026-08-11, still 1.4.1; RFG stands — §2.1 |

**Handed to A2b unchanged:**

| Item | Why it waits |
|---|---|
| The injection seam | Cheaper to design once two adapters exist and the testkit's real needs are visible |
| `platform-testkit` | Needs two implementations to generalise from. Do **not** promise one case per numbered rule: rule 1 is unfalsifiable as a test, and rules 2–3 bind `start`/`stop`, which live on `ContinuoCore` in `core`, not in this package |
| SPI v1 revision | The roadmap's M2 gate; A2a produces the evidence, A2b acts on it |

**Untouched by A2a, deliberately:** the M5 edge- vs level-triggered actuation decision.
1.7.10's actuation stays mechanically identical to Fabric's so M5 can change both in one
move. A2a MUST NOT let either adapter start re-asserting held inputs.
