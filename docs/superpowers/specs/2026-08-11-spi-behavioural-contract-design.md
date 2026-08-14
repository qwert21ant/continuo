# SPI behavioural contract — design

**Date:** 2026-08-11
**Status:** approved and implemented, on branch `spi-behavioural-contract`. §6, §7 and §8 have
been revised post-implementation to describe what actually shipped rather than what was
planned; §4 and §5 gained a caveat index (§4.1). Where this document and the `platform`
javadoc differ, **the javadoc is normative** — it is the artefact M2 measures the Forge
adapter against, and this one summarises it.
**Predecessor:** `2026-08-01-mc-automation-roadmap-design.md` §"Carried forward from M1", item 1
**Successor:** M2 (A2, Forge 1.7.10 adapter)

---

## 1. Why

The SPI defines seven *shapes* and almost no *semantics*. `onClientTick`'s entire
specification today is "called once per client tick, per phase". It does not say once per
*tick* rather than per *frame*. It does not say that `PRE` must fire before the game reads
input for that tick — the reason the A1 walk covers 40 ticks and not 39. It does not say who
owns input state after `setInput` returns.

M2's stated purpose is to test whether a second adapter implements the SPI *faithfully*.
"Faithfully" is currently undefined and untestable. Two adapters will diverge on exactly
these points and produce plausible-looking bots that walk slightly wrong distances — a
failure mode that presents as a wrong number, not as an error.

This sub-project writes the contract down before the second adapter exists, so that M2 has
something to conform *to*.

### Evidence that the gap is already live

`ContinuoFabricMod` registers `ClientTickEvents.START_CLIENT_TICK`, which fires on every
client tick including at the main menu and on loading screens — with no world and no local
player. The core is therefore ticked outside a world today. It is harmless only because
`walking` defaults to false. Nothing in the SPI says whether this is correct, so a second
adapter choosing `START_WORLD_TICK` or a Forge equivalent would be equally defensible and
behave differently.

---

## 2. Scope

**In scope.** Behavioural semantics written into the `platform` module's javadoc, plus the
minimum changes to `adapter-fabric-1.21.11` needed to make the one existing adapter conform,
plus the smoke-checklist corrections those changes force.

**Out of scope — deliberately.**

- **`platform-testkit`.** The roadmap recommends building the conformance suite during M2,
  when two implementations exist to generalise from. Writing it now against a single adapter
  risks encoding Fabric's accidents as the contract.
- **Edge- vs level-triggered actuation.** Owned by M5. See global rule 4.
- **Any SPI signature or type change.** `package-info` states that every type added to this
  package is a future version-compatibility problem. This sub-project adds none.
- **Contract annotations** (`@MainThread` and similar). Rejected: they add types to a package
  whose own documentation demands minimalism, and they buy nothing until tooling exists to
  read them, which is nowhere on the roadmap.
- **CI.** Untouched, per the standing instruction.

---

## 3. Expression: global rules in `package-info`, specifics per type

Threading, lifecycle, fault handling and input persistence are cross-cutting. Repeating them
on each of seven types guarantees the copies drift apart the first time one is edited. They
therefore live once, as a numbered list, in `package-info.java` — which already exists and
already carries this package's other global rules. Per-type javadoc states only what is
specific to that type and cites the global rules by number.

The numbering is not cosmetic. M2's testkit gets an obvious structure to mirror — cases
organised by rule number, plus per-type cases — and code review gains a way to cite a
requirement precisely. Note that not every rule reduces to a test; see §8.

RFC 2119 keywords (MUST, MUST NOT, MAY) are used throughout and mean what they normally mean.

---

## 4. The four global rules

To be written into `platform/src/main/java/dev/continuo/platform/package-info.java`.

**Rule 1 — Threading.** Every SPI method, in both directions, is called on the client main
thread. No SPI implementation may block. No SPI method may be called from any other thread.
Both target versions are single-threaded at the tick level; this rule exists to forbid an
adapter from routing calls through an asynchronous event bus.

**Rule 2 — Lifecycle.** `start(IPlatformContext)` is called exactly once per adapter
lifetime, before any other core method. `stop()` may be called any number of times, is
idempotent, and leaves the core reusable — no second `start()` follows it. An adapter MUST
call `stop()` on world unload, on disconnect, and on client shutdown.

**The client-shutdown clause above was narrowed in A2a to MUST-where-available; it is no
longer unconditional. `dev.continuo.platform`'s `package-info` javadoc is normative — see its
Rule 2 for the current text.**

Note for readers of the test suite: `ContinuoCoreTest.startTwiceReplacesContext` asserts that
the core *tolerates* a second `start()`. This rule binds adapters, not the core. The core's
leniency is not licence for an adapter to rely on it.

