# C1a — Heuristic rates design

**Date:** 2026-08-25
**Status:** 🟡 Proposed — approved in brainstorm, awaiting spec review
**Milestone:** amendment to M4 / C1 (`2026-08-15-c1-pathfinder-core-design.md`)
**Depends on:** C1 (pathfinder core), C2 (movement registry) — both shipped
**Blocks:** C3 (`WorldSnapshot`), because C3's central question is how much world a search
touches and that number is currently an artefact of this defect

---

## 1. What this is

A\* estimates the remaining cost with a **Chebyshev** distance — `max(|dx|, |dy|, |dz|)` — scaled
by a single multiplier, `ActiveMovements.cheapestAxisStep()`. But `walk.diagonal` is a registered
movement and costs `TRAVERSE × √2`, so a diagonal step is credited at the **cardinal** rate. The
estimate is therefore short by a factor of √2 on any diagonal, A\* degrades toward Dijkstra, and
the search fans out.

This amendment replaces the single multiplier with two rates and the Chebyshev distance with an
**octile** distance.

**In scope:**

- `HeuristicRates` — an immutable pair of rates, replacing the bare `double`
- `octileUnits` — one shared definition of horizontal distance
- `IMovementType`'s declaration: `minCostPerAxisStep()` replaced by two methods
- `Goal.heuristic`'s parameter, and both implementations
- `MovementContract`'s audit of the declarations

**Explicitly not in scope:**

| Deferred to | What |
|---|---|
| C3 | Anything about snapshots, sections or fill. This exists so C3 measures a real number |
| I | Node state, `SLAB_BOTTOM`, sub-block surface heights |
| Later | Ladders and swimming. §4.2's vertical rate is the seam they will land on, and that is the point |
| Never | Changing any cost constant. `MovementCosts` is untouched |

---

## 2. Evidence

Measured 2026-08-25 against an unbounded flat world at the probe's 10,000-node budget. Throwaway
harness, not kept.

| goal | outcome | expanded | distinct blocks read | bounding region |
|---|---|---|---|---|
| 256 straight | FOUND, 257 steps | 257 | 3,092 | 258×4×3 — 17 sections |
| 90 diagonal | FOUND, 91 steps | **4,506** | 19,614 | 49 sections |
| 180 diagonal | **BUDGET_EXCEEDED** | 10,000 | 42,379 | 121 sections |

A textbook octile heuristic, substituted through a custom `Goal` and measured the same way:

| goal | Chebyshev | octile |
|---|---|---|
| 90 diagonal | 4,506 expanded | **91** |
| 180 diagonal | BUDGET_EXCEEDED | **181**, FOUND |
| 256 straight | 257 | 257 — unchanged |

Path cost is identical either way (453.57 at 90 diagonal), so this is search efficiency, not a
different answer. **The 180-diagonal case fails on flat, open, empty ground** — no terrain is
involved, which is what makes it a heuristic defect rather than a budget being too small.

The declared rates that produce it:

| movement | `minCostPerAxisStep()` |
|---|---|
| `walk.traverse` | 3.5636 ← the minimum |
| `walk.descend` | 4.0108 |
| `walk.diagonal` | 5.0397 |
| `walk.ascend` | 6.5582 |
| `walk.parkour` | 5.0609 — read from source, not from the run; see §8 |

`cheapestAxisStep = 3.5636`. A diagonal step truly costs `5.0397 = 3.5636 × √2`, and the heuristic
credits `3.5636` for it. The shortfall is exactly √2, and nothing else contributes: `walk.descend`
and `walk.parkour` both sit above the minimum, so neither drags it down.

**The owner's own long-distance probe run was nearly axis-aligned** — its path ran straight along
Z at fixed X — which is why it succeeded in 840 expansions. The same distance on a diagonal would
have hit the budget.

---

## 3. Decisions

