# C3 — World snapshot design

**Date:** 2026-08-25
**Status:** 🟡 Proposed — approved in brainstorm, awaiting spec review
**Milestone:** M4 / sub-project C3 (`2026-08-15-c1-pathfinder-core-design.md` §1)
**Depends on:** C1 (pathfinder core), C1a (heuristic rates) — both shipped
**Draft input:** `2026-08-14-b2-world-view-design.md`, which is C3's draft and is superseded in
four places by §3 below
**Blocks:** nothing. C4 and M5 are the beneficiaries, not the gate

---

## 1. What this is

An immutable, point-in-time copy of the part of the world a search actually reads, built by
memoising a `BlockSource` and frozen by a one-way `seal()`.

It is **not** what B2 drafted. B2 proposed copying whole 16×16×16 sections in bulk. §2 measures
that at 10–95× more `IBlockView` calls than the search needs, and measures the memoising
alternative at **4–16× fewer** calls than the search makes today. The storage layout, the uniform
section case, the two-phase single object and the hard dependency on B1 all fall out of the design
as a consequence.

**In scope:**

- `WorldSnapshot` — a memoising, main-thread `BlockSource` decorator
- `SealedSnapshot` — its frozen, any-thread result, with `covers()`
- `PositionKey` — the packed-`long` position key, extracted from `Pos` into `:core`
- Wiring the snapshot into `PathProbe`, and reporting its ratio in the probe summary

**Explicitly not in scope:**

| Deferred to | What |
|---|---|
| C4 | Keeping a snapshot alive across ticks, and the discard-on-level-transition question that comes with it. Segmentation, search-effort budget |
| M5 | Threading. Pre-warming. The fault-back protocol §5.2 describes but does not build |
| M5 | The primitive `long`→object map that would replace `HashMap<Long, BlockData>` |
| Probe | The render's read budget — 262,144 worst case, capped on file size alone. A rendering-policy question, not a snapshot one |
| Never | An SPI type for snapshots. B2 §2.1, settled 2026-08-14 |

---

## 2. Evidence

Measured 2026-08-25 against the shipped `AStarPathfinder` at the probe's 10,000-node budget, with
four active movements (`walk.parkour` is `runtimeOnly` from `:runtime` and absent in
`:core-pathfinder`). Two fixtures: unbounded flat ground, and flat ground with solid barriers
every 24 blocks each holding a two-block gap at a shifting X. Throwaway harness, deleted.

| run | outcome | expanded | `at()` calls | distinct positions | **repeat factor** | sections touched | sections in bounding box |
|---|---|---|---|---|---|---|---|
| flat, axis-aligned 256 | FOUND, 257 | 257 | 17,664 | 3,092 | **5.7×** | 72 | 72 |
| flat, diagonal 90 | FOUND, 91 | 91 | 6,210 | 1,634 | **3.8×** | 38 | 98 |
| flat, diagonal 180 | FOUND, 181 | 181 | 12,420 | 3,254 | **3.8×** | 74 | 338 |
| walled, straight 180 | BUDGET_EXCEEDED | 10,000 | 677,241 | 42,636 | **15.9×** | 125 | 192 |
| walled, diagonal 180 | BUDGET_EXCEEDED | 10,000 | 678,061 | 42,372 | **16.0×** | 120 | 162 |
| flat, unreachable goal | BUDGET_EXCEEDED | 10,000 | 689,931 | 41,702 | **16.5×** | 110 | 128 |

Distinct-position counts reproduce C1a §2 exactly (3,092 / 1,634 / 3,254), which is what
establishes that the method is the same one.

### 2.1 Five findings, four of which overturn something inherited

**1. "17 sections" was wrong by a factor of four.** C1a §2 and the C3 handoff both report the
256-straight corridor as occupying 17 sections. That is the X-axis section span alone. The corridor
is `X -1..256, Y 63..66, Z -1..1`, which straddles a section boundary in **both** Y and Z: 18×2×2 =
**72**. The figure C3 was told to size itself against was off by 4×, in the direction that flatters
bulk copying.

