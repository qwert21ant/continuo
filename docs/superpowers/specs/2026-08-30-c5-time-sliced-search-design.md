# C5 — Time-sliced search design

**Date:** 2026-08-30
**Status:** 🟢 Approved — brainstormed with the owner on 2026-08-29/30 and approved 2026-08-30; two
mechanisms and one fault policy were rejected on measurement during the brainstorm itself
**Milestone:** M4 (C), fifth sub-project — added after C4, before M5
**Depends on:** C1 (`2026-08-15-c1-pathfinder-core-design.md`) — the search and the determinism
requirement; C3 (`2026-08-25-c3-world-snapshot-design.md`) — the snapshot, §4.7, §5.2, §9;
C4 (`2026-08-26-c4-segmentation-design.md`) — §1.2, §13, the millisecond instrumentation
**Design input:** Baritone (`C:/projects/baritone`), read on 2026-08-30 — §3.4
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M5

---

## 1. What C5 is, and what it is not

C4 §1.2 deferred cross-tick search on an explicitly unmeasured premise and named **80 ms** as the
point at which it becomes "the most urgent thing in the project". C4 §13.2 then measured 110–173 ms
and declared the deferral spent.

**C5 exists because that measurement was incomplete in two ways, both discovered during this
brainstorm.** It is not a repudiation of C4 — C4 built the instrument that made both discoveries
possible — but the numbers C5 is scoped against are not the numbers C4 handed over.

### 1.1 What the fuller measurement changed

**C4 §13's figures were cold.** The identical search — same route, same 25,053 expansions, cost
`1169.8916476768006` bit-for-bit — measured **164.0 ms** on 2026-08-26 and **58.2 ms** on
2026-08-29 once JIT had settled (§3.1). A search blocks the client for **1.2 ticks at steady state**,
not the 2–3.5 the C4 handoff records. The first press of a session still costs 109 ms.

**The registry construction was inside the timed region.** `new SegmentedSearch(new
AStarPathfinder(budget))` runs `MovementRegistry.discover()`, a `ServiceLoader` scan. Every figure
in C4 §13 includes it. Hoisted and timed separately on 2026-08-29 it is **4.5 ms on a first press,
0.8 ms warm** — small, but it was never search time and is no longer counted as such.

**Neither correction rescues the premise.** 58 ms still does not fit a 50 ms tick, the cold press is
double one, and C4 §13's run 2 — three segments, 75,000 expansions — is several times worse. Cross-
tick is still needed. It is needed less urgently, and against a smaller number, than C4 believed.

### 1.2 What C5 does not do

- **No threads.** §2 D2. The search stays on the client main thread.
- **No fault-and-refill protocol**, and therefore no consumer for `covers()`. §3.3 measures why.
- **No executor, no goal manager, no actuation.** Those are M5. C5's only consumer is the probe.
- **No SPI change.** No new type in `dev.continuo.platform`, no new `IGameEvents` method.

---

## 2. Decisions

