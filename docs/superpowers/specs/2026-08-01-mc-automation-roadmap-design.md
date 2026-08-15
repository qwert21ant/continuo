# Continuo — Build Roadmap Design

**Date:** 2026-08-01
**Status:** Approved
**Source architecture:** [`mc-automation-architecture.md`](../../../mc-automation-architecture.md)

This document is the *build plan*, not the architecture. The architecture — pure core,
narrow platform SPI, thin per-version adapters, plugin movements and scripts — is settled
in `mc-automation-architecture.md` and is taken as given here. What follows is how the
project gets built: how it decomposes, in what order, and which rules bind every piece.

---

## 1. Context and constraints

Decisions taken during brainstorming, each of which shapes the plan:

| Decision | Value | Consequence |
|---|---|---|
| Ambition | Full vision as written | All ten sub-projects are in scope; nothing is descoped up front |
| Resourcing | Solo, with agents writing most code | Tight specs and machine-checked invariants matter more than usual; tests are the review mechanism |
| Sequencing | Walking skeleton first | End-to-end slice before depth, contra the source doc's core-first phase order |
| Bot host | Client mod only | No headless protocol client. Adapters hook the vanilla client; one bot per game instance |
| Target env | Singleplayer first, anticheat-aware design | Optimal actuation now, behind a pluggable humanizer seam in `IActuator` |
| First adapter | Fabric 1.21.11 | Modern baseline; M1 |
| Second adapter | Forge 1.7.10 | Priority #2 — its own milestone (M2), immediately after the skeleton |
| Core bytecode | Java 8 (`--release 8`) | One core jar genuinely loads on both. No records, sealed types, pattern switches, or `var` in core |

### The defining constraint

Supporting **Fabric 1.21.11 and Forge 1.7.10** is a far wider spread than the source
architecture assumes. 1.7.10 has no `BlockState` (blocks are `Block` + metadata int), a
different chunk and lighting model, MCP obfuscation, its own mixin stack, and a Java 8
runtime. Every decision below is downstream of taking that spread seriously.

Two things follow immediately:

1. **The core compiles to Java 8 bytecode from day one**, even though nothing needs it
   until M2. This is a permanent ergonomic tax on the code you will live in most, accepted
   deliberately so that "one jar for all versions" stays literally true rather than
   aspirational. It is enforced by the build (§4) so it cannot silently lapse during M1.
2. **The 1.7.10 adapter lands at M2** — after the first working bot, but before any core
   depth is built on the SPI. Rationale in §3.

---

## 2. Decomposition

Ten sub-projects. Each has one purpose, a defined interface to its neighbours, and gets
its own spec → plan → implementation cycle.

| # | Sub-project | Purpose | Depends on | Done when |
|---|---|---|---|---|
| **A1** | Walking skeleton | Gradle multi-module, modern toolchain, SPI v0, trivial core, `adapter-fabric-1.21.11` | — | A bot walks forward for 40 ticks (≈8–9 blocks) on 1.21.11, driven by a Java 8 core jar |
| **A2** | Legacy adapter | `adapter-forge-1.7.10` against the same SPI; second toolchain; SPI v1 after the lessons | A1 | The *same unmodified core jar* produces the same 40-tick walk on 1.7.10 |
| **B** | World abstraction | **Scoped to B1 on 2026-08-14:** `IBlockView`/`BlockDescription`, the core-side classifier, data-driven per-version block property registry. *The chunk snapshot cache moved to C* | A2 | Fake-world unit tests pass; both adapters produce identical `BlockData` for the same logical block |
| **C** | Pathfinder | A*, segmentation, cost model, `IMovementRegistry`, capability negotiation, `mv-walk`, **plus the world snapshot/cache folded in from B** | B | Headless tests find correct paths through fixture worlds, with zero MC on the classpath |
| **D** | Engine | Goals, `ProcessManager`, `PathExecutor`, tick loop, resync on `onPositionCorrection` | C | `goto <x,y,z>` works in-game on both adapters |
| **E** | Bridge | `bridge-rpc` (WebSocket + protobuf: state stream + command channel), `bridge-config` (`Setting<T>` → JSON Schema, hot-reload), localhost bind + token auth | D | An external client can set a goal, stream A* nodes, and change settings |
| **F** | Web UI | Separate repo: React, schema-generated config forms, path and A* visualisation, packet timeline | E | The bot is drivable end-to-end from the browser |
| **G** | Scripts | `IScript`, behavior-tree library, `ServiceLoader` plugin loading, `sc-mine`, `sc-build` (schematic) | D | The bot mines a vein and builds a litematic |
| **H** | Version expansion | Stonecutter preprocessing, further modern versions and loaders (NeoForge, 1.20.x), mapping pipeline | A2, B | Adding a version touches only `adapters/` |
| **I** | Advanced movement | Extended node state (`Node<S>`, `StateDimension`), `mv-elytra`, modded movements | C | Elytra pathing on 1.21.11; 1.7.10 silently omits it via capabilities |

