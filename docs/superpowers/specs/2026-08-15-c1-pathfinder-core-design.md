# C1 — Pathfinder core design

**Date:** 2026-08-15
**Status:** 🟢 Approved — brainstormed with the owner on 2026-08-15
**Milestone:** M4 (C), first of four sub-projects
**Depends on:** B1 (`2026-08-14-b1-block-model-design.md`) — hard dependency, the whole block model
**Design input:** `2026-08-14-b2-world-view-design.md` (a draft, not an approved design)
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M4

---

## 1. M4 decomposes into four sub-projects

M4 as the roadmap states it is four largely independent subsystems in one entry. It is split, and
the split is not cosmetic — it puts each piece behind a consumer that can judge it.

| # | Sub-project | Purpose |
|---|---|---|
| **C1** | **Pathfinder core** | `BlockSource`, A\*, the cost model, four movements, text-art fixture worlds, the ASCII path renderer. **This spec.** |
| C2 | Movement registry | `IMovementType`, `IMovementRegistry`, capability negotiation, `ServiceLoader`. Extracts C1's movements and proves the plugin seam by *adding* one. |
| C3 | World snapshot | The world view folded in from B2: `WorldSnapshot`, section storage, the fill protocol, the `FILLING`/`SEALED` question. |
| C4 | Segmentation | Incremental cost backoff and the search-effort budget. |

**Why A\* comes before the snapshot.** B2 §9 argued that a snapshot with no consumer encodes a
guess as a design, and the owner accepted that on 2026-08-14 by folding B2 into M4. The identical
argument orders the work *inside* M4. A\* does not need `WorldSnapshot`; it needs `BlockSource`,
which is three methods. Live reads go through `BlockLookup` on the main thread, and only M5 wants
off-thread. Building C3 after C1 means every one of B2 §2.2's unsigned decisions — D1 lazy versus
eager, D2 storage layout, D4 out-of-region reads — gets made with a real A\* in front of it that
can size a region and measure memory. Doing it the other way round would repeat, one level down,
exactly the mistake the owner just declined.

### 1.1 In scope for C1

`BlockSource` and the `BlockLookup` retrofit; the `:core-pathfinder` module; the standability
predicates; A\* with goals and an admissible heuristic; four movements; a derived tick cost model;
text-art fixture worlds implementing `BlockSource`; an ASCII renderer of world, path and expanded
nodes.

### 1.2 Explicitly not in C1

| Deferred to | What |
|---|---|
| C2 | The movement registry, capabilities, `ServiceLoader`, and any movement beyond the four below |
| C3 | `WorldSnapshot`, sections, the fill protocol, `isChunkLoaded`'s first consumer |
| C4 | Segmentation, incremental cost backoff, returning a best-effort partial path |
| M5 | Executing a path, threading, resync, repathing |
| Sub-project I | `Node<S>`, `StateDimension`, sub-block surface heights |
| A later batched visit | Slipperiness and fluid height — see §9 |

---

## 2. Decisions

Every row was decided with the owner during the 2026-08-15 brainstorm.

| # | Decision | Rejected alternative |
|---|---|---|
| D1 | M4 splits into four sub-projects, A\* first and the snapshot third | One M4 spec; or the snapshot first, as B2 assumed |
| D2 | Four movements: traverse, ascend, descend, diagonal | Cardinal-only; or adding parkour, ladders and swimming now |
| D3 | C1 touches no SPI type and neither adapter | Adding slipperiness, or slipperiness plus fluid height, now |
| D4 | Tick costs, with the few load-bearing constants derived from the decompiled sources and cited | Undocumented estimates; a full two-version physics derivation; unitless weights |
| D5 | The renderer emits ASCII only | ASCII plus PNG; PNG only |
| D6 | Movements are a package-private `Move` interface over a fixed list | An inline expander; or shipping the public `IMovementType` now |
| D7 | Fixtures and the renderer share one format: Y-slices, lowest first | A vertical cross-section; or both layouts behind an orientation header |
| D8 | The pathfinder gets its own `:core-pathfinder` module | A package inside `:core` |
| D9 | Standability is decided by **measurement** (`collisionTop`), not by a `BlockShape` switch | A category test over `{FULL, SLAB_TOP, STAIR}` — see §4.2 |