Every row was decided with the owner during the 2026-08-29/30 brainstorm. Three of them reverse a
position taken earlier in that same brainstorm, on evidence gathered during it.

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | C5's scope | Section-backed snapshot storage, a resumable search, and snapshot lifetime across ticks | §1.1 — the three are one problem: slicing a search requires the world it reads to survive between slices |
| D2 | Mechanism | **Main-thread time slicing.** Not an off-thread search | **Reversed twice during the brainstorm.** §3.3 measures the off-thread fault loop at 131–385 round trips. Global rule 1 pins the fill to the main thread regardless, so off-thread moves 78% of the work and leaves the protocol problem behind |
| D3 | Storage shape | **4×4×4 sections behind a last-section memo**, replacing `HashMap<Long, BlockData>` | §3.2 — 35% of search time for 3.4× the references. Baritone's 16×16×16 buys 41% for **32.6×**, which is a trade it does not have to make and we do |
| D4 | Slice budget unit | **Nodes per tick.** Milliseconds reported, never consulted | C1 §5.1 makes determinism a hard requirement and C4 D6 settled this once already. A wall-clock slice boundary makes every path assertion in the suite flaky |
| D5 | Where the memo lives | On the per-search store, never on shared or published state | §4.2 — a memo field makes a store stateful, which is why Baritone hangs it on `BlockStateInterface`, a per-search object. This is also why D3 is easy under D2 and would not have been under an off-thread design |
| D6 | Suspension semantics | A slice boundary **suspends**, it does not abort. No outcome, no partial result, no observable state change | §5.2 — the alternative silently turns every long search into a `PARTIAL`, which is C4's backoff firing for a reason that has nothing to do with the budget |
| D7 | Correctness property | **A sliced search returns bit-identical results to an unsliced one**, for every slice size | §5.3 — this is the whole proof obligation, it is cheap to test exhaustively, and nothing else pins slicing |
| D8 | Snapshot lifetime | Owned by the search run; discarded on completion, cancellation, or level change | §6 — discharges C3 §9, using the level-identity trigger `AdapterRuntime` and `PathProbe` already share |
| D9 | `covers()` | **Left with no consumer.** Not given an invented one | §3.3 — the only design that needed it was measured and rejected. C3's argument for it stays unvindicated, and saying so is cheaper than building a caller to justify it |
| D10 | C5's consumer | **The probe**, driving a sliced search across ticks | B2 §9's rule. A sliced search nothing drives is exactly the guess-encoded-as-design the rule exists to stop, and §7.2 makes the probe a real verification consumer rather than a token one |
| D11 | Adapter edits | **Two, one per adapter**, replacing one per-tick call with one per-tick call | §7.2. C4 shipped zero and that was right for C4. Here the alternative is D10 with no way to reach a tick, which is worse |

---

## 3. Evidence

Everything in this section was measured during the brainstorm, on 2026-08-29 and 2026-08-30. The
in-game figures come from the owner's client; the rest are headless and reproducible.

### 3.1 The fill/search split, measured in a client

The probe was changed to seal its snapshot and then **replay the identical search against the seal**,
twice. Every position the live run read is covered by the seal, so a replay makes no SPI call at all
and expands the same nodes in the same order: its time is the search's own arithmetic, and the
difference is what reading the world cost. The replays are compared to the live run field by field,
costs bit-for-bit, so a divergence is reported rather than silently timed.

Route `(1588, 71, −967) → (1737, 72, −786)`, budget 25,000. All four presses returned `FOUND`,
2 segments, 245 steps, 25,053 expanded, cost `1169.8916476768006` — bit-identical to C4 §13 run 3,
across a JVM restart.

| press | setup | **live** | sealed 1st / 2nd | **fill** | fill share | snapshot |
|---|---|---|---|---|---|---|
| 16:14:33 | 4.5 | 109.0 | 83.2 / 53.0 | 56.0 | 51% | 145,067 / 1,841,968 |
| 16:15:19 | 0.8 | 73.9 | 40.2 / 40.9 | 33.0 | 45% | 145,078 / 1,841,977 |
| 16:15:44 | 0.9 | 60.0 | 45.1 / **45.1** | 14.9 | 25% | 145,106 / 1,842,024 |
| 16:18:20 | 0.8 | 58.2 | 45.4 / **45.4** | 12.8 | 22% | 145,109 / 1,842,010 |

The first two presses are JIT warm-up, visible **inside** press 1: its two replays of the same search
differ by 30 ms. Presses 3 and 4 have settled — the two replays agree to the decimal.

**At steady state the split is 22–25% fill, 75–78% search.** That inverts the advice the C4 handoff
carries — *"search cost is dominated by first-touch world reads… anything M5 does about search cost
should target reads"* — which was drawn from C4 §13.3's observation about *marginal* scaling across
runs. Both are true; only the actionable half was wrong.

**The fill is 145,109 live reads for 12.8 ms — 88 ns each.** That figure sizes every fill budget in
this document.

**A caveat that constrains what may be built on any of this.** The snapshot's position count drifted
across presses (145,067 → 145,109) while expansions and cost stayed bit-identical. The route did not
change; the world did, slightly, somewhere the route did not go. C4 §13.3 recorded the same effect
from the other side. Determinism is guaranteed over an identical world, and a live world is never
quite identical twice.

### 3.2 The storage sweep

