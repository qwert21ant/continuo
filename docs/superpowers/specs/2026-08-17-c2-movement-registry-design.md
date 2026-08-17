# C2 — Movement registry design

**Date:** 2026-08-17
**Status:** 🟡 Awaiting owner review — brainstormed with the owner on 2026-08-17
**Milestone:** M4 (C), second of four sub-projects
**Depends on:** C1 (`2026-08-15-c1-pathfinder-core-design.md`) — hard dependency, the whole pathfinder
**Design input:** `mc-automation-architecture.md` §3, which sketches `IMovementType` / `IMovementRegistry`
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M4

---

## 1. Scope

C1 built four movements behind a package-private `Move` interface and an `AStarPathfinder` that
iterates a fixed `MOVES` array. C1 spec D6 held the public signature back on purpose, so that a
real registry could shape it rather than have it frozen by the first four movements. C2 is that
registry.

The deliverable is not the registry on its own. **A registry with no new movement proves nothing**,
so C2 also adds a parkour jump — in its own Gradle module, discovered by `ServiceLoader`, unable to
compile against the pathfinder's internals. That module is the evidence.

### 1.1 In scope

`IMovementType`, `ExpansionContext`, `Capability`, `CapabilitySet`, `IMovementRegistry`,
`ActiveMovements` and a `MovementRegistry` implementation with `ServiceLoader` discovery; a new
`:core-movement` module to publish them from; the relocation of `MovementCosts` and `Standability`
into it; capability filtering; the heuristic multiplier derived over the active set; an executable
form of the movement cost contract; and a `:movement-parkour` module proving the seam.

### 1.2 Explicitly not in C2

| Deferred to | What |
|---|---|
| M5 | `IMovementExecutor` and `IMovementType.executor()` — see §4.4 |
| The first movement that needs one | Any real source of player or world capabilities — see §4.3 and §11 |
| C3 | `WorldSnapshot`, sections, the fill protocol |
| C4 | Segmentation, search effort, and any performance consequence of a looser heuristic |
| Sub-project I | `Node<S>`, `StateDimension`, and the node state `ExpansionContext` is shaped to carry |
| A later movement | Ladders (`BlockTag.CLIMBABLE` is in `BlockData` and still unused) and swimming |

C2 adds **no SPI type and touches neither adapter**, the same promise C1 made as its D3.

---

## 2. Decisions

Every row was decided with the owner during the 2026-08-17 brainstorm.

| # | Decision | Rejected alternative |
|---|---|---|
| D1 | Capabilities are core-side only: a `CapabilitySet` the caller supplies. No SPI change | Adding `Capability` and `IPlatformInfo.worldCapabilities()` to `:platform` plus an `IPlayerState`, as the architecture doc sketches |
| D2 | The seam is proven by a separate Gradle module discovered by `ServiceLoader` | A test-scoped `META-INF/services` file inside `:core-pathfinder`; or explicit registration only |
| D3 | A new `:core-movement` module publishes the API, and `MovementCosts` + `Standability` move into it | Publishing the API from `:core-pathfinder` and letting the parkour module depend on the whole pathfinder |
| D4 | The registry **derives** the heuristic multiplier as `min(minCostPerAxisStep)` over the active set | Validating a declared constant and rejecting movements that violate it; or deriving plus a floor |
| D5 | `IMovementType` declares one number, `minCostPerAxisStep()` | Declaring `axisSpan()` and `minCost()` separately |
| D6 | The four built-in movements stay in `:core-pathfinder` | Moving them into `:core-movement` for cohesion |
| D7 | Iteration order is registration order; `ServiceLoader` discovery sorts by `id()` before registering | A total order by `id()` over all movements, however registered |
| D8 | `ExpansionContext` replaces C1's flat `(world, x, y, z)` parameters | Keeping the flat signature |
| D9 | No `executor()` on `IMovementType` | Shipping it now, as the architecture doc sketches it |
| D10 | Parkour's cost is a declared upper bound, not derived from the decompiled sources | A second two-version physics derivation |
| D11 | The cost contract is an executable checker class in `:core-movement` | A `:core-movement-testkit` module mirroring `platform-testkit`; or inline assertions in parkour's own tests |

