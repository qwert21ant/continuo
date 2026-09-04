# D2 — the path executor

**Date:** 2026-09-03
**Milestone:** M5 (Engine), sub-project D2 of three. D1 shipped the SPI extension; D3 is the process
manager and `goto`.
**Status:** design, for review.

---

## 1. What D2 is, and what it is not

**D2 turns a path into per-tick inputs.** It is the first thing in this project that moves the player
deliberately rather than as a demonstration. Its scope is the executor itself, the per-tick position
resync that replaced the dissolved `onPositionCorrection`, off-path and stuck detection, repath, and
the humanizer seam the roadmap places in M5.

**It is not** goals, a process manager, a chat command, or `goto`. Those are D3. D2's trigger is a
dev key, deliberately, so that the executor can be verified in a client before anything is built on
top of it.

**It is not parkour.** Decision 7 grants the executor `CapabilitySet.none()`, so it executes four
movements and never presses `SPRINT`. The probe continues to search with `Capability.PARKOUR` and is
untouched.

**It does not touch either adapter's Minecraft-facing code.** The only adapter edits are a build-file
dependency, an import, and the rewiring of one existing key. That is a deliberate contrast with D1,
which was entirely adapter code and therefore entirely review-gated; D2's substance is pure and
testable.

---

## 2. Decisions

| # | Decision | Rejected alternative, and why |
|---|---|---|
| 1 | **A path carries movement identity, derived once when the path is assembled.** `PathResult` and `SegmentedResult` gain `steps()`, a list of `Step { Pos to; MovementKind kind; }` | Threading an edge id through `MoveSink.offer` is exact but changes core-movement's hottest interface, all five movement types, the testkit and every call site. Re-deriving inside the executor each tick restates the movement rules in a second place with nothing tying them together |
| 2 | **The executor overlaps searching with walking at the executor level.** It spends one slice per tick on a pending `Run` *while* driving inputs from the path it already has | Segment-level overlap needs `Run` to hand back each `PARTIAL` as it lands — a change to code C5 verified bit-identical across three refactors. Deferring overlap entirely means restructuring the tick loop later rather than adding to it |
| 3 | **The walk key drives the executor.** K stops being the 40-tick forward walk and becomes "walk to the goal marked with H" | A fifth dev key leaves two things in the core that both mean "move forward". Core-owned goal marking builds D3's surface on a guess about what D3 wants |
| 4 | **The executor re-anchors to the nearest path node within a bounded window each tick** | A global nearest-node scan is purer but skips a whole loop where a route passes near itself. A monotonic index needs an explicit case for each of knockback, rubber-band and respawn — exactly what §3.5 of D1 argued the executor would not need |
| 5 | **The humanizer seam ships with one real behaviour: bounded turn rate on `setLook`** | An identity decorator can only ever prove that it delegates; the shape is validated by writing something real through it |
| 6 | **A new module, `core-engine`, holds the executor, the humanizer and `ContinuoCore`** | `core` sits below `core-pathfinder`, so `ContinuoCore` cannot reach a path where it stands. Putting the executor in `runtime` means bot behaviour in the module that exists to discharge the global rules. An interface in `core` for the tick to drive exists only to satisfy the module graph |
| 7 | **The executor grants `CapabilitySet.none()`** | Granting `PARKOUR` makes three unaudited residuals load-bearing at once — 1.7.10's `SPRINT`, the absent velocity, and `onGround()`'s cross-version semantics — and a missed jump is a fall, the loudest possible failure for a first executor |

---

## 3. Evidence — read from the repository, 2026-09-03

Every claim below is cited. D1's post-mortem found all five of its defects in the two sections whose
claims carried no citations, so this section states nothing it did not read.

### 3.1 The module graph forces decision 6

`core/build.gradle.kts` declares `api(project(":platform"))` and nothing else.
`core-movement/build.gradle.kts` declares `api(project(":core"))`, and
`core-pathfinder/build.gradle.kts` declares `api(project(":core"))` and
`api(project(":core-movement"))`. So the direction is
`platform → core → core-movement → core-pathfinder`, and `ContinuoCore`, which lives in `core`,
cannot name a `PathResult`. This is a fact about the build, not a preference.

`runtime/build.gradle.kts` declares `api` on `platform`, `core`, `core-pathfinder` and
`core-movement`, plus `runtimeOnly(project(":movement-parkour"))`. `runtime` is therefore a sibling
of any new module above `core-pathfinder`, not an ancestor of it — which is why
`PathProbe.yawToward` cannot be reused where it stands.

### 3.2 The five movements, and what deltas they can emit