Method: record every position one search reads, load the identical dataset into each candidate
store, re-time the same search against each, and assert every store returns the same outcome, cost
and expansion count. In-game scale, synthetic open ground: **25,000 expansions, 142,004 positions,
1,724,931 reads** — against the client's 25,053 / 145,109 / 1,842,010.

| store | min ms | saving | slots | occupancy | sections |
|---|---|---|---|---|---|
| boxed `HashMap<Long, BlockData>` (shipped) | 63.7 | — | 142,004 | 100% | — |
| primitive open-addressed `long` map | 50.1 | 21% | 524,288 | 27% | — |
| section 16×16×16 *(Baritone's)* | **37.8** | **41%** | 4,628,480 | 3.1% | 1,130 |
| section 8×8×8 | 41.0 | 36% | 1,153,024 | 12.3% | 2,252 |
| **section 4×4×4 — D3** | **41.5** | **35%** | **479,872** | **29.6%** | 7,498 |
| section 1×16×1 (column) | 64.6 | **−1%** | 1,183,968 | 12.0% | 73,998 |
| section 2×16×2 | 48.3 | 24% | 1,343,232 | 10.6% | 20,988 |
| section 4×16×4 | 41.8 | 34% | 1,919,488 | 7.4% | 7,498 |
| section 4×32×4 | 42.7 | 33% | 3,838,976 | 3.7% | 7,498 |

**The column shape was a hypothesis and it was wrong.** A standability check reads three blocks in
one vertical column, so a column-shaped section looked like it should hit the memo constantly.
Measured, it is *worse than the shipped map*: 73,998 sections means the memo misses more than it
hits, and every miss pays the map lookup the shape existed to avoid. Recorded so nobody re-derives
it.

**Why not Baritone's 16³.** It is the fastest and by a wide margin the worst memory trade — 3.1%
occupancy, ~18 MB of references per search. Baritone does not face this: it reads Minecraft's
*already-allocated* chunks and stores nothing per block, so occupancy is not its problem. We
allocate our own copy, so it is ours. 4×4×4 keeps 35 of the 41 points for a tenth of the waste.

**Two limits on these numbers, both load-bearing.**

1. **Absolute times are not comparable between runs of this probe.** The shipped boxed store
   measured 39.4 ms on 2026-08-29 and 63.7 ms on 2026-08-30 on the identical fixture. The
   benchmark's `at()` call site sees every candidate implementation and goes megamorphic, where
   production sees one. **Only ratios within a single run are signal.** The ratio is stable: 29–32%
   for the primitive map across both days.
2. **Nothing here was measured at in-game scale on real terrain.** The three committed terrain
   fixtures all complete in under 2 ms, where timing noise exceeds the effect and their rankings
   contradict each other. The only trustworthy row is synthetic open ground.

**Projection, stated as a projection.** 45.4 ms × 0.65 ≈ 29.5 ms, so a full search becomes
≈ 13 + 29.5 = **43 ms** — inside a tick, with about 15% headroom, at steady state, on one route. It
does not cover the 109 ms cold press or C4 §13's three-segment run. **Storage is why slicing gets
easier, not a reason it becomes unnecessary.**

### 3.3 The fault-and-refill loop, measured and rejected

The off-thread design C3 §5.2 sketched requires an answer to "the worker read a position the seal
does not cover". The policy chosen in the brainstorm was batch soft-fault: the worker never blocks,
an uncovered position reads as impassable and is recorded, the main thread fills the recorded set,
the worker searches again, until a search demands nothing new.

**Correctness is perfect and it is not the problem.** Every fixture, at every fill radius, converged
to the bit-identical cost of a full-information search. Iterations are the problem, and each
iteration costs at least one tick of round trip:

| fill radius | d-cliff | b-cave-climb | a-big-obstacle | open ground, 180 blocks |
|---|---|---|---|---|
| **0 — fill exactly what was demanded** | **131** | **188** | **241** | **385** |
| 1 | 34 | 38 | 53 | 66 |
| 2 | 20 | 22 | 31 | 44 |
| 4 | 13 | 10 | 17 | 27 |
| 8 | 6 | 7 | 8 | 16 |
| 16 | 3 | 4 | 5 | 9 |

Coverage grows by roughly one frontier layer per round trip, so iterations scale with the route's
**length in blocks** — about two per block — not with search size. The policy as chosen costs 6 to
19 seconds per path. Dilating the fill rescues it, at 5× the necessary fill work and a burst of
~100k reads in one tick at radius 8, which then needs its own per-tick cap. **The whole structure is
machinery that time slicing does not need**, which is what settled D2.

The worker also re-runs the entire search each round trip: total off-thread expansions reached
**49,209× the baseline** at radius 0 and 1,686× at radius 8. Off-thread work is not free work.

### 3.4 What Baritone does, and what of it transfers

Read from source on 2026-08-30, at `C:/projects/baritone`.

**It neither slices nor spans ticks.** It runs the search to completion on a worker thread
(`PathingBehavior.java:501`), bounded by wall clock rather than nodes — `primaryTimeoutMS` 500,
`failureTimeoutMS` 2000, `planAhead*` 4000/5000 (`Settings.java:598–615`) — checked every 64 nodes
using `currentTimeMillis`, with the comment *"since nanoTime is slow on windows (takes many
microseconds)"* (`AStarPathFinder.java:84–85`). On timeout it returns best-so-far, which is C4's
`PARTIAL` under another name.