D3's reasoning is worth keeping: the roadmap promises M4 is *"pure, headless, no Minecraft
anywhere"*, and slipperiness would make that false for a cost refinement nothing yet measures.
Batching it with fluid height means the two untestable adapter modules are edited once rather
than twice.

D6's reasoning: four movements exist on day one, so the `Move` abstraction has four consumers and
is not speculative. C2's job then becomes *publish, filter by capability, and load* rather than a
restructure — but the public signature stays unfrozen until C2 can shape it against a registry.

---

## 3. Module layout

A new Gradle module, `:core-pathfinder`, applying `id("continuo-pure-module")` and depending on
`:core`. Java 8 bytecode, `-Xdoclint:all,-missing -Xwerror`, same as its neighbours.

Two reasons over a package inside `:core`. It is the name the source architecture already uses
(`mc-automation-architecture.md` §3, "core-pathfinder"). And this project's execution mode
requires that a reviewer never overlap a writer in the same Gradle module — a reviewer who cannot
get an independent build run silently degrades to inspection-only — so module boundaries are
scheduling boundaries here, not just naming ones.

`BlockSource` itself lands in **`:core`**, not the new module, because `BlockLookup` implements it
and `:core` must not depend on `:core-pathfinder`.

---

## 4. The world interface and the standability predicates

### 4.1 `BlockSource`

The B2 draft's §5.1 signature stands unchanged:

```java
// :core
public interface BlockSource {
    BlockData at(int x, int y, int z);
    int minY();   // inclusive
    int maxY();   // exclusive
}
```

`BlockLookup` already declares all three methods with exactly these semantics, so the retrofit is
an `implements` clause and nothing more. `WorldSnapshot` will implement it at C3; fixture worlds
implement it in C1's tests. The core codes against this interface, never against a concrete type —
that is a stated done-criterion in §8, not merely an intention.

Javadoc on `BlockSource` must not `{@link}` `WorldSnapshot`, which does not exist yet. Javadoc is
build-failing in the pure modules and a forward reference to a missing type breaks the build.

### 4.2 The predicates are measurements, not category switches — D9

The first draft of this design made support a `BlockShape` test over `{FULL, SLAB_TOP, STAIR}`.
Checking it against B1 §4 falsified it, and the reasoning is recorded because it is the single
most load-bearing decision in C1.

`BlockShape` has nine values, and `PARTIAL` is a catch-all: *"has collision, but matches no other
category. Includes unrecognised modded geometry."* B1 §4 records farmland as **`FULL` on 1.7.10
and `PARTIAL` with a collision top of `0.9375` on 1.21.11**. A category test therefore refuses to
walk on modern farmland while walking legacy farmland, and refuses to walk on unrecognised modded
blocks as a class.

So the predicates read `collisionTop()`, exactly as `BlockShape`'s own javadoc directs — *"code
that needs a real measurement should read `BlockData.collisionTop()`"*.

```
passable(p)   ::  shape != UNKNOWN
              &&  collisionTop <= PASSABLE_MAX_TOP   (0.25, the THIN_LAYER ceiling)
              &&  fluid == NONE
              &&  !tags.contains(AVOID)

supports(p)   ::  shape != UNKNOWN
              &&  shape != FENCE
              &&  SUPPORT_MIN_TOP <= collisionTop <= 1.0   (0.9 .. 1.0)
              &&  fluid == NONE
              &&  !tags.contains(AVOID)

standable(x,y,z) ::  passable(x, y,   z)
                 &&  passable(x, y+1, z)
                 &&  supports(x, y-1, z)
```

Four details, each guarding a trap B1 recorded:

- **`UNKNOWN` is tested first, by shape, before any number is read.** `BlockData.UNKNOWN` has a
  `collisionTop()` of `0.0`, indistinguishable from air, while meaning "unreadable, might be
  solid". Reading the number first silently treats unknown terrain as safe. The pathfinder never
  routes through unknown.
