# C4 — Segmentation design

**Date:** 2026-08-26
**Status:** 🟡 Drafted — brainstormed with the owner on 2026-08-26, awaiting review
**Milestone:** M4 (C), fourth and last of four sub-projects
**Depends on:** C1 (`2026-08-15-c1-pathfinder-core-design.md`) — the search, the budget seam, the
determinism requirement; C1a (`2026-08-25-c1a-heuristic-rates-design.md`) — `HeuristicRates`
**Design input:** C3 (`2026-08-25-c3-world-snapshot-design.md`) §2.3, §4.7, §9
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M4

---

## 1. What C4 is, and what it deliberately is not

C1 §1 named C4 as *"segmentation, incremental cost backoff and the search-effort budget"*, and
`AStarPathfinder`'s javadoc has carried the seam ever since:

> **A budget hit returns no partial path.** Returning the best node so far *is* incremental cost
> backoff, and that is C4's whole subject. C1 names the seam and stops there.

C4 fills that seam. It does **not** make a search span ticks.

### 1.1 Segmentation and backoff are one mechanism

The brainstorm's first finding. A route to a distant goal does not need a waypoint decomposition
subsystem: if a budget-exhausted search returns the best node it reached toward the goal, **that
returned prefix is the segment**. Walk it, re-search from its end, repeat. "Reach far goals at all"
falls out of "never return nothing" for free, and no separate component exists to build.

This matters because the obvious alternative — picking intermediate goals along the straight line
to the target — is worse in exactly the way Minecraft terrain is unkind: an interpolated waypoint
lands inside a mountain or over a ravine as often as not, and a search toward an unreachable
waypoint is wasted whole.

### 1.2 Cross-tick search is deferred, and why

The owner's third driver was *"don't stutter the game"*. It rests on a premise nothing in this
project has measured. **`grep` over the entire repository finds no `nanoTime` and no
`currentTimeMillis`, in main source or test.** Every figure C1, C1a and C3 produced is a call count
or an expansion count; C3's merge record says so in as many words — *"the claim is about call
counts, which are exact, not milliseconds, which are not."*

A 4,030-expansion search might cost 3 ms, in which case cross-tick machinery buys nothing and costs
the whole of C3 §9's inheritance: snapshot lifetime, `seal()`'s retained `live` reference and the
level it pins, discard-on-level-transition. Or it might cost 80 ms, in which case it is the most
urgent thing in the project. **C4 produces that number and defers the decision to it.** This is the
same rule that put A\* before C3 — B2 §9's *"a snapshot with no consumer encodes a guess as a
design"* — applied one sub-project later.

C3 §9's inheritance therefore passes through C4 untouched, not solved and not started.

## 2. Decisions

Every row was decided with the owner during the 2026-08-26 brainstorm.

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | C4's scope | Segmentation + backoff + budget policy + wall-clock instrumentation. Cross-tick deferred | §1.2 — its premise is unmeasured and its consumer (M5) does not exist |
| D2 | Backoff rule | Minimise `h + g/C` among eligible nodes | Lowest `h` alone walks into dead-end pockets and rewards routes that wandered; `g` is the penalty that prices the wandering |
| D3 | Livelock guard | Eligibility on **heuristic progress**, not path length | §3.4 — progress admits a termination proof; length does not |
| D4 | Result contract | New `PathOutcome.PARTIAL` | Additive. No existing outcome changes meaning, and a caller handling only `FOUND` cannot mistake a segment for a complete path |
| D5 | Open-set exhaustion | No backoff — `NO_PATH` unchanged | YAGNI. In a real world the open set empties only when the bot is boxed in, where walking to the nearest corner is worthless. Keeps the one definitive "give up permanently" signal |
| D6 | Stopping condition | Stays counted in **nodes**. Milliseconds are reported, never consulted | C1 §5.1 makes determinism a hard requirement; a wall-clock stop makes every path assertion flaky |
| D7 | Calibration terrain | Both: committed real-terrain dumps set the constants, synthetic traps prove the rule | §6 — synthetic-only is circular, real-only may never contain the failure mode |
| D8 | Segmented driver | In scope, as a pure class in `:core-pathfinder` | §5 — without it nothing exercises segmentation and the calibration metric has no numerator |

## 3. The mechanism

### 3.1 One collaborator

```java
final class SegmentSelector {
    SegmentSelector(double startH, double coefficient, double minProgress);
    void consider(long packed, double g, double h);   // O(1), no allocation
    boolean hasCandidate();
    long candidate();
}
```

Package-private in `dev.continuo.pathfinder`. It holds three doubles and a `long`, touches no
world, and is therefore unit-testable as pure arithmetic with no fixture at all.