**2. Bulk section fill costs 10–95× more `IBlockView` calls than the search needs.**

| run | distinct positions needed | SPI reads to fill the touched sections | over-read |
|---|---|---|---|
| flat, diagonal 180 | 3,254 | 303,104 | **93×** |
| flat, axis-aligned 256 | 3,092 | 294,912 | **95×** |
| walled, budget-exhausted | 42,636 | 512,000 | **12×** |

B2 §7 rates snapshot memory "Medium" and fill cost "Low–Medium, **unmeasured**". That is inverted.
Memory never approaches a problem; fill is 300,000–500,000 main-thread `stateId` calls, and the
1.7.10 adapter composes each one from a block id plus metadata.

**3. Memoising is cheaper than reading live, by 4–16×.** `BlockLookup` memoises *classification by
state id*, never *position*, so a position read sixteen times costs sixteen `stateId` calls today.
The repeat factor column is therefore the exact SPI saving a memoising snapshot delivers. This is
the finding that makes C3 pay for itself immediately rather than at M5.

**4. Eager copying fails on unpredictability, not on volume.** Nothing here approaches the ~2,900
sections B2 feared; the worst case is 125. But the flat 180-diagonal touches 74 sections inside a
338-section bounding box, and the walled 180-diagonal — *same start, same goal* — wandered to
`X -25` while chasing a goal at `X 180`, never coming within 70 blocks of it in X. **No region
sized from start and goal covers what a search reads**, because what it reads depends on terrain
it has not looked at yet. That, not the section count, is what kills eager copying, and it is also
what makes B2 §4's pre-warm obligation on M5 hard.

**5. The vertical extent is four blocks.** Every search stayed within `Y 63..66` for a floor at
`Y 63`. Three quarters of any 16-tall section fill is wasted on Y alone, and the 4-block band still
spans two section layers because it straddles `y = 64`. B2's full-height fear — ~24 section layers
— is the opposite of what happens.

### 2.2 Confirmed on real terrain

The owner ran four in-game probes on 2026-08-25 (Fabric 1.21.11). Dumps are on disk, gitignored,
at `adapters/adapter-fabric-1.21.11/run/continuo-path-probe*.txt`.

| run | outcome | steps | expanded | expanded/steps |
|---|---|---|---|---|
| straight 140 | FOUND | 141 | 141 | 1.00 |
| diagonal 100 | FOUND | 101 | 142 | 1.41 |
| diagonal 200 | FOUND | 201 | 201 | 1.00 |
| short | FOUND | 33 | 57 | 1.73 |

**This also closes C1a's first `NOT VERIFIED` line** — *"Nothing was run in a Minecraft client. A
diagonal goal ~180 blocks out should now succeed where it previously returned BUDGET_EXCEEDED, but
nobody has pressed the key."* A 200-block diagonal over real terrain now costs 201 expansions.

**The repeat factor survives the move off synthetic ground.** The short run is unclamped and
contains no `?`, so it replays as a fixture; the three long runs are clamped and have
`goal() == null`, so they were re-run to a synthetic corner goal:

| source | outcome | `at()` calls | distinct | repeat |
|---|---|---|---|---|
| short run — real terrain, real path | FOUND, 33 steps | 3,864 | 680 | **5.68×** |
| straight-140 window, synthetic goal | NO_PATH, 320 expanded | 19,398 | 1,560 | 12.43× |
| diag-200 window, synthetic goal | NO_PATH, 4,096 expanded | 277,784 | 16,900 | 16.44× |
| diag-100 window, synthetic goal | NO_PATH, 4,096 expanded | 277,784 | 16,900 | 16.44× |

**5.68× is the load-bearing figure** — real terrain, real path, at the top of §2's flat-ground
FOUND band of 3.8–5.7×. Snapshot memory for that search: 42 KB.