### Three structural notes

**A1 and A2 together are the make-or-break.** A1 proves the core/game boundary exists at
all; A2 proves it holds across fifteen years of Minecraft. Neither alone is sufficient
evidence. They are split so there is a working bot to show after A1, but nothing depends
on the SPI until A2 has stressed it.

**B depends on A2, and that dependency is the whole point.** The world abstraction is the
first substantial thing built *on* the SPI. Building it before the legacy adapter has
tested the SPI would mean designing the block model around 1.21-shaped assumptions and
discovering the mismatch after there is real code to rework. The cost of the delay is one
milestone of impatience; the cost of skipping it is a rewrite of B and C.

**H is not a phase, it is a property.** After A2, every version added is a re-test of
whether the boundary held. The source doc's post-phase-3 check — *how much code is in the
adapter? if there is logic rather than translation, the SPI is wrong* — becomes a standing
gate applied after every sub-project, not a one-time review.

---

## 3. Milestone ordering

Strictly sequential. Each milestone ends at a state the project could stop at without
waste.

### M1 · Walking skeleton, Fabric 1.21.11 (A1)

Gradle multi-module; core compiled `--release 8`; modern toolchain; SPI v0. Deliverable:
a bot walks forward for 40 ticks. Full spec: [`2026-08-01-a1-walking-skeleton-design.md`](2026-08-01-a1-walking-skeleton-design.md).

Design SPI v0 *as if* 1.7.10 already existed — no `BlockState`-shaped types, no
assumptions about registry names or chunk formats leaking into the interfaces. M1 is where
that discipline is cheapest to apply and hardest to remember.

### M2 · Legacy adapter, Forge 1.7.10 (A2) — the SPI stress test

Second toolchain, MCP mappings, Java 8 runtime. Deliverable: the **same unmodified core
jar** from M1 produces the same 40-tick walk on 1.7.10.

**M2 is split into two sub-projects.** The split is not cosmetic: A2b's conformance suite has
to be written with two implementations in front of it — writing it against one adapter risks
encoding Fabric's accidents as the contract — so A2a is what generates the evidence A2b acts
on.

- **A2a — ✅ DONE 2026-08-13.** RetroFuturaGradle on a Java 25 daemon, one build,
  `adapters/adapter-forge-1.7.10`, the three contract questions the adapter could not be
  written without, and manual verification on both versions. Spec:
  [`2026-08-12-a2a-legacy-adapter-design.md`](2026-08-12-a2a-legacy-adapter-design.md). The
  deliverable holds: both adapters consume the identical `:core` artifact from one build
  graph, so "the same unmodified core jar" is a property of the build rather than a claim,
  and the owner measured the 40-tick walk at 8–9 blocks on a real 1.7.10 client
  (`docs/smoke-checklist-a2.md`, verified 2026-08-13).
- **A2b — ✅ DONE 2026-08-14.** All three deliverables, and both smoke checklists re-run green
  against real clients *after* the conversion: the owner ran `docs/smoke-checklist-a1.md` on
  1.21.11 and `docs/smoke-checklist-a2.md` on 1.7.10 and reported that everything is still
  working. That is what closes the sub-project — the conformance suite cannot see the platform
  binding, so only these runs can show the extraction preserved behaviour in a live client.
  Both records note that the owner reported a summary rather than a per-step table and gave no
  displacement figure, so the earlier measurements remain the only measured figures. The
  injection seam, `platform-testkit`, and the SPI v1 revision. Spec:
  [`2026-08-13-a2b-conformance-testkit-design.md`](2026-08-13-a2b-conformance-testkit-design.md).
  The seam **dissolved rather than got solved**: extracting both adapters' shared conformance
  machinery into `:runtime` made the runtime the object worth observing, and the testkit
  constructs it directly, so no substitution mechanism inside an adapter is needed and no type was
  added to `dev.continuo.platform`. Rule 3 fault handling, the click drain and PRE/POST pairing
  are now offline assertions. The SPI v1 revision was a documentation pass: **no SPI type,
  method, signature or enum constant changed.**

**Gate: evaluated 2026-08-13 — NOT tripped.** The rule is *if the two adapters require
materially different SPI shapes, stop and redesign*. They do not. Every difference between
the two adapters is platform-forced translation; none is a difference in SPI shape. The full
finding, with the evidence it rests on and what it deliberately does not cover, is in the
carried-forward section below. The SPI is still revised to v1 on the strength of what 1.7.10
taught — that is A2b's job — but as a refinement, not a redesign. This is the last cheap
moment to change the SPI.

### Carried forward from M1 — read before starting M2

