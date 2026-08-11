# SPI behavioural contract — design

**Date:** 2026-08-11
**Status:** approved, not yet implemented
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

The numbering is not cosmetic. M2's testkit gets an obvious structure to mirror — one
conformance case per numbered rule, plus per-type cases — and code review gains a way to cite
a requirement precisely.

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
the tick window under rule 2 and §5's `onClientTick` rule — one event, one state transition,
no separate recovery machinery.

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

---

## 5. Per-type semantics

### `IGameEvents.onClientTick(TickPhase)`

- Called once per client **tick**, not per render frame. An adapter MUST NOT bind this to a
  frame or render event. Nominal rate is 20/second; it may be lower under load and MUST NOT
  be higher.
- `PRE` MUST fire before the game reads input for that tick. The consequence is the part
  worth writing down: `setInput` called during `PRE` of tick *N* affects the player's
  movement on tick *N*. This is why a 40-tick walk yields 40 ticks of travel, not 39.
- `POST` MUST fire after the game has finished processing that tick's logic, and after `PRE`
  for the same tick.
- Adapters MUST deliver both phases. See §6 for why, and for the cost.
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

### `adapters/adapter-fabric-1.21.11/` — four conformance changes

All in `ContinuoFabricMod.java`:

1. **In-world guard.** Deliver ticks only when a world and a local player are present.
   Closes the §1 gap.
2. **`POST` delivery.** Register `ClientTickEvents.END_CLIENT_TICK` → `onClientTick(POST)`.
3. **Fault handling.** Wrap core calls per rule 3: catch, log with stack trace, `stop()`, set
   a faulted flag, cease delivery.
4. **Fault recovery.** Clear the faulted flag on world load.

**To verify during implementation, not assume:** whether `ClientPlayConnectionEvents.DISCONNECT`
fires when leaving a *singleplayer* world. Rule 2 requires `stop()` on world unload, and the
current adapter relies on `DISCONNECT` alone to cover it.

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

**Addition.** Pressing the walk key at the title screen must do nothing.

**Addition.** Walking, then exiting to title mid-walk, must leave no key held.

---

## 7. Verification, and its limits

`./gradlew clean build` covers the core's 14 tests and the three `buildSrc` invariant checks.
Since the core does not change, these serve as a regression guard rather than as evidence for
the contract.

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
  against §4 and §5.
- `platform-testkit` gains a ready-made structure: one conformance case per numbered global
  rule, plus per-type cases, with rule 3 as its first priority since nothing else covers it.
- Rule 4 constrains M2 concretely: keep 1.7.10's actuation mechanically identical to Fabric's,
  so M5 can change both together.
- The SPI v1 revision at the end of M2 revises this document alongside the code. The contract
  and the shapes are versioned together.
