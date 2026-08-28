# C4 — Segmentation design

**Date:** 2026-08-26
**Status:** 🟢 Approved — brainstormed with the owner on 2026-08-26; D2 reversed on measurement the same day
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

**What segmentation is *not*, per D9.** It does not make an arbitrarily distant goal reachable on a
small budget. §2.1 measures backoff reaching the goal only when the budget is a large fraction of
what the search needs, and failing below roughly 70% of it. The primary answer to a far goal is an
adequate budget; segmentation is what keeps the bot moving usefully when even that is exceeded.
This was not the framing C4 was scoped with, and the correction came from measurement.

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
| D2 | Backoff rule | **Lowest `h` among eligible nodes.** No cost term, no coefficient | **Reversed on measurement — see §2.1.** The original choice, `h + g/C`, fails to reach the goal on every fixture at every budget; only `C = ∞` arrives. Penalising `g` prefers nodes near the *start*, which is what produces timid segments, dead-end pockets and ping-pong |
| D3 | Livelock guard | Eligibility on **heuristic progress**, not path length or cost | §3.4 — progress admits a termination proof; neither alternative does. §2.1 measures the difference: with the cost guard the same scoring livelocks |
| D4 | Result contract | New `PathOutcome.PARTIAL` | Additive. No existing outcome changes meaning, and a caller handling only `FOUND` cannot mistake a segment for a complete path |
| D5 | Open-set exhaustion | No backoff — `NO_PATH` unchanged | YAGNI. In a real world the open set empties only when the bot is boxed in, where walking to the nearest corner is worthless. Keeps the one definitive "give up permanently" signal |
| D6 | Stopping condition | Stays counted in **nodes**. Milliseconds are reported, never consulted | C1 §5.1 makes determinism a hard requirement; a wall-clock stop makes every path assertion flaky |
| D7 | Calibration terrain | Both: committed real-terrain dumps set the constants, synthetic traps prove the rule | §6 — synthetic-only is circular, real-only may never contain the failure mode |
| D8 | Segmented driver | In scope, as a pure class in `:core-pathfinder` | §5 — without it nothing exercises segmentation and the calibration metric has no numerator |
| D9 | What segmentation is for | **Graceful degradation, not range.** The primary answer to a far goal is an adequate budget | §2.1 — backoff reaches the goal only when the budget is a large fraction of what the search needs. The 111-block route needs 17,423 expansions; a budget above that solves it outright |

D2 and D9 were settled on 2026-08-26 by measurement rather than discussion, and both reverse what
the brainstorm concluded. §2.1 is the evidence.

## 2.1 The measurement that reversed D2

Before this plan was written, the backoff rule was prototyped in a throwaway harness and swept
across the three replayable fixtures, four budgets expressed as fractions of what each fixture's
search actually needs, seven values of `C`, and three eligibility rules. The house rule that
fixtures are executed before being written into a plan is what produced this; the design as
originally specced does not work.

**`a-big-obstacle` is the discriminator** (optimal cost 514.48, needs 1,247 expansions):

| rule | budget 39% | 56% | 69% | 84% |
|---|---|---|---|---|
| `h + g/C`, any finite `C`, h-progress guard | fails, cost 22–42 | fails | fails | fails |
| `h + g/C`, any finite `C`, cost guard | livelock, cost 3,656–4,698 | livelock | livelock | livelock |
| **lowest `h`, h-progress guard** | fails safe | fails safe | fails safe | **FOUND, 2 segments, 547.39 (ratio 1.064)** |

Three findings, all of which changed the design:

1. **A cost term makes the rule worse, not safer.** §2's original D2 argued that lowest `h` alone
   would walk into dead-end pockets and reward wandering routes. The opposite happens. Penalising
   `g` biases selection toward nodes near the *start*, producing segments too timid to commit —
   and on `d-cliff` it is precisely the finite-`C` runs that walk into a pocket and return
   `NO_PATH`, at every budget up to 69%, while lowest `h` does not.
2. **The eligibility guard earns its place, and the cost-based alternative does not.** Lowest `h`
   with the h-progress guard never livelocks; when it cannot proceed it returns
   `BUDGET_EXCEEDED` with no path, which is a safe failure. Lowest `h` with a cost guard
   livelocked on `d-cliff` below 84%.