| type | offer site | delta |
|---|---|---|
| `walk.traverse` | `TraverseMove.java:44` | `(±1, 0, 0)` or `(0, 0, ±1)` |
| `walk.diagonal` | `DiagonalMove.java:73` | `(±1, 0, ±1)` |
| `walk.ascend` | `AscendMove.java:55` | `(±1, +1, 0)` or `(0, +1, ±1)` |
| `walk.descend` | `DescendMove.java:96` | `(±1, −n, 0)` or `(0, −n, ±1)`, `1 ≤ n ≤ MovementCosts.MAX_SAFE_FALL` |
| `walk.parkour` | `ParkourMove.java:102` | `(±2, 0, 0)` or `(0, 0, ±2)` |

`TraverseMove.expand` (`:38`) iterates `Cardinals` at the origin's own `y`; `DiagonalMove.expand`
(`:59`) iterates its four `{dx, dz}` pairs at the same `y`; `AscendMove.expand` (`:44`) offers at
`y + 1`; `DescendMove.expand` (`:83`) offers only the landing, which its class javadoc (`:17–18`)
states is the first floor below rather than every level passed through; `ParkourMove.expand`
(`:76`) offers two blocks along one axis at the same `y`, and its javadoc (`:17–30`) records that
one block and the same height is the only case it emits.

**The five delta sets are pairwise disjoint and jointly cover everything the current registry can
produce.** That is what makes decision 1 lossless *today*, and §5.3's test is what keeps it true.

### 3.3 A joined path is a sequence of single moves

`Run.append` (`:175–184`) drops each segment's first position when joining, because "every segment
after the first begins where the previous one ended, so appending whole would repeat that position
and make the route non-contiguous by its own test" (`:172–173`). So a multi-segment
`SegmentedResult.path()` contains no duplicate positions, and every adjacent pair is one legal
movement. The derivation in §5 therefore applies to a whole run, not only to one segment.

### 3.4 What rule 4 obliges the executor to do

`platform/package-info.java:100–131`. While driving, the core **MUST** state its full desired input
set every tick and **MUST NOT** rely on a previous `setInput` persisting. While idle it writes
nothing at all — not even `false` — because a core holding every input at `false` would fight the
user's keyboard. On `stop` it releases what it holds, once. The same reasoning is stated for
`setLook` at `:129–131`: a core driving the player rewrites its desired facing every tick, which is
what makes a server's rotation correction self-healing rather than a case to detect.

`Input` (`Input.java:10–18`) has exactly seven constants: `FORWARD`, `BACK`, `LEFT`, `RIGHT`,
`JUMP`, `SNEAK`, `SPRINT`. "Full desired input set" is therefore seven writes.

### 3.5 The call-window asymmetry between the two SPI directions

`IPlayerView`'s methods **MUST** only be called while `onClientTick`'s delivery window is open, and
outside it behaviour is unspecified (`IPlayerView.java:9–13`). `IActuator.setLook`, by contrast,
**MUST NOT** throw outside that window — it does nothing — because "a bot must never fault the
game's tick loop over a race with a disconnect" (`IActuator.java:102–106`).

**A decorator that implements `IActuator` by reading `IPlayerView` inherits the stricter of the
two.** §8.2 states that consequence rather than leaving it latent.

### 3.6 Lifecycle facts the executor inherits

`Run.cancel()` (`:133–137`) nulls the world the run holds, and its class javadoc (`:19–23`) records
why: a run may live for hundreds of milliseconds holding a snapshot still filling from the client
level, so a level change during a run must cancel it or the run keeps the old level reachable.

`AdapterRuntime.updateLevel` (`:158–182`) calls `core.stop()` on every client level-instance change,
including the dimension change that replaces the level without ending the session. **So an executor
whose `stop()` cancels its pending `Run` needs no new hook**, and gets the same discharge
`PathProbe.onLevel` has, for free.

`SegmentedSearch`'s class javadoc (`:21–23`) records that `SegmentedResult.expanded()` accumulates
across every segment and is bounded by roughly `cap × nodeBudget` entries. §6.4 is why that matters
more under D2 than it did under C5.

### 3.7 `NO_PATH` is the definitive signal

`SegmentedResult.outcome()`'s javadoc (`:34–42`) states that `NO_PATH` proving the goal unreachable
is the only definitive "stop retrying" signal the search produces, and that collapsing it would
leave a caller re-searching a goal already proven impossible. §7.4's termination rule rests on this.

### 3.8 What already exists and is reused rather than rewritten

`PathProbe.yawToward` (`:446–450`) is `-atan2(dx, dz)` in degrees, targeting a block centre, and its
javadoc records that both versions derive forward motion as `(−sin yaw, cos yaw)`, which inverts to
that expression. `PathProbe.wrapDegrees` (`:475–485`) folds degrees into `[−180, 180)`.
`PathProbe.SLICE_NODES` (`:111`) is 2,000, measured on 2026-09-02 at a worst slice of 22.3 ms cold
and 6.1–8.8 ms warm.