**It reads the live world off-thread by copying the chunk reference array**, not block data —
`createThreadSafeCopy()` (`MixinClientChunkProvider.java:39`), constructed on the main thread
(asserted, `BlockStateInterface.java:72`). Chunks stay live objects the main thread mutates, so
reads can be stale; Baritone accepts that and repaths. **This is the part our SPI forbids**, and
deliberately: reaching into `ClientChunkCache` through a mixin is per-version untestable adapter
code, and one core serving adapters fifteen years apart is the reason the SPI exists.

**The chunk memo is the part that transfers, and it is D3's source.** `BlockStateInterface.java:105`
keeps the last-touched chunk in a field:

> `// there's great cache locality in block state lookups`
> `// generally it's within each movement`
> `// if it's the same chunk as last time`
> `// we can just skip the mc.world.getChunk lookup`
> `// which is a Long2ObjectOpenHashMap.get`
> `// see issue #113`

A second memo covers 512×512 cached regions (`:123`), and the read itself allocates nothing —
`getFromChunk` indexes `chunk.getSections()[y >> 4]` with a `hasOnlyAir()` fast path (`:168`).
**Baritone has no per-block memo map at all.** Our 1.84 million boxed `HashMap` lookups have no
counterpart in its design.

**Two findings worth recording so they are not re-derived.**

*`h + g/C` is not contradicted.* Baritone scores best-so-far with exactly the formula C4 §2.1
reversed, over `COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10}` — all finite, no infinity
(`AbstractNodeCostSearch.java:69`). The uses differ: Baritone wants *something to walk while it keeps
thinking*, guarded by a minimum distance from the start, and takes the smallest coefficient that got
far enough. C4 uses backoff to *chain segments toward the goal*, where §2.1 measured every finite
coefficient failing. Both can be right. **C4 D2 is not reopened by this.**

*Baritone overlaps computation with movement.* It computes one segment, starts walking, and plans
the next from the current path's destination when the current one has ≤7.5 s left, at relaxed
timeouts because there is no hurry (`PathingBehavior.java:228–231`). Our `SegmentedSearch` runs every
segment back to back **before returning anything**, which is why C4 §13's run 2 spent 172.8 ms in a
single tick. **This is an M5 execution-architecture finding, not a C5 one**, and it is recorded in
§10 because it may matter more to the bot's felt latency than everything in this spec.

---

## 4. Design — the section store

### 4.1 Shape

A store of `BlockData[64]` sections, keyed by `PositionKey.pack(x >> 2, y >> 2, z >> 2)`, with the
in-section offset `((y & 3) << 4) | ((z & 3) << 2) | (x & 3)`.

`null` in a section array means **never filled**, which is sound because a snapshot stores
`BlockData.UNKNOWN` — a real object — for a position it read and could not answer for. That
preserves C3 §4.5's four-case distinction exactly, without `covers()` needing to exist for the
search's sake.