The two identical 16.44× rows are **not independent evidence**. `4,096 = 64 × 64` is the whole XZ
area of one slice, so those searches exhausted the drawn window rather than meeting terrain. They
corroborate the shape of §2's flood-fill cases; they do not add a second real-terrain sample.

**The paste-back round trip is exact when it applies.** The short run replayed as a fixture
returned `FOUND, 33 steps, 57 expanded, cost 159.79403497705567` — identical to the in-game run to
all seventeen digits, and identical *without* `walk.parkour`, which the probe granted in game but
which is `runtimeOnly` and absent from `:core-pathfinder`. The C3 handoff calls the round trip
"real but conditional"; the condition is **no `?` cells and not clamped**, and when it holds the
reproduction is exact rather than merely same-verdict.

### 2.3 Varied terrain, including vertical

Two further runs on the evening of 2026-08-25, chosen for terrain rather than distance, and
measured after `1334cfa` made a clamped map's goal recoverable:

| dump | terrain | in game | `at()` calls | distinct | repeat | memory |
|---|---|---|---|---|---|---|
| close-vary-terrain | **36-block climb**, 41 Y slices, 506 `?` | FOUND, 73 steps, 4,030 expanded | 68,847 | 7,173 | **9.60×** | 448 KB |
| long-very-terrain | 140 steps, water, clamped, 238 `?` | FOUND, 140 steps, 7,915 expanded | 84,300 | 8,475 | **9.95×** | 529 KB |

Both sit in the middle of the 4–16× band, *above* §2.2's 5.68× surface figure. The vertical case is
the one §2.2 could not reach: a route climbing 36 blocks across 41 Y slices reads 7,173 distinct
positions and still repeats each one 9.6 times.

**The second dump is measurable only because of `1334cfa`.** It is clamped, so `goal() == null`;
the goal came from the clamp notice, which now names it.

**Fidelity survived 506 `?` cells.** The close run replayed to `FOUND, 73 steps, cost
390.6757658034911` — identical to the in-game run, with 506 cells re-parsed as impassable
`UNKNOWN` and without `walk.parkour`. So `?` cells are a **risk rather than a certainty**: they
bite only when they sit near the optimal route. Expansions differ (4,030 in game, 1,249 replayed)
because `FixtureWorld` answers `UNKNOWN` outside its extent and the live world is unbounded.

**A tighter climb-aware heuristic was tested and rejected.** `h` reaches only 45% of true cost on
the climbing route, because the horizontal leg prices all 49 octile units at the cheapest flat rate
(3.5636) when 36 of them must be `walk.ascend` at 6.5582. Substituting the provable bound
`climb × ASCEND + max(0, units − climb) × horizontal` — admissible, since only `AscendMove` raises
Y and it offers `(x±1, y+1, z)` — lifts `h` to 72% of true cost and moves expansions **1,249 →
1,169, a 6% saving**. Identical path, identical cost. This is deliberately *not* a second C1a: the
fan-out on varied terrain is terrain forcing detours, which no admissible heuristic can foresee.
Recorded in §9 rather than acted on. **The caveat that keeps it open at all:** the replay fixture
is bounded, so it cannot reproduce the in-game 4,030 and may understate the benefit.

### 2.4 What the evidence still does not cover

- **Three real-terrain samples, all overworld surface-or-mountain.** 5.68×, 9.60× and 9.95×. No
  cave, no ravine, no Nether. §5.1 wires the probe to report the ratio on every run so the sample
  grows for free.
- **No adapter cost.** `IBlockView.stateId` cannot be timed headlessly. The claim throughout is
  about *call counts*, which are exact, not about milliseconds, which are not.
- **Cave and overhang terrain is unmeasured.** Every fixture and every probe run is a surface
  world; the 4-block Y band is a property of those, not a proven property of pathfinding.

---

## 3. Decisions

### 3.1 B2 §2.2's four, resolved