`candidate()` is defined only when `hasCandidate()` is true and throws `IllegalStateException`
otherwise. It does not return a sentinel: every `long` is a valid packed position, so there is no
value it could return that a caller could not mistake for terrain.

### 3.2 What A\* does differently

`findPath` computes `hStart` once from the start position, then inside the **existing** expansion
block — where `cx`/`cy`/`cz` and `current.g` are already in hand — adds one call:

```java
selector.consider(current.packed, current.g, goal.heuristic(cx, cy, cz, rates));
```

Nothing else about the search changes. Same movement iteration, same open set, same
`QueuedNodeOrder`, same closed-set handling.

**`h` is recomputed rather than taken as `entry.f − entry.g`.** The subtraction is exact, but only
by a three-step argument about stale heap entries: an improved `g` enqueues a *new* entry, the old
one has the same `h` and a larger `g`, so a larger `f`, so it cannot be polled first while the node
is open. That reasoning is correct and it is precisely the kind of subtle invariant this project's
mutation reviews exist to catch. The saving is a few `abs` and `max` calls on arithmetic that
performs no world read. Recompute.

### 3.3 Eligibility, then score

A node is **eligible** only if:

```
h ≤ hStart − minProgress
```

Among eligible nodes, the lowest `h + g/C` wins. The incumbent is replaced only on a **strictly**
lower score, so ties fall to the earliest expansion — and since expansion order is already
deterministic, so is the segment. C1 §5.1's hard determinism requirement survives untouched: an
identical search over an identical world still returns an identical path, segment or not.

**The start node is structurally ineligible**, because its own `h` equals `hStart` and
`minProgress` is positive. A zero-length segment cannot be returned by construction rather than by
a special case that a mutation could delete.

`minProgress` is specified in **blocks** and multiplied by `rates.horizontal()` at search time.
Ticks are the unit `h` is denominated in, but blocks are the unit a person reasons in, and routing
the conversion through `HeuristicRates` keeps the constant meaningful when a changed movement set
changes the cheapest rate.

### 3.4 Why a run terminates

`h` is admissible, so it lower-bounds the remaining cost to the goal. A segment ends at a node
whose `h` is at least `minProgress` below the `h` the search started from, and the next search
starts *at that node* — so `hStart` falls by at least `minProgress` every segment, and `h` is
bounded below by zero. **A run needs at most `hStart / minProgress` segments.**

This is a bound, not an expectation, and it holds for **any** value of `C`. `C` decides which good
candidate wins; it never decides whether progress happened. Miscalibrating `C` costs path quality
and cannot cause a livelock. A path-length guard — Baritone's rule, and this design's first draft —
gives no comparable bound, because a long segment can circle back to where it began.

### 3.5 A consequence, not a deferred risk

A goal that is unreachable but sits inside a *large* region never empties the open set, so D5's
`NO_PATH` never fires and the search returns `PARTIAL` indefinitely — a bot chasing something it can
never reach. The guard closes this with no extra machinery. `h` strictly decreases each segment, so
the run converges on a minimum-`h` position; past that, nothing clears `minProgress`, the result is
`BUDGET_EXCEEDED` with no path, and the run ends on its own.

## 4. The result contract

```java
/** A real path, to somewhere that is not the goal. */
PARTIAL
```

| Outcome | Path | Meaning | Changed by C4? |
|---|---|---|---|
| `FOUND` | to the goal | reached | no |
| `PARTIAL` | to the segment end | budget ran out, this is real progress toward the goal | **new** |
| `BUDGET_EXCEEDED` | empty | budget ran out and no node cleared `minProgress` | no — still "no path at all" |
| `NO_PATH` | empty | everything reachable was searched; the goal is not among it | no |

`PathResult.cost()` for a `PARTIAL` is the segment's own `g`, which is its true cost, not an
estimate of the whole route.

`BUDGET_EXCEEDED` keeps its exact present meaning, which is what makes the change additive: no
existing consumer's assumption is invalidated, and every existing test file should change **by
addition only**. An existing assertion that needs editing is a signal that something went wrong,
not a chore.

## 5. The segmented driver

A pure class in `:core-pathfinder`: search, take the segment, re-search from its end, repeat until
`FOUND`, `NO_PATH`, `BUDGET_EXCEEDED`, or a segment cap. It reports the segments, the total cost,
and the count.

It has two live consumers on the day it lands — the probe and §6's calibration sweep — so it is not
built on a guess about M5. It stays inside a single tick, so no part of C3 §9's lifetime
inheritance leaks back into C4.

