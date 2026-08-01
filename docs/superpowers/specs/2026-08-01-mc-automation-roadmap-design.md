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
| **B** | World abstraction | `IBlockView`/`IBlockData`, chunk snapshot cache, data-driven per-version block property registry | A2 | Fake-world unit tests pass; both adapters produce identical `IBlockData` for the same logical block |
| **C** | Pathfinder | A*, segmentation, cost model, `IMovementRegistry`, capability negotiation, `mv-walk` | B | Headless tests find correct paths through fixture worlds, with zero MC on the classpath |
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

**Gate:** if the two adapters require materially *different* SPI shapes, stop and redesign.
The SPI is revised to v1 here on the strength of what 1.7.10 taught, and nothing beyond
this milestone starts until that revision is settled. This is the last cheap moment to
change the SPI.

### M3 · World abstraction (B)

The block-property table is **data, not code** — a per-version JSON mapping
(`soul_sand` on 1.21.11, `Block 88:0` on 1.7.10) to `BlockShape` + `BlockTag`. Cross-adapter
parity test: both adapters must yield identical `IBlockData` for the same fixture world.

### M4 · Pathfinder (C)

Pure, headless, no Minecraft anywhere. Fixture worlds expressed as text art; A* plus
segmentation plus `mv-walk`; movement registry with capability filtering.

Includes a **test-time path renderer** (ASCII/PNG of world, path, and expanded nodes). It
costs almost nothing at this point and is the difference between debugging A* comfortably
for the next year and debugging it blind until the web UI exists at M7.

### M5 · Engine (D) — first genuinely useful bot

Goals, process manager, path executor, per-tick position resync, `onPositionCorrection`
handling. `goto x y z` works in-game on both versions. `IActuator` gains its **humanizer
seam** here (no-op by default) so anticheat plausibility can be added later without
touching core or movements.

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
| SPI cannot express both 1.7.10 and 1.21.11 without bloating | M2 | A2 is deliberately placed before anything depends on the SPI. The M2 gate stops everything if it happens |
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