A missing section and a `null` slot both yield `UNKNOWN`, matching `SealedSnapshot.at`'s contract
unchanged.

### 4.2 The memo, and where it may live

One field holding the last section key and its array. A read in the same 4×4×4 block skips the map
entirely — three shifts, a compare, an array index, no hash and no allocation.

**The memo makes the store stateful, so it may only live on an object one search owns.** A shared or
published store carrying a mutable memo is neither immutable nor safely publishable, which is the
property `SealedSnapshot`'s javadoc rests on. D5 states this as a rule rather than a convention
because the failure is silent: a memo on shared state is a data race that produces wrong blocks, not
an exception.

### 4.3 What this replaces, and what it does not

`WorldSnapshot` and `SealedSnapshot` keep their public contracts entirely: `at`, `minY`, `maxY`,
`size`, `reads`, `covers`, `seal`. Only the backing store changes. Every existing test in
`WorldSnapshotTest`, `SealedSnapshotTest` and `SnapshotSearchTest` must pass unchanged — that is the
cheapest available proof that the contract survived.

`size()` keeps meaning **positions stored**, not slots allocated. A new `slots()` is added for the
occupancy figure the sweep needs, and the probe reports both.

---

## 5. Design — the sliced search

### 5.1 Shape

`AStarPathfinder.findPath` today runs one loop to completion over state held in locals. C5 lifts
that state into an object and adds a bounded-advance entry point:

```
Search  begin(world, startX, startY, startZ, goal, caps)   // no expansions yet
boolean Search.advance(int maxNodes)                       // true when finished
PathResult Search.result()                                 // throws while unfinished
```

`findPath` becomes `begin(...)` followed by `advance(Integer.MAX_VALUE)`, so **it is not a new code
path**: every existing caller, test and fixture drives the same loop it drives today. This is the
property that makes D7 testable rather than aspirational.

### 5.2 A slice boundary is not an outcome

D6. When `advance` exhausts its slice it returns `false` and changes nothing else — no
`PathOutcome`, no partial path, no mutation of the node budget's own accounting. The node budget and
the slice budget are independent quantities that happen to share a unit: the first is a property of
the search, the second of how it is being driven.

Conflating them is the failure mode worth naming. If a slice boundary produced `PARTIAL`, every
search longer than one slice would trigger C4's backoff, chain a segment, and return a worse route —
for a reason with nothing to do with the budget C4 calibrated. `SegmentedSearch` would then drive
segments off an artefact of tick scheduling.

### 5.3 The correctness property

**D7: for any slice size, a sliced run returns a result bit-identical to an unsliced one.** Same
outcome, same path, same expansion sequence, same cost to sixteen digits.

This is provable by construction — the state is the same state and the loop is the same loop — and
it is the only thing that needs pinning, so it is tested exhaustively rather than at a sample: every
fixture, at slice sizes 1, 2, 3, 7, 64, 1000 and `MAX_VALUE`. Slice size 1 is the interesting one,
because a boundary between every pair of expansions is the strongest possible test that suspension
carries no state in a local.

### 5.4 The slice budget

**In nodes, per D4.** A wall-clock boundary would make the path depend on machine speed and every
determinism assertion in the suite flaky — C1 §5.1, restated by C4 D6.

Provisional value **4,000 nodes**, from §3.1's measurements rather than from taste: 25,053
expansions at 4,000 per tick is 7 ticks (0.35 s); a slice's own cost is ≈ 4,000 × 1.18 µs search
plus ≈ 23,200 new reads × 88 ns fill ≈ **7 ms**, comfortably inside a tick.

**This number is provisional and §9 criterion 6 is what fixes it.** The per-slice cost is not
uniform: early slices touch all-new terrain and pay the fill, later ones hit the memo. C4 §13.3
measured that non-linearity directly. A node budget therefore buys determinism at the price of a
variable millisecond cost, and the calibration has to be done in a client, not headlessly.

### 5.5 A segmented run is sliced too, and it is the case criterion 6 exercises