**1. Write the SPI's behavioural contract into the SPI — ✅ DONE 2026-08-11.** The contract
half of this item shipped as its own sub-project; see
[`2026-08-11-spi-behavioural-contract-design.md`](2026-08-11-spi-behavioural-contract-design.md).
Four numbered global rules (1 Threading, 2 Lifecycle, 3 Faults, 4 Input persistence) live in
`dev.continuo.platform`'s `package-info`, with per-type semantics on all seven types. The
Fabric adapter was made to conform: in-world tick window, both tick phases with a PRE/POST
latch, fault handling with world-load recovery, an unconditional click drain. A build check
now fails on an unresolvable javadoc reference. **Where that spec and the javadoc differ, the
javadoc is normative** — it is what M2 measures A2 against.

**Verified 2026-08-11:** the owner ran `docs/smoke-checklist-a1.md` (10 steps) against a real
1.21.11 client. All steps passed; measured displacement 8 blocks, inside the 8–9 band. Note
that 8.6 is the steady-state figure and ignores the acceleration ramp from standstill, so 8
is what a correct 40-tick walk looks like — do not read it as 0.6 blocks short. This manual
run is the *only* evidence the adapter conforms; it has no automated tests and cannot get
any without Minecraft on the classpath.

**Verified again 2026-08-13, on both versions:** the owner ran `docs/smoke-checklist-a2.md`
against a real 1.7.10 client and re-ran the full `docs/smoke-checklist-a1.md` against a real
1.21.11 client, both including the new portal step. All steps passed on both. The 1.7.10 walk
measured 8–9 blocks. Two specifically tracked risks closed: with the vanilla Forward key
unbound the 1.7.10 bot still walked, and no `IllegalAccessError` occurred, so the access
transformer takes effect at **runtime** and not merely at compile time. The owner reported a
summary rather than a per-step table and gave no displacement figure for the Fabric re-run;
the checklists record it that way deliberately. These remain the only evidence either adapter
conforms.

**Resolved by A2a — ✅ 2026-08-13.** Each was a question the 1.7.10 adapter could not be
written without answering. Full reasoning in
[`2026-08-12-a2a-legacy-adapter-design.md`](2026-08-12-a2a-legacy-adapter-design.md).

- **Is a dimension change a world unload? — Yes.** The trigger is now stated as an observable
  condition rather than as per-platform events: an adapter MUST call `stop()` on each of three
  client level-instance transitions — to `null`, between two different non-`null` instances,
  and from `null` to non-`null`. Both adapters run the identical `updateLevel` level-identity
  watch, so they cannot diverge here. A2a spec §3.3 and §5.1; verified by the portal step on
  both versions.
- **Mechanism for `IActuator`'s unbound-key clause — dissolved, not chosen.** Both platforms
  address the key binding *per instance* (`KeyMapping.setDown`; `KeyBinding.pressed` via the
  access transformer), and movement reads that field rather than polling the keyboard, so an
  unbound key is not a failure mode on the route either adapter takes. The clause was deleted
  rather than satisfied. A2a spec §3.2 and §5.3; confirmed in-game 2026-08-13 with Forward
  set to NONE.
- **Client shutdown on 1.7.10 vs rule 1 — MUST-where-available.** `stop()` MUST be called on
  world unload and on disconnect; on client shutdown it MUST be called where the platform
  exposes a main-thread client-stopping event and MAY be omitted where none exists. `stop()`'s
  only observable effects cannot outlive the process, so the obligation is hygiene, not a
  defended failure mode, and rule 1 stays exception-free. A2a spec §5.2.
- **Minor — the §4.1 caveat count.** The contract spec said "four caveats were added"; five
  shipped. Corrected. A2a spec §5.4.

**Closed by A2b — ✅.** A2a deliberately did not attempt these; see the A2a spec's §1
"explicitly not in A2a" and its §7 ledger. Each bullet keeps the text it was written with, so
the reasoning that was acted on stays legible, with A2b's outcome recorded beneath it.

- **An injection seam — ✅ dissolved, not built.** Both adapters hard-code `new
  ContinuoCore()`, so there is no way to substitute a recording `IGameEvents`. Without a seam
  the testkit cannot observe an adapter at all. This is A2b's first testkit problem, and it
  must not be solved by adding a type to `dev.continuo.platform`.
  *Outcome:* both adapters still hard-code `new ContinuoCore()`, and no seam was added.
  Extracting their shared conformance machinery into `:runtime` moved the object worth
  observing out of the adapters, and the testkit constructs `AdapterRuntime` directly against a
  recording core. The constraint held: no type was added to `dev.continuo.platform`.
- **The `platform-testkit` conformance suite — ✅ shipped, 28 cases.** Deferred until two
  implementations existed to generalise from; they now do, which is the precondition A2b was
  waiting on. Do not promise one case per numbered rule: rule 1's "no implementation may
  block" is unfalsifiable as a test, and rules 2 and 3 bind `start`/`stop`, which live on
  `ContinuoCore` in `core`, not in the SPI package at all.
  *Outcome:* the warning was right and was obeyed. The suite is organised by the global rule
  numbering and covers rules 2 and 3 against the core-side interface that declares
  `start`/`stop`; rules 1 and 4, and `onClientTick`'s re-entrancy clause, have no cases and are
  documented as deliberate gaps rather than left silent. What it asserts is the shared
  `AdapterRuntime`, not either adapter's platform binding.