**Rule 3 — Faults.** If a core method throws, the adapter MUST catch it, log it with a stack
trace, call `stop()` to release any held input, and deliver no further ticks until the next
world load clears the fault. A core fault MUST NOT propagate into the game's tick loop.

If the `stop()` call inside the fault handler itself throws, the adapter MUST log that too and
still enter the faulted state. The handler must not be able to fault.

Rationale: a bot bug must never crash the user's game, and a half-dead core must never leave
a movement key held. Recovery is tied to world load because that is the same event that opens
the tick window under §5's `onClientTick` rule — one event, one state transition, no separate
recovery machinery. (The window is `onClientTick`'s alone; rule 2 is lifecycle only and does
not state it. The numbering is load-bearing for M2's testkit, so the attribution matters.)

**Rule 4 — Input persistence is not guaranteed.** State set through `setInput` may be cleared
by the platform at any time without notice. Any screen opening does this on both target
versions (`KeyMapping.releaseAll`; 1.7.10's `KeyBinding.unPressAllKeys`), as does the user
physically tapping the key. The SPI requires neither edge- nor level-triggered actuation from
core or adapter.

**Resolving this is M5's job**, per the roadmap's deferral: the executor's per-tick position
resync will make re-assertion of held inputs a special case of the same reconciliation loop.
Until then, both adapters MUST behave identically here, so that M5 can change both in one
move. The A1 core's assumption that a single `setInput(FORWARD, true)` persists for 40 ticks
is documented-as-unguaranteed by this rule, not fixed by it.

### 4.1 Caveats added during implementation

**Caveats 1–3 were settled in A2a, and this section was rewritten to match in A2b's SPI v1
revision. The normative text is `dev.continuo.platform`'s `package-info` javadoc;
`2026-08-12-a2a-legacy-adapter-design.md` records the reasoning.**

Five caveats were added to the shipped javadoc after this section was approved. They are
listed here by reference rather than restated, because §3's whole argument is that a rule
copied into two documents drifts. Read the javadoc for the wording that binds.

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
4. **§5's "both phases MUST be delivered" — one exception.** See §5 below.
5. **`onClientTick`, ticks counted vs. ticks travelled.** The callback keeps firing while the
   game is paused or the death screen is up — the tick loop, a world, and a local player all
   still exist, but the level itself is frozen — so a core counting forty of these ticks can
   cover less than forty ticks of ground. `IGameEvents#onClientTick`'s javadoc records this;
   what happens to a held input across those screens is rule 4's subject, deferred to M5.

---

## 5. Per-type semantics

### `IGameEvents.onClientTick(TickPhase)`

- Called once per client **tick**, not per render frame. An adapter MUST NOT bind this to a
  frame or render event. Nominal rate is 20/second; it may be lower under load and MUST NOT
  be higher.
- `PRE` MUST fire before the game reads input for that tick. The consequence is the part
  worth writing down: `setInput` called during `PRE` of tick *N* affects the player's
  movement on tick *N*. This is why a 40-tick walk yields 40 ticks of travel, not 39.
  *Shipped caveat:* ticks counted are not ticks travelled — the callback keeps firing while
  the game is paused or the death screen is up, with the level frozen.
- `POST` MUST fire after the game has finished processing that tick's logic, and after `PRE`
  for the same tick.
- Adapters MUST deliver both phases. See §6 for why, and for the cost. *Shipped caveat:*
  `POST` MAY be suppressed for a tick whose `PRE` was already delivered, if and only if the
  tick window has closed or the adapter has faulted by the time `POST` is due — never for any
  other reason, and never the other way round. This is the intended resolution of the tension
  with the world-window and rule 3 clauses, and the javadoc states it so a conformance suite
  can encode it as an assertion rather than a failure.
- Delivered only while a world is loaded and a local player exists.
- MUST NOT be delivered re-entrantly.

### `IActuator.setInput(Input, boolean)`

- Takes effect at the game's next input read — therefore the same tick when called from
  `PRE`.
- Idempotent: setting an already-pressed input to pressed is a no-op from the game's
  perspective. (Already documented; retained.)
- Adapters MUST support every `Input` value. Throwing for a valid enum constant is a
  conformance failure.
- The core MUST NOT pass a null `Input`. Adapter behaviour on null is unspecified.
- Subject to global rule 4: the effect does not necessarily persist.

### `IPlatformContext`

- `actuator()` and `info()` MUST NOT return null, and MUST return the same instance on every
  call. The core may therefore cache them.
- The context is valid for the adapter's entire lifetime.

### `IPlatformInfo`

- `gameVersion()` returns the game's release version as the loader reports it — `"1.21.11"`,
  `"1.7.10"`. Never null. Returns `"unknown"` when it cannot be determined.
- `gameVersion()` is explicitly **not** for feature detection. Branching core behaviour on a
  parsed version string is what capability negotiation is for, and that does not exist yet.
- `loader()` never returns null and is constant for the adapter's lifetime.

### Enums

- **`Input`** — adapters MUST map every value.
- **`TickPhase`** — ordering and delivery obligations as under `onClientTick` above.
- **`Loader`** — adding a constant breaks exhaustive switches in adapters and is therefore a
  breaking change to this package.

---

## 6. Changes to existing code

### `platform/` — javadoc only

Eight files: `package-info.java` gains §4's rules; the seven types gain §5's semantics. No
signature changes, no new types, no behaviour change. The Java 8 bytecode check, the
no-`net.minecraft`-in-core check, and the dependency-direction check are all unaffected.

### `core/` — no change

`ContinuoCore.onClientTick` already returns early for any phase other than `PRE`, and
`ContinuoCoreTest.ignoresPostPhaseTicks` already asserts it. Mandating `POST` delivery
therefore requires nothing of the core. Stated here explicitly so that implementation does
not drift into unrelated edits.

### `adapters/adapter-fabric-1.21.11/` — six conformance changes

Four were planned; two more were found necessary during implementation. All in
`ContinuoFabricMod.java`:

1. **In-world guard.** Deliver ticks only when a world and a local player are present.
   Closes the §1 gap.
2. **`POST` delivery.** Register `ClientTickEvents.END_CLIENT_TICK` → `onClientTick(POST)`.
3. **Fault handling.** Wrap core calls per rule 3: catch, log with stack trace, `stop()`, set
   a faulted flag, cease delivery.
4. **Fault recovery.** Clear the faulted flag on world load.
5. **`preDelivered` latch** *(added during implementation)*. The in-world and faulted flags
   are re-read independently by each phase's handler, so either can flip between
   `START_CLIENT_TICK` and `END_CLIENT_TICK` of one `Minecraft.tick()` — a dimension change,
   or a disconnect processed mid-tick. Without the latch, `POST` could fire with no same-tick
   `PRE`, violating change 2's own ordering clause. The latch is cleared unconditionally on
   every `END_CLIENT_TICK` entry so it cannot wedge across ticks. Its residual effect — a
   `PRE` left unpaired — is the exception now written into §5.
6. **Unconditional click drain** *(added during implementation)*. Change 1 drained clicks only
   on the out-of-world path. Clicks queued while faulted, or left queued when `requestWalk()`
   threw partway through the consume loop, then leaked into a walk once `JOIN` cleared the
   fault. The drain now also runs on the faulted path and after the guarded `PRE` block.

**Deviation from the plan, owner-approved.** Changes 5 and 6 alter code the implementation
plan supplied verbatim. Review found that the verbatim code did not satisfy §5's phase-pairing
clause or rule 3's "release any held input" intent, and the conflict was escalated rather than
resolved silently. The owner ruled: fix both — the `IGameEvents` contract governs over the
plan's literal code. Recorded here because a spec that shows only the planned code would make
the shipped adapter look like an unexplained divergence to M2.

**On the "verify, don't assume" item.** `ClientPlayConnectionEvents.DISCONNECT` is expected to
fire when leaving a *singleplayer* world — quit-to-title tears down the integrated server
connection like any other. That is a static reading, not an in-game observation; smoke
checklist step 10 exists to confirm it directly, and the checklist has not been run in this
branch. Separately, and regardless of how step 10 comes out, `DISCONNECT` does **not** cover a
dimension change, which replaces the client level with no disconnect at all — see §4.1 caveat
1, the unresolved half of this question.

**On mandating both phases.** This is a new requirement rather than a fix to an existing
violation, and is the one place this sub-project exceeds "javadoc only". It was chosen
deliberately: the alternative — `POST` delivery is optional — means the core can never use
`POST` without a capability check, which reintroduces precisely the divergence this contract
exists to prevent. The cost is roughly three lines in the adapter and one ignored call per
tick in the core.

### `docs/smoke-checklist-a1.md` — one correction, two additions

**Correction, step 5.** The diagnostic currently reads: *"Roughly double (~17 blocks) …
means the tick hook is firing at the wrong rate (e.g. registered on both client tick
phases)"*. Once `POST` delivery is mandatory this advice is actively wrong — the adapter will
be registered on both phases, correctly, and the core will ignore one of them. Left as-is it
sends a future debugger after a non-bug. The doubled-distance diagnostic must be rewritten to
point at the core acting on both phases rather than at registration on both phases.

**Addition.** Pressing the walk key at the title screen must do nothing. *As shipped* this is
step 9, and it carries an explicit disclaimer: Minecraft accumulates `KeyMapping` clicks only
while no `Screen` is open, so the title screen queues nothing and the step cannot exercise the
click drain of change 6. It evidences the in-world guard and the absence of the log line, and
says so. The drain is knowingly unverified — every reachable no-world moment has a screen up,
and the one drain path reachable in play is the faulted one, which is out of scope alongside
rule 3.

**Addition.** Walking, then exiting to title mid-walk, must leave no key held. *As shipped*
this is split across steps 8 (symptom, after rejoining) and 10 (cause: the
`Continuo stopping: disconnected` line).

**Also shipped, not planned.** Two disclosure paragraphs at the foot of the checklist, for the
drain and for PRE/POST phase pairing, alongside the planned rule 3 one. The pairing paragraph
records that the `preDelivered` latch closes only the POST-without-PRE direction and that an
unpaired `PRE` is reachable by design.

---

## 7. Verification, and its limits

`./gradlew clean build` covers the core's 15 tests and four invariant checks: `checkCorePurity`
and `checkCoreBytecode` from the `continuo-pure-module` convention plugin,
`checkDependencyDirection` from the root build, and — added by this sub-project — `javadoc`
with `-Xdoclint:all,-missing -Xwerror`, wired into `check` for `platform` and `core`. The
fifteenth test (`stopIsIdempotent`) was added here; the javadoc check exists because nothing
else in the build read the javadoc, so a broken `{@link}` in a contract expressed *entirely*
as javadoc would have been invisible. The other three serve as a regression guard rather than
as evidence for the contract.

The adapter has zero automated tests and cannot get them without Minecraft on the classpath.
Adapter conformance therefore rests on the manual smoke checklist.

**Known gap: rule 3 is not verified by this sub-project.** Exercising the fault path requires
deliberately making the core throw, which is not something to leave in the tree, and the
manual checklist cannot reach it. Rule 3 is written, implemented, and knowingly untested until
M2's `platform-testkit` covers it. This is recorded rather than papered over: the checklist
must not imply it exercises the fault path.

---

## 8. Consequences for M2

- "Faithfully implements the SPI" now has a referent. M2's Forge 1.7.10 adapter is measured
  against §4 and §5 — and, where they differ, against the `platform` javadoc, which is
  normative.
- `platform-testkit` gains a ready-made structure to organise by: the numbered rules, plus
  per-type cases, with rule 3 the first priority since nothing else covers it. **Not** one
  case per rule — an earlier draft of this section promised that, and it is not achievable as
  the code is currently wired:
  - **Rule 1** is half-testable. "Called on the client main thread" can be asserted by a
    recording implementation that captures `Thread.currentThread()`. "No implementation may
    block" cannot: it has no threshold, no observable failure, and no way to distinguish a
    slow tick from a blocking one. It stays a review rule, not a test.
  - **Rules 2 and 3** bind `start` and `stop`, which are methods on `ContinuoCore` in the
    `core` module. They are not on any type in `dev.continuo.platform`, and §2 forbids adding
    one. A testkit that depends only on the SPI package cannot express them; it would have to
    depend on `core`, or M2 must decide the lifecycle belongs on an SPI type after all.
  - **Rule 4** is a statement that something *may* happen, not that it must. There is no
    assertion to write; it constrains review and M5, not a test.
- **Seam problem M2 must solve first.** `ContinuoFabricMod` constructs its core with
  `new ContinuoCore()` inline, with no way to inject anything else. Every conformance case
  worth writing needs to substitute a *recording* `IGameEvents` — one that logs phases, ticks,
  threads and lifecycle calls — and observe what the adapter actually delivers. Until an
  adapter can be handed its `IGameEvents` rather than creating one, the testkit can only test
  the core against a fake adapter, which is the direction that was never in doubt. The seam is
  deliberately **not** added in this sub-project: §2 keeps this to javadoc plus the minimum
  adapter conformance changes, and a seam is a design decision that wants both adapters in
  view. M2 designs it once, for both.
- Rule 4 constrains M2 concretely: keep 1.7.10's actuation mechanically identical to Fabric's,
  so M5 can change both together.
- §4.1's five caveats are M2's opening agenda. Caveat 1 (what counts as a world unload) is the
  one that must be decided before the Forge adapter's lifecycle wiring is written, because
  both adapters change together whichever way it goes.
- The SPI v1 revision at the end of M2 revises this document alongside the code. The contract
  and the shapes are versioned together.