`FakePlayerView` is a fully settable `IPlayerView` and `FakeActuator` records every `setInput` and
`setLook` call in order. Both shipped in D1 and are what makes §10 real coverage rather than
review-by-proxy.

### 3.9 Jumping, on both versions — read 2026-09-03

This section did not exist when the spec was first written; §6.3's ascend rule rested on an uncited
claim, §14 named it as the one such claim in the document, and reading it turned out to change the
design. It is recorded here rather than silently patched.

**1.7.10**, `EntityLivingBase.onLivingUpdate`. The counter is decremented at the top of the method
(`:1930–1932`), and the jump block is `:1998–2016`:

```java
if (this.isJumping) {
    if (!this.isInWater() && !this.handleLavaMovement()) {
        if (this.onGround && this.jumpTicks == 0) { this.jump(); this.jumpTicks = 10; }
    } else { this.motionY += 0.03999999910593033D; }
} else { this.jumpTicks = 0; }
```

**1.21.11**, `LivingEntity.aiStep`. Decremented at `:2871–2873`, jump block at `:2926–2950`:

```java
if (this.jumping && this.isAffectedByFluids()) {
    ...
    if ((this.onGround() || bl && g <= h) && this.noJumpDelay == 0) {
        this.jumpFromGround(); this.noJumpDelay = 10;
    }
    ...
} else { this.noJumpDelay = 0; }
```

**Three facts, identical on both versions fifteen years apart:**

1. **A held jump does nothing while airborne.** Both gate on their own ground flag. The original
   claim holds.
2. **Both impose a ten-tick cooldown**, armed on jumping (`jumpTicks = 10`, `noJumpDelay = 10`) and
   decremented once per tick.
3. **Both clear that cooldown when the key is released** — the `else` branch of the outer `if`, on
   both. The cooldown is therefore a property of *holding* the key, not of having jumped.

Fact 3 is what §6.3 acts on, and it is the one that could not have been guessed: it means a
level-triggered executor that holds `JUMP` for the whole of an ascend is throttled where one that
conditions on `onGround()` is not.

---

## 4. Design — module layout

### 4.1 The new module

`core-engine`, package `dev.continuo.engine`, declaring `api(project(":core-pathfinder"))`. It holds:

- `PathExecutor` — the tick loop
- `HumanizedActuator` — the seam
- `ContinuoCore` — **moved** from `core`

`core` keeps the world model, `BlockLookup`, `CoreApi` and the new `Yaw` helper, and stays at the
bottom of the graph.

```
platform
  core                world model, CoreApi, Yaw
    core-movement     + MovementKind
      core-pathfinder + Step, steps()
        core-engine   ContinuoCore, PathExecutor, HumanizedActuator      (NEW)
        runtime       AdapterRuntime, PathProbe
          adapters -> runtime + core-engine
```

### 4.2 Registration, and what the move costs

Four build files: `settings.gradle.kts`, the new module's own, and both adapters', which gain
`:core-engine` because they construct `ContinuoCore`.

**Real code coupling to `ContinuoCore` is three sites**: the two adapter constructions and
`ContinuoCoreTest`. Every other reference in the tree — in `AdapterRuntime`, `BlockLookup`, `Run`,
`AdapterConformanceTest` and `platform/package-info.java` — is `{@code ContinuoCore}` javadoc text
that survives a package move untouched.

### 4.3 `Yaw`, in `core`

`PathProbe.yawToward` and `PathProbe.wrapDegrees` are package-private in `runtime`, and `core-engine`
is `runtime`'s sibling (§3.1), so they cannot be reused where they stand. They move to
`dev.continuo.core.Yaw`:

```java
public static float toward(double fromX, double fromZ, int toX, int toZ);   // block centre
public static float wrap(float degrees);                                    // [-180, 180)
```

`core` is the only module both `runtime` and `core-engine` can see. `PathProbe` delegates to `Yaw`
and keeps its existing tests, which is what makes this a verifiable extraction rather than a second
copy of the arithmetic — the handoff's "reuse it, do not re-derive it", made structurally true
rather than merely intended.

### 4.4 What is deleted

`ContinuoCore.WALK_TICKS` (`ContinuoCore.java:22`), `requestWalk` (`:66–75`), the walk branch of
`onClientTick` (`:89–102`), and their tests. The executor replaces them as rule 4's obeying
consumer, and replaces them with something stronger: 40 unconditional presses become a per-tick
input set derived from live player state.

**Two documentation files describe the deleted behaviour** — `docs/smoke-checklist-a1.md` and
`docs/smoke-checklist-a2.md` both carry "the 40-tick walk travels ≈8 blocks". D2 owes them an edit.
This is named here rather than assumed, because the owner has asked for the checklists to be left
alone once already.

---

## 5. Design — `MovementKind` and `Step`