- **The SPI v1 revision — ✅ settled as a documentation pass.** A2a produced the gate evidence;
  A2b acts on it. The gate did not trip (below), so this is a refinement pass, not a redesign —
  but it is still the gate's own condition that nothing beyond M2 starts until the revision is
  settled.
  *Outcome:* a refinement is what it turned out to be, and a smaller one than budgeted for. **No
  SPI type, method, signature or enum constant changed.** The revision was confined to the
  global-rules preamble in `package-info`, which now points at the suite and states that the
  rule numbering is load-bearing, plus reconciling the contract spec's §4.1. The gate's
  condition is therefore met and M3 is unblocked.

**The M2 gate — evaluated 2026-08-13, NOT tripped.** The rule: *if the two adapters require
materially different SPI shapes, stop and redesign.* Answered against both adapters as built,
not predicted.

Every difference between `dev.continuo.adapter.fabric` and `dev.continuo.adapter.forge` is
platform-forced translation — a different API name, event bus, or logging framework expressing
the same behaviour. The list, so none of it is mistaken later for a discovery: SLF4J vs log4j2;
two tick callbacks vs one `TickEvent.ClientTickEvent` with a `phase` discriminator, on the FML
bus; Fabric API callbacks vs `@SubscribeEvent`; Mojang vs MCP field names (`level`/`player` vs
`theWorld`/`thePlayer`); GLFW vs LWJGL2 keycodes; a registered `KeyMapping.Category` vs a
`String` category; `consumeClick()` vs `isPressed()`; `KeyMapping.setDown` vs a
`KeyBinding.pressed` write enabled by an access transformer; `FabricLoader`'s `Optional` vs a
null-guarded FML `Loader` lookup; and, at build level only, two toolchains plus a dev-run-only
deletion of the OpenAL natives that dodges a 1.7.10 sound-engine race unrelated to any adapter
or SPI code.

What did *not* differ is what the gate actually asks about. The seven SPI types are unchanged
by A2a — no type, method, signature, or enum constant was added, removed, or altered for
1.7.10. All seven `Input` constants map on both versions (the dedicated sprint keybind landed
in 1.7.2), closing the one possibility that would have been genuine SPI surgery. The tick
machinery is the same code on both: the `inWorld` window, `guarded`/`faulted`, the
`preDelivered` latch, and `drainClicks` on all three tick-start paths. The lifecycle condition
is literally one condition, `updateLevel`, with the same fault-clear-before-`stop()` ordering
on both.

Two differences deserve their classification stated rather than assumed, because they are the
ones that could be misread as shape:

- *The access transformer* is a build-configuration fact. The Java it enables is a
  per-instance write to the same conceptual field that `setDown` writes; `IActuator` is
  untouched. It made the SPI **smaller**, not larger — the unbound-key clause was deleted
  because the mechanism that needed it is not the one either adapter takes.
- *No client-stopping event on 1.7.10* is the closest call, and it is still not shape. No SPI
  type or signature changed; one contract clause was relaxed to a capability-conditional. The
  two adapters do not *interpret* that clause differently — they conform to the same clause,
  one with the capability and one without — and the obligation has no observable effect either
  way, since `stop()`'s effects cannot outlive the process. This is the roadmap's own
  "version differences are data, not branches" pattern applied to the contract. Calling that
  relaxation "not a shape difference" is itself a judgement the verdict hinges on, not a
  mechanical fact: `IGameEvents` states the project's anti-capability-check principle in
  absolute terms, and rule 2's client-shutdown clause is now exactly the kind of
  capability-conditional obligation that principle rules out elsewhere — defensible here only
  because the obligation has no observable effect, which is where a disagreeing reader should
  push.

The strongest evidence runs the other way, and it is worth recording as the finding rather
than as a footnote: **the one place where two defensibly conformant adapters genuinely would
have diverged — rule 2's unload trigger across a dimension change — was closed by making the
SPI more uniform, not by making it more accommodating.** Restating the trigger as level
identity rather than as per-platform events gave both adapters the same code, and the portal
step confirmed the same in-game behaviour on both versions. 1.7.10 tightened the SPI; it did
not stretch it.

**What this finding does not cover.** It is evidence about the surface the two adapters
actually exercise: one input, tick delivery, the lifecycle condition, and platform info. It
says nothing about the block model M3 will need, which is where the version spread is
genuinely hard, and it is not a promise that the SPI will hold there. The standing SPI audit —
H is a property, not a phase — re-asks this question after every sub-project, and M3 is the
next time it is asked in earnest.