**D1's reasoning, which is C1's D3 reasoning reused.** The roadmap promises M4 is *"pure, headless,
no Minecraft anywhere"*. The architecture doc's §3 wants the active set to be
`IPlayerState.capabilities() ∩ IPlatformInfo.worldCapabilities()`, and neither exists —
`IPlayerState` is not in the codebase at all and `IPlatformInfo` has only `gameVersion()` and
`loader()`, whose javadoc says outright that it is *not* for feature detection. Adding them would
break M4's promise and edit two adapter modules that have no tests and cannot get any.

It would also buy nothing yet, and this is the load-bearing half of the argument. The architecture
doc names **three** inputs to the active set, not two — the third is
`Настройки (allowParkour…)`, settings, which are core-side. Parkour is not version-dependent:
every Minecraft version can jump a one-block gap. So a platform-sourced capability introduced now
would ship with no discriminating consumer, which is exactly the speculative abstraction C1's D6
declined. The first movement that genuinely needs one — elytra is the doc's own example — is when
the SPI addition earns its cost, and it should be batched with the slipperiness and fluid-height
work C1 already deferred, so the untestable modules are edited once.

**D6's reasoning.** Moving the four built-ins would be tidier, but they become consumers of the
published API either way, from their existing package. Leaving them alone avoids relocating the
text-art fixtures and every movement test with them, for no gain in what C2 proves.

---

## 3. Module layout

| Module | Change | Contents |
|---|---|---|
| `:platform` | **none** | — |
| `:core` | **none** | — |
| **`:core-movement`** | new | The published API, the registry, `MovementCosts`, `Standability`, `MovementContract` |
| `:core-pathfinder` | modified | A\*, goals, `Pos`, path types, the four built-in movements, fixtures, renderer |
| **`:movement-parkour`** | new | `ParkourMove` and its `META-INF/services` entry |

Both new modules apply `id("continuo-pure-module")`: Java 8 bytecode, `-Xdoclint:all,-missing
-Xwerror`, same as their neighbours. Both must be added to `allowedProjectDependencies` in the root
`build.gradle.kts`, or `checkDependencyDirection` fails the whole build — C1's plan missed this and
it cost a build.

```kotlin
":core-movement"    to setOf(":core"),
":core-pathfinder"  to setOf(":core", ":core-movement"),
":movement-parkour" to setOf(":core", ":core-movement")
```

**The absence of `:core-pathfinder` from that last line is the seam.** A movement that cannot
compile against the pathfinder cannot reach its package-private internals, and the dependency-
direction check fails the build if anyone ever adds it. The seam is machine-checked, not asserted —
which matters, because a plugin boundary maintained only by convention is one careless import away
from not existing.

`:movement-parkour` may take a **test-scoped** dependency on `:core-pathfinder` so that its tests
can run a real A\* search over a fixture world. Test-scoped project dependencies are deliberately
excluded from the allowlist rule (see the comment in `build.gradle.kts`), and the production
direction is what the seam claims.

---

## 4. The published API

### 4.1 `IMovementType`

```java
public interface IMovementType {
    String id();
    Set<Capability> requires();
    double minCostPerAxisStep();
    void expand(ExpansionContext ctx, MoveSink sink);
}
```

`id()` is a stable dotted string — `"walk.traverse"`, `"walk.parkour"`, the doc's
`"mod.jetpack.fly"`. It is not decoration: it is the deduplication key the registry rejects
collisions on, and the sort key discovery uses under §5.2.

`MoveSink` becomes public unchanged from C1. `Move.CARDINALS` moves onto `IMovementType` as a
constant, because every cardinal movement needs it and a plugin author has no other way to match
the built-ins' expansion order.

Implementations must offer neighbours in a fixed order. C1's determinism guarantee rests on this,
and it is now a published obligation rather than an internal comment.

### 4.2 `minCostPerAxisStep()` — one number, not two

The heuristic is `multiplier × Chebyshev distance`, so a movement must cost at least
`axisSpan × multiplier`, where `axisSpan` is the largest number of steps it takes along any single
axis (C1 spec §5.3). The obvious API is to declare `axisSpan()` and `minCost()` and let the
registry divide.

**That API has a wrong answer that looks right.** Descend's honest declaration is
`(12.0323, 3)` — its worst ratio, at `k = 3`. But `(8.1783, 1)` describes a one-block descend
perfectly well, is the movement's genuine cheapest cost, and would push the multiplier to `8.1783`,
making A\* inadmissible with a green suite. The two-number form has a silently fatal pairing; the
quotient is the only thing ever used; so the API declares the quotient.