- **`FENCE` is excluded from `supports` twice over**, by shape and by the `1.0` upper bound.
  B1 §4 records fences and walls at exactly `1.5` on both versions, reached by different routes,
  and B1's classifier rule 2 *defines* `FENCE` as any box with `maxY > 1.0` — nothing else in the
  audit set exceeds `1.0`. So the two checks are equivalent today. Both are kept: the numeric
  bound is what makes `supports` a self-contained measurement, and the shape check preserves the
  behavioural intent (`FENCE` means "cannot walk over, cannot jump") if that classifier rule ever
  changes.
- **Fluid is tested independently of shape.** On 1.21.11 a block can be `FULL` or `SLAB_*` *and*
  report `Fluid.WATER`; 1.7.10 cannot express that state at all. Neither predicate may assume the
  two are mutually exclusive.
- **`AVOID`** (lava, fire, cactus, magma) is never entered and never stood on.

### 4.3 Two consequences, stated plainly

**B1's recorded version divergences become behaviourally invisible.** B1 §4 pinned carpet and
farmland per version rather than reconciling them, and the handoff into M4 predicted that if the
divergence mattered behaviourally the answer would be a table row. Under measured predicates it
does not matter and no table row is needed:

| Block | 1.7.10 | 1.21.11 | C1 verdict |
|---|---|---|---|
| carpet | `AIR`, top `0.0` | `THIN_LAYER`, top `0.0625` | passable on both |
| farmland | `FULL`, top `1.0` | `PARTIAL`, top `0.9375` | supportive on both |

This is a claim, and §7 lists it as a test.

**`SLAB_BOTTOM` floors are unwalkable in C1.** A collision top of `0.5` falls in the gap between
`PASSABLE_MAX_TOP` and `SUPPORT_MIN_TOP`, so a bottom slab is neither enterable nor standable — an
obstacle. This is honest rather than accidental: a node is an integer block position and cannot
represent feet resting at y+0.5. Representing it needs a per-column surface height, which is node
state, which the roadmap already assigns to sub-project I. The limitation is deliberate, tested
for, and recorded in §9.

The residual error the thresholds accept is at most 0.25 of a block in foot height (standing on a
thick snow layer) and 0.1 (standing on farmland). Minecraft's own auto-step absorbs both.

---

## 5. The search

### 5.1 A\*

Standard open/closed A\*. Node identity is a packed `long` — x and z at 26 signed bits, y at 12 —
in a `HashMap<Long, PathNode>`; the open set is a binary min-heap keyed on f. Java 8, so
`PathNode` is a plain class with mutable `g`, `f`, `parent` and a closed flag, not a record.

**Determinism is a hard requirement.** The tests assert *which* path is returned, so ties must
break identically on every run: a fixed movement iteration order, f-ties broken by lower h, then
by insertion sequence. Without this C1 grows a class of flaky tests that present as A\* bugs, and
the cost of diagnosing that later is far above the cost of specifying it now.

**Termination** is one of: goal reached, open set exhausted, or a hard node budget hit.
`PathResult` carries the outcome (`FOUND`, `NO_PATH`, `BUDGET_EXCEEDED`), the path positions, and
expansion statistics for the renderer.

**A budget hit returns no partial path.** Returning the best node so far *is* incremental cost
backoff, and that is C4's whole subject. C1 names the seam and stops there.

### 5.2 Goals

```java
public interface Goal {
    boolean isReached(int x, int y, int z);
    double heuristic(int x, int y, int z);
}
```

`GoalBlock` (an exact position) and `GoalXZ` (any Y at a column) in C1. `GoalNear` is deferred —
nothing needs it before M5.

### 5.3 The heuristic

```
h = cheapestMoveCost × max(|dx|, |dz|, |dy|)
```

Every C1 movement changes each axis by at most one, so this cannot overestimate. Admissibility
holds **by construction** rather than by an argument about diagonal factors — which is the version
that survives a reviewer, and the version that stays true when C2 adds movements, provided
`cheapestMoveCost` remains a genuine lower bound over the active set.