| # | Decision | Alternative rejected |
|---|---|---|
| D1 | `ActiveMovements` hands out an immutable `HeuristicRates`, not a `double` | An additive default method on `IMovementType` and an extra `Goal.heuristic` parameter — smaller now, but one more parameter per rate added later |
| D2 | Two rates: **horizontal** (per octile unit) and **vertical** (per block of Y), each an independent minimum | One rate over all axes, as today. Rejected in §4.2 |
| D3 | The unit of horizontal distance is **octile**, not Chebyshev | Keeping Chebyshev and special-casing diagonals inside `GoalBlock` from `MovementCosts` constants — makes admissibility numeric again, which is the coupling `ActiveMovements` exists to remove |
| D4 | `minCostPerAxisStep()` is **replaced**, not deprecated alongside a new method | Carrying both contracts. Every implementor is in this repo; a clean break beats two ways to be right |
| D5 | A rate of `POSITIVE_INFINITY` declares "this movement never displaces along that axis class" | A `null`, a sentinel, or a separate `boolean`. Infinity is already the identity for `min` and needs no branch |

**D1 was the owner's call in the 2026-08-25 brainstorm.** Recorded honestly: octile *alone* would
not have needed a value object. Redefining the unit changes `walk.diagonal`'s declared number from
`5.0397` to `5.0397 / √2 = 3.5636` and leaves every other movement's number alone, because their
horizontal displacement lies on a single axis where octile and Chebyshev agree. What the value
object buys is D2's horizontal/vertical split, which is a different benefit — see §4.2.

---

## 4. Design

### 4.1 `HeuristicRates` and the octile unit

Both live in `:core-movement`, beside `ActiveMovements`. `:core-pathfinder` already depends on
`:core-movement`, so no module edge changes and `allowedProjectDependencies` is untouched.

```java
public final class HeuristicRates {
    private final double horizontal;   // ticks per octile unit
    private final double vertical;     // ticks per block of Y

    public double horizontal();
    public double vertical();

    /** One cardinal step is 1 unit; one diagonal step is √2 units. */
    public static double octileUnits(int dx, int dz);
}
```

```
octileUnits(dx, dz):
    lo = min(|dx|, |dz|)
    hi = max(|dx|, |dz|)
    return (hi - lo) + SQRT2 * lo
```

**One definition, shared by the heuristic and the audit that polices it**, for the same structural
reason `BlockLegend` is shared by the renderer and the fixture parser: two copies that agree today
drift silently the moment either side changes, and the drift is invisible because both halves
still look well formed. `MovementContract` calls the same method `GoalBlock` does.

**Why octile is a valid distance to build a heuristic on.** Octile is the true shortest-path
metric on an 8-connected grid with step weights `1` and `√2`, and because `1 ≤ √2 ≤ 2` it is a
metric — in particular subadditive. Subadditivity is what lets a per-edge bound sum along a path
into a whole-path bound, and it holds for displacements of any shape, not only for the unit steps
the current movements happen to offer. A future two-block or knight-shaped movement needs no
special case.

### 4.2 What each movement declares

`IMovementType` loses `minCostPerAxisStep()` and gains:

```java
double minCostPerHorizontalUnit();   // min over its offers of cost / octileUnits(adx, adz)
double minCostPerVerticalStep();     // min over its offers of cost / |ady|
```

Both must be positive and non-`NaN`; `POSITIVE_INFINITY` is legal and declares that the movement
never displaces along that axis class (D5). At least one must be finite — a movement that displaces
along neither axis class moves nowhere, and `MovementRegistry` rejects it at registration, beside
the non-positive check already there.

`ActiveMovements` minimises each **independently**:

| movement | horizontal | vertical |
|---|---|---|
| `walk.traverse` | 3.5636 | ∞ |
| `walk.diagonal` | 5.0397 / √2 = **3.5636** | ∞ |
| `walk.ascend` | 6.5582 | 6.5582 |
| `walk.descend` | cost of a one-block drop — see below | 4.0108 |
| `walk.parkour` | 10.1218 / 2 = 5.0609 | ∞ |

giving `horizontal = 3.5636`, `vertical = 4.0108`.

`walk.descend` is the one row that must be derived rather than transcribed. Its current
declaration comes from `worstRatio()`, the minimum over fall depths of `cost / depth`, which
`MAX_SAFE_FALL = 3` puts at the *deepest* drop. The two new rates split that apart: every descend
offer displaces one horizontal unit whatever its depth, so the horizontal rate is the minimum
**cost** over its offers — the *shallowest* drop — while the vertical rate keeps the per-depth
minimum and so keeps `4.0108`. The shallowest drop necessarily costs at least `4.0108`, which is
above `3.5636`, so `walk.descend` does not touch the horizontal minimum either way; the exact
figure is an implementation detail to compute, not a number to assume.

