# D1 — SPI player state and look actuation design

**Date:** 2026-09-02
**Status:** 🟢 Approved — brainstormed with the owner on 2026-09-02 and approved the same day
**Milestone:** M5 (D), first sub-project of three
**Depends on:** A2b (`2026-08-13-a2b-conformance-testkit-design.md`) — `AdapterRuntime`, the
conformance suite and its reusability limit; B1 (`2026-08-14-b1-block-model-design.md`) — §3.2's
SPI-addition pattern and §6.2's standing adapter audit; C5
(`2026-08-30-c5-time-sliced-search-design.md`) — §7.2's probe-as-consumer rule and the
level-identity lifecycle trigger
**Design input:** the decompiled Minecraft sources on disk for both target versions, read
2026-09-02 — every claim in §3 is cited by file and line
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md)
§3, M5

> **A note on numbering.** This sub-project is called D1 because M5's roadmap letter is D. The
> decisions table in §2 therefore numbers its rows **1–9** rather than using this project's usual
> `D#` decision ids, so that "D1" always means the sub-project and never a decision inside it.

---

## 1. What D1 is, and what it is not

M5 is the milestone where the bot becomes useful: `goto x y z` on both versions. The roadmap
assigns it goals, a process manager, a path executor, per-tick position resync,
`onPositionCorrection` handling, the `IActuator` humanizer seam, the edge- vs level-triggered
actuation decision deferred from M1, and an SPI audit gate.

**That is more than one spec, and the pieces separate cleanly.** M5 is therefore three
sub-projects, agreed with the owner on 2026-09-02:

| | | why it is its own sub-project |
|---|---|---|
| **D1** | The SPI extension | It is the only part that touches adapters, where nothing is testable and review is the sole gate. Designing an SPI change and its first consumer in one breath is what C3 deliberately avoided |
| **D2** | The path executor | Turns a path into per-tick inputs; resync, off-path detection, repath; the humanizer seam. Headlessly testable end to end **because** D1 landed first |
| **D3** | Process manager, goals, `goto` | What the bot is trying to do, overlapped planning, and the roadmap's SPI audit gate. Where M5's done criterion is discharged in game |

### 1.1 Why the SPI has to move at all

Nothing in `dev.continuo.platform` exposes the player. Each adapter reads the position itself and
hands integers to `PathProbe` — `client.player.blockPosition()` on Fabric
(`ContinuoFabricMod.java:156–167`), and floored `posX`/`boundingBox.minY`/`posZ` on Forge
(`ContinuoForgeMod.java:161–163`, `:219–221`). And `IActuator` offers only
`setInput(Input, boolean)` over seven movement keys — with **no way to change where the player is
facing.**

Movement input is interpreted relative to facing on both versions (§3.3). A bot that can press
`FORWARD` but cannot steer walks in whatever direction the user last pointed the mouse. **`goto`
is not merely hard without look control; it is impossible.** This is the largest single gap
between what the SPI offers today and what M5 needs, and neither the roadmap nor C5's handoff
names it.

### 1.2 What D1 does not do

- **No executor, no goals, no process manager.** Those are D2 and D3. D1's only consumer is the
  probe, per C5 §7.2's rule.
- **No new `IGameEvents` method.** §3.5 and decision 6 dissolve `onPositionCorrection` rather than
  deferring it. `IGameEvents`' javadoc does change, in one place: §6.1 removes its forward reference
  to M5 now that rule 4 is settled.
- **No mixin and no coremod.** Neither adapter does bytecode injection today and D1 does not
  start.
- **No humanizer.** Decision 8 moves the seam to D2, where its first caller lives.
- **No new module and no new dependency.**
- **No velocity, no eye height, no fluid state, no bounding-box dimensions.** Decision 3.

---

## 2. Decisions

Every row was decided with the owner during the 2026-09-02 brainstorm.

| # | Decision | Choice | Why |
|---|---|---|---|
| 1 | M5's shape | **Three sub-projects**, D1/D2/D3 | §1. The adapter-touching half has no automated gate and should not share a review with the half that has a complete one |
| 2 | How player state reaches the core | **A new `IPlayerView`, pulled, on `IPlatformContext`** | §4. Reuses `IBlockView`'s proven pattern exactly — same direction, same call window, same cacheable instance. Purely additive; no existing method's signature changes. The rejected alternative pushes state through `onClientTick`, changing an interface both adapters and the conformance suite implement and materialising state every tick whether read or not |
| 3 | The field budget | **Six methods: `x y z yaw pitch onGround`.** No velocity | §4.2. This project's own precedent decides it: C3 added `covers()` speculatively and it still has no consumer two milestones later, flagged in every handoff since. `IPlayerView` has two implementations, both in this repo, and D2 is next — widening it then is mechanical |
| 4 | What `y()` means | **The bottom of the collision box — the feet** | §3.2. The two versions disagree about which field that is, silently, by 1.62 blocks. Defining it once in the SPI is the only place the disagreement can be resolved |
| 5 | Look actuation | **`IActuator.setLook(float yaw, float pitch)`** — absolute, combined, one method, zero new types | §5. `IActuator` already calls itself the single channel through which the core influences the game. Absolute rather than relative because the server overwrites rotation (§3.5) and deltas compound across corrections |
| 6 | `onPositionCorrection` | **Dissolved, not deferred.** No event, no hook | §3.5, §6.2. It has no hook on either side, so it costs a coremod on 1.7.10 and a mixin on 1.21.11 — the most expensive thing proposed for this project — and under decision 7 it buys nothing, because the executor reads true position every tick anyway |
| 7 | Global rule 4 | **Level-triggered.** The core states its full desired input set every tick while driving | §6. Needs no SPI addition where edge-triggering needs a read-back or an event; produces byte-identical network output, so there is no plausibility argument either way; and it makes D2's executor a pure function of `(path, player state) → input set` rather than a state machine |
| 8 | The humanizer seam | **Moved to D2.** Not an SPI change | §6.3. A humanizer belongs on the core side of `IActuator` as a decorator. In an adapter it is per-version, duplicated, untestable, and fails B1 §6.2's audit on sight |
| 9 | D1's first obeying consumer | **`ContinuoCore`'s 40-tick walk is converted to level-triggered** | §7.1. Otherwise D1 ships a contract with nothing obeying it. Converting makes rule 4 assertable headlessly for the first time — 40 calls where there was 1 |