It is deliberately loose. C1 has no performance target to trade tightness against, and search
effort is C4's subject.

### 5.4 The four movements — D2

Each is a package-private class implementing a `Move` interface with an
`expand(context, sink)`-shaped method. A\* iterates a fixed list.

| Move | Directions | Precondition beyond `standable(target)` |
|---|---|---|
| Traverse | 4 cardinal, same y | — |
| Ascend | 4 cardinal, y+1 | `passable(x, y+2, z)` at the origin — headroom to jump |
| Descend | 4 cardinal, y−k for k ≤ max safe fall | the intervening column is passable |
| Diagonal | 4 diagonal, same y | both orthogonal neighbours passable at feet **and** head — Minecraft does not permit squeezing a corner |

---

## 6. The cost model — D4

Costs are in ticks, which the source architecture §3 takes from Baritone as-is. The table lives in
pure `:core-pathfinder` and is therefore shared across both versions; there is no per-version seam
and C1 does not introduce one.

**No numeric value appeared in this spec until it was derived.** The project's standing rule is
that Minecraft API and behaviour claims are evidenced from the decompiled sources on disk, never
recalled — B1 caught three silent, one-version-only wrong answers that way. The implementation
plan carried a derivation task whose deliverable was `file:line` citations from **both** decompiled
trees; the table below is now filled in from it. The full evidence, with the quoted source line for
every raw value and the arithmetic that turns it into ticks, is in
`.superpowers/sdd/2026-08-15-c1-pathfinder-core/task-4-report.md`; the citations are also carried on
each constant in `MovementCosts`.

| Constant | Derived from | Value | Citations (1.7.10 / 1.21.11) |
|---|---|---|---|
| Horizontal ticks per block | movement speed attributes, `EntityLivingBase` and its modern equivalent | **3.5636** (sprint; the walk figure derives to 4.6327) | `EntityLivingBase.java:1618,1621,1626,1704,2021`, `Entity.java:1195,1198,1200`, `Block.java:453`, `PlayerCapabilities.java:20`, `EntityLivingBase.java:57`, `ModifiableAttributeInstance.java:188` / `LivingEntity.java:159-160,2338-2339,2357,2527-2529,2571-2572,3007-3009`, `Entity.java:1636`, `BlockBehaviour.java:983`, `Player.java:214,465`, `AttributeInstance.java:160-161` |
| Ascend surcharge | jump velocity and gravity | **+2.9946** on top of a traverse (6.5582 total) | `EntityLivingBase.java:1557,1700,1703` / `Attributes.java:45-50`, `LivingEntity.java:169,2253-2254,2266,2346,2356-2357`, `BlockBehaviour.java:985` |
| Fall ticks per block | gravity | **2.8229** (mean over the deepest damage-free drop) | `EntityLivingBase.java:1700,1703` / `Attributes.java:45-47`, `LivingEntity.java:169,2346,2356-2357` |
| Maximum safe fall distance | the fall-damage threshold | **3** blocks | `EntityLivingBase.java:1125` / `Attributes.java:73-75`, `LivingEntity.java:1747,1750-1751` |
| Diagonal factor | geometric √2 | **5.0397** = 3.5636 × √2 | declared, not derived |
| Turn penalty | — | **omitted** | no figure exists in either tree; omitted rather than invented |

Two things the derivation task **recorded rather than assumed**:

1. **Walk figure or sprint figure, per movement type — the sprint figure, for every movement.**
   M5's executor will sprint wherever it can, so costing every movement at the walk rate would
   systematically misrank long straight runs. The binding reason is admissibility, though: §5.3's
   heuristic multiplies `cheapestMove()` by a move count, so that figure must never exceed a real
   move's cost, and sprinting is the fastest a vanilla player moves on flat ground. Traverse uses
   it directly; Diagonal inherits it through the √2 factor; Ascend uses it for the horizontal
   block and adds the jump surcharge, because both versions add a forward impulse when a
   sprinting entity jumps and airborne motion decays at 0.91 rather than 0.546, so sprint speed
   survives the hop; Descend uses it for the step off the ledge. The walk figure is derived and
   recorded, but not used.