| # | B2's draft | C3's resolution |
|---|---|---|
| D1 | Two-phase `FILLING`/`SEALED` on one object; lazy section fill chosen over eager whole-region copy | **Superseded.** Two *types*, not two phases (§4.3). Fill is per position, not per section (§4.4). B2's lazy-versus-eager framing is answered by §2.1 finding 4, not by its own argument |
| D2 | Sections store `int[4096]` of state ids with a uniform-section special case | **Dropped.** There are no sections. The uniform case is real for a *bulk* copy and meaningless for an exact one — you never see a whole section, so there is nothing to detect |
| D3 | `BlockSource` as the shared interface | **Shipped in C1**, unchanged |
| D4 | Reads outside the filled region return `UNKNOWN`, never air | **Ratified unchanged**, and already shipped in `BlockSource`'s javadoc. Extended, not amended, by `covers()` on `SealedSnapshot` only (§4.5) |

### 3.2 New, decided in this brainstorm

| # | Decision | Alternative rejected |
|---|---|---|
| D5 | The snapshot decorates a `BlockSource`, not an `IBlockView` | Decorating the SPI directly. That would need the classifier, state ids and B1 — see §3.3 |
| D6 | `seal()` transfers ownership and invalidates the filling handle; any later use throws | `seal()` copies and the handle stays usable; or `seal()` is idempotent and reads degrade silently |
| D7 | `PositionKey` is extracted into `:core` and `Pos` delegates to it | Duplicating the packing privately in `WorldSnapshot`; or placing the snapshot in `:core-pathfinder` |
| D8 | `PathProbe` searches through a snapshot and reports its ratio | Shipping the type with no production consumer |

### 3.3 The B1 dependency evaporates

B2 §3 calls the dependency on B1 "concrete rather than procedural: a snapshot stores state ids".
Under D5 it stores `BlockData` references obtained from a `BlockSource`, so it needs no state id,
no classifier and no per-version table. `BlockLookup` already interns one `BlockData` per state id,
so a snapshot of 42,636 positions holds a few dozen distinct instances and 42,636 references to
them.

This is worth stating because B2 §3 also warns that "if B1's implementation changes the interning
model this draft needs re-reading, particularly D2". Under C3 there is no D2 and no exposure.

---

## 4. Design

### 4.1 `PositionKey` — D7

`WorldSnapshot` needs one `long` per position to key its map on. A\* already has that packing in
`dev.continuo.pathfinder.Pos`: X and Z at 26 signed bits, Y at 12. `:core-pathfinder` depends on
`:core`, so `:core` cannot reach it.

```java
// :core
public final class PositionKey {
    public static long pack(int x, int y, int z);
    public static int unpackX(long packed);
    public static int unpackY(long packed);
    public static int unpackZ(long packed);
}
```

`Pos` keeps its entire public API — `pack`, `unpackX`, `unpackY`, `unpackZ`, `unpack`, `packed()`,
`hashCode()` — and delegates the arithmetic. **No call site outside `Pos.java` changes.** The
javadoc explaining the 26/12/26 split and the ±33,554,432 / −2048..2047 ranges moves to
`PositionKey` and `Pos` points at it.

Two definitions of a position packing in one codebase is a silent-aliasing bug waiting for someone
to change one of them. One definition, in the module both need, is the reason this small edit to
C1's code is in scope at all.

### 4.2 `WorldSnapshot` — filling

```java
// :core
public final class WorldSnapshot implements BlockSource {
    public WorldSnapshot(BlockSource live);

    public BlockData at(int x, int y, int z);
    public int minY();
    public int maxY();

    public int size();            // distinct positions held
    public int reads();           // at() calls served
    public SealedSnapshot seal(); // one-way; invalidates this object
}
```

Main thread only, inside `IGameEvents.onClientTick`'s delivery window — a restriction it inherits
from whatever `BlockSource` it wraps rather than declaring for itself, exactly as `BlockLookup`
does. It depends on `BlockSource` and `BlockData` and nothing else: not `IBlockView`, not
`BlockClassifier`, not `:platform`. A fixture for it is a four-line `BlockSource`.