### 5.1 `MovementKind`, in `core-movement`

```java
public enum MovementKind {
    TRAVERSE("walk.traverse"), DIAGONAL("walk.diagonal"),
    ASCEND("walk.ascend"),     DESCEND("walk.descend"),
    PARKOUR("walk.parkour");

    public String movementId();
    public static MovementKind forDelta(int dx, int dy, int dz);   // null if unnameable
}
```

Each constant carries the `IMovementType.id()` it corresponds to. **That association is what lets
§5.3's test check the derivation against the movements themselves rather than against a second
hand-written table**, which would only ever prove the table agrees with itself.

`forDelta` implements §3.2's table exactly:

| condition | kind |
|---|---|
| `dy == 0`, `abs(dx) + abs(dz) == 1` | `TRAVERSE` |
| `dy == 0`, `abs(dx) == 1 && abs(dz) == 1` | `DIAGONAL` |
| `dy == +1`, `abs(dx) + abs(dz) == 1` | `ASCEND` |
| `dy < 0 && dy >= -MovementCosts.MAX_SAFE_FALL`, `abs(dx) + abs(dz) == 1` | `DESCEND` |
| `dy == 0`, two along exactly one axis (`abs(dx) == 2` with `dz == 0`, or the transpose) | `PARKOUR` |
| anything else | `null` |

`PARKOUR` is in the enum even though D2's executor never grants the capability, because `steps()` is
derived for every search result including the probe's, and the probe does grant it.

### 5.2 `Step` and `steps()`, in `core-pathfinder`

`Step` is `{ Pos to(); MovementKind kind(); }`. Both `PathResult` and `SegmentedResult` gain
`steps()`, derived **eagerly in the constructor** through one shared package-private helper so the
two cannot drift.

- `steps().size() == max(0, path().size() - 1)`; `steps()[i]` is the move from `path()[i]` to
  `path()[i + 1]`.
- Eager is affordable because the derivation walks `path()` only. On the measured C4/C5 route that
  is 245 entries, against `expanded()`'s 25,053 — **the derivation must never touch `expanded()`**,
  and a `PathResult` for `NO_PATH` or `BUDGET_EXCEEDED` has an empty path and therefore no steps at
  all.
- `SegmentedResult` derives its own rather than delegating to `asPathResult()`, because that method
  copies `expanded()` a second time (`SegmentedResult.java:99–104`) and would turn every adoption
  into a 25,000-element copy.

**`kind()` may be `null`, and does not throw.** A delta the table cannot name is a defect in the
table, but throwing from `PathResult`'s constructor would break the probe on data it can currently
render. So `null` propagates, the executor refuses to drive a `null` kind and stops with a message
naming the delta, and §5.3's test is what keeps `null` from ever occurring.

### 5.3 The totality guard

A test in `core-pathfinder` that expands **every `ServiceLoader`-registered `IMovementType`** over
generated worlds and asserts three properties over the offers collected:

1. **Totality** — every offered delta maps to a non-`null` `MovementKind`.
2. **Disjointness** — no two movement types ever produce the same delta.
3. **Agreement** — every offer's derived kind is the one whose `movementId()` equals the producing
   type's `id()`.

Adding a diagonal ascend, a three-block parkour, a ladder climb or a water move then **fails the
build at the point of addition**, rather than mis-executing silently in a client. This test is the
entire justification for decision 1 over threading edge identity through the search, so it is the
one test in D2 whose absence would invalidate a decision rather than merely reduce coverage.

---

## 6. Design — the executor's tick

### 6.1 Where it is driven from

```java
// ContinuoCore.onClientTick
if (phase != TickPhase.PRE || !executor.active()) {
    return;                       // rule 4's idle clause: nothing is written
}
executor.tick(context.player());
```

`PRE`, because `setInput` and `setLook` take effect at the game's next input read and therefore on
the current tick when called from `PRE` (`IActuator.java:15–16`, `:71–72`).

### 6.2 The loop

```
tick(player):
  1. pending Run?  spend one slice (SLICE_NODES); adopt if it finished — appending for a
                   plan-ahead (§7.1), replacing for a repath (§7.2)
  2. no path to drive?                          -> write nothing, return
  3. re-anchor within [anchor - W, anchor + W], nearest by 3D distance
  4. no candidate, or nearest > OFF_PATH_RADIUS -> repath (§7.2)
  5. anchor is the last node and within ARRIVE_RADIUS -> arrived (§7.4)
  6. drive steps[anchor]
```

**Step 2 writes nothing**, which is the correct reading of rule 4: an executor that has been asked
for a path but has none yet is not driving, and a core that is not driving writes nothing at all.