**Known unverified, by design:** global rule 3 (fault handling) is implemented but untested —
exercising it needs a deliberate throw, which is not something to leave in the tree, and the
manual checklist cannot reach it. The click drain is likewise unreached: every
tester-reachable moment with no world loaded also has a screen open, and Minecraft only
accumulates key clicks while no screen is open, so no manual sequence queues a click for the
drain to discard. PRE/POST pairing is unobservable while the core ignores `POST`. All three
are the testkit's job. Do not treat a green smoke run as covering any of them.

**The 2026-08-13 runs on both versions change nothing here.** Both checklists carry the
disclaimers verbatim and both were recorded as passing *with* them. A future session must not
read two green manual runs as having covered rule 3, the drain, or phase pairing; the runs did
not and structurally could not.

**Superseded by A2b for the shared logic.** Global rule 3, the click drain and PRE/POST pairing
are now asserted offline by `platform-testkit` against `AdapterRuntime`, which both adapters
delegate to. What remains unverified by anything automated is the platform binding itself:
whether each adapter passes the correct level and player objects, and whether `setInput` moves
the player. A green smoke run still does not cover the three behaviours, and a green suite
still does not cover the binding.

**Both checklists were re-run on 2026-08-14, after the conversion, and passed.** Both adapters
were rewritten in A2b to delegate to `AdapterRuntime`, which made the 2026-08-13 runs evidence
about code the repository no longer contains. The owner re-ran both against real clients and
reported that everything is still working. This closes the gap that mattered: the suite is
evidence that the extracted logic behaves, and only these runs are evidence that each adapter
is still wired to it correctly in a real client. Neither substitutes for the other, and that
division is now permanent rather than a snapshot — every future adapter inherits it.