3. **Backoff is not a substitute for budget.** Every failure sits below roughly 70% of the
   expansions the search needs, and every success at or above it. This is what D9 records.

`d-cliff` at 84% is the design working as intended: `FOUND` in 2 segments at cost 274.42 against
an unsegmented optimal of 274.42 — a ratio of **1.000**.

**The prototype was deleted.** It proved a design claim, not a behaviour anyone ships, and keeping
it would leave a second copy of the search loop to drift.

## 3. The mechanism

### 3.1 One collaborator

```java
final class SegmentSelector {
    SegmentSelector(double startH, double minProgress);
    void consider(long packed, double h);   // O(1), no allocation
    boolean hasCandidate();
    long candidate();
}
```

**`g` is not a parameter.** It was, until §2.1 measured what it does.

Package-private in `dev.continuo.pathfinder`. It holds three doubles and a `long`, touches no
world, and is therefore unit-testable as pure arithmetic with no fixture at all.

`candidate()` is defined only when `hasCandidate()` is true and throws `IllegalStateException`
otherwise. It does not return a sentinel: every `long` is a valid packed position, so there is no
value it could return that a caller could not mistake for terrain.

### 3.2 What A\* does differently

`findPath` computes `hStart` once from the start position, then inside the **existing** expansion
block — where `cx`/`cy`/`cz` and `current.g` are already in hand — adds one call:

```java
selector.consider(current.packed, goal.heuristic(cx, cy, cz, rates));
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

Among eligible nodes, the lowest `h` wins. The incumbent is replaced only on a **strictly** lower
`h`, so ties fall to the earliest expansion — and since expansion order is already deterministic, so
is the segment. C1 §5.1's hard determinism requirement survives untouched: an identical search over
an identical world still returns an identical path, segment or not.

Note what the two rules together reduce to: **the node closest to the goal that the search reached,
provided it is meaningfully closer than where this segment began.** That is the whole rule. It was
offered during the brainstorm, argued against, and is now restored by measurement.

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

This is a bound, not an expectation. §2.1 measures what it is worth in practice: the guard is the
difference between failing safe and livelocking, and it is why D3 survived D2's reversal intact.

Both alternatives were measured and neither bounds anything. A **path-length** guard — Baritone's
rule, and this design's first draft — permits a long segment that circles back to where it began. A
**cost** guard permits the same, and §2.1 shows it doing exactly that: 200 segments and cost 3,656
against an optimal of 514.

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

**D2's reversal deleted half of this section.** With no coefficient there is nothing to trade off,
and `quality(C)` has no argument. One constant remains.

The metric is unchanged in form and still the right one:

```
quality = cost(segmented run to goal) / cost(single unbounded search)
```

Both terms are computable headlessly. The denominator is `findPath` at a fixed 200,000-node budget
— run headlessly the sweep needs its own generous ceiling for "unbounded," independent of whatever
`DEFAULT_NODE_BUDGET` is set to elsewhere, since §6.2 sets that default from evidence the sweep
does not need; the numerator is §5's driver at a deliberately small one. §2.1 already reports the
metric at three points — 1.000 on `d-cliff` at 84%, 1.064 on `a-big-obstacle` at 84%, 1.06 on
`b-cave-climb` — so the sweep in §6.1 confirms and tabulates a figure the design has already been
shown to achieve, rather than discovering it.

`minProgress` sweeps against a different failure: too large and nothing qualifies, so
its metric is the share of budget-exhausted searches that return an empty `BUDGET_EXCEEDED` where a
useful segment existed.

**The default budget is a deliverable, but not of this sweep.**
`AStarPathfinder.DEFAULT_NODE_BUDGET` is 100,000 and its javadoc promises *"C4 replaces this with a
real search-effort policy."* Segmentation changes what the number means: today it is a failure
threshold, after C4 it is a latency knob — a smaller budget gives up sooner and segments instead,
which may produce a cheaper total route in less time.

**It cannot be calibrated on replayed terrain.** §7.1.2 measures replay under-expanding by 3.6–7.6×
against the same route in game, for a structural reason no capture technique removes. A budget
fitted to replayed expansion counts would be fitted to a fiction. The number that decides it is
milliseconds per expansion in a live client, which is precisely what §8's instrumentation produces
and §1.2 defers the cross-tick decision to. So the budget is set from in-game evidence, from the
run that discharges §10's criterion 6, and the javadoc promise is discharged there — not from the
headless sweep.

**And D9 makes it the most consequential number in C4.** Since backoff only reaches the goal when
the budget is a large fraction of what the search needs (§2.1), the budget is no longer a
tie-breaker between failure modes — it is the primary mechanism, and segmentation is the safety net
behind it. The evidence bounds it directly:

| route | expansions needed | 10,000 is |
|---|---|---|
| `c-short-hop` | 94 | 106× over |
| `d-cliff` | 2,082 | 4.8× over |
| `b-cave-climb` | 3,474 | 2.9× over |
| `a-big-obstacle` | 4,445 | 2.2× over |
| **111-block route** | **17,423** | **57% of need — short** |

A budget of 25,000 puts every route measured so far inside a single search, the 111-block one at
143%. That is the shape of the answer; the millisecond figure decides whether the client can afford
it, which is why §8's instrumentation is built before the budget is chosen and not after.

### 6.1 The sweep, run

`MinProgressSweepTest` ran the metric above over the three fixtures with headroom to segment —
`d-cliff`, `b-cave-climb`, `a-big-obstacle` — at five candidate margins, each fixture's own search
budget fixed at 84% of what an unbounded search needs, matching the known-good reference points a
throwaway prototype had already found at that same 84%. The printed table:

```
d-cliff.txt: needs 273 expansions, optimal cost 274.4170743526183, sweeping at budget 229 (84% of need)
  minProgress 1.0 blocks: FOUND, 2 segments, cost 274.41707435261833, quality 1.000
  minProgress 2.0 blocks: FOUND, 2 segments, cost 274.41707435261833, quality 1.000
  minProgress 4.0 blocks: FOUND, 2 segments, cost 274.41707435261833, quality 1.000
  minProgress 8.0 blocks: FOUND, 2 segments, cost 274.41707435261833, quality 1.000
  minProgress 16.0 blocks: BUDGET_EXCEEDED, 1 segments, cost 0.0, quality -