The contract on it: **the smallest `cost / chebyshevSpan(from, to)` of any neighbour this movement
can ever offer.** For a movement that moves one block per axis this is simply its cheapest cost.
§8 makes the declaration checkable rather than trusted.

| Movement | Cheapest cost | Span | `minCostPerAxisStep()` |
|---|---|---|---|
| Traverse | 3.5636 | 1 | **3.5636** |
| Ascend | 6.5582 | 1 | 6.5582 |
| Diagonal | 5.0397 | 1 | 5.0397 |
| Descend | 12.0323 at `k = 3` | 3 | 4.0108 |
| Parkour | 10.1218 | 2 | 5.0609 |

Traverse is the minimum, so the multiplier is `3.5636` — identical to C1's `cheapestMove()`. **C2
changes no search result at all on the built-in set.**

### 4.3 `Capability` and `CapabilitySet`

```java
public enum Capability { PARKOUR }
```

One value, because one has a consumer. `Capability`'s javadoc records the three sources the
architecture doc names — platform, player, settings — and states that C2 supplies only the third,
so that nobody later reads the enum as evidence that platform negotiation exists.

`CapabilitySet` is an immutable value type that copies its input on construction, the same
treatment `BlockData` gives its tags and for the same reason: a public API that aliases a caller's
mutable set lets the caller mutate a registry's filtering decision after the fact.

**Who supplies it.** The caller of `AStarPathfinder.findPath`. Today that is only tests — verified:
nothing in `:core`, `:runtime` or either adapter references `AStarPathfinder`, so the pathfinder has
no production consumer yet. M5 is the first caller that will have to decide a real policy, and it
is also the first thing that could plausibly want platform-sourced capabilities. Deferring is not
a gap; it is declining to invent a source for a value nothing production reads.

### 4.4 `ExpansionContext`, and why there is no `executor()`

```java
public interface ExpansionContext {
    BlockSource world();
    int x();
    int y();
    int z();
}
```

C1's `expand(world, x, y, z, sink)` would force every published movement to change signature when
sub-project I adds node state to this exact call. A context absorbs that additively.

A\* passes **one reused instance per search**, mutated between node expansions, documented as MUST
NOT be retained past the `expand` call. That is one allocation per search against C1's one sink
allocation per expansion, so this is a small reduction in allocation rather than a cost. It is the
one mutable object in the API, and the restriction is stated on the interface because a plugin that
stashes it would see silently wrong coordinates.

**No `executor()`.** The architecture doc puts `IMovementExecutor executor()` on `IMovementType`.
Nothing in the codebase can execute a path until M5, so the signature would be invented rather than
derived — the same reason C1 omitted a turn penalty instead of inventing a figure. Java 8 permits
default methods, so M5 can add `executor()` with a default without breaking any published movement.
That is what makes omitting it safe rather than merely cheaper, and it is why no default method is
used anywhere in C2: the mechanism is being held in reserve, not adopted as a style.

---

## 5. The registry

### 5.1 `IMovementRegistry` and `ActiveMovements`

```java
public interface IMovementRegistry {
    void register(IMovementType type);
    ActiveMovements activeFor(CapabilitySet caps);
}

public final class ActiveMovements {
    List<IMovementType> movements();
    double cheapestAxisStep();
}
```

`register` throws on a duplicate `id()`. Two movements answering to one id would make the
deduplication silent and the sort order in §5.2 unstable.

`activeFor` returns the filtered set **and** its multiplier in one object, deliberately. The failure
mode this whole section exists to prevent is a movement set and a multiplier drifting apart, and a
type that cannot hand you one without the other makes the drift unrepresentable. `ActiveMovements`
is immutable and computes `cheapestAxisStep()` once at construction.

A movement is active when `caps` contains every capability in its `requires()`. An empty
`requires()` is always active, which is what keeps the four built-ins on for every caller.

### 5.2 Ordering and determinism

**`ServiceLoader`'s iteration order is unspecified.** It follows classpath order, which varies by
environment. C1's A\* is deterministic only because movements expand in a fixed order — the
comparator's third leg is discovery sequence, so movement order decides which of two equal-cost
paths comes back. Feeding an unspecified order into that would make paths environment-dependent,
and the suite would still be green everywhere it happened to be run.