The cap is `⌈hStart / minProgress⌉` — §3.4's own bound, evaluated once at the start of the run, with
no margin added. A correct implementation can never reach it. It is belt-and-braces over the proof,
which is only as good as `h`'s admissibility, and C1 §5.3 already established that admissibility
here is *a checked numeric property, not a structural one*. Reaching the cap therefore means the
proof's premise has failed, and the driver says so — it is a distinct reported condition, not a
quiet stop, because a silent cap would hide exactly the bug it exists to survive.

## 6. Calibration

**`C` gets an objective function, not a judgement call.** The rule degenerates predictably at both
ends: as `C → ∞` the `g` term vanishes and it collapses into min-`h`, the rule D2 rejected; as
`C → 0` cost dominates and it picks the cheapest node that barely clears the guard. Between them is
a value that makes a segmented run nearly as cheap as an unsegmented one, and that ratio is the
metric:

```
quality(C) = cost(segmented run to goal) / cost(single unbounded search)
```

Both terms are computable headlessly. The denominator is `findPath` at the existing 100,000-node
budget; the numerator is §5's driver at an artificially small one. Sweep `C` across every committed
route, take the value that minimises the ratio, and commit the table.

`minProgress` sweeps the same way against a different failure: too large and nothing qualifies, so
its metric is the share of budget-exhausted searches that return an empty `BUDGET_EXCEEDED` where a
useful segment existed.

**The default budget is a deliverable, but not of this sweep.**
`AStarPathfinder.DEFAULT_NODE_BUDGET` is 100,000 and its javadoc promises *"C4 replaces this with a
real search-effort policy."* Segmentation changes what the number means: today it is a failure
threshold, after C4 it is a latency knob — a smaller budget gives up sooner and segments instead,
which may produce a cheaper total route in less time.

**It cannot be calibrated on replayed terrain.** §7.1.1 measures replay under-expanding by 3.6–7.6×
against the same route in game, for a structural reason no capture technique removes. A budget
fitted to replayed expansion counts would be fitted to a fiction. The number that decides it is
milliseconds per expansion in a live client, which is precisely what §8's instrumentation produces
and §1.2 defers the cross-tick decision to. So the budget is set from in-game evidence, from the
run that discharges §10's criterion 6, and the javadoc promise is discharged there — not from the
headless sweep.

The seven in-game runs of 2026-08-26 bound it from below in the meantime: the hardest, a large
obstacle spanning 17 blocks, used 4,445 expansions, so 10,000 is not yet demonstrably too small for
short-range goals. What no run has yet produced is a real `BUDGET_EXCEEDED`.

## 7. Fixtures

### 7.1 Real terrain — supplied, replayed, and verified

**The gap this section opened is now closed.** When C4 was scoped, no real-terrain fixture existed
in the repository: every figure in C3's spec came from replaying a `continuo-path-probe.txt` that
was never checked in, four of which were lost in one evening to the hardcoded filename. The owner
supplied four fresh runs on 2026-08-26, renamed between presses. They are committed under
`core-pathfinder/src/test/resources/terrain/` **verbatim, unedited** — a hand-touched dump is no
longer evidence.

A dump needs no conversion: the probe emits exactly `FixtureWorld.parse`'s format, an `origin:`
line followed by `--- y=N` slices, and `//` lines are skipped by the parser. The `*` and `+`
overlays parse back as air, which is correct — they mark feet positions, which were air.

| Fixture | Terrain | In-game start → goal | Δx, Δy, Δz | In-game expanded | Replays exactly? |
|---|---|---|---|---|---|
| `a-big-obstacle` | large obstacle | (-73,84,-203) → (-62,88,-186) | 11, 4, 17 | 4,445 | **No** — see below |
| `b-cave-climb` | cave then a 39-block climb | (350,69,-770) → (344,108,-779) | 6, **39**, 9 | 3,474 | Yes |
| `c-short-hop` | short hop, 0 `?` cells | (282,70,-772) → (281,72,-773) | 1, 2, 1 | 94 | Yes |
| `d-cliff` | large cliff | (710,119,-1078) → (702,121,-1068) | 8, 2, 10 | 2,082 | Yes |

"Replays exactly" means outcome, step count and cost to sixteen digits, verified by running each
through `AStarPathfinder` at the same 10,000-node budget. `b`, `c` and `d` reproduce their captured
cost bit for bit. **`a` does not** — 94 steps at 514.4780665840374 against the captured 92 at
512.4329751331646. It carries 397 `?` cells and some sit near the optimal route, which is exactly
the documented failure mode. It is kept, because as a *world* it is perfectly valid realistic
terrain and §6's sweep compares segmented against unbounded cost **within a fixture**; it simply
may never be cited as reproducing an in-game measurement.