**The anchor is a hint, not an authority.** It is one `int`, and every tick it is recomputed from
the player's actual position. Knockback moves the player back a step and the anchor follows without
a special case; a teleport moves the player outside the window and step 4 repaths. Neither is a
state the executor tracks — both fall out of one rule, which is what D1 §3.5 promised when it
dissolved `onPositionCorrection` rather than deferring it.

**Distance compares feet to feet.** A node's `y` is the block the feet occupy, which is exactly what
`IPlayerView.y()` returns after D1. Horizontal distance is measured to the block centre
(`x + 0.5, z + 0.5`), matching `Yaw.toward`'s target.

### 6.3 The drive table

Every driving tick writes **all seven `Input` constants**, per §3.4:

| kind | `FORWARD` | `JUMP` | `BACK` `LEFT` `RIGHT` `SNEAK` `SPRINT` |
|---|---|---|---|
| `TRAVERSE` | true | false | false |
| `DIAGONAL` | true | false | false |
| `ASCEND` | true | **`player.onGround()`** | false |
| `DESCEND` | true | false | false |
| `PARKOUR` | — | — | — cannot occur under decision 7; stops with a message |
| `null` | — | — | — §5.2; stops with a message |

Writing all seven rather than only what changed costs seven field writes a tick and makes a leftover
`JUMP` from a finished ascend structurally impossible. `LEFT` and `RIGHT` are never pressed: a
diagonal is walked by *facing* the diagonal destination and holding `FORWARD`.

Facing is `humanizer.setLook(Yaw.toward(player.x(), player.z(), step.to()), player.pitch())`. Pitch
is passed straight back, which is the case `IActuator.setLook`'s javadoc anticipates when it explains
why yaw and pitch are one call (`:62–64`).

**`ASCEND` presses `JUMP` only while `player.onGround()`, and §3.9 is why.** Both games gate the jump
on their own ground flag *and* impose a ten-tick cooldown that is armed on jumping and **cleared the
moment the key is released**. Holding `JUMP` through a staircase therefore throttles the ascent to
one block per ten ticks; releasing it while airborne clears the cooldown so the next landing jumps
immediately.

Conditioning on `onGround()` produces exactly that release, and it does so **without any memory** —
it remains a pure function of the path and the player's current state, which is the property §6.2
rests on. A version that remembered whether it pressed last tick would achieve the same effect and
forfeit that.

This is the one design change the pre-implementation source check produced, and it reverses one of
§12's residual answers: **`onGround()` acquires its first consumer after all.**

### 6.4 Two lifecycle obligations

**The executor copies `path()` and `steps()` out of the result and drops the `SegmentedResult`.**
Under C5 `expanded()`'s unbounded accumulation was held for the duration of one search; an executor
that retained the result would hold it for the duration of a *walk*, which is seconds rather than
milliseconds and strictly worse than today (§3.6). This is the one place where D2 could quietly
worsen an inherited residual, so it is a design rule rather than an implementation detail.

**`stop()` cancels the pending `Run`.** A pending run holds a `WorldSnapshot` wrapping a live
`BlockSource` and so pins a level (§3.6). `AdapterRuntime` already calls `stop()` on every level
transition, so this needs no new hook — the same discharge `PathProbe.onLevel` has, obtained for
free. `stop()` also writes all seven inputs `false`, once, per rule 4's third clause.

---

## 7. Design — overlap, repath, stuck, termination

**Plan-ahead and repath are different mechanisms.** Conflating them is how decision 2 would be
quietly lost, because a single "start a search when something is wrong" rule hides the fact that only
one of the two cases can be overlapped with walking at all.

### 7.1 Plan-ahead — the case overlap exists for

The path can end short of the goal: `SegmentedResult` returns a real prefix with a non-`FOUND`
outcome (`SegmentedResult.java:48–56`). When `remaining steps ≤ PLAN_AHEAD_STEPS` **and** the last
node is not the goal, the executor begins a `Run` **from the path's end, not from the player**, and
keeps walking uninterrupted while spending one slice per tick on it. On completion the new path is
appended.

This is C5 §3.4's Baritone finding in step units rather than seconds, and it is the whole of what
overlap buys.

**Adoption is an append, and it preserves the anchor.** The new path's positions are appended to the
existing one, dropping the first — the same join `Run.append` performs (§3.3), and for the same
reason. The anchor is an index into a list that only grew at its far end, so it stays valid and the
player does not so much as break stride. This is the entire mechanical difference between plan-ahead
and repath, and it is why only one of them can be overlapped.

### 7.2 Repath — the case it does not

Off-path (§6.2 step 4) or stuck (§7.3) starts a `Run` **from the player**, replacing the path
wholesale. The executor has no valid step to drive meanwhile, so it releases all seven inputs once
and stands still until the new path lands.

**Adoption is a replacement, and it resets the anchor to 0.** The old path is discarded whole,
because the executor has just concluded it does not describe where the player is.