The rule: **iteration order is registration order, and discovery sorts by `id()` before
registering.** One rule, two properties. Classpath order can never leak into a search, and the
built-ins register in the order C1's `MOVES` array listed them, so C1's iteration order is
bit-identical.

A total order by `id()` over all movements was rejected as D7. It would have perturbed the
discovery sequence feeding `QueuedNodeOrder`'s third leg, putting
`AStarPathfinderTest.theMovementIterationOrderIsPinnedSoAReorderingCannotPassUnnoticed` at risk —
a test whose own comment records that an earlier fixture was rejected for having no genuine tie to
break. Perturbing a hard-won guard to buy nothing is a bad trade.

### 5.3 The multiplier is derived, so admissibility is structural

C1 could only assert admissibility as *"a checked numeric property, not a structural one"*, because
`cheapestMove()` was a constant over a closed set. Deriving the multiplier from the active set
changes its status:

```
cheapestAxisStep = min over active movements of minCostPerAxisStep()
```

Every movement then satisfies `cost(m) ≥ axisSpan(m) × cheapestAxisStep` by the definition of a
minimum. **C1's landmine stops being a landmine.** Raising `MAX_SAFE_FALL` to 4 with a correctly
derived `fallTicks(4)` — the change C1 spec §5.3 warns silently breaks A\* — now drops the
multiplier from `3.5636` to `13.4753 / 4 = 3.3688` automatically. The heuristic gets slightly
looser and stays admissible, instead of getting wrong.

`MovementCosts.cheapestMove()` is **deleted**, not deprecated. A static lower bound over a set that
is no longer static is the trap itself; keeping it as a second source of truth is worse than the
breaking change. Its javadoc's "carrying this forward" note to C2 is discharged here.

The cost is real and accepted: a cheap wide movement loosens the heuristic for *every* search, so
the search expands more nodes. There is no correctness loss, and search effort is C4's subject.
D4's rejected alternative — reject such a movement at registration — was declined because it makes
the plugin seam refuse a legitimate movement rather than accommodate it.

### 5.4 `ServiceLoader` discovery

`MovementRegistry.discover()` loads `IMovementType` implementations from the classpath, sorts them
by `id()`, and registers each. Implementations need a public no-argument constructor, which the
interface's javadoc states because `ServiceLoader` requires it and the failure is otherwise a
runtime surprise in a consumer's build.

Discovery is additive to explicit registration, not a replacement. `AStarPathfinder` keeps a
no-argument constructor whose registry registers the four built-ins explicitly, in C1's `MOVES`
order, and then discovers — so a plugin on the classpath is appended in a deterministic position
after them, and a caller who wants a different set passes their own registry to the other
constructor. Discovery is never implicit in the registry itself: `new MovementRegistry()` is empty,
and `discover()` is a call someone makes.

---

## 6. What changes in existing code

### 6.1 Relocations

`MovementCosts` and `Standability` move from `dev.continuo.pathfinder` to `:core-movement`, with
`MovementCostsTest` and `StandabilityTest`. Parkour needs both, and a plugin that cannot cost
itself against the same table as the built-ins cannot be ranked against them.

`StandabilityTest` keys its map-backed `BlockSource` on `Pos.pack`, and `Pos` stays in
`:core-pathfinder`. The test gets a local packing helper rather than dragging `Pos` across; moving
`Pos` is a larger change with no consumer asking for it.

`Move` becomes `IMovementType`; `MoveSink` becomes public; `Move.CARDINALS` moves onto
`IMovementType`. The four built-ins stay in `dev.continuo.pathfinder` as package-private classes
implementing the public interface, and gain `id()`, `requires()` (empty) and
`minCostPerAxisStep()`.

### 6.2 Breaking changes

| Type | Change | Why |
|---|---|---|
| `Goal.heuristic` | gains a `double cheapestAxisStep` parameter | The multiplier is now per-search. Goals stay stateless and reusable across searches with different active sets |
| `AStarPathfinder` | constructor takes an `IMovementRegistry`; `findPath` takes a `CapabilitySet` | The fixed `MOVES` array is the seam being replaced |
| `MovementCosts.cheapestMove()` | deleted | §5.3 |

Both types are public and C1 shipped them. C2 is entitled to break them: no production code
consumes the pathfinder, so the blast radius is C1's own tests.

### 6.3 Why C1's results are provably unchanged

Three independent reasons, and the plan must verify each rather than assume it:

1. The multiplier over the built-in set is `3.5636`, identical to C1's `cheapestMove()` (§4.2).
2. Registration order reproduces C1's `MOVES` order exactly (§5.2).
3. Parkour declares `requires() == {PARKOUR}`, so it is inactive for any caller that does not grant
   it, and C1's tests do not.

Reason 3 is also the capability gate's first real witness: flipping `PARKOUR` on and off changes
which paths come back, which is a test rather than a hypothetical.

**If any C1 test does change, that is a discrepancy to report, not to fix by editing the test.**
Ten of C1's defects were found exactly this way.

---

## 7. Parkour

### 7.1 The movement

A one-block gap, cardinal only: from `(x, y, z)` to `(x + 2dx, y, z + 2dz)`. Preconditions beyond
`standable(destination)`:

| Condition | Why |
|---|---|
| the gap column is passable at feet and head | the player flies through it |
| the gap column is **not** standable | if it were, `TraverseMove` already offers two edges over it, and parkour would add a duplicate edge with a worse cost |
| `passable(x, y + 2, z)` at the origin | headroom for the jump, the same precondition `AscendMove` documents |

Same height only, and one block only. A sprint jump clears more, but every further block is a claim
about momentum that nothing in the codebase can check and M5 has not yet measured. `axisSpan` is 2,
which is the point: it is the first movement to exercise §5.3's condition non-trivially, and the
reason parkour was chosen over ladders as the proof of the seam.

### 7.2 Its cost, declared as an upper bound

```
PARKOUR = 2 × TRAVERSE + 2.9946 = 10.1218
```

Two horizontal blocks at the sprint figure, plus the jump surcharge `ASCEND` already derives.
**Declared, not derived** — and this follows the reasoning `ASCEND` records for adding its surcharge
rather than overlapping it: the rise and the crossing genuinely happen at the same time, so the sum
is an upper bound, taken deliberately because only M5 can measure the truth. Commissioning a second
two-version physics derivation for a movement nothing executes would spend C1's most expensive task
again for no consumer.

The bound is honest in the direction that matters. An over-costed movement makes the search prefer
walking around when it should jump — a quality loss. An under-costed one breaks admissibility. This
errs toward the recoverable side, and `10.1218` still beats routing around a gap, which costs at
least three moves.

---

## 8. The movement cost contract, executable

`minCostPerAxisStep()` is a declaration, and a wrong one is silently fatal. So it is checked:

```java
public final class MovementContract {
    public static List<String> violations(IMovementType type);
}
```

It expands the movement over seeded synthetic worlds and asserts, for every neighbour offered:

```
cost / chebyshevSpan(from, to) ≥ minCostPerAxisStep()
```

Returning violations rather than asserting them keeps JUnit out of a production module while
letting any module's tests call it — `:core-pathfinder` runs it over the four built-ins,
`:movement-parkour` over its own.

D11 rejected a `:core-movement-testkit` module mirroring `platform-testkit`. The precedent is real
and the module would be the more conventional answer, but one class with no new Gradle module, no
JUnit on a production compile classpath and no third module to schedule reviewers around does the
same job here. `platform-testkit` earned its module by also carrying six fakes and an abstract
conformance test that adapters extend; this carries one function.

Worlds are generated programmatically, seeded, in the shape C1's Dijkstra oracle already
established over 400 worlds. This is why no text-art fixture has to move out of
`:core-pathfinder`.

---

## 9. Testing

TDD throughout. Both new modules are pure and headless, so the standing adapter test exemption
applies nowhere in C2.

- **Registry** — duplicate `id()` rejected; `requires()` filtering, including the empty set;
  registration order preserved; `activeFor` returning an immutable set; `cheapestAxisStep()`
  computed as the minimum.
- **Discovery** — a `META-INF/services` entry is found; discovered movements are sorted by `id()`
  regardless of the order the loader yields them; discovery appends after explicit registration.
- **Parkour** — the expansion, and each of the three preconditions in §7.1 independently.
- **Contract** — `MovementContract` returns no violations for all five movements, and does return
  one for a deliberately mis-declared movement.
- **A\*** — C1's suite carried over against the new signatures, plus the Dijkstra oracle re-run
  with parkour active.
- **Relocated** — `MovementCostsTest` and `StandabilityTest` carried over unchanged except for the
  `Pos.pack` helper of §6.1.