### 7.1.1 Expansion counts do not survive a replay

Measured, not suspected. C3 recorded the worry that a bounded replay fixture "may understate"; it
understates badly:

| Fixture | In-game expanded | Replayed expanded | Factor |
|---|---|---|---|
| `a-big-obstacle` | 4,445 | 1,247 | 3.6× |
| `b-cave-climb` | 3,474 | 726 | 4.8× |
| `d-cliff` | 2,082 | 273 | 7.6× |
| `c-short-hop` | 94 | 67 | 1.4× |

The cause is structural, not a bug: a dump is a **window**, and every position outside its extent
reads `UNKNOWN`, which is impassable. The replay world therefore has walls the real one does not,
and those walls prune the search. No amount of care in capturing dumps fixes this; a wider window
merely moves the walls.

**Two consequences, and they point opposite ways.** §6's calibration metric is a ratio of *costs*,
and costs round-trip exactly, so `C` and `minProgress` are safely calibrated on replayed terrain
with budget exhaustion forced by a deliberately small budget — a knob C4 controls. But **the default
node budget cannot be**, because expansion counts on a replayed fixture are not real numbers. §6
says so explicitly.

### 7.2 Synthetic traps

Three text-art worlds, each isolating one thing that real terrain may or may not happen to contain:

| Fixture | What it pins |
|---|---|
| **Dead-end alcove** | A deep pocket beside the goal that min-`h` walks into and `h + g/C` refuses |
| **Wandering route** | A long detour buying little `h` for much `g`, against a shorter honest approach |
| **Boxed start** | Nothing clears `minProgress`, pinning the empty-`BUDGET_EXCEEDED` branch that would otherwise never execute |

Per the process note that earned its keep in C3: **each fixture is executed before it is written
into the implementation plan.** C3's Task 4 fixture needed `y=65`/`y=66` air slices or every square
had zero head clearance and the search returned `NO_PATH` over open ground — a blocked round, caught
only because the fixture was run first.

## 8. The probe

`PathProbe` gains **wall-clock timing** — the whole point of D1's scoping. It reports milliseconds
alongside the existing counts, **additively**, so `PathProbeTest`'s integer parsing keeps working.
It is reported only and never consulted as a stopping condition (D6).

It also gains a key that drives §5's driver, so one press reports a segmented run end to end:

```
Continuo path probe: reached goal in 4 segments, 1240.3 ticks
  (optimal 1190.1, ratio 1.042), budget 10000, 6.8 ms
```

This is what gives C4 in-game evidence at all. C3 could only discharge its last done criterion with
a live run; C4 is built so that one keypress discharges the equivalent and adds a real-terrain
sample at the same time.

Unchanged: the summary line goes to the game log and the map to the `.txt`, so reading a run needs
`adapters/adapter-fabric-1.21.11/run/logs/latest.log` **as well as** the dump.

## 9. Testing

Four layers.

1. **`SegmentSelector`, pure arithmetic, no world.** Eligibility exactly at the `minProgress`
   boundary; strict-improvement tie-break; the no-candidate case; the structurally-ineligible start.
2. **The three synthetic traps**, pinning the rule's behaviour against min-`h`.
3. **The driver's convergence properties.** `h` falls monotonically across segments; the segment
   count stays within `⌈hStart / minProgress⌉`; the concatenated route is contiguous.
4. **Regression, and D5's contract.** Every existing test file changes by addition only (§4). Plus
   one new test that pins D5 directly: a world whose goal is walled off inside a small reachable
   region returns `NO_PATH` with an empty path, **not** `PARTIAL`, even though eligible nodes were
   expanded. Without it, §9.1's last mutation has nothing to fail against.

Determinism gets its own test at this level, as C1 §5.1 requires of anything that decides which path
comes back: the same search run twice returns a byte-identical segment and cost.

### 9.1 Mutations the final review must execute

Named here rather than left to the reviewer's imagination, because five consecutive sub-projects
have now had a final whole-branch review find, by *running* broken code, what reading the diff did
not. Each must map to a named failing test; any that fails to fail is a gap.

| Mutation | Expected to break |
|---|---|
| Delete the eligibility guard | The convergence and no-candidate tests |
| Relax the strict `<` in the score comparison to `<=` | Determinism |
| Transpose `h` and `g` in the score | The wandering-route trap |
| Return the most recent qualifying candidate, not the best | The dead-end-alcove trap |
| Return `PARTIAL` on open-set exhaustion | D5's `NO_PATH` contract test |