`minY()` and `maxY()` are captured as `final int` in the constructor. A sealed snapshot must hold
no reference to anything live, and delegating them would defeat that.

### 4.3 `SealedSnapshot` — frozen — D1 superseded

```java
// :core
public final class SealedSnapshot implements BlockSource {
    // no public constructor; only WorldSnapshot.seal() makes one

    public BlockData at(int x, int y, int z);
    public int minY();
    public int maxY();

    public boolean covers(int x, int y, int z);
    public int size();
    public int reads();
}
```

`size()` and `reads()` are the filling snapshot's counters **frozen at the moment of sealing** —
`reads()` is the number of `at()` calls served while filling, and a sealed snapshot does not count
its own. It cannot: counting would mutate it, and §4.3's safe-publication argument depends on
nothing being written after construction. Carrying the pair across the seal is what lets §5.1
report the ratio from the sealed object rather than making the caller capture it beforehand and
hold two numbers across a call that invalidates their source.

**Two types rather than B2's two phases, for three reasons:**

1. **The sealed object structurally cannot call the SPI.** It has no reference to a live source and
   no method that would use one. B2 §6 asks for a test proving "a filling read after sealing does
   not call `IBlockView`"; here the state is unrepresentable, and the test (which C3 still writes,
   see §6) is confirming a type-level fact rather than a runtime check.
2. **Safe publication is free.** `SealedSnapshot`'s fields are `final` and its map is never mutated
   after construction, so the JMM's final-field freeze guarantees any thread seeing a properly
   constructed reference sees the whole map. M5 hands one to an executor without having to argue a
   happens-before edge. This holds *only* because D6 stops the filling handle writing afterwards.
3. **`covers()` has one unambiguous meaning.** On a filling snapshot "do you cover this?" is
   ambiguous — it can always go and ask. On a sealed one it is a fact.

### 4.4 The fill protocol

`WorldSnapshot.at(x, y, z)`:

1. If `y < minY() || y >= maxY()`, return `BlockData.UNKNOWN` **without storing anything**.
   Out-of-world is permanent and computable; storing it lets a search that probes above or below
   grow the map with entries carrying no information.
2. Look up the packed key. `BlockData.UNKNOWN` is a non-null instance, so a single `get` returning
   non-null means present — no second `containsKey`.
3. Otherwise call the live source **exactly once**, store the result, return it.

`SealedSnapshot.at` is the same minus step 3: a miss returns `BlockData.UNKNOWN`.

**`UNKNOWN` is stored verbatim.** An unloaded chunk, or a `stateId` of `-1` inside an otherwise
loaded chunk, is a real answer at the moment it was read. Storing it is what preserves the
stability property, and it is what stops the 4–16× repeat factor from re-hitting the SPI on exactly
the positions a search probes hardest — the edges of what it can see.

Both counters are maintained unconditionally: `reads()` counts every `at()` including out-of-world
ones, `size()` counts stored positions. `reads() / size()` is the ratio §2 measures.

### 4.5 `covers()` — D4 extended, not amended

Four situations, two answers:

| situation | `at()` returns | `covers()` |
|---|---|---|
| read while filling, ordinary block | the block | `true` |
| read while filling, unloaded chunk or `stateId == -1` | `UNKNOWN` | **`true`** |
| outside `minY`/`maxY` | `UNKNOWN` | **`true`** |
| never read | `UNKNOWN` | **`false`** |

Only the last row is a hole in the snapshot. The other three are the world's own answer, frozen.

**Why this is not a second rule on `BlockSource`.** `BlockSource.at`'s contract is unchanged and
its shipped javadoc — "`UNKNOWN` for every reason a position might be unreadable … one rule, no
position-dependent special cases" — stays exactly true. `covers()` exists only on
`SealedSnapshot`, so nothing holding a `BlockSource` ever sees a second rule. D4 is ratified, not
amended.