**Mutation proof is required, not optional,** for every test whose subject is *"X does not happen"*.
The actual failing output must be pasted into the report, and the committed state of any mutated
file verified afterwards with `git diff --stat` — C1 had one left broken on disk.

| Test | The mistake it must catch |
|---|---|
| The multiplier accounts for axis span | computing `min(minCost)` and ignoring span. **The Dijkstra oracle must fail.** If it does not, the oracle is not sized to separate the two implementations — the exact failure C1 hit with its optimality test, which passed against the very bug it was written for |
| Parkour is off without its capability | making `activeFor` ignore `requires()` |
| Discovery order is deterministic | dropping the sort, fed a loader that yields reverse order |
| A duplicate `id` is rejected | dropping the check — two registrations must not both survive |
| Parkour does not duplicate a traverse | dropping the "gap is not standable" precondition |
| Parkour needs headroom | dropping the `y + 2` check |
| The contract catches a bad declaration | making `violations` return empty unconditionally |

C1's recurring defect shape was *a test that names a property it cannot witness* — several
instances, the last of them fixed only after the merge. The question to ask of every test above
whose subject is a negative:
**would this fail if the guard it names were removed?** Two of these are the known traps: the
multiplier test is worthless unless the oracle is sized to separate the implementations, and the
capability-gate test is worthless over a world where parkour's edges change nothing.

---

## 10. Done criteria

1. `./gradlew build --rerun-tasks` green, including `checkDependencyDirection` with both new
   modules listed. **Not `clean`** — that destroys the 1.7.10 decompiled sources at
   `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`, which are the evidence base for
   every cost citation in `MovementCosts`.
2. `:movement-parkour` has no production dependency on `:core-pathfinder`, enforced by the
   dependency-direction check rather than by review.
3. A\* finds a path that uses a parkour jump, over a fixture world with a one-block gap, with
   `PARKOUR` granted — and does not, without it.
4. Every mutation in §9's table proved, with output recorded.
5. The Dijkstra oracle passes with parkour active, and fails against a span-ignoring multiplier.
6. An empty diff against `platform/` and `adapters/`, which is D1's promise made checkable.
7. No in-game verification is owed, and criterion 6 is the evidence for that.

---

## 11. Risks and what carries forward

| Risk | Severity | Status |
|---|---|---|
| A cheap wide plugin movement loosens the heuristic and slows every search | Medium | Accepted as D4. Correctness is preserved by construction; search effort is C4's. The alternative was refusing the movement |
| Parkour's cost is declared, not derived | Medium | Accepted as D10, and it errs toward over-costing, whose failure mode is a quality loss rather than a wrong path. M5 measures |
| `ExpansionContext` is mutable and reused | Low–Medium | Stated as a MUST NOT on the interface. No test can catch a plugin that retains it; this is a documented contract, like SPI rule 1's "no implementation may block" |
| The published API is not yet stable for out-of-tree authors | Low | Stated in the package javadoc. M5 adds `executor()` as a default method, which is additive; sub-project I extends `ExpansionContext`, which is also additive |
| `Capability` has one value and looks like scaffolding | Low | It has exactly one consumer, which is the discipline C1's D6 set. Its javadoc records the two sources that do not exist yet, so it cannot be misread as evidence they do |

**Carried forward, so that nothing is rediscovered:**

- **The SPI capability addition** — `Capability` on `IPlatformInfo`, and an `IPlayerState` — is
  deferred to the first movement that needs a platform-sourced capability, and should be **batched
  with C1's deferred slipperiness and fluid-height work** so the two untestable adapter modules are
  edited once. This is C1's D3 reasoning applied a second time.
- **`IMovementExecutor`** is M5's, and lands as a default method on `IMovementType`.
- **B2 §4's pre-warm-before-seal obligation on M5** and **C3's unresolved `FILLING`/`SEALED`
  tension** survive C2 untouched and must land in C3's spec. C2 neither resolves them nor is
  entitled to.
- **`SLAB_BOTTOM` and sub-block surface heights** remain unwalkable, and node state remains
  sub-project I's. `ExpansionContext` exists partly so that arrival is additive.
- **Ladders and swimming** remain deferred. `BlockTag.CLIMBABLE` is in `BlockData` and still
  unused; `Fluid.WATER` is still an obstacle.
- **`powder_snow`, `sweet_berry_bush`, `bubble_column`, `lily_pad`** were never audited against
  source in B1, and C2 does not change that.