**Overlap does not hide repath latency**, and this design says so rather than implying otherwise.
Continuing to walk a path the executor has just concluded it is not on would be driving toward a node
chosen by no current evidence, which on a cliff edge is the difference between a pause and a death.

**At most one `Run` is pending at any time**, and a repath cancels a pending plan-ahead before
starting its own. A plan-ahead searches from a path end the repath is about to discard, so letting
the two race would append a continuation of a route that no longer exists. `Run.cancel()` is what
releases the world it held (§3.6), so the cancellation is a lifecycle obligation as well as a
correctness one.

### 7.3 Stuck

`anchor` failing to advance for `STUCK_TICKS` consecutive driving ticks triggers a repath.

Measuring **path progress** rather than position is what makes this immune both to jitter — a player
shuffling against a block boundary still has a stable anchor — and to a legitimately slow tick. It
also means it needs no velocity, which is why §12 can leave `IPlayerView`'s field budget at six.

### 7.4 Termination

| condition | result |
|---|---|
| anchor is the last node, within `ARRIVE_RADIUS` | release all seven once, go idle |
| a search returns `NO_PATH` | stop, log, **do not retry** — §3.7 |
| a search returns an empty path for any other reason | counts as a failed repath |
| `MAX_CONSECUTIVE_REPATHS` repaths without the anchor advancing | stop and log |
| `stop()` | release once, cancel the pending `Run`, clear the path |

The repath counter resets on any anchor advance, so a long route that repaths five times while making
progress is not treated as a failure.

---

## 8. Design — the humanizer seam

### 8.1 Stateless, which is the point

```java
final class HumanizedActuator implements IActuator {
    void setLook(float targetYaw, float pitch) {
        float current = player.yaw();
        float step    = clamp(Yaw.wrap(targetYaw - current),
                              -MAX_DEG_PER_TICK, +MAX_DEG_PER_TICK);
        delegate.setLook(current + step, pitch);
    }
    void setInput(Input input, boolean pressed) { delegate.setInput(input, pressed); }
}
```

No stored target, no accumulated rotation, no per-tick method of its own. It is a pure function of
the requested yaw and the player's *actual* yaw, and that is what makes it self-healing: if the
server rewrites rotation or the user moves the mouse, the next tick starts from where the player
genuinely is rather than from a remembered value that has gone stale.

**This shape only works because actuation is level-triggered.** The executor re-states its desired
facing every tick (§3.4), so the decorator never needs to remember what it was aiming at. D1 §6.3
argued that in the abstract; this is the concrete form, and it is the evidence that decision 7 of D1
was right for the reason it gave.

`Yaw.wrap` is what makes the turn take the shortest arc, so a target of 179° from a current yaw of
−179° turns 2° rather than 358°.

### 8.2 The contract wrinkle, stated rather than discovered

`IActuator.setLook` MUST NOT throw outside the tick window, but `IPlayerView.yaw()` is unspecified
there (§3.5). `HumanizedActuator` therefore carries a **stricter contract than the interface it
implements**: call it only inside the tick window.

That is legitimate — it is a core-side type with exactly one caller, the executor, which runs only in
`PRE` — but it is documented on the class rather than left as a trap for whoever wraps it next.

### 8.3 `setInput` is passthrough, deliberately

Jittering presses would fight rule 4's "state the full desired input set every tick" (§3.4). The two
cannot both be true, and rule 4 is normative. Whatever input humanization eventually looks like, it
is not a decorator that drops presses.

---

## 9. Constants

**D2 introduces seven extrapolated constants into a project that closed its last one on 2026-09-02.**
That is the honest headline of this design and §13 treats it as the top risk.

| constant | proposed | reasoning, to be confirmed in a client |
|---|---|---|
| `W` (anchor window) | 6 | a walking player covers ~0.22 blocks/tick, so even ten dropped ticks move under three steps; a 3×3 spiral staircase revolves in roughly 8–12 steps, so 6 stays inside one revolution in both directions |
| `OFF_PATH_RADIUS` | 2.0 blocks | wider than knockback displaces, narrower than a teleport |
| `ARRIVE_RADIUS` | 0.5 blocks | block-centre tolerance |
| `PLAN_AHEAD_STEPS` | 20 | a search is ~13 slices ≈ 13 ticks at one slice per tick; a step is ~4.6 ticks at vanilla walking speed, so three steps would cover it. 20 is margin — about 4.6 s, close to Baritone's 7.5 s trigger |
| `STUCK_TICKS` | 40 | 2 s without path progress |
| `MAX_CONSECUTIVE_REPATHS` | 3 | |
| `MAX_DEG_PER_TICK` | 30 | 180° in six ticks ≈ 0.3 s |