**Why it is needed at all.** Rows 2 and 4 both return `UNKNOWN`, and an off-thread search must
treat them completely differently: row 2 is terrain to route around, row 4 is a question the main
thread has to answer. Collapsing them is what makes B2 §4's pre-warm obligation undischargeable,
and §2.1 finding 4 is why pre-warming cannot simply be made large enough to avoid the problem.

### 4.6 `seal()` — D6

`seal()` hands the map to a new `SealedSnapshot` and nulls its own two references. **No copy** —
O(1), no transient doubling.

Afterwards, on the filling handle, `at()`, `size()`, `reads()` and a second `seal()` all throw
`IllegalStateException`. A caller still holding it has a bug, and the alternatives are worse:
returning `UNKNOWN` silently would present to them as *terrain*, and a phantom wall that only
appears after a seal is the single worst failure mode this design has. Throwing is also what keeps
§4.3's safe-publication argument to one line.

### 4.7 Lifecycle: none

In C3 a snapshot is created, filled, used and dropped inside a single `PathProbe.run()`. Nothing
holds one across ticks. Therefore:

- No `clear()`, no registration in `ContinuoCore.stop()`.
- **Global rule 2 is untouched** — no new condition for an adapter to evaluate or get wrong.
- The `minY`/`maxY` captured in the constructor cannot go stale, because a snapshot cannot outlive
  the tick it was built in, let alone the level.

C4 is the first thing that keeps a snapshot alive across ticks and inherits all three of those
questions. That is recorded in §9, not solved here.

### 4.8 Storage

`HashMap<Long, BlockData>`. It boxes a `Long` per lookup exactly as `BlockLookup` already boxes an
`Integer` per lookup, so it is not a regression against the code it replaces — and it removes an
SPI call per read while adding only the box.

Measured cost at roughly 64 bytes per entry: **208 KB** for the flat 180-diagonal (3,254 entries),
**2.7 MB** for the pathological budget-exhausted case (42,636). §2.1 finding 2 is why memory is not
the risk B2 thought it was.

A primitive open-addressed `long`→object map is the swap if a search ever profiles hot. `Pos`'s own
javadoc already parks the identical idea — *"a primitive map would be a C4 concern"* — and C3 parks
it the same way, with the same trigger: a measurement, not a suspicion.

Java 8 throughout. No `var`, no records, no `List.of`, no lambdas in main source.

---

## 5. The consumer — D8

### 5.1 `PathProbe`

```java
WorldSnapshot snapshot = new WorldSnapshot(world);
PathResult result = new AStarPathfinder(nodeBudget).findPath(snapshot, startX, startY, startZ,
    new GoalBlock(goal.x(), goal.y(), goal.z()), CapabilitySet.of(Capability.PARKOUR));
SealedSnapshot sealed = snapshot.seal();
// summary gains: "snapshot 3254 positions / 12420 reads (3.8x)"
```

Three deliberate choices:

- **The search reads through the snapshot; `ProbeBounds` and `PathRenderer` keep reading the live
  source.** The render window can be 64 blocks per axis — 262,144 cells — and each is touched once,
  so pushing it through the snapshot would add ~16 MB of transient entries to save nothing. The
  repeat factor lives in the search.
- **The probe seals and reports from the sealed snapshot.** The summary line gains `size()` and
  `reads()`, so the next probe run measures §2's ratio **on real terrain** rather than on a
  synthetic fixture. The seal is not ceremony: the reported numbers come from the sealed object, so
  the transfer is on the path that produces the output. It is *not* claimed that sealing needs a
  client to be exercised — `seal()` is pure Java with no platform contact and §6's tests cover it
  fully.
- **No adapter changes.** `PathProbe` lives in `:runtime`; both adapters only call `run()`. All
  three machine-checked invariants are untouched, and there is no new module or dependency.

`PathProbe`'s class javadoc currently argues that C3 is not a prerequisite for it — *"a snapshot is
what makes reads safe off the main thread, and nothing here leaves it."* That is still true and is
no longer the whole reason: the snapshot earns its place here on read count. The javadoc is updated
to say so.