2. **Whether the two versions' figures differ — they barely do, and the slower is taken.** Every
   raw input is identical across the fifteen-year gap: walk speed 0.1, the +0.3 multiplicative
   sprint modifier, block friction 0.6, the 0.91 air factor, the 0.98 input damping, jump velocity
   0.42, gravity 0.08, vertical drag 0.98, safe fall distance 3.0. The single difference is
   bookkeeping — the horizontal-acceleration normaliser is spelled `0.16277136F` with the 0.91
   folded in on 1.7.10 and `0.21600002F` with it factored out on 1.21.11 — which separates the two
   by 2e-7 ticks per block. 1.21.11 is the marginally slower and the rounded constants take it. A
   shared pure core cannot branch on version, and no per-version cost seam was introduced.

Two approximations are recorded on the constants rather than hidden. The ascend surcharge is
**added** to the horizontal crossing although the rise and the crossing really overlap, which
makes `ASCEND` an upper bound; the design requires a climb to cost more than level ground and only
M5 can measure how much more. And a fall accelerates, so no single per-block constant is exact —
`FALL_PER_BLOCK` is the mean over the deepest damage-free drop, exact at that depth and an
approximation above it.

Nothing in C1 can validate that these numbers are *realistic* — only that they are admissible and
consistent. M5 is the first thing that executes a path and therefore the first thing that can
measure. §9 records this.

---

## 7. Fixtures, the renderer, and testing

### 7.1 Fixture format — D7

Y-slices, lowest first. Within a slice, columns run +X and rows run +Z. A header declares the
origin of the lowest slice's first cell.

```
origin: 0,64,0
--- y=64
#####
#####
--- y=65
S...G
..#..
```

A fixed built-in legend, so fixtures read uniformly across the suite:

| Char | Block | Char | Block |
|---|---|---|---|
| `.` | air | `f` | fence |
| `#` | full solid | `?` | unknown |
| `_` | bottom slab | `~` | water |
| `^` | top slab | `!` | avoid (lava) |
| `>` | stair | `S` | start, over air |
| `c` | carpet (`THIN_LAYER`) | `G` | goal, over air |
| `p` | partial, top `0.9375` | | |

A programmatic hook registers additional characters for a test needing an exotic `BlockData`.

**The fixture implements `BlockSource` directly.** This is the payoff B1 set up and the roadmap
promised: headless pathfinding tests need neither an `IBlockView`, nor the classifier, nor a
table. They construct `BlockData` values and hand them over. Reads outside the declared extent
return `UNKNOWN`, matching both B2's D4 and the behaviour of the real world.

### 7.2 The renderer — D5

The same layout, with overlay characters replacing the terrain character in a cell: `*` for a path
position, `+` for an expanded node, plus `S` and `G`. Output is a `String`, emitted into
assertion-failure messages and available on demand.

ASCII only, and the reason is specific to this project rather than general: subagents and
reviewers read test output as text and cannot open a PNG. An image written to the build directory
would be invisible to everyone in the loop that actually debugs this code.

**Overlay characters parse back as air**, so a rendered failure pastes straight in as a regression
fixture — terrain survives the round-trip and the annotations degrade harmlessly. That property is
what makes the renderer worth building at C1 rather than waiting for the web UI at M7.

### 7.3 Testing

TDD throughout. The module is pure and headless, so the standing adapter test exemption does not
apply anywhere in C1.

- **Predicates** — every `BlockShape` value against `passable`, `supports` and `standable`, crossed
  with fluid and tags. Including the §4.3 table: carpet passable on both versions' values,
  farmland supportive on both.
- **Movements** — each movement's expansion and each of its preconditions.
- **A\*** — shortest path found, obstacles routed around, `NO_PATH` when walled off,
  `BUDGET_EXCEEDED` at the cap, and identical results across repeated runs.
- **Heuristic** — `h` never exceeds the actual cost of the path found, across the fixture suite.
- **Parser and renderer** — round-trip: render a result, re-parse it, get the same terrain.

**Mutation proof is required, not optional,** for every test whose subject is *"X does not
happen"*:

| Test | The mistake it must catch |
|---|---|
| Never routes through `UNKNOWN` | reading `collisionTop` before checking `shape` |
| Never enters `AVOID` | dropping the tag check |
| Never enters water | assuming fluid and solid collision are exclusive |
| Never exceeds max safe fall | an off-by-one in the descend limit |
| Rejects corner-cutting | dropping one of the two orthogonal checks |
| `SLAB_BOTTOM` is an obstacle | widening either threshold |
| `FENCE` is not a floor | dropping *either* the shape exclusion or the `1.0` bound from `supports` — the test must fail for each removed independently, or it only proves the redundancy |

B1 found five tests that read as correct and guarded nothing; every one was invisible on
inspection and visible only by breaking the code and watching the test fail. Two were found by
implementers who were told to report rather than adjust. Every dispatch here requires **the actual
failing output in the report**, not a claim that mutation was performed — and the committed state
of any file a mutation touched must be verified, because B1 had one left broken on disk.

---

## 8. Done criteria

1. `./gradlew build --rerun-tasks` green, including all machine-checked invariants, with the Java 8
   bytecode check extended to `:core-pathfinder`. **Never `./gradlew clean`** — it destroys the
   1.7.10 decompiled sources at `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`,
   which are the evidence base for every API claim in this project.
2. The headless suite passes, and every test in §7.3's mutation table has had its non-vacuity
   demonstrated with the failing output recorded.
3. `BlockSource` is implemented by `BlockLookup` and by the fixture worlds, and the pathfinder
   reads through the interface rather than through any concrete type.
4. **No new SPI types, no new `IGameEvents` methods, no adapter changes** — verifiable by
   inspecting the diff, and a stated success condition rather than a hoped-for outcome.
5. The cost constants in §6 carry `file:line` citations from both decompiled trees, and the two
   recorded decisions (walk-or-sprint per movement, version divergence) are written down.
6. **No smoke checklist and no in-game verification.** C1 adds nothing to `dev.continuo.platform`
   and changes neither adapter. This is stated because every sub-project since A1 has had an
   in-game obligation and a reader will reasonably expect one here.

---

## 9. Risks and what carries forward

| Risk | Severity | Status |
|---|---|---|
| Cost constants are unvalidated until a bot executes a path | Medium | C1 can prove admissibility and consistency, never realism. **M5 is the first thing that can measure.** Accepted deliberately |
| The `0.25` and `0.9` thresholds are judgement calls | Low–Medium | Both are tested at their boundaries; the accepted foot-height error is ≤0.25 and Minecraft's auto-step absorbs it. The `1.0` upper bound is not a judgement call — it is B1's classifier rule 2 restated |
| `PARTIAL` blocks get a measured verdict rather than a refusal | Low | This is the intent of D9 and the reason modded worlds work at all, but it means an unrecognised block is trusted. §7.3 tests the claim on farmland's two values |
| A\* determinism regresses silently | Low | Specified in §5.1 and asserted by exact-path tests rather than by re-run comparison |

**Carried forward, so that nothing is rediscovered:**

- **B2 §4's pre-warm-before-seal obligation on M5** survives C1 completely untouched and **must
  land in C3's spec**. C1 neither resolves it nor is entitled to.
- **`SLAB_BOTTOM` and sub-block surface heights** — unwalkable in C1 by §4.3. Revisited when node
  state arrives at sub-project I.
- **Slipperiness and fluid height** — B1's two open block-model gaps, batched into one later
  adapter-touching change so the two untestable modules are edited once. Both versions answer
  slipperiness natively (`Block.slipperiness` / `Properties.friction`).
- **`powder_snow`, `sweet_berry_bush`, `bubble_column`, `lily_pad`** were never audited against
  source in B1. Measured predicates give them plausible answers, but lily pad and powder snow are
  genuine behavioural traps. Noted, not fixed; each is a one-line audit row when it matters.
- **The client-shutdown soft spot** (A2b spec §6.1) and **M5's edge- versus level-triggered
  actuation** remain open and are not C1's, recorded so nobody infers C1 resolved them.