Every one is measured or observed in §11's in-game run and its javadoc rewritten with the measured
figure, exactly as `SLICE_NODES` was in D1 §15.3. A constant whose javadoc still states a prediction
after that run is an unmet done criterion, not a tidy-up.

---

## 10. Testing

`core-engine` is pure, so all of this is real coverage rather than review-by-proxy. `FakePlayerView`
and `FakeActuator` (§3.8) are the whole harness.

### 10.1 Derivation

- §5.3's totality guard: totality, disjointness, agreement, over every registered `IMovementType`.
- `steps()` over a joined two-segment path, which §3.3 establishes contains no duplicate positions.
- `steps()` is empty for `NO_PATH` and `BUDGET_EXCEEDED`, and never reads `expanded()`.

### 10.2 The executor

- **Level-triggering**: every driving tick writes all seven constants; an idle tick writes **zero**
  actuator calls. The second half is the one that pins rule 4's idle clause.
- Drive table per kind; `DESCEND` never presses `JUMP`; **`ASCEND` presses `JUMP` when
  `onGround()` is true and releases it when false**, which is §3.9's cooldown rule and needs both
  halves asserted — a test that only checks the press passes on an implementation that holds it.
- Facing equals `Yaw.toward` for the current step's destination.
- The anchor **absorbs** a displacement inside the window and does not repath.
- The anchor **repaths** on a displacement outside it, and releases all seven inputs once first.
- **The spiral fixture.** This is the test that justifies `W` at all, so it must be shown to fail
  with an unbounded window and pass at `W = 6`. A spiral test that passes under both window sizes
  proves nothing and is worse than no test, because it looks like evidence.
- Stuck fires at `STUCK_TICKS` and **not** at `STUCK_TICKS − 1`. The negative assertion is what pins
  the boundary rather than the behaviour.
- **Plan-ahead starts a new `Run` at the threshold *and* inputs are still written on those same
  ticks.** Without the second clause the test passes on a sequential implementation, which would
  make decision 2 unenforced.
- **Adoption**: a plan-ahead appends and leaves the anchor where it was; a repath replaces and resets
  it to 0; a repath cancels a pending plan-ahead, asserted through `Run.cancelled()`.
- `NO_PATH` stops without retry; the repath budget terminates; the counter resets on an advance.
- `stop()` releases all seven once and cancels the pending `Run`, asserted through `Run.cancelled()`
  by reference rather than through behaviour — C5's mutation 7 precedent.

### 10.3 The humanizer

- A 180° turn takes `ceil(180 / MAX_DEG_PER_TICK)` ticks and no fewer.
- The shortest arc is taken across the ±180 wrap.
- `setInput` is passed through unchanged.
- The step is computed from the player's live yaw, not from the previous request — the assertion
  that pins statelessness, and the one a stateful reimplementation would fail.

### 10.4 Fixtures and mutations

Fixture worlds need headroom: a walk layer in the top slice makes nothing standable and `NO_PATH`
looks like a correct answer.

Predicted mutation kills go into the plan as **hypotheses to execute, not as claims**. They were
wrong in three of six in C4 and three of seven in C5, and a survivor is a finding rather than an
inconvenience.

---

## 11. Done criteria

1. `./gradlew build --rerun-tasks` green, test count recorded from a full unfiltered run.
2. §5.3's totality guard exists and is demonstrated to fail when a colliding movement is added.
3. §10.2's spiral test is demonstrated to fail with an unbounded window.
4. The executor drives the player to an H-marked goal on **both** versions, over flat ground, off a
   drop, and **up a staircase of at least four consecutive ascends** — four because §3.9's cooldown
   is ten ticks and a single ascend cannot expose a throttle, and because a run of ascends is what
   exercises `onGround()`, which §12 now makes load-bearing.
5. **Opening the inventory mid-walk does not stop the player** — rule 4's regression check,
   re-pointed at the executor now that the 40-tick walk is gone. This is the check D1 §15.1 found
   was the only real in-game evidence for level-triggering.
6. **`/tp` mid-walk causes a repath and the bot continues.** This is the criterion that discharges
   dissolved `onPositionCorrection`: if it passes, D1 §3.5's argument holds; if the executor needs to
   be *told* it was moved, that is the finding that reopens the question as "add a mixin and a
   coremod".
7. Turning is visibly gradual rather than instant, on both versions.
8. No standing-invariant notice from the probe on the same terrain.
9. **All seven constants in §9 observed, and every javadoc rewritten with the measured figure.**
10. **D2's mutation, executed rather than reasoned about:** raise `W` past a spiral staircase's
    revolution in a live client and confirm the executor skips the loop. D1 §15.2 established that
    executing one mutation in a client is worth more than any amount of arguing about it, and this is
    the claim in D2 most vulnerable to being merely plausible.

---

## 12. Carried forward, not solved here