b-cave-climb.txt: needs 726 expansions, optimal cost 387.87625725436385, sweeping at budget 609 (84% of need)
  minProgress 1.0 blocks: FOUND, 2 segments, cost 572.3831401561094, quality 1.476
  minProgress 2.0 blocks: FOUND, 2 segments, cost 572.3831401561094, quality 1.476
  minProgress 4.0 blocks: FOUND, 2 segments, cost 572.3831401561094, quality 1.476
  minProgress 8.0 blocks: FOUND, 2 segments, cost 572.3831401561094, quality 1.476
  minProgress 16.0 blocks: FOUND, 2 segments, cost 572.3831401561094, quality 1.476
a-big-obstacle.txt: needs 1247 expansions, optimal cost 514.4780665840374, sweeping at budget 1047 (84% of need)
  minProgress 1.0 blocks: FOUND, 2 segments, cost 547.389717878801, quality 1.064
  minProgress 2.0 blocks: FOUND, 2 segments, cost 547.389717878801, quality 1.064
  minProgress 4.0 blocks: FOUND, 2 segments, cost 547.389717878801, quality 1.064
  minProgress 8.0 blocks: FOUND, 2 segments, cost 547.389717878801, quality 1.064
  minProgress 16.0 blocks: BUDGET_EXCEEDED, 1 segments, cost 0.0, quality -