---

## 3. Evidence — read from the decompiled sources, 2026-09-02

Both versions' sources are on disk and were read directly rather than recalled. 1.7.10 at
`adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java`; 1.21.11 at
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/…/sources`. Line numbers below
are from those trees.

### 3.1 What both versions expose

| | 1.7.10 | 1.21.11 |
|---|---|---|
| position | `Entity.posX/posY/posZ`, public fields (`Entity.java:74`) | `Entity.getX()/getY()/getZ()`, public final (`Entity.java:3699/3715/3735`) |
| rotation, read | `rotationYaw`, `rotationPitch`, public (`:86`, `:88`) | `getYRot()`, `getXRot()` (`:3823`, `:3840`) |
| rotation, write | the same public fields | `setYRot(float)`, `setXRot(float)` (`:3832`, `:3844`) — the fields themselves are **private**, and `setXRot` clamps; see §3.6 |
| previous rotation | `prevRotationYaw`, `prevRotationPitch`, public (`:89–90`) | `yRotO`, `xRotO`, public (`:223–224`) |
| ground flag | `onGround`, public (`:93`) | `onGround()` (`:696`) |
| velocity | `motionX/Y/Z`, public (`:80`) | `getDeltaMovement()` (`:3675`) |

**Every field D1 needs is natively public on both versions.** No access transformer, no mixin, no
reflection. The 1.7.10 adapter's existing access transformer (`META-INF/continuo_at.cfg`, widening
`KeyBinding.pressed`) does not grow.

### 3.2 `y()` means different things on the two versions — the sub-project's sharpest hazard

**1.7.10's `Entity.posY` is not the feet.** `setPosition` computes the collision box as

```java
this.boundingBox.setBounds(x - f, y - this.yOffset + this.ySize, z - f, …);   // Entity.java:352
```

with `EntityPlayer.yOffset = 1.62F` (`EntityPlayer.java:170`). So the box's floor sits `yOffset`
below `posY`.

**The game's own network code settles which quantity is which**, so this is not an inference.
`EntityClientPlayerMP` sends its position every tick as

```java
new C03PacketPlayer.C06PacketPlayerPosLook(
    this.posX, this.boundingBox.minY, this.posY, this.posZ, …);   // EntityClientPlayerMP.java:162
```

and `C06PacketPlayerPosLook.writePacketData` (`C03PacketPlayer.java:149–156`) writes those four in
constructor order onto the wire, where 1.7.10's protocol reads them as **X / feetY / stance / Z**.
The client therefore reports `boundingBox.minY` as its feet and `posY` as its stance. `posY` is 1.62
blocks above the ground the player is standing on.

**1.21.11's `getY()` is the feet directly** — `position()` (`Entity.java:3650`) is the bottom-centre
of the collision box, and the modern protocol sends feet Y with no stance field at all.

**This has already been discovered once, empirically, and worked around in adapter code.** The Forge
adapter floors `boundingBox.minY` for the probe's start position (`ContinuoForgeMod.java:162`,
`:220`) while the Fabric adapter floors `blockPosition()`, which is the feet — the two already agree,
deliberately, and nothing anywhere states the rule that makes them agree. **That is the argument for
D1 in miniature:** the knowledge exists, it is duplicated in two untestable files, and it is written
down nowhere a third adapter could read it. Decision 4 moves it into the contract.

**Why this is the whole risk of D1.** An adapter author writing `ForgePlayerView` reaches for the
field whose name matches the method — `posY` for `y()` — and is wrong by 1.62 blocks on exactly one
of the two versions. Nothing in the build catches it. No headless test can reach it, because the
conformance suite cannot instantiate a real adapter (`AdapterUnderTest`'s own javadoc says so). A
pathfinder handed a start position 1.62 blocks too high begins its search inside the player's own
head, in air, and returns `NO_PATH` or a route that starts with a fall — a failure that looks like
a pathfinding bug and is not one. §9.2 exists specifically to catch this.

This is the same class of divergence B1 §4 catalogued for carpet and farmland: the two games
genuinely disagree, and the adapter's job is to report one agreed quantity truthfully, not to
report the field with the matching name.

### 3.3 The yaw convention is bit-for-bit identical across fifteen years

Movement direction derives from yaw and from nothing else, by the same arithmetic on both:

```java
// 1.7.10, Entity.moveFlying (Entity.java:1201–1203)
float f4 = MathHelper.sin(this.rotationYaw * PI / 180F);
float f5 = MathHelper.cos(this.rotationYaw * PI / 180F);
this.motionX += strafe * f5 - forward * f4;
this.motionZ += forward * f5 + strafe * f4;