**This reintroduces an in-game verification obligation** that B2 §6 was pleased to be without
("the first sub-project since A1 with no in-game verification obligation"). One smoke run, and the
run is the measurement.

### 5.2 What M5 gets, and what it still owes

**B2 §4's pre-warm-before-seal obligation lands here, as C1 §9 and C1a §8 both require.** Deferred
by B2, C1, C2 and C1a — four times — it is recorded in this spec with something concrete attached
for the first time:

- **Pre-warming needs no API.** It is `at()` over a region while filling, on the main thread.
- **A region sized from start and goal is a poor guess**, by §2.1 finding 4 — measured, not
  suspected.
- **`covers()` is the primitive that makes the alternative viable.** The shape M5 is likely to want:
  search off-thread against a sealed snapshot; on `covers() == false`, fault; the main thread fills
  the missing region on the next tick; resume. C3 designs none of that and does not commit M5 to
  it. It provides the one thing M5 cannot add later without changing this type.

---

## 6. Verification

Headless, except the single probe run in §5.1.

| test | what it pins |
|---|---|
| `at()` matches the live source across an entire region, filling | transparency |
| exactly one live read per distinct position, over a counting source | **the 4–16× claim of §2** |
| a live source returning a different value on every call → the snapshot returns the first, forever | ★ **the stability property C3 was chosen for** |
| a live source that throws on any call → a sealed snapshot still answers from its cache | `seal()` really cuts the SPI |
| an `UNKNOWN` read once is stored and never re-read, against a source that would answer differently | ★ **"unloaded is not air" — B2 §6's own pick for the test most worth mutating** |
| `covers()` is `false` for never-read and `true` for read-as-`UNKNOWN` | the M5 distinction. Both rows return `UNKNOWN` from `at()`, so an `at()`-only assertion cannot tell them apart |
| `covers()` is `true` outside `minY`/`maxY`, and `size()` does not grow there | no junk entries, and out-of-world is not a hole |
| `at()`, `size()`, `reads()` and a second `seal()` all throw on the **filling** handle after sealing | D6 |
| the sealed snapshot's `size()` and `reads()` equal the filling handle's last values, and do not move when the sealed snapshot is read | §4.3's frozen counters, and that reading a sealed snapshot mutates nothing |
| a real A\* search through a snapshot returns a path byte-identical to the same search live | the decorator is invisible to C1, determinism intact |
| `PositionKey.pack` returns the pre-extraction values for negatives, `Y = -64`, `Y = 319` and the X/Z extremes | D7 changed no bits |

**Both ★ tests must have their non-vacuity demonstrated by mutation**, and the mutations recorded
in the branch, as C1a's were. Their subject is "Y does not happen", which is the shape A2b found
two vacuous tests in.

The final whole-branch review **must execute mutations rather than read the diff**, and must report
**what failed to fail** — that has found what per-task reviews could not for four consecutive
sub-projects.

---

## 7. Risks

| Risk | Severity | Mitigation / status |
|---|---|---|
| The 4–16× repeat factor rests on few real-terrain samples | **Low**, downgraded twice | Three in-game routes replayed: **5.68×** (33 steps, surface), **9.60×** (73 steps, 36-block climb, 41 Y slices) and **9.95×** (140 steps, water). All inside the band, none near 1×, and the two varied-terrain figures are the higher ones. What remains is that all three are overworld and none is a cave or ravine. §5.1 makes every future probe run add a sample for free |
| C3's chosen purpose (a stable world across a search) has no consumer until C4 | Medium, accepted | Stated plainly rather than implied: a synchronous main-thread search already sees a stable world, so the property is **latent**. C3 pays for itself today on read count alone |
| M5 pre-warms wrongly and an off-thread search paths through phantom walls | Medium | `covers()` (§4.5) makes the failure detectable instead of silent. §5.2 records the obligation. C3 cannot discharge it |
| Boxing a `Long` per read is a hot-path allocation | Low | Not a regression — `BlockLookup` boxes per read today and also makes an SPI call. §4.8 parks the primitive-map swap with a measurement trigger |
| A caller keeps the filling handle after sealing | Low | D6 throws. The alternative presents as terrain |
| `covers()` is public API that only M5 will use, and M5 may want something else | Low | One boolean method on one class. Cheaper to have and not need than to add after M5 has built around its absence |
| Memory at a pathological search | Low, measured | 2.7 MB at 42,636 positions, and that case is a budget-exhausted search that returns no path |