`SegmentedSearch.run` loops segments internally and returns only when the run ends. Slicing the
inner search alone would leave the outer loop synchronous and the whole run still in one tick — and
**criterion 6's route takes two segments**, so this is the case C5 is verified against, not an edge
one.

So the run gets the same treatment as the search, one level up:

```
Run     SegmentedSearch.begin(world, startX, startY, startZ, goal, caps)
boolean Run.advance(int maxNodes)      // advances the current segment; starts the next when one ends
SegmentedResult Run.result()           // throws while unfinished
```

`run(...)` becomes `begin(...)` plus `advance(Integer.MAX_VALUE)`, exactly as §5.1 does for
`findPath`, so C4's existing driver and every one of its tests keep driving the same loop.

**Three properties carry over unchanged, and one is new.**

- D7 extends verbatim: a sliced run returns a `SegmentedResult` bit-identical to an unsliced one,
  including the segment count. §8's slice-size sweep runs against `SegmentedSearch` as well as
  `AStarPathfinder`.
- D6 extends too, and matters more here: a slice boundary that fell *between* segments must not be
  observable, and must not be confused with the genuine `PARTIAL` that ends a segment. A segment
  boundary and a slice boundary can coincide, and the test suite must contain that case explicitly.
- **One snapshot spans the whole run**, as it already does — C4's probe builds one `WorldSnapshot`
  and passes it through every segment, which is where §3.1's 12.7× repeat factor comes from.
  Ownership in §6 is therefore the *run's*, not the individual search's, and a mid-run level change
  cancels all of it together.
- New: `SegmentedResult.expanded()` accumulates across every segment and is already documented as
  unbounded at roughly `cap × nodeBudget` — ~925k `Pos` on shipped defaults (C4 §11). Slicing does
  not change the bound, but it does change how long the list is *held*: a run now lives for hundreds
  of milliseconds rather than existing inside one call. Recorded, still not bounded; only the probe
  reads it.

---

## 6. Design — snapshot lifetime, and C3 §9 discharged

C3 §4.7 states its lifecycle as "none", true only because nothing outlived a `run()`. C5 is what
changes that, and inherits all three of C3 §9's questions.

**A correction first, because C3 §9 and the C4 handoff both state it wrongly.** They name
*"`seal()`'s retained `live` reference and the level it pins"*. `WorldSnapshot.seal()` **does** null
`live` — `WorldSnapshot.java:136`, beside `blocks = null`. The hazard is real but it is on the
*unsealed* snapshot: while filling, it holds the live source, and a live source wraps the client
level. Nothing has ever held one across a tick, so it has never mattered. **Under C5 an unsealed
snapshot lives for the duration of a search — several hundred milliseconds — which is exactly when
it starts to.**

**Ownership.** The snapshot belongs to the `Search`, is created with it, and dies with it. There is
no registry, no cache surviving between searches, and nothing for `ContinuoCore.stop()` to clear
that is not already reachable from the search it is cancelling.

**Discard.** A search is cancelled, and its snapshot released, on the same **client level identity
change** that `AdapterRuntime.updateLevel` and `PathProbe.onLevel` already use — the one observable
condition that covers unload, disconnect, quit-to-title and dimension change alike, stated once in
the SPI's global rule 2 so that two adapters cannot drift on it. One trigger, now three obligations.

**Staleness stays the contract**, unchanged from `SealedSnapshot`'s javadoc. A sliced search sees the
world as it was when each position was first read, and that is the *reason* the snapshot must survive
between slices rather than a defect: a search re-reading a changing world mid-run could contradict
its own closed set and return a route through a block that has moved.

**The captured `minY`/`maxY` cannot go stale**, because a level change cancels the search that owns
them before any read can use them against a different level.

---

## 7. Verification, and C5's consumer

### 7.1 Headless

The section store and the sliced search are both pure `:core`/`:core-pathfinder` and fully testable.
§5.3's exhaustive slice-size equivalence is the primary obligation; §3.2's sweep becomes a committed
calibration test in the shape of C4's `MinProgressSweepTest`, so the shape constant has a table
behind it rather than a paragraph.

### 7.2 In-game — the probe drives it, per D10