// 1.21.11, Entity.getInputVector (Entity.java:1637–1639), called from moveRelative with getYRot()
float h = Mth.sin(g * PI / 180F), i = Mth.cos(g * PI / 180F);
return new Vec3(vec32.x * i - vec32.z * h, vec32.y, vec32.z * i + vec32.x * h);
```

With `strafe = 0, forward = 1` both reduce to `Δ = (−sin yaw, cos yaw)`. Therefore, on both
versions:

| yaw | direction |
|---|---|
| `0` | **+Z** |
| `90` | **−X** |
| `180` | **−Z** |
| `270` | **+X** |

Pitch is degrees, `−90` straight up, `0` the horizon, `+90` straight down, on both.

**This is a fact about both games, not a convention D1 invents**, so the SPI can state it as one.
D2 computing `yaw = −atan2(dx, dz)` in degrees to face a target follows from it directly.

### 3.4 What clears held input, and what re-writing it cannot do

Global rule 4's hazard is real and identical in shape on both versions:

- **1.21.11** — `Minecraft.setScreen` calls `KeyMapping.releaseAll()` for **every** non-null screen
  (`Minecraft.java:1148`).
- **1.7.10** — `Minecraft.setIngameNotInFocus()` calls `KeyBinding.unPressAllKeys()`
  (`Minecraft.java:1400`) on any focus loss.

**Re-writing input state every tick cannot fabricate a queued click on either version**, which is
what makes decision 7 safe rather than merely cheap:

- 1.21.11's `KeyMapping.setDown(boolean)` writes only `isDown` (`KeyMapping.java:189–191`).
  `clickCount` is incremented solely by `click()` (`:34`) and drained by `consumeClick()`
  (`:117–121`).
- 1.7.10's `KeyBinding.pressed` and `pressTime` are separate fields (`KeyBinding.java:24–25`), and
  `pressTime` is incremented only in `onTick` (`:36`).

This matters directly rather than theoretically: the walk key and both probe keys are driven off
exactly those click counters. A level-triggered core writing `pressed` forty times a second must not
be able to fire the walk key, and it cannot.

### 3.5 A position correction has no hook on either side

1.7.10's `NetHandlerPlayClient.handlePlayerPosLook` (`:631`) is plain vanilla code:

```java
entityclientplayermp.motionX = entityclientplayermp.motionY = entityclientplayermp.motionZ = 0.0D;
entityclientplayermp.setPositionAndRotation(d0, d1, d2, f, f1);
```

There is no `ForgeEventFactory` call in it or near it, and Forge 1.7.10's entire event surface —
`net/minecraftforge/event/` and `cpw/mods/fml/common/gameevent/` — contains nothing equivalent.
Fabric API exposes no vanilla-packet event on 1.21.11 either; its client networking API covers
custom payloads.

So an `onPositionCorrection` event costs **a coremod on 1.7.10 and a mixin on 1.21.11**. Neither
adapter does bytecode injection today: the Fabric adapter ships no mixin config in
`src/main/resources` or `build.gradle.kts`, and the Forge adapter ships only an access transformer.

Note also what the handler does to rotation: `setPositionAndRotation` overwrites **yaw and pitch**
as well as position. Under decision 7 that is self-healing rather than a special case, because the
core rewrites its desired look every tick.

### 3.6 The rotation *write* paths diverge, and this spec originally got it wrong

Added 2026-09-02, after Task 2's review caught a false claim in §5 that had already reached shipped
javadoc. Recorded here rather than silently corrected, because the correction is more useful than
the original claim was.

**What §5 originally asserted:** *"Neither version clamps on a direct field write."* That is wrong
for 1.21.11, twice over.

| | 1.7.10 | 1.21.11 |
|---|---|---|
| the field | `public float rotationPitch` (`Entity.java:88`) — a direct write is possible | `private float yRot; private float xRot;` (`Entity.java:221–222`) — **there is no direct write path at all** |
| the setter | `setRotation` does `% 360` but an adapter writing the field bypasses it (`:336–340`) | `setXRot` stores `Math.clamp(f % 360.0F, -90.0F, 90.0F)` (`:3844–3849`) — **it clamps** |
| a non-finite value | reaches the field, and turns the player's motion into `NaN` | `setYRot`/`setXRot` **discard it and log** (`:3832–3849`) |

**Two consequences, both of which improve the contract rather than weakening it.**

*An out-of-range pitch is a core bug only one adapter can expose.* Passing pitch `200` on 1.7.10
produces a visibly broken player and sends `200` to the server, which is the loudest signal a
plausibility check can read. The identical call on 1.21.11 is silently corrected to `90`. A core
developing against 1.21.11 would therefore never see the bug it is shipping to 1.7.10 users. That
is a sharper reason for the `[−90, 90]` obligation than "neither version clamps" ever was.

*"Finite" is a real requirement.* The spec asked for it on the reasoning that the movement
arithmetic is periodic, which is true but incidental. The actual reason is that the two versions
handle a non-finite yaw differently and one of them handles it catastrophically.

**This is the B1 carpet-and-farmland pattern again** — the two games genuinely disagree, and the
adapters are left to report their own platform's behaviour truthfully rather than being made to
agree. Nothing in the design changes; what changes is that the divergence is now written down.

**Method note.** The wrong sentence survived a spec self-review, a plan self-review and a
pre-flight scan, and was caught only because Task 2's reviewer was told to verify every Minecraft
claim against the decompiled sources rather than against the implementer's report. Every claim in
§3 is cited to a file and line for exactly this reason; §5's was not, and that is where the error
was.

---

## 4. Design — `IPlayerView`

### 4.1 Shape

A new interface in `dev.continuo.platform`, on `IBlockView`'s exact pattern: **the adapter
implements it and the core calls it**, the same instance for the adapter's lifetime, cacheable by
the core, and reachable through a fourth `IPlatformContext` accessor.

```java
public interface IPlayerView {
    double x();
    double y();
    double z();
    float yaw();
    float pitch();
    boolean onGround();
}
```

`IPlatformContext` gains `IPlayerView player()`. Its class javadoc's "All three accessors MUST NOT
return `null`" becomes four.

### 4.2 The field budget, and what is deliberately absent

Six methods, chosen against B1 §6.2's rule — the adapter reports facts, the core makes judgements —
and against this project's own experience of speculative surface.

| method | why it is in |
|---|---|
| `x() y() z()` | the position resync has nothing to reconcile without it |
| `yaw() pitch()` | you cannot compute how far to turn without knowing where you are pointed |
| `onGround()` | jump timing, fall detection, and "have I landed yet" |

**Velocity is excluded**, though §3.1 shows both versions expose it natively and Baritone uses it.
The reason is precedent, not doubt about its usefulness: **C3 added `covers()` on exactly this kind
of anticipation and it still has no consumer two milestones later**, carried in every handoff since
and named in C5 §10. `IPlayerView` has two implementations, both in this repo, and D2 is the very
next sub-project — if D2 needs velocity it adds three methods to two files. Adding them now on the
argument that D2 *might* is how `covers()` happened.

Excluded on the same grounds: `isInWater()`, eye height, bounding-box dimensions, and
sneaking/sprinting state.

### 4.3 The contract, and the four clauses that carry weight

1. **Call window.** Identical to `IBlockView`'s, and stated by reference to it rather than restated:
   methods may be called only while `IGameEvents.onClientTick`'s delivery window is open — a world
   loaded and a local player present. Outside it the behaviour is unspecified. Reusing the existing
   condition means there is nothing extra for an adapter to evaluate or get wrong.

2. **`y()` is the bottom of the collision box.** Not "the player's Y", which is ambiguous and
   resolves differently on the two versions (§3.2). The javadoc must carry the 1.7.10 consequence
   explicitly — *an implementation on 1.7.10 MUST return `boundingBox.minY`, not `posY`* — because
   that sentence is the only place in the whole system where the divergence can be caught by
   reading.

3. **`yaw()` and `pitch()` are degrees in the convention of §3.3**, stated as a fact about both
   games. `yaw()` is **not normalised**: it returns whatever the platform currently holds, which may
   be any finite value including one outside `[−180, 180)`. A core needing a normalised angle
   normalises it. The alternative — requiring the adapter to normalise — is arithmetic in an adapter
   and fails the audit for no benefit.

4. **Values describe the current tick and MUST NOT be cached across ticks.** A core that wants last
   tick's position stores three doubles itself. Stated so that the discontinuity reasoning in §6.2
   rests on something the contract actually guarantees.

There is no null to guard: every method returns a primitive.

---

## 5. Design — `setLook`

One method on `IActuator`. **Zero new types.**

```java
void setLook(float yaw, float pitch);
```

- **Absolute, not relative.** The core computes the angle it wants from `IPlayerView.yaw()` and the
  target; deltas compound rounding across ticks and go wrong the moment the server rewrites
  rotation, which §3.5 shows it does.
- **Combined rather than `setYaw`/`setPitch`.** A steering-only caller passes `IPlayerView.pitch()`
  straight back. One method, one atomic write, matching how both games apply rotation.
- **Pitch MUST be in `[−90, 90]`; behaviour outside is unspecified.** This mirrors how `setInput`
  treats `null` — an obligation on the core, not a clamp in the adapter, because a clamp is a
  judgement. **It is unspecified rather than defined because the two versions genuinely differ**,
  which §3.6 establishes: 1.7.10 writes an out-of-range pitch straight through to the renderer and
  the server, while 1.21.11 silently clamps it. So an out-of-range pitch is a core-side bug that
  **only one adapter can ever expose** — loud on one version, invisible on the other. That
  asymmetry is the argument for the obligation, and it is stronger than the one this spec
  originally gave.
- **Yaw may be any finite value.** Both games' movement arithmetic is periodic in yaw (§3.3), so
  requiring normalisation would buy nothing. **Finite is load-bearing, not a formality** — §3.6.
- **No round-trip guarantee.** `yaw()` is not required to return the last value passed here. The
  user's mouse writes it, and so does the server. This is global rule 4's principle applied to
  rotation instead of key state, and stating it now stops any core from ever assuming otherwise.
- **Timing matches `setInput`:** takes effect at the game's next input read, therefore on the
  current tick when called from `TickPhase.PRE`.
- **One adapter obligation, stated so the two cannot diverge on it.** An adapter MUST also write the
  corresponding previous-rotation field — `prevRotationYaw`/`prevRotationPitch` on 1.7.10,
  `yRotO`/`xRotO` on 1.21.11 — so the local camera does not interpolate visibly across the change.
  1.7.10's own `setPositionAndRotation` does exactly this: `this.prevRotationYaw = this.rotationYaw
  = yaw` (`Entity.java:1262–1263`). The need arises because the game snapshots the previous rotation
  once per tick in `onUpdate` (`Entity.java:404–405`), so a write landing after that snapshot leaves
  the renderer interpolating from a stale angle. Cosmetic rather than behavioural, and pure
  translation, but an obligation rather than a preference because one adapter doing it and the other
  not is precisely the cross-version divergence this contract exists to stop.

---

## 6. Design — global rule 4, resolved

### 6.1 The new rule

Rule 4 is today a hazard statement that explicitly declines to resolve itself and points at M5. It
becomes an obligation on the core alone:

> **While it is driving, the core MUST state its full desired input set every tick.** It does not
> track what it has already pressed and does not rely on any previous `setInput` call persisting.
>
> **While it is idle, the core writes nothing at all.** It MUST NOT hold every input at `false` each
> tick; that would fight the user's own keyboard whenever the bot is not running.
>
> **On `stop()` the core releases what it holds, once.**
>
> **Adapters owe nothing new.** `setInput` remains idempotent and remains documented as clearable at
> any time. No adapter is required to re-assert anything.

**The keywords are load-bearing, and this spec originally omitted them.** The package's own preamble
says MUST, MUST NOT and MAY carry their RFC 2119 meanings, and rule 4's central clause — the one
this whole sub-project exists to establish — was drafted here in the declarative. Task 3's review
caught it in the shipped javadoc; the omission was the spec's. The clause is normative and now says
so. The related hazard sentence in `IActuator.setInput` had the same problem in a worse place: it
told a reader that the SPI requires "neither side" to re-assert, which after this rule is true only
of adapters, and `setInput` is the method a core author actually reads.

`IActuator`'s deferral paragraph (`IActuator.java:21–27`) and `IGameEvents`' M5 note
(`IGameEvents.java:32–34`) both lose their forward references. The conformance suite's "Rules with
no cases, and why" section (`AdapterConformanceTest.java:26–28`) loses its rule 4 entry — the rule
still generates no *adapter* obligation, but its reason changes from "the SPI declines to require
anything" to "the obligation binds the core", and the suite tests adapters.

### 6.2 Why level, and what it makes unnecessary

- **It needs no SPI addition.** Edge-triggering requires the core to learn when the platform cleared
  its state, which means either an `isPressed(Input)` read-back on `IActuator` or a new event. Level
  needs neither. The worst case after a screen opens is one lost tick, self-healing.
- **There is no plausibility argument on either side.** Re-asserting is a field write inside the
  client; the server sees only the resulting movement packets. The two models produce **byte-
  identical network output**.
- **It costs seven field writes per tick.** 140 a second, against a measured 88 ns per snapshot
  read. It does not register.
- **The decisive argument belongs to D2.** Level-triggering makes the executor a pure function of
  `(path, player state) → input set`, recomputed each tick, instead of a state machine issuing
  commands and remembering what it has already sent. That is the difference between an executor
  that is trivially testable headlessly and one that is not, and D2 is where all the real logic
  lands.
- **It dissolves `onPositionCorrection` (decision 6).** An executor that recomputes from
  `IPlayerView` every tick does not need to be *told* it was teleported; it reads where it actually
  is and steers from there. Rotation corrections self-heal the same way, because `setLook` is
  written every tick too. The one thing an event would add is the ability to distinguish "the server
  moved me" from "I walked there" — and D2 needs an off-path check regardless, which subsumes every
  use of that distinction found so far: teleport, death respawn, knockback, rubber-band. Dimension
  changes are already covered by rule 2's level-identity trigger.
- **What would reopen it.** A consumer in D2 or D3 that genuinely needs the distinction and cannot
  get it from the off-path check. It reopens as "add a mixin and a coremod", which is a decision that
  should be made against a real consumer rather than in advance.

### 6.3 The humanizer seam is not here

The roadmap says "*`IActuator` gains its humanizer seam here (no-op by default)*", which reads as an
SPI change. It is not one. A humanizer belongs on the **core** side of `IActuator`: a class
implementing `IActuator`, wrapping the adapter's, mangling timing and rotation, and delegating. In
an adapter it is per-version, duplicated, untestable, and fails B1 §6.2's audit immediately — logic
in an adapter. In the core it is plain Java with no Minecraft on the classpath.

Either way `IActuator`'s *interface* does not change, so the seam is not D1's. It moves to **D2**,
where the executor — its first and only caller — lives, and where the choke point it wraps is a real
class with a real consumer rather than a no-op waiting for one. Same milestone; the roadmap is
satisfied.

**Worth recording:** decision 7 is what makes the decorator shape work at all. Plausible aiming means
turning toward a target over several ticks, which a decorator can only do if something calls it every
tick. Level-triggering guarantees that. Under edge-triggering a humanizer would have needed its own
tick source.

---

## 7. Design — the core and the probe

### 7.1 `ContinuoCore` becomes level-triggered

The only core that exists sets `FORWARD` once at tick 1 and releases it at tick 41
(`ContinuoCore.java:82–89`), assuming forty ticks of persistence — the exact assumption §3.4 shows
both versions break. It becomes:

```java
if (phase != TickPhase.PRE || !walking) {
    return;                                   // idle: write nothing (§6.1)
}
tick++;
if (tick > WALK_TICKS) {
    context.actuator().setInput(Input.FORWARD, false);
    walking = false;
    tick = 0;
    return;
}
context.actuator().setInput(Input.FORWARD, true);
```

Behaviour is unchanged — `FORWARD` is held across ticks 1–40 and released on tick 41, so travel is
the same 40 ticks the M1 and M2 smoke checklists measured at ≈8 blocks. **What changes is that it
now issues 40 presses and one release instead of one and one**, which is the first thing in the
project to obey rule 4 and the first thing that can assert it (§10.1).

### 7.2 The probe is D1's consumer

C5 §7.2's rule: an SPI addition nothing drives is a guess encoded as design. `PathProbe` gains four
jobs, all cheap, all on paths the owner already presses several times a session:

1. **Its search start comes from `IPlayerView`**, not from the integers the adapter passes in. This
   makes the new interface load-bearing rather than decorative, and it is what turns §3.2's hazard
   into something a press can expose.
2. **It reports player state** in the summary line — position to two decimals, yaw, pitch,
   `onGround`.
3. **It runs the two self-checks in §9.2**, reporting a divergence notice rather than passing
   silently, in the same style as C5's seal-and-replay comparison.
4. **It faces the marked goal** when the path key is pressed, which is `setLook`'s entire in-game
   done criterion and takes one call.

**The adapter's per-tick call order is load-bearing, and this spec did not say so.** Discovered in
Task 5's review, recorded here because it is the difference between §9.2's check 2 working and
being vacuous. Both adapters must call the probe's `advance` **before** the block that may `start`
a new run:

> `onLevel` → poll both keys → null-player check → `if (mark)` → advance-and-report → `if (path)`
> start

With `start` first, the look written by `setLook` is read back by `advance` a few statements later
in the same tick handler, so the deferred read is not deferred at all. It still catches a
wrong-field write or a wrong-field read — both halves touch real fields — but it cannot catch the
failure the deferral exists for: **a rotation write that lands and then does not survive the
tick**, which is live on 1.21.11, where the write happens at `END_CLIENT_TICK` and the game
re-applies rotation during the next tick's input phase. It also makes §9.2's "no notice on a tick
where the mouse was still" close to vacuous, since a mouse cannot move between two adjacent
statements. Neither key poll may move: `consumeClick` and `isPressed` drain a queued press as a
side effect. The cost of the correct order is that a run's first slice lands one tick later.

Each adapter's own position read for the probe's start — `blockPosition()` on Fabric, floored
`posX`/`boundingBox.minY`/`posZ` on Forge — is removed, which is what makes §3.2's duplicated
knowledge collapse into the one place decision 4 puts it. The block-dump key keeps its own read; it
is a separate feature and out of scope.

---

## 8. Design — the adapters

Four new files and two edited ones, symmetric across the pair.

**`FabricPlayerView`** — `getX()`, `getY()`, `getZ()`, `getYRot()`, `getXRot()`, `onGround()` on
`minecraft.player`.

**`ForgePlayerView`** — `posX`, **`boundingBox.minY`**, `posZ`, `rotationYaw`, `rotationPitch`,
`onGround` on `minecraft.thePlayer`. **The `boundingBox.minY` line is the single riskiest line in
the sub-project** (§3.2) and must carry a comment saying why it is not `posY`.

**Both actuators gain `setLook`** — write the rotation field and its prev-field (§5).

**Both platform contexts gain `player()`**, returning the same instance every call, exactly as
`blocks()` does.

**Audit expectation, per B1 §6.2.** Both `IPlayerView` implementations should contain **zero
conditionals** beyond a null-player guard: every method is a direct field or accessor read. If either
grows an `if` that decides something rather than resolving a name or guarding a null, the audit has
failed and the logic belongs in the core.

---

## 9. Verification

### 9.1 What can be verified headlessly, and what cannot

The conformance suite drives `AdapterRuntime` through fakes and **cannot instantiate a real
adapter** — `AdapterUnderTest`'s javadoc states this as its reusability boundary. So D1's new
adapter surface has no automated gate, exactly as C5's adapter edits had none. Review is the gate,
and every Minecraft API claim in §3 is cited to a file and line so that review has something to check
against rather than to trust.

`FakePlayerView` joins `FakePlatformContext` so the fake context is complete. That is plumbing and
proves nothing on its own; the substantive headless work is §10.1.

### 9.2 In-game, self-checking

Both checks report a **divergence notice** rather than asserting, because both have legitimate
failure cases. This is C5's method: a divergence is reported rather than silently passing.

**Check 1 — the standing invariant.** When `onGround()` is true, read the block at
`(floor(x), floor(y) − 1, floor(z))`. It should not be passable.

This is the check that catches §3.2. Under a correct `y()` the sampled block is the ground the
player is standing on. Under the 1.62 bug it is roughly a block above the player's head — and
anywhere a player can stand has headroom, so it is air essentially always. **The asymmetry is what
makes the check worth having:** correct behaviour fails it only in a short list of odd cases
(standing on a slab or stair edge, on a fence post, straddling a block boundary, in a boat, on a
ladder, in fluid), and the bug fails it every time. A notice on plain flat ground is a real defect.

**Check 2 — the look round trip.** After `setLook(yaw, pitch)`, the next tick's `IPlayerView.yaw()`
should agree, modulo wrapping. The contract does not guarantee this (§5), because the mouse and the
server also write rotation — so it is a notice, not an assertion. But on a tick where nobody touched
the mouse, a mismatch means one of the two new SPI halves is wrong, and **the two halves checking
each other is the only automated-ish leverage D1 has on adapter code.**

**Yaw only, corrected 2026-09-02 after the whole-branch review.** This section originally said "and
`pitch()`". Checking pitch would prove nothing: the probe passes `IPlayerView.pitch()` straight back
into `setLook` rather than choosing a pitch, so a pitch round trip compares a value to itself and
holds even for an adapter that writes yaw and silently drops pitch — the exact failure it would
appear to be testing. The shipped code checks yaw alone and is right; this is the spec catching up
to it. **A pitch check would need the probe to ask for a pitch of its own**, which nothing yet has a
reason to do.

**And the order in which the adapter calls the probe is what makes this check real at all** — see
§7.2. With `start` called before `advance` in the same tick handler, the "next tick's" read happens
a few statements after the write and the check degenerates into a same-tick read-back.

### 9.3 In-game, by eye

- Pressing the path key **turns the player to face the marked goal**, on both versions. This is
  `setLook`'s done criterion and needs no instrumentation.
- The 40-tick walk still travels ≈8 blocks on both versions — the existing M1/M2 smoke checklist
  item, re-run because §7.1 changed the code under it.
- Reported position matches the F3 readout on both versions.

---

## 10. Testing

### 10.1 Headless, and TDD applies in full

| test | pins |
|---|---|
| `ContinuoCore` issues `setInput(FORWARD, true)` on **every** tick 1–40, not once | decision 9 and rule 4; this is the assertion the whole rule reduces to |
| `ContinuoCore` issues exactly one `setInput(FORWARD, false)`, on tick 41 | the release edge survives the conversion |
| `ContinuoCore` writes **nothing** while idle — before `requestWalk`, and after the walk ends | §6.1's second clause, the one that stops the core fighting the user's keyboard |
| `stop()` mid-walk releases `FORWARD` and writes nothing after | unchanged behaviour, re-pinned under the new shape |
| travel is still 40 held ticks | guards against the conversion changing the walk's length |
| `FakePlatformContext.player()` returns the same instance on every call | `IPlatformContext`'s same-instance clause, extended to the fourth accessor |

**Guard the walk-length test against vacuity**: assert the tick count is 40 and non-zero *before*
comparing anything, per the standing rule that three tests in C5 passed while proving nothing.

### 10.2 Mutations to execute at review — hypotheses, not predictions

Recorded as hypotheses on purpose: predicted kills were wrong in 3 of 6 in C4 and 3 of 7 in C5, and
a surviving mutant is a finding requiring one of three different responses (unpinned guarantee,
equivalent mutant, or correct-as-is).

1. `ContinuoCore` presses `FORWARD` only on tick 1 again — reverts decision 9. Expected kill:
   the every-tick test.
2. `ContinuoCore` writes `setInput(FORWARD, false)` every tick while idle. Expected kill: the
   idle-silence test. **This is the mutant most likely to survive**, because nothing before D1
   asserted silence.
3. `ContinuoCore` releases on tick 40 rather than 41. Expected kill: the walk-length test — if that
   test was written against tick counts rather than call counts.
4. `ForgePlayerView.y()` returns `posY`. **No headless test can kill this**, by construction; §9.2's
   check 1 is the only thing that can, and only in game. Executing this mutation is how the review
   confirms the in-game check actually works rather than assuming it.
5. `setLook` writes the rotation field but not the prev-field. Expected: survives everything. It is
   cosmetic, and no test should be invented to catch it — recorded so the survivor is understood
   rather than investigated.
6. `FabricPlayerView.yaw()` returns `getXRot()` and `pitch()` returns `getYRot()` — transposed.
   Expected kill: §9.2's check 2, in game only.

---

## 11. Done criteria

1. `IPlayerView`, `IPlatformContext.player()` and `IActuator.setLook` exist, with the contracts in
   §4.3 and §5 written into their javadoc — including the 1.7.10 `boundingBox.minY` sentence and the
   §3.3 yaw table.
2. Global rule 4 is rewritten per §6.1; `IActuator`'s and `IGameEvents`' forward references to M5 are
   gone; the conformance suite's rule 4 note is updated.
3. `ContinuoCore` is level-triggered and §10.1's tests pass.
4. Both adapters implement `IPlayerView` and `setLook`; both audit at zero conditionals beyond a null
   guard.
5. `PathProbe` takes its start position from `IPlayerView`, reports player state, and runs §9.2's two
   checks.
6. `./gradlew build --rerun-tasks` is green, with the test count recorded before and after. Gate on
   `build`, never `:test` — javadoc is build-failing and `:test` does not run it.
7. **In game, on both versions:** the path key turns the player to face the marked goal; no standing
   invariant notice on flat ground; no look round-trip notice; the 40-tick walk still travels ≈8
   blocks; reported position matches F3.

Criterion 7 is the only evidence that exists for §8, and it cannot be met headlessly.

---

## 12. Carried forward, not solved here

- **Velocity**, excluded by decision 3 until a consumer needs it. D2 is the first candidate.
- **The humanizer seam**, moved to D2 by decision 8.
- **`onPositionCorrection`**, dissolved by decision 6. §6.2 records exactly what would reopen it.
- **The SPI audit gate** the roadmap places inside M5 is milestone-wide and belongs to D3. §8's
  expectation is D1's local slice of it.
- **`SPRINT` on 1.7.10 is unaudited.** `GameSettings.keyBindSprint` exists (`:128`, keycode 29) and
  the SPI already requires every `Input` constant to be supported, but whether holding it actually
  sprints on 1.7.10 — where sprinting is conventionally double-tap-forward — has never been checked.
  Pre-existing, not D1's, and D2's first sprint is when it matters.
- **`covers()` still has no consumer**, three sub-projects after C3 argued for it. D2's executor is
  now its last candidate.
- **The probe's render is still budgeted by nothing** — 262,144 reads worst case. Untouched again.
- **`SegmentedResult.expanded()`** still accumulates unbounded across segments, carried from C4.
- **The climb-aware heuristic**, parked at a measured 6% lower bound (C3 §2.3, C4 §11).
- **`SLICE_NODES = 2000` is still extrapolated, not measured.** One probe press settles it; the owner
  has deferred it deliberately.

---

## 13. Risks

| Risk | Assessment |
|---|---|
| `ForgePlayerView.y()` ships as `posY` | **The one that can ship silently**, and the reason §9.2 exists. Review plus a comment on the line is not enough on its own — the standing invariant is what actually catches it, and mutation 4 is what proves the invariant works. Mitigated in part by there being working precedent to copy in the same repo (`ContinuoForgeMod.java:162`), which is also the thing D1 exists to stop relying on |
| Level-triggering fights the user's keyboard | Mitigated by §6.1's write-nothing-when-idle clause, which is a rule with a test rather than a convention. Real if that clause is dropped |
| Level-triggering fires a keybind by accident | **Measured against source and ruled out** — §3.4. `setDown` and `pressed` are disjoint from the click counters on both versions |
| `setLook` fights the user's mouse while the bot drives | Real and accepted; Baritone does the same. It only applies while driving, by the same clause |
| The two adapters diverge on the prev-rotation write | Mitigated by making it a stated obligation in §5 rather than an implementation preference. Cosmetic if it happens |
| The SPI grows a fourth accessor and a seventh actuator method, and keeps growing | Named in the package javadoc's own terms — every type added is a future version-compatibility problem. Decision 3 is the discipline: one new type, six methods, one method on an existing type, nothing speculative |
| An adapter reads a null player inside the call window | The window's own definition excludes it (world loaded **and** local player present), and it is `IBlockView`'s existing position. A null guard is translation, not logic |

---

## 14. Honest uncertainties

- **§9.2's check 1 has never been run.** Its value rests on the claim that anywhere a player can
  stand has headroom, so the 1.62-offset sample is air. That is a strong argument, not a measurement.
  Mutation 4 is what converts it into one, and it must actually be executed in a client rather than
  reasoned about.
- **The list of legitimate check-1 failures is a first draft.** Slabs, stairs, fences, boats, ladders
  and fluid are the cases found by thinking about it. In-game use will find more, and the right
  response is to extend the list rather than to weaken the check.
- **Whether `onGround()` means the same thing on both versions has not been audited to the depth
  §3.2 gave `y()`.** Both expose a native flag and both set it in their own move code, which is why
  it is in the budget as a reported fact. If D2 finds the two disagree about coyote-time or about
  standing on a fence, that is a per-version note like B1's carpet and farmland rows, not a
  classifier change.
- **No estimate is offered for how much of D2 this makes easy.** Decision 7's claim that the executor
  becomes a pure function is a design argument. It will be true or false when D2 writes one.