**Two things to notice.** `walk.diagonal` and `walk.traverse` now declare the *same* number —
that is the octile unit being the right one, since `DIAGONAL` is defined as `TRAVERSE × √2`. And
`vertical` tightens from `3.5636` to `4.0108` purely as a side effect of the split, because Y is
no longer estimated using a rate that only horizontal movement can achieve.

**Why the split earns its keep.** The current design's javadoc already concedes that a cheap wide
movement "merely loosens the heuristic". A ladder is exactly that, on the vertical axis: it would
declare a low `minCostPerVerticalStep`, and under one shared minimum that number would degrade
every *horizontal* estimate the search ever makes, for a movement that cannot travel horizontally
at all. C1 and C2 both defer ladders and swimming; this is the seam they land on without another
interface change.

### 4.3 The heuristic

`Goal.heuristic(int x, int y, int z, HeuristicRates rates)` replaces the `double` parameter.

`GoalBlock`:

```
h = max( rates.horizontal() × octileUnits(dx, dz),
         rates.vertical()   × |dy| )
```

`GoalXZ` targets a column and ignores Y, so it carries the horizontal term alone and no vertical
term at all — its existing reason for ignoring Y (a candidate far above the column may still be one
move from satisfying the goal) is unchanged.

**Max rather than sum**, for the reason the current javadoc gives: `walk.ascend` closes a
horizontal axis and a vertical one in a single move, so summing would double-count.

**Admissibility, and why it stays structural.** For any offer with cost `c` and displacement
`(adx, ady, adz)`:

- `c ≥ horizontal × octileUnits(adx, adz)` — by definition of the horizontal minimum, which is
  taken over exactly the movements this search may use, this one included
- `c ≥ vertical × ady` — likewise

so `c ≥ max(...)`, which bounds the heuristic's decrease across that edge. Summing along any path,
using octile's subadditivity from §4.1, bounds the whole estimate. This is the same argument shape
as today's, one axis-class finer. It remains a property of *how the rates are derived*, not a
numeric fact about the current cost table, which is the property C2 worked to obtain.

**On flat ground the estimate becomes essentially exact**: `3.5636 × 254.558 = 907.15` against a
true cost of `180 × 5.0397 = 907.15` for the 180-diagonal. That is why expansions drop to 181.

### 4.4 The contract audit

`MovementContract.violations` computes `span = max(|nx|, |ny − y|, |nz|)` and checks
`cost / span ≥ declared`. It gains the two-way check against the two declarations, using
`HeuristicRates.octileUnits` for the horizontal half.

Both existing guards are preserved unchanged, and neither is incidental:

- the **self-offer guard** (`span == 0`), which was deleted once and restored, because `cost / 0`
  is `Infinity` and passes every comparison in silence;
- the **never-offered guard** (`offers == 0`), which distinguishes "the declaration held" from
  "nothing was ever checked".

The zero-span condition is now per-axis-class: an offer with no horizontal displacement is simply
not evidence about the horizontal declaration, and must be skipped for that half rather than
treated as a violation or as a self-offer. An offer with neither is still the self-offer error.

### 4.5 Blast radius

Enumerated so the change is not discovered piecemeal:

- **Interface and plumbing:** `IMovementType`, `IMovementRegistry`, `MovementRegistry`,
  `ActiveMovements`, `MovementContract`, `MoveSink` (javadoc reference only), `Goal`,
  `AStarPathfinder`.
- **Movements:** `TraverseMove`, `AscendMove`, `DescendMove`, `DiagonalMove`, `ParkourMove`.
- **Goals:** `GoalBlock`, `GoalXZ`.
- **Test doubles that implement `IMovementType`:** `FakeMovement` (two of them, in
  `:core-movement` and `:core-pathfinder`), `DiscoverableMovement`, `PreconditionGatedMovement`,
  `TwoOfferMovement`.
- **Tests asserting the multiplier:** `MovementRegistryTest`, `MovementCostsTest`,
  `DefaultRegistryTest`, `HeuristicMultiplierAdmissibilityTest`, `BuiltInMovementContractTest`,
  `ParkourMoveTest`, `ParkourPathfindingTest`.

`MovementCosts` itself is **not** edited. No constant changes value.

---

## 5. Verification

Entirely headless. No adapter changes, no SPI changes, no in-game step.