B2 §9: *a component with no consumer encodes a guess as a design*. That rule has ordered this
project's work correctly four times, and a sliced search nothing drives would break it.

**The probe becomes the consumer.** The `L` key starts a sliced search instead of running one to
completion; each tick advances it by one slice; when it finishes, the probe reports what it always
reported plus **total ticks, worst single-slice milliseconds, and whether the sliced result matched
an unsliced run of the same search**. That last check is D7 verified in a live client against real
terrain — the one place §3.2's fixtures cannot reach.

Verifying the match costs a second, unsliced search on the tick the sliced one finishes — a 58 ms
freeze on that tick alone. Acceptable in a dev probe, which already runs the search three times as
of `46ed86f`, and stated here so it is not mistaken for a slicing failure when the owner reads the
worst-slice figure.

**This costs two adapter edits, one per adapter — D11.** Verified against both on 2026-08-30:
`ContinuoFabricMod.java:145` and `ContinuoForgeMod.java:205` both call `probe.onLevel(...)`
unconditionally every tick, before the keys and before the null-player check. That call becomes the
tick hook the sliced search advances on, which needs the live `BlockSource` and the player position
passed with it.

**The null-player case is the part to get right, and the two adapters differ underneath it.** Both
compute the position only *after* their null check — Fabric from `blockPosition()`, 1.7.10 from
`floor(posX)`, `floor(boundingBox.minY)`, `floor(posZ)` — so the hook must either accept "no player
this tick" or be called from where a position exists. A search advanced with a stale position is
worse than one not advanced at all, so **the hook takes the level and advances only when a position
is available**, and a tick without a player advances nothing while still discharging the
level-identity check that call site exists for.

C4 shipped zero adapter edits and that was right for C4; here the alternative is a component with no
consumer, which the project's own rule ranks as worse. The edit is symmetric, one call site per
adapter, and **adapters have no tests and cannot get any — so review is the gate**, every Minecraft
API claim is checked against the decompiled sources on disk, and the change is deliberately small
enough to review.

---

## 8. Testing

| What | How |
|---|---|
| Slice equivalence (D7) | Every fixture × slice sizes 1, 2, 3, 7, 64, 1000, `MAX_VALUE`; assert outcome, cost to sixteen digits, and the full expansion sequence. Against **both** `AStarPathfinder` and `SegmentedSearch`, the latter also asserting segment count |
| Suspension carries no local state | Slice size 1 — a boundary between every pair of expansions |
| A slice boundary is not an outcome (D6) | A search suspended mid-run reports no outcome and `result()` throws; the final outcome equals the unsliced one |
| A slice boundary coinciding with a segment boundary (§5.5) | A multi-segment fixture driven at a slice size that lands a boundary exactly where a segment ends; the run's `PARTIAL` must be the segment's, never the slice's |
| Section store contract | Every existing `WorldSnapshotTest`, `SealedSnapshotTest`, `SnapshotSearchTest` passes **unchanged** |
| Section store correctness | Store and boxed map answer identically over a recorded dataset, including `UNKNOWN` and never-filled |
| `covers()` unchanged | C3 §4.5's four cases still hold on the new store |
| Shape calibration | Committed sweep test producing §3.2's table |
| Cancellation | A level-identity change mid-search releases the snapshot and the search reports cancelled, not failed |

**Mutations the final review must execute**, built by the reviewer as well as from this list — C4's
39-mutation review found every real defect in the contract layer *around* the algorithm, and all six
mutations its spec mandated were caught by tests that already existed:

1. Slice boundary returns `PARTIAL` instead of suspending → §8 row 3.
2. Memo not invalidated when a section is first created → wrong block after a fill.
3. Memo key compared with `equals` on a boxed `Long` rather than `==` on a `long` → correct but slow; **predicted to be caught by nothing**, and named here for that reason.
4. `null` slot treated as `UNKNOWN` at fill time rather than read time → `covers()` inverts.
5. Section offset bit order transposed (`x`/`z` swapped) → a symmetric fixture will not catch this; the test must be asymmetric.
6. Search state reset on `advance` re-entry → slice size 1 hangs or repeats.
7. Snapshot not released on cancellation → the level stays pinned; assert by reference, not by behaviour.