## 10. Done criteria

1. `./gradlew build --rerun-tasks` green — **never `:test`**; javadoc is build-failing under
   `-Xdoclint:all,-missing -Xwerror` and `:test` never runs it, so a green `:test` can hide a broken
   build.
2. `C` and `minProgress` each have a committed sweep table, produced by §6's metric over the §7.1
   fixtures. The default node budget is **not** among them and is set from criterion 6's in-game
   run instead, for the reason §7.1.1 measures.
3. ✅ **Done at spec time.** Four real-terrain probe dumps are committed under
   `core-pathfinder/src/test/resources/terrain/`, with per-fixture replay fidelity verified and
   recorded in §7.1.
4. Existing test files changed by addition only; `FOUND` and `NO_PATH` behaviour identical.
5. Every mutation in §9.1 executed, with its result recorded in the merge commit.
6. **One in-game run** in which the probe reaches, in *n* segments, a goal that a single search
   cannot reach at all — with milliseconds reported.

## 11. Carried forward, not solved here

- **Cross-tick search and all of C3 §9's inheritance**: snapshot lifetime, `seal()`'s retained
  `live` reference and the level it pins, discard-on-level-transition. Deferred to a future
  sub-project, which C4's millisecond figures will let the owner size honestly. These are three
  questions that are really one and should be solved once.
- **The climb-aware heuristic**, measured at 6% and parked in C3 §2.3, where `h` reaches only 45% of
  true cost on a climbing route because the horizontal leg prices every octile unit at the cheapest
  flat rate. It now has a sharper trigger than "reopen if the budget binds": **if the sweep shows
  vertical routes needing disproportionately many segments.** The caveat that keeps it live is
  unchanged, and §7.1.1 now **measures** the understatement it warned of: a replayed fixture expands
  3.6–7.6× fewer nodes than the same route in game, so a 6% improvement measured on one is a lower
  bound on the real figure, not an estimate of it. `b-cave-climb` is the fixture that will settle
  it — a 39-block climb costing 3,474 in-game expansions to travel 9 blocks horizontally is the
  vertical case C3 §2.3 describes, now available headlessly for the first time.
- **A primitive `long`→object map.** `Pos`'s javadoc parks it as *"a C4 concern"*. C4 answers with
  C3's rule — a measurement, not a suspicion — and C4 is the first sub-project able to take it:
  swap only if the new millisecond figures show map operations hot.
- **The probe's unbudgeted render**, 262,144 reads worst case. C3 §9 offers it here on the grounds
  that C4 owns search effort. Declined: it is a rendering policy, not a search-effort question.
- **`covers()` still has no production consumer.** M5 remains its first. Unchanged by C4.

## 12. Risks

| Risk | Assessment |
|---|---|
| `C` calibrated on too little terrain | Real, and the reason D7 takes both kinds of fixture. Mitigated further by §3.4: a bad `C` costs path quality, never termination |
| Admissibility violated, breaking §3.4's proof | C1 §5.3 establishes admissibility as a checked numeric property, not a structural one. It holds today; the margin shrinks as `k` grows and the `k = 4` row is already negative, so raising `MAX_SAFE_FALL` from 3 to 4 would break it — and that is a change a reader would take for a routine re-derivation. §5's cap turns the resulting hang into a reported failure |
| The in-game run never exhausts the budget | **Now the most likely risk, and sharper than when this section was written.** Four deliberately awkward runs on 2026-08-26 — large obstacle, cave-and-climb, cliff, short hop — all returned `FOUND`, peaking at 4,445 expansions, 44% of the budget. Eleven real routes across C3 and C4 have now produced zero real `BUDGET_EXCEEDED`. Criterion 6 is therefore discharged by **lowering the budget for the run**, which is legitimate — segmentation's behaviour does not depend on why the budget was reached — provided the merge record says plainly that the exhaustion was induced |
| Segmentation solves a problem that does not occur | Worth stating rather than hiding. The four runs show expansion count is driven by terrain difficulty and vertical distance, **not range**: `a-big-obstacle` spent 4,445 expansions to travel 17 blocks. That is the argument that a long-range goal over the same terrain would exceed 10,000 by a wide margin — but it is an extrapolation, and no measured route yet demonstrates it. The cheapest way to settle it is one probe run at a goal several hundred blocks out |
| Timing measured on the client thread is noisy | Accepted. The figure is needed to distinguish 3 ms from 80 ms, not to resolve 3 ms from 4 ms |
| Segmented routes materially worse than optimal | §6's ratio is exactly this quantity, measured rather than hoped. If it comes out badly, that is a finding about the rule and reopens D2 |