1. **The octile identity.** `walk.diagonal`'s declared horizontal rate equals `walk.traverse`'s, to
   `1e-9`. This is the test that proves the unit is the right one, and it is the one that fails if
   someone later "corrects" `DiagonalMove` back to declaring `DIAGONAL`.
2. **The expansion-count regression.** A 90-block diagonal search on open flat ground completes
   within a small multiple of its path length. **This is the test that would have caught the
   original defect**, and it is the reason this amendment is testable at all rather than being a
   judgement call. Pinned with slack, because the exact figure is not the contract; the order of
   magnitude is.
3. **Admissibility as a property**, via `MovementContract` over its seeded worlds, for both
   declarations rather than one.
4. **Consistency/monotonicity** of the octile estimate, which A\* needs for its closed set to be
   safe and which C1 asserted for the Chebyshev version.
5. **The rates are independent.** A synthetic movement declaring a very cheap vertical rate must
   not change the horizontal rate. This is D2's whole justification and nothing else tests it.
6. **`POSITIVE_INFINITY` declarations behave**: a movement that never moves vertically does not
   make `vertical` infinite when another movement does move vertically, and a registry whose
   movements all decline an axis class yields an infinite rate there rather than `NaN` or zero.

**Non-vacuity by mutation, run and recorded, not asserted:** revert `DiagonalMove` to declare
`MovementCosts.DIAGONAL` and confirm tests 1 and 2 both fail. On this repo the whole-branch review
has three times found by executing mutations what reading a diff did not, and a test whose subject
is "the search does not fan out" is exactly the shape that reads as a pass while checking
nothing — the same shape A2b found two vacuous tests in.

---

## 6. Risks

| Risk | Severity | Mitigation / status |
|---|---|---|
| The expansion-count test is brittle — an unrelated cost or tie-break change moves the number | Medium | Pinned with slack as an order-of-magnitude guard, not an exact figure. The alternative, no test, is how the original defect survived C1's review, C2's, and a whole-branch review |
| Rebasing the vertical rate from 3.5636 to 4.0108 silently changes existing expectations | Medium | Every changed expectation is enumerated in §4.5 and each must be re-derived, not blessed. A test updated to match observed output is not evidence |
| Floating point: octile introduces √2 into a comparison chain that was integral | Low | The audit already compares with a `1e-9` tolerance. Rates are `double` throughout, as today |
| A future movement whose displacement shape octile does not model | Low | §4.1 — octile is a metric, so the per-edge bound sums for any displacement. The declaration is `cost / octileUnits(its own offer)`, so a movement of any shape declares a bound that holds for itself |
| Scope creep into re-tuning `MovementCosts` | Low | Explicitly out of scope in §1. No constant changes value; the only number that moves is `walk.diagonal`'s declaration, and only because its unit changed |

---

## 7. Done criteria

1. `./gradlew build --rerun-tasks` green — full build, so javadoc runs. Never gate on `:test`.
2. The suite passes with no test's expectation updated to match observed output without a stated
   derivation.
3. Tests 1 and 2 of §5 have had their non-vacuity demonstrated by the §5 mutation, with the failure
   output recorded in the commit.
4. `minCostPerAxisStep()` no longer exists anywhere, including javadoc references — verifiable by
   grep, and a stated success condition rather than an outcome.
5. No SPI change, no adapter change, no new module dependency — verifiable from the diff.
6. The 180-block diagonal that currently returns `BUDGET_EXCEEDED` returns `FOUND`.

---

## 8. Carried forward

Recorded so C3 does not rediscover them:

- **B2 §4's pre-warm-before-seal obligation on M5** survives this amendment completely untouched,
  as it survived C1 and C2. **It must land in C3's spec.**
- **C3's `FILLING`/`SEALED` tension** is likewise untouched and is still C3's to resolve.
- **The region measurements in §2 become meaningful once this ships**, and C3 should re-take them
  rather than reuse them: the whole reason for doing this first is that C3 would otherwise size a
  snapshot against a number this change invalidates.
- **`walk.parkour` is absent from `:core-pathfinder`'s ServiceLoader**, because the
  `movement-parkour` dependency is `runtimeOnly` from `:runtime`. Measurements taken in
  `:core-pathfinder` therefore exclude it. This is correct and already guarded by
  `PathProbeTest.theParkourMovementIsOnTheClasspathTheProbeSearchesWith`, but it surprised this
  brainstorm once and will surprise the next reader.