- **`covers()` gets its answer, and the answer is no.** D2 does no path revalidation against the live
  world, so its last candidate consumer declines it. Four sub-projects after C3 argued for it, it
  should be **recorded as unused rather than carried forward a fifth time**.
- **`onGround()` acquires its first consumer**, which the first draft of this spec denied. §3.9's
  ten-tick cooldown is why: `ASCEND` conditions `JUMP` on it. D1's merge record predicted D2 would be
  where the two versions' ground flags get their first real comparison, and that prediction is now
  live rather than deferred. **If they disagree about coyote-time or standing on a fence, the visible
  symptom is a missed or throttled jump on a staircase**, which criterion 4 exercises directly.
- **Velocity stays absent from `IPlayerView`, and 1.7.10's `SPRINT` stays unaudited.** Both are
  downstream of parkour, which decision 7 excludes.

Older, unchanged: `SegmentedResult.expanded()`'s unbounded accumulation (§6.4 stops D2 making it
worse but does not fix it); the climb-aware heuristic at its measured 6% lower bound; `powder_snow`,
`sweet_berry_bush`, `bubble_column` and `lily_pad` never audited against source; slipperiness and
fluid height, with `ice` still classifying as `stone`; the probe's unbudgeted render;
`HeuristicMultiplierAdmissibilityTest`'s stale class name.

New from D2:

- **Segment-level overlap** — decision 2 takes the executor-level half and leaves `Run` handing back
  each `PARTIAL` as it lands for later. §7.1 is the structure that makes it a contained change.
- **Parkour execution**, and with it sprint, jump timing, and whatever velocity that needs.
- **Input humanization beyond turning** — §8.3 establishes it is not a press-dropping decorator, and
  says nothing about what it is.

---

## 13. Risks

| Risk | Assessment |
|---|---|
| **Seven extrapolated constants** | Real, and the top risk. Mitigated by every one having a failure mode visible in a client rather than silent, and by §11's criterion 9 making the measurement a done criterion rather than a follow-up. This is the same trade `SLICE_NODES` took and closed in one run |
| The anchor window is wrong for real terrain | `W` is the one constant whose failure is *not* obvious — too large silently skips a loop, which looks like a pathfinding bug. This is exactly why criterion 10 executes it as a mutation rather than trusting §10.2's fixture |
| Facing a block centre and holding `FORWARD` oscillates near the target | Plausible and unmeasured. Bounded by the anchor advancing as soon as the player is nearer the next node, so the worst case is a wobble rather than a stall. Observed in criterion 4 |
| The two versions' `onGround()` flags disagree | Now load-bearing, per §12. Never audited to the depth `y()` was. The symptom is a missed or throttled jump on a staircase rather than anything silent, and criterion 4 walks a staircase on both versions specifically to expose it |
| The module move breaks an adapter | Two adapter edits, and adapters have no tests. Review is the only gate, permanently. Mitigated by the edits being an import and a build file rather than Minecraft-facing code |
| Repath thrash on hostile terrain | `MAX_CONSECUTIVE_REPATHS` bounds it, and the counter resetting on progress stops a long successful route being killed by it. The bound is extrapolated like the rest |
| D2 quietly worsens `expanded()`'s accumulation | Named in §6.4 as a design rule rather than left to an implementer to notice. Asserted in §10.2's `stop()` test by reference |
| The deleted walk demo leaves the smoke checklists stale | §4.4. A documentation edit D2 owes, flagged rather than assumed |

---

## 14. Honest uncertainties

- **Nothing in D2 has run in a client**, and the whole of §9 is arithmetic until it has. The pattern
  from D1 is instructive: warm figures landed on their prediction and cold ran 24% over, so the
  assumption behind the extrapolation was wrong even where the value survived.
- **Resolved before implementation, and it changed the design.** §6.3's ascend rule originally rested
  on the one claim in this spec carrying no citation. It was read from both trees on 2026-09-03 and
  is now §3.9. The claim itself held; the ten-tick cooldown beside it did not appear in the claim at
  all, and it is what moved `JUMP` from held to `onGround()`-conditioned. **D1's post-mortem said
  every one of its five defects was in an uncited claim; this is the first time that lesson was
  applied before an implementer saw the text rather than after.**
- **`PLAN_AHEAD_STEPS` assumes the executor's searches cost roughly what the probe's do.** The probe
  searches with `PARKOUR` and D2 does not, so D2's branching factor is lower and its searches should
  be cheaper — meaning 20 is conservative in the safe direction, but by an unmeasured amount.
- **Whether a bot that stands still during a repath feels broken** is a judgement no test settles.
  §7.2 argues it is safer than the alternative; only criterion 6 shows what it looks like.
- **The spiral-staircase revolution length of 8–12 steps is reasoned, not counted.** If a real
  staircase revolves in six, `W = 6` is already too large.