---

## 8. Done criteria

1. `./gradlew build --rerun-tasks` green. **Gate on `build`, never `:test`** — javadoc is
   build-failing and a dead `{@link}` fails as hard as a missing symbol. Test counts taken only
   from a full run; filtered runs corrupt the XML.
2. `checkCorePurity`, `checkCoreBytecode` and `checkDependencyDirection` all green.
3. No new module, no new dependency, no new SPI type, no `IGameEvents` method, **no adapter edit** —
   a stated success condition, verifiable from the diff.
4. `Pos`'s public API is unchanged and no call site outside `Pos.java` was touched by D7.
5. Both ★ tests in §6 have recorded, reproducible mutations.
6. One probe run in a live client, reporting the snapshot line.
7. Both throwaway harnesses deleted from `core-pathfinder/src/test/java/dev/continuo/pathfinder/`:
   `RegionMeasurementThrowaway.java` (§2) and `RealTerrainMeasurementThrowaway.java` (§2.2). The
   second reads absolute paths under `adapters/adapter-fabric-1.21.11/run/`, which is gitignored,
   so it would fail on any machine but this one.

---

## 9. Carried forward

Recorded so that nothing here is rediscovered:

- **C4 inherits cross-tick snapshot lifetime**: the discard-on-level-transition question, the
  staleness of a captured `minY`/`maxY`, and whether a segmented search refills or re-snapshots.
  §4.7 is only true because nothing in C3 outlives a `run()`.
- **M5 owes the pre-warm.** §5.2. Now with `covers()` and a measured reason why sizing from start
  and goal does not work.
- **The probe's render is budgeted by nothing** — 262,144 reads worst case, justified on file size
  alone. Untouched by C3 and explicitly out of scope.
- **A tighter climb-aware heuristic is available and was measured at 6%.** §2.3. `h` reaches 45%
  of true cost on a climbing route because the horizontal leg prices every octile unit at the
  cheapest *flat* rate even where `|dy|` of them must be ascends — structurally the same shape C1a
  fixed one axis over. The provable bound lifts it to 72% and saves 1,249 → 1,169 expansions.
  **Not worth a sub-project on this evidence**, and recorded so nobody re-derives it. Reopen if C4
  finds the node budget binding on vertical routes: the close run used 40% of it and the long run
  79%, both on routes that succeeded. The measurement was taken on a *bounded* replay fixture and
  may understate the saving in a live world.
- **Two probe findings from §2.2 belong to the probe, not to C3**, and are recorded here because
  nothing else carries them: the paste-back round trip is **exact** when a map has no `?` and is
  not clamped, which sharpens the "real but conditional" framing the handoff uses; and a clamped
  map's `goal() == null` still forces a synthetic goal on any replay, which is the one thing that
  stopped the three long runs from being independent terrain samples. A clamp notice that named the
  goal's coordinates would fix the second, and is a probe change, not a snapshot one.
- **`walk.parkour` is absent from `:core-pathfinder`'s ServiceLoader**, being `runtimeOnly` from
  `:runtime`. Every figure in §2 excludes it. Correct, guarded, and repeatedly surprising.
- **B2's residual §7 risks are now measured**, and the answers are in §2.1: memory is not a problem,
  fill cost was, and eager sizing fails for a reason B2 did not name.
- **Two C1a cosmetic residuals remain open**: `ActiveMovements`' `@throws` javadoc still says
  "multiplier", and one over-long line in `HeuristicMultiplierAdmissibilityTest`. Neither is C3's.