---

## 9. Done criteria

1. `./gradlew build --rerun-tasks` green — **never `:test`**; javadoc is build-failing under
   `-Xdoclint:all,-missing -Xwerror` and `:test` never runs it.
2. §5.3's slice equivalence passes at every listed slice size on every fixture.
3. Existing snapshot and search tests pass **unchanged** — additions only.
4. The shape constant has a committed sweep table, not a paragraph.
5. Every mutation in §8 executed, each result recorded in the merge commit.
6. **In-game.** A sliced search completes over C4 §13's route, reports total ticks and worst-slice
   milliseconds, and matches an unsliced run of the same search. **The slice budget in §5.4 is set
   from this run**, not from the projection in §3.2.
7. No SPI change, no new module, no new dependency, no `IGameEvents` method.

---

## 10. Carried forward, not solved here

- **Overlapping computation with movement.** §3.4. Baritone hides search latency behind walking by
  planning segment *N+1* while walking segment *N*; `SegmentedSearch` runs all segments back to back
  before returning. This is M5's to take, and it may reduce felt latency more than slicing does.
- **`covers()` still has no consumer** — D9, and now with a measured reason rather than a deferral.
  The design that needed it is in §3.3, rejected. M5 remains its only candidate.
- **The 25,000 node budget is still justified on node counts**, not on time. §3.1 gives the first
  real per-expansion cost; nobody has re-derived the budget from it.
- **The cold-start cost.** §3.1's first press is 109 ms against a warm 58 ms, and no slice budget
  calibrated warm is safe cold. C5 mitigates this structurally — a slice that overruns costs one
  stutter, not a frozen search — but nothing measures it.
- **A primitive `long`→object map** is superseded rather than done: §3.2 measures it at 21% against
  the section store's 35%, so `Pos`'s javadoc note can be closed by pointing here.
- **The climb-aware heuristic**, still parked at a measured 6% lower bound (C3 §2.3, C4 §11).
- **The probe's unbudgeted render**, 262,144 reads worst case. Untouched again.

---

## 11. Risks

| Risk | Assessment |
|---|---|
| The slice budget is calibrated warm and overruns cold | Real — §3.1 measures a 1.9× cold penalty. Consequence is one stuttered tick, not a wrong path or a hung search, because D4's budget is in nodes and cannot fail to terminate. Criterion 6 measures it; it does not fix it |
| The section shape is calibrated on synthetic terrain only | Real, and named in §3.2. The three real fixtures are too small to discriminate — all under 2 ms. Mitigated by D3 choosing the *conservative* corner of the sweep: 4×4×4 gives up 6 points of speed for a tenth of the memory, so being wrong about the shape costs less than being wrong about 16³ would |
| Section over-allocation hurts a real client | ~1.9 MB of references per search at 4×4×4, against ~18 MB at Baritone's 16³. Bounded by the search, released with it. Measured occupancy is 30%, not the 3% the cube gives |
| Slicing changes a path | D7 makes this a testable property rather than a hope, and §5.1's construction — one loop, driven differently — makes it structural. Slice size 1 is the test that would catch it |
| A slice boundary is mistaken for a budget outcome | §5.2 names it as the design's sharpest failure mode. It is mutation 1 in §8 |
| The unsealed snapshot pins a level | §6 — the real form of C3 §9's inherited question, now stated correctly. Discharged by the level-identity trigger, and mutation 7 asserts release by reference rather than by behaviour |
| The adapter edit breaks a global rule | D11 accepts two adapter edits knowingly. Adapters are untestable, so this is review-only, and every Minecraft API claim must be checked against the decompiled sources on disk |
| Latency is still poor after all this | 7 ticks for a typical route (§5.4), against 1.2 ticks of freeze today. C5 trades a stutter for a delay, which is the right trade for a bot and the wrong one for a probe — and §10's first entry is what actually fixes it |
| C5 is unnecessary because storage alone fits a tick | Honest possibility, and §3.2's projection lands at 43 ms against a 50 ms tick — a 15% margin, warm, on one route. Criterion 6 could show slicing is rarely needed. That would be a finding, not a failure, and the storage half stands regardless |