```

**Reading it:** margins of 1, 2, 4 and 8 blocks reach `FOUND` on all three fixtures, at cost
figures identical bit-for-bit within each fixture across that whole range — the same backoff
candidate clears every one of those margins, so the choice among them is a true tie on both of the
sweep's own criteria (fixtures reaching `FOUND`, then quality ratio). 16 blocks is where the tie
breaks: it is too strict a requirement for `d-cliff` and `a-big-obstacle`, and both return an empty
`BUDGET_EXCEEDED` where a useful segment existed — exactly the failure mode §6 names above.

**The sweep does not pick a winner among 1, 2, 4 and 8 — the plan's stated tie-break (lowest
quality ratio) does not discriminate, because the ratios are identical, not merely close.** The
choice has to be made on grounds outside the sweep:

- **The risk either side of the tie is asymmetric.** Too large is a functional failure — at 16,
  two of three fixtures return an empty `BUDGET_EXCEEDED`, meaning no node qualified and the run
  produced nothing walkable. Too small has no measured cost anywhere in the tied range. When one
  direction fails outright and the other has never cost anything measurable, the safer seat is
  further from the cliff.
- **Every other piece of evidence in this branch was measured at 4.0.** §2.1's whole comparison
  table, the throwaway prototype that reversed D2, `BackoffTest`'s in-game-derived cost assertions,
  and `SegmentedSearchTest`'s mutation-checked `274.41707435261833` all predate this sweep and were
  all gathered at the old default. Shipping a different tied value, for no measured benefit, would
  weaken every one of those claims' connection to the code that now ships.
- **Vertical terrain is expected to bite the margin harder than these three fixtures show.** §11
  records `h` reaching only about 45% of true cost on a climbing route, so on vertical terrain a
  given `h` improvement demands much more real movement than these fixtures' quality ratios
  suggest — a stricter margin fails there first, and none of the three swept fixtures is that case.

**Chosen: 4.0 blocks** — two doublings short of the 16-block failure rather than one, and the
value the rest of the branch's evidence already stands on. `AStarPathfinder.DEFAULT_MIN_PROGRESS_BLOCKS`
is set to it, and its javadoc's PROVISIONAL paragraph is replaced with this measurement, stated
honestly as a judgment call on an exact tie rather than as the sweep's unique answer.

### 6.2 The budget, chosen without the millisecond figure

**The millisecond figure this section originally deferred to does not exist yet.** The in-game
timing instrumentation §8 adds has never been run in a Minecraft client on this branch. That is
not the same as having no evidence: the owner ran a direct 200,000-node-budget probe on the
`e-long-range` route on 2026-08-26 (§7.1.1), and it completed at 17,423 expansions — proof, not
extrapolation, that 25,000 is affordable in node-count terms for the hardest route measured so far.
The §6 table above already shows 25,000 covers every route measured, the hardest at 143% of its
need. `AStarPathfinder.DEFAULT_NODE_BUDGET` and `PathProbe.NODE_BUDGET` are both set to 25,000 on
that basis.

**What this does not settle:** whether 25,000 expansions fits inside a tick's time budget on a
live client. That is §10 criterion 6's question, and it still needs the in-game run this section
cannot substitute for — the javadoc on both constants says so, and criterion 6 is not ticked here.

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
| `e-long-range` | same terrain, 111 blocks out | (1626,62,-863) → (1737,72,-786) | **111**, 10, 77 | **17,423** (`BUDGET_EXCEEDED` at 10,000) | No — clamped, see §7.1.1 |

"Replays exactly" means outcome, step count and cost to sixteen digits, verified by running each
through `AStarPathfinder` at the same 10,000-node budget. `b`, `c` and `d` reproduce their captured
cost bit for bit. **`a` does not** — 94 steps at 514.4780665840374 against the captured 92 at
512.4329751331646. It carries 397 `?` cells and some sit near the optimal route, which is exactly
the documented failure mode. It is kept, because as a *world* it is perfectly valid realistic
terrain and §6's sweep compares segmented against unbounded cost **within a fixture**; it simply
may never be cited as reproducing an in-game measurement.

### 7.1.1 `e-long-range` — the premise, measured

**C4's premise is no longer an extrapolation.** On 2026-08-26 at 23:25, a goal 111 blocks out over
the same kind of terrain exhausted all 10,000 expansions and returned `BUDGET_EXCEEDED` with no
path — 57,220 snapshot positions, 739,968 reads. It is the **first real budget exhaustion in the
project's history**, across every route C3 and C4 have measured, and it confirms the shape the
other four fixtures suggested: search effort is driven by terrain difficulty and distance together,
not by either alone. `a-big-obstacle` spent 4,445 expansions to travel 17 blocks; six times the
distance over comparable terrain is past the ceiling.

**Re-run at a 200,000 budget, the same route returns `FOUND` in 17,423 expansions** — 151 steps,
cost 818.98, snapshot 97,560 positions / 1,291,886 reads at **13.2×**, the highest repeat factor
this project has recorded. That number is why C4 has the shape it does. The budget was not an order
of magnitude short, it was at 57% of need, which §2.1 places right at the edge of where backoff
starts working and D9 turns into "raise the budget". A route needing 200,000 would have re-scoped
C4 entirely.

**It cannot replay, and no re-run at current settings would help.** `ProbeBounds.MAX_EXTENT` is 64
and a clamped axis is anchored on the start, so the window is x[1624..1687] and the goal's x of
1737 lies 50 blocks beyond it. `FixtureWorld.parse` yields `goal() == null`; with the goal retyped
by hand from the clamp notice, as that notice instructs, it sits outside the world and the replay
returns **`NO_PATH` after 2,085 expansions**, not `BUDGET_EXCEEDED` after 10,000. Raising the
replay budget to 200,000 changes nothing: 2,085 nodes is the whole reachable region inside the
window.

So it serves §6's calibration not at all — no reachable goal means no denominator. §6 does not need
it: exhaustion there is induced with a deliberately small budget, which C4 controls.

**It earns its place as the D5 fixture instead.** It is real terrain in which the open set genuinely
empties while eligible, progress-making nodes exist — `h(start)` is 509.2 and the search advances a
long way into the window before running out. That is a far better test of D5's "no backoff on
open-set exhaustion" than §7.2's synthetic boxed start, which exhausts trivially.

A true long-range *calibration* fixture would need `MAX_EXTENT` raised to roughly 128 so both ends
of a budget-exhausting route fit. At the current density of about one byte per drawn cell, a
128×32×128 window is near 500 KB — large for a test resource but not prohibitive, and `ProbeBounds`
lives in `:runtime`, which has tests. **Not taken now**, on YAGNI: it is a lever to pull only if
§6's sweep over `b` and `d` produces a `C` that looks unrepresentative of long routes.

### 7.1.2 Expansion counts do not survive a replay

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

**D2's reversal repurposed these.** They were drafted to pin the *scoring* rule — an alcove min-`h`
was supposed to fall into, a wandering route `g` was supposed to price. §2.1 measured both
predictions as false, so pinning them would pin fiction. What actually needs pinning is the
**guard**, because §2.1 shows the guard is the load-bearing half.

| Fixture | What it pins |
|---|---|
| **Local minimum** | A pocket whose `h` is low but from which the goal requires moving *away* first. The run must return `BUDGET_EXCEEDED` with no path — failing safe — rather than looping. This is the failure §2.1 found on `a-big-obstacle`, reduced to a world small enough to reason about |
| **Two-segment corridor** | A route solvable in exactly two segments at a chosen budget, asserting the concatenation is contiguous, that `h` strictly fell between segments, and that the total cost matches the unsegmented optimum |
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
   one new test that pins D5 directly, on `e-long-range`: a real-terrain world whose goal lies
   outside the reachable region returns `NO_PATH` with an empty path, **not** `PARTIAL`, even
   though the search advanced far into the window and eligible nodes were expanded in quantity
   (`h(start)` is 509.2, and 2,085 nodes are reached before the open set empties). Without it,
   §9.1's last mutation has nothing to fail against. This fixture's goal must be supplied by hand —
   it is clamped out of the map, so `goal()` is `null`; §7.1.1 explains why.

Determinism gets its own test at this level, as C1 §5.1 requires of anything that decides which path
comes back: the same search run twice returns a byte-identical segment and cost.

### 9.1 Mutations the final review must execute

Named here rather than left to the reviewer's imagination, because five consecutive sub-projects
have now had a final whole-branch review find, by *running* broken code, what reading the diff did
not. Each must map to a named failing test; any that fails to fail is a gap.

| Mutation | Expected to break |
|---|---|
| Delete the eligibility guard | The local-minimum trap — this is the mutation that matters most, because §2.1 measured the guard as the difference between failing safe and livelocking |
| Weaken the guard from `h ≤ hStart − minProgress` to `h < hStart` | The local-minimum trap, by permitting segments too small to converge |
| Relax the strict `<` in the `h` comparison to `<=` | Determinism |
| Return the most recent eligible candidate, not the lowest `h` | The two-segment corridor's cost assertion |
| Make the start node eligible (drop the positivity of `minProgress`) | The zero-length-segment guarantee in §3.3 |
| Return `PARTIAL` on open-set exhaustion | D5's `NO_PATH` contract test on `e-long-range` |

## 10. Done criteria

1. `./gradlew build --rerun-tasks` green — **never `:test`**; javadoc is build-failing under
   `-Xdoclint:all,-missing -Xwerror` and `:test` never runs it, so a green `:test` can hide a broken
   build.
2. ✅ **Done.** `minProgress` has a committed sweep table (§6.1), produced by §6's metric over three
   of the §7.1 fixtures. The sweep itself ties exactly across 1, 2, 4 and 8 blocks; 4.0 is chosen
   as a judgment call outside the sweep — furthest of the tied values from the 16-block failure and
   the value the rest of the branch's evidence was measured under (§6.1). There is no `C` to
   calibrate — D2's reversal removed it. The default node budget is not from the sweep either:
   §7.1.2 shows replayed expansion counts are fiction, so it is set (§6.2) from the §6 table of
   per-route expansion needs and the direct 200,000-budget probe of 2026-08-26, not from a
   millisecond figure — that instrumentation has not yet been run in a client, and criterion 6
   below remains unticked for exactly that reason.
3. ✅ **Done at spec time.** Five real-terrain probe dumps are committed under
   `core-pathfinder/src/test/resources/terrain/`, with per-fixture replay fidelity verified by
   execution and recorded in §7.1.
4. Existing test files changed by addition only; `FOUND` and `NO_PATH` behaviour identical.
5. Every mutation in §9.1 executed, with its result recorded in the merge commit.
6. **One in-game run** in which the probe reaches, in *n* segments, a goal that a single search
   cannot reach at all — with milliseconds reported. **The route is already chosen and already
   proven to defeat a single search:** `e-long-range`'s (1626, 62, −863) → (1737, 72, −786), which
   returned `BUDGET_EXCEEDED` at 10,000 expansions on 2026-08-26. The criterion is met when that
   same goal is reached by segmentation, so it needs no budget lowered and no case induced.

## 11. Carried forward, not solved here

- **Cross-tick search and all of C3 §9's inheritance**: snapshot lifetime, `seal()`'s retained
  `live` reference and the level it pins, discard-on-level-transition. Deferred to a future
  sub-project, which C4's millisecond figures will let the owner size honestly. These are three
  questions that are really one and should be solved once.
- **The climb-aware heuristic**, measured at 6% and parked in C3 §2.3, where `h` reaches only 45% of
  true cost on a climbing route because the horizontal leg prices every octile unit at the cheapest
  flat rate. It now has a sharper trigger than "reopen if the budget binds": **if the sweep shows
  vertical routes needing disproportionately many segments.** The caveat that keeps it live is
  unchanged, and §7.1.2 now **measures** the understatement it warned of: a replayed fixture expands
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
| ~~The in-game run never exhausts the budget~~ | **Closed 2026-08-26.** Four awkward-terrain runs all returned `FOUND` at up to 44% of the budget, and this looked like C4's largest risk. A fifth run at 111 blocks over the same terrain then returned `BUDGET_EXCEEDED` at 10,000 expansions — §7.1.1. Criterion 6 needs no induced exhaustion; the route is known |
| ~~Segmentation solves a problem that does not occur~~ | **Closed by the same run.** The premise was an extrapolation from `a-big-obstacle` spending 4,445 expansions to travel 17 blocks. It is now a measurement, and the shape it predicted held: six times the distance over comparable terrain is past the ceiling |
| ~~`C` unrepresentative of long routes~~ | **Dissolved by D2's reversal.** There is no coefficient to fit. `minProgress` remains, but §2.1 shows it changing failure *mode* rather than success, and the termination proof holds for any positive value |
| Segmentation rarely exercised once the budget is raised | The honest consequence of D9. If 25,000 puts every real route inside one search, `PARTIAL` becomes a path few runs take, and untaken paths rot. Mitigated by the §7.2 traps and the driver's headless tests, which exercise it at induced small budgets regardless of what the shipped budget is |
| The raised budget is unaffordable in ms | The one genuinely open question, and the reason §8's instrumentation is task one rather than task last. 17,423 expansions with 1.29M snapshot reads froze the client visibly during the 200,000-budget run. If 25,000 costs more than a frame, D9's answer weakens and cross-tick — §1.2's deferral — becomes the next sub-project rather than a someday |
| Timing measured on the client thread is noisy | Accepted. The figure is needed to distinguish 3 ms from 80 ms, not to resolve 3 ms from 4 ms |
| Segmented routes materially worse than optimal | §6's ratio is exactly this quantity, measured rather than hoped. If it comes out badly, that is a finding about the rule and reopens D2 |