**2. Do NOT lock in edge- vs level-triggered actuation yet — it is an M5 decision.**
Today the core sets `FORWARD` once at tick 1 and assumes it persists for 40 ticks. It does
not necessarily: Minecraft clears key state whenever a screen opens
(`KeyMapping.releaseAll`; 1.7.10's `KeyBinding.unPressAllKeys`) and when the user physically
taps the key. Opening the inventory mid-walk on a multiplayer server — where ticks keep
running — silently truncates the walk with no error, and the failure presents as a wrong
distance. The behaviour is consistent across both target versions, which is good for
portability and bad for robustness.

M1's review recommended deciding this before two adapters exist. **Deferred deliberately.**
The right answer depends on machinery that does not exist yet: M5 builds the executor's
per-tick position resync and `onPositionCorrection` handling, and a core that already
reconciles against authoritative server state each tick has effectively answered this
question — re-asserting held inputs becomes a special case of the same reconciliation loop.
Choosing now would mean guessing at a contract that the resync design will either confirm
or invalidate.

What M2 must do instead: keep the 1.7.10 adapter's actuation *mechanically identical* to
Fabric's, so that whichever model M5 picks can be applied to both adapters in one change.
Do not let one adapter quietly start re-asserting inputs while the other does not — that
divergence is the thing that would actually be expensive.

**3. Budget for a 1.7.10 `KeyBinding` workaround — ✅ SPENT 2026-08-13.** The access
transformer route was taken, not reflection; `META-INF/continuo_at.cfg` widens
`KeyBinding.pressed` (`field_74513_e`), registered on both `deobfuscateMergedJarToSrg` and
`applyJST`. Confirmed working at runtime, not merely at compile time — no `IllegalAccessError`
on the 2026-08-13 run. The original note is kept below because it is the reasoning that ruled
out the two rejected alternatives.

1.7.10's `KeyBinding` has no
per-instance setter — `pressed` is private, and the only public route is the static,
keycode-addressed `KeyBinding.setKeyBindState(int keyCode, boolean)`. That silently does
nothing when the movement key is unbound (keycode 0), and it addresses whichever binding
occupies that keycode, which may not be the intended one. A faithful adapter needs
reflection on the private field or an access transformer. Roughly half a day — not a
redesign, but not free either.

### M3 · World abstraction (B) — **scoped down to B1 on 2026-08-14 — ✅ DONE 2026-08-15**

The block-property table is **data, not code** — a per-version JSON mapping
(`soul_sand` on 1.21.11, `Block 88:0` on 1.7.10) to `BlockShape` + `BlockTag`. Cross-adapter
parity test: both adapters must yield identical block data for the same fixture world.

**M3 is now B1 — the block model — alone.** Full spec:
[`2026-08-14-b1-block-model-design.md`](2026-08-14-b1-block-model-design.md).

Three things a future session must not re-derive:

- **The classification decision.** The adapter reports **raw physical facts**; a shared,
  version-independent classifier in `:core` produces the block data; a small per-version JSON
  table supplies the non-geometric exceptions. The consequence that matters: `BlockData`,
  `BlockShape` and `BlockTag` live in **`:core`, not `dev.continuo.platform`**, so no future
  adapter ever has to speak the core's classification vocabulary. M3 adds exactly two SPI types,
  both of raw fact. This is the third time a tension has been resolved by keeping the SPI smaller
  (after A2a's unbound keys and A2b's injection seam).
- **`IBlockData` is now `BlockData`,** a final class in `:core`. One implementation, immutable,
  constructed directly by fixtures. This paragraph is that rename.
- **The world view (snapshot, chunk cache, section copying) moved to M4**, drafted as
  [`2026-08-14-b2-world-view-design.md`](2026-08-14-b2-world-view-design.md) and kept as design
  *input* to M4 rather than as an approved design. It has no consumer until A\* exists, and its
  central choices — region size, storage layout, fill cost, and whether a two-phase
  `FILLING`/`SEALED` snapshot is the right shape — are all things only M4 can measure. Same
  reasoning that made A2b wait for two adapters before writing a conformance suite.

**Carry into M4:** the draft's §4 found that **lazy section filling and off-thread A\* are
incompatible** — a lazy fill from a worker thread would call `IBlockView` off the main thread and
break global rule 1 in the one place the design exists to protect it. If M4 adopts the two-phase
answer, the pre-warm-before-seal obligation lands on M5 and must be written there, not discovered.

**The world view is confirmed bound to M4, not reopened here.** §351's bullet already states the
move; this line exists only so a future reader does not have to infer it — B2's draft is design
input to M4's brainstorm, nothing in M3's closeout reopens where the snapshot, cache, or section
copying live.

**The B1 gate — evaluated 2026-08-15, NOT tripped.** The rule: *if either adapter cannot produce a
faithful `BlockDescription` without judgement logic, or if any field can be answered honestly on
only one version, stop and redesign.* Answered against both adapters as built, not predicted — the
same standard the M2 gate above was held to. Full finding:
[`2026-08-14-b1-block-model-design.md`](2026-08-14-b1-block-model-design.md) §6.1.

Every one of `BlockDescription`'s six fields — `id`, `stateKey`, `collisionBoxes`, `fluidId`,
`climbable`, `gravity` — is answered on both adapters through a native, generic API the version
already exposes for every block (registry lookup, collision-geometry query, a data-driven tag or a
type check against the game's own "this falls" abstraction), never through a table of specific
blocks written into the adapter. The one field worth naming directly: `ForgeBlockView.fluidId()`
originally tested `material == Material.water || material == Material.lava`, a block-identity
conditional; review caught it before this evaluation and it now reads `block.getMaterial()
.isLiquid()`, the question the game already answers generically. Re-reading the current source
confirms the fix holds and nothing else regressed it.

The real-client parity run corroborates this: `BlockParityTest` reports 8 tests, 0 skipped, 0
failures; 27 of 27 compared indices match exactly between the two dump files; the five excluded
indices are exactly the five predicted from decompiled sources before either client ran; index 21
(one-layer snow) classifies `AIR` on both, which is rule 0 working in a real client rather than
only in a synthetic test; and index 9 (fence) classifies `FENCE top=1.5` on both, which is the
silent-`FULL`-on-1.7.10-alone failure the original bounds-field design would have shipped.

**What this finding does not cover**, following the M2 gate's own paragraph as the model: the
fixture is one 32-block row, not an exhaustive block set; no modded blocks were exercised on
either version, so the argument that the generic APIs reach modded blocks correctly is from the
APIs' shape, not a demonstration; carpet and farmland are asserted as genuine divergences from a
single-version reading of decompiled sources, not cross-checked by an independent second observer;
slipperiness is inexpressible in the current `BlockTag` set, so "faithful" means faithful to the
six fields the SPI asks for today, not to everything a future consumer will want; and a green dump
shows the adapters *agree*, not that the classifier's own rules are *right* — agreement between two
independently-read games is strong evidence against a version-specific bug, not proof the model is
correct.

**The standing SPI audit — run 2026-08-15.** `wc -l adapters/*/src/main/java/dev/continuo/adapter/*/*.java`
puts B1's new adapter surface at `FabricBlockView.java` (132 lines) and `ForgeBlockView.java` (129
lines), against 373 total lines in the Fabric adapter module and 434 in the Forge one. Both files
were read line by line for this audit. Every conditional in either is a null level/world guard, a
vertical-range or chunk-loaded guard, or (Fabric only) a formatting branch over an empty-or-not
property map — translation, all of it. **Zero conditionals about block identity were found in
either adapter.** Full accounting: B1 spec §6.2.

### Carried forward from M2 — read before starting M4

**B1 did not resolve any of the three items the B1 spec's §6.3 carried in from A2b.** Recorded
explicitly so no reader infers otherwise:

- **The client-shutdown soft spot.** Rule 2's clause is capability-conditional while `IGameEvents`
  states the anti-capability-check principle absolutely. B1 added no lifecycle obligations and
  bears on this not at all; it carries forward untouched. See A2b spec §6.1.
- **M5 actuation**, edge- vs level-triggered — still deferred to M5, per the M2 carry-forward notes
  above. Untouched by B1.
- **`guarded(core::stop)`** in `AdapterRuntime`. Readability only, owner's call, nothing depends on
  it. Untouched by B1.

**Two items B1's audit opened that must carry into M4 alongside the above:**

- **Slipperiness is inexpressible in the current `BlockTag` set.** `ice` and `packed_ice` classify
  identically to `stone`. Both versions answer slipperiness natively (`Block.slipperiness` /
  `BlockBehaviour.Properties.friction`), and it meets §7's field-budget test on both counts, so it
  is a live candidate the first time `mv-walk` needs it — not a gap to rediscover.
- **The two divergent fixture rows, carpet and farmland.** The two games genuinely disagree — 1.7.10
  carpet has no collision, 1.21.11's does; 1.7.10 farmland is a full cube, 1.21.11's is 15/16 —
  and the classifier reports each truthfully rather than being made to agree. They are pinned per
  version in the golden files, not reconciled. If M4 finds the divergence behaviourally significant,
  the answer is a per-version table row, not a classifier change.

### M4 · Pathfinder (C) — **now also carries the world view, formerly B2**

Pure, headless, no Minecraft anywhere. Fixture worlds expressed as text art; A* plus
segmentation plus `mv-walk`; movement registry with capability filtering.

Plus the world view folded in from B2: the immutable snapshot, section copying, and the chunk
cache. Note the payoff B1 set up — M4's text-art fixture worlds implement `BlockSource` directly,
so headless pathfinding tests need neither an `IBlockView`, nor the classifier, nor a table.

Includes a **test-time path renderer** (ASCII/PNG of world, path, and expanded nodes). It
costs almost nothing at this point and is the difference between debugging A* comfortably
for the next year and debugging it blind until the web UI exists at M7.

### M5 · Engine (D) — first genuinely useful bot

Goals, process manager, path executor, per-tick position resync, `onPositionCorrection`
handling. `goto x y z` works in-game on both versions. `IActuator` gains its **humanizer
seam** here (no-op by default) so anticheat plausibility can be added later without
touching core or movements.

**Decide edge- vs level-triggered actuation here** (deferred from M1 — see the M2
carry-forward notes). Minecraft can clear held key state at any time, so a walk can silently
truncate. Once the executor reconciles against authoritative server position every tick,
re-asserting held inputs is a special case of that same loop, and the contract should fall
out of the resync design rather than being guessed at in advance. Whatever is chosen goes
into the SPI's behavioural contract and applies to both adapters in one change.

**Gate — SPI audit:** count adapter lines that are *logic* rather than *translation*.
Logic in an adapter means it leaked out of the core. Fix before continuing.

### M6 · Bridge (E) → M7 · Web UI (F)

Visibility and control.

### M8 · Scripts (G)

`sc-mine`, `sc-build`, behavior trees. The first real payoff.

### M9 · Version expansion (H)

Third and fourth adapters. Each is a re-test of the boundary.

### M10 · Advanced movement (I)

Extended node state, elytra, modded movements.

### Stopping points

After **M5** the project is a working goto-bot on two Minecraft versions fifteen years
apart. After **M8** it is the product. M9 and M10 are expansion, not completion.

### Ordering caveat

Bridge and UI sit at M6–M7 rather than earlier, on the bet that the M4 test renderer covers
debugging needs through M5. If pathfinding proves hard to diagnose in-game, pulling E
forward is the correct response.

---

## 4. Cross-cutting decisions

These bind every sub-project.

### Repo and build

Single repo `continuo/` for all JVM modules, following the source doc's module tree.
`web-ui` lives in a separate repo with its own Node toolchain and release cadence, and
communicates only over the bridge protocol.

### Toolchain

**RESOLVED IN M1 — the single-plugin premise did not survive.** This section originally
chose **unimined**, on the reasoning that one plugin handling Forge 1.7.10 (MCP) and modern
Fabric (Mojmap) in one build is precisely this project's version spread. M1's toolchain
spike found unimined's newest published release is **1.4.1 (2025-06-30)**, which predates
Minecraft 1.21.11 (2025-12-09). Minecraft classes do not deobfuscate on 1.21.11 under it
(unimined issue #189). A fix exists — PR #185, merged 2026-03-29 into the `lts/1.4` branch —
but has never been cut as a release; it lives only in a floating `1.4.2-SNAPSHOT`, which is
not an acceptable dependency for a toolchain meant to serve multiple milestones, since
snapshot coordinates are mutable and can be garbage-collected.

**The project therefore uses Fabric Loom 1.17.17** for modern versions, with Gradle 9.6.1.
See [`docs/toolchain-decision.md`](../../toolchain-decision.md) for the full evidence trail.

**Consequence for M2, which is a real cost and not a footnote:** Forge 1.7.10 needs a
second, unrelated toolchain — **RetroFuturaGradle** is the maintained option. M2 must budget
for standing it up, and the two adapters will not share build infrastructure. The claim that
this project's version independence lives in the *code* rather than the *build* is now
load-bearing rather than convenient: the SPI has to carry weight the build no longer does.

Worth re-checking at M2: if unimined has cut a 1.4.2 release by then, consolidating onto one
plugin is worth reconsidering, since the original reasoning was sound and only the release
cadence defeated it.

**Re-checked 2026-08-11: still 1.4.1.** No 1.4.2 release has been cut; the fix remains only
in the floating snapshot. Consolidation is not available, so **M2 stands up
RetroFuturaGradle as planned** and should not spend time re-litigating this. Re-check again
only if M2 slips far enough that a new release becomes plausible.

Mixins: standard Fabric mixin on 1.21.11; **UniMixins** on 1.7.10 — but **not before M3**.
M1 and M2 need only plain event subscriptions (`ClientTickEvents` on Fabric,
`TickEvent.ClientTickEvent` on Forge), which keeps the most environment-sensitive tooling
out of the milestones whose job is proving the environment works.

**Stonecutter applies only within the modern family.** 1.7.10 is a separate source tree
implementing the same SPI, not a preprocessor branch (source doc §5.3).

### Machine-checked invariants

Three build-failing checks, added in M1:

1. `net.minecraft` anywhere on the core classpath → build fails.
2. Module dependency direction violated (`core → adapter`, `platform → core`, etc.) → build fails.
3. Core bytecode above Java 8 → build fails.

Check 3 has no consumer until M2, and is therefore exactly the one that would rot without
enforcement. All three go in at M1.

This matters more than usual here. With most code written by agents, an architectural rule
that lives only in a document will erode. A rule that breaks the build cannot.

### Testing

The core is pure, therefore fully headless-testable, and that is the backbone: fixture
worlds as text art, deterministic A* assertions, cross-adapter parity tests. In-game
verification is a short manual smoke checklist per milestone per version — not the primary
safety net. CI builds every adapter and runs core tests on every change.

### Version differences are data, not branches

1.7.10 versus 1.21.11 differences surface as **absent capabilities**, never as
`if (version < X)` in core. No elytra, no swimming, no MLG bucket on 1.7.10 — the platform
simply does not advertise those capabilities and the corresponding movements deactivate.

### Licensing

Baritone is LGPL. This architecture is *inspired by* it, which is unproblematic, but its
source must not be lifted. The implementation stays clean-room unless `continuo` is
deliberately licensed compatibly.

---

## 5. Risk register

| Risk | Milestone | Mitigation |
|---|---|---|
| SPI cannot express both 1.7.10 and 1.21.11 without bloating — **did not materialise at A2a, or at B1** | M2, M3 | A2 is deliberately placed before anything depends on the SPI. The M2 gate stops everything if it happens. **Evaluated 2026-08-13: gate not tripped** — no SPI type or signature changed for 1.7.10, and the contract got smaller, not larger (§3). Scope of that evidence is one input, ticks, lifecycle and platform info; the block model at M3 was the next place this risk was live. **Evaluated again 2026-08-15, at M3: gate not tripped** — both adapters answer all six `BlockDescription` fields honestly through native, generic APIs, with zero block-identity conditionals in either `IBlockView` (§ M3 above). The next place this risk is live is M9, the third and fourth adapters |
| SPI v0 designed around 1.21-shaped assumptions during M1 | M1 | Design SPI v0 as if 1.7.10 already existed; explicit SPI v1 revision at the end of M2 |
| Forge 1.7.10 dev environment proves hostile in 2026 | M2 | unimined first, RetroFuturaGradle as fallback. Discovered in month 1, not month 6 |
| ~~Build plugin chosen at M1 cannot handle 1.7.10 at M2~~ **MATERIALIZED** | M1 | Unavoidable: unimined has no release supporting 1.21.11, so Loom was forced. M2 now needs RetroFuturaGradle as a second toolchain. Cost accepted, recorded in `docs/toolchain-decision.md` |
| Logic leaks from core into adapters | M5, ongoing | SPI audit gate after every sub-project; adapter LOC tracked as a metric |
| Java 8 core becomes painful to write | ongoing | Accepted cost. Revisit only if it demonstrably slows delivery; the escape hatch is a desugaring build step |
| A* is undebuggable without visualisation | M4–M5 | Test-time path renderer in M4; pull E forward if insufficient |
| Agent-written code erodes architecture | ongoing | Machine-checked invariants; headless tests as the review mechanism |

---

## 6. Next step

Brainstorm sub-project **A1** (walking skeleton, Fabric 1.21.11) into its own spec, then an
implementation plan. No code before that spec exists.
