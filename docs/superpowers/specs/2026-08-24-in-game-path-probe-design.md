# In-game path probe design

**Date:** 2026-08-24
**Status:** 🟡 Awaiting owner review — brainstormed with the owner on 2026-08-24
**Milestone:** An interlude between C2 and C3, not a sub-project of either
**Depends on:** C1 (`2026-08-15-c1-pathfinder-core-design.md`) and C2 (`2026-08-17-c2-movement-registry-design.md`) — the pathfinder and the registry are what this exists to look at
**Design input:** `2026-08-01-mc-automation-roadmap-design.md` §3, "Ordering caveat"
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M4

---

## 1. Scope

Three sub-projects — B1, C1 and C2 — have shipped since anything last ran in a
running game. Each was pure and headless by design, and each shipped with an
explicitly empty diff against `platform/` and `adapters/`. A block model, a
pathfinder and a movement registry now sit behind a boundary that no in-game
execution has ever crossed.

The roadmap anticipated a version of this. Its ordering caveat says bridge and UI
sit at M6–M7 *"on the bet that the M4 test renderer covers debugging needs through
M5"*, and that *"if pathfinding proves hard to diagnose in-game, pulling E forward
is the correct response."* The real situation is simpler than that trigger
describes: there is no diagnosis channel to be hard, because there is none at all.

This builds the smallest thing that answers *does any of this work against a real
world*, using machinery that already exists. It is not a milestone and does not
displace one.

### 1.1 In scope

A `PathProbe` in `:runtime` that runs A\* against the live world through a
`BlockSource` and reports the result; the promotion of C1's text-art renderer from
test scope into `:core-pathfinder`'s published API, generalized from a fixture
world to any `BlockSource`; two keybinds per adapter to mark a goal and run the
search; and headless tests over the probe in `:runtime`.

### 1.2 Explicitly not in scope

| Deferred to | What |
|---|---|
| D / M5 | Any movement execution. Nothing here presses a key or moves the player |
| The deferred adapter batch | Any SPI addition — no look control, no `IPlayerState`, no raycast, no chat send |
| C3 | `WorldSnapshot` and off-thread reads. §5.4 explains why this does not need them |
| E / M6 | Any external client, socket, or protocol |
| C4 | Any real search-effort policy. §5.2's budget is a stall guard, not a policy |

This adds **no SPI type**, the same promise C1 made as its D3 and C2 repeated. The
diff against `platform/` is empty. The diff against `adapters/` is not, for the
first time since M2, and §6 keeps it to keybind registration and a method call.

---

## 2. Decisions

Every row was decided with the owner during the 2026-08-24 brainstorm.

| # | Decision | Rejected alternative |
|---|---|---|
| P1 | The probe reports a route; it does not walk one | Executing the path, which needs look control in the SPI and both untestable adapters |
| P2 | The goal is marked by keybind at the player's feet, and the search runs from wherever the player then stands | A fixed offset from the player; a crosshair raycast; a `goto x y z` chat command |
| P3 | Output is a one-line verdict through `RuntimeLog` **and** the full text-art map to a file | The map alone; the verdict alone; extending `BlockDumpWalker`'s flat per-position format |
| P4 | The probe is kept in `:runtime` and tested headlessly | A throwaway probe; or kept but untested as dev-only tooling |
| P5 | `PathRenderer` gains a public `BlockSource` form in main sources; the `FixtureWorld` form stays in test sources as a delegate | Promoting the class wholesale with its fixture signature; or a second renderer owned by `:runtime` |
| P6 | The probe lives in `:runtime`, and `:runtime` gains `:core-movement` and `:core-pathfinder` | Putting the probe in the adapters, which have no tests and cannot get any |

**P5's reasoning, which is the load-bearing one.** C1's renderer emits the same
text-art format its fixtures are *parsed* from, and that round-trip is the whole
prize. If the in-game harness emits that format, an in-game pathfinding failure
becomes a checked-in headless regression fixture by copy-paste. That converts this
from a one-time probe into a standing pipeline from real-world failures into the
test suite — which is a far better answer to the accumulated-unverified-work
complaint than looking at a map once. A separate renderer in `:runtime` would have
cleaner module boundaries and would throw that property away.

Publishing the whole class would drag `FixtureWorld` and the fixture-parsing half
of `FixtureBlocks` into main API. They are test scaffolding and should stay test
scaffolding, so only the `BlockSource` form and the legend are published.

**P1's reasoning.** Rendering a route needs no SPI change at all. Walking one needs
yaw control, which `IActuator` does not have — it exposes only
`setInput(Input, boolean)` over `FORWARD/BACK/LEFT/RIGHT/JUMP/SNEAK`. Adding it
means editing both adapters, which have no tests and cannot get any, and it is
exactly the batch C1 (slipperiness, fluid heights) and C2 (`IPlayerState`,
`worldCapabilities()`) have each deferred once. The cost gap between *see the path*
and *watch it walk* is therefore much wider than it first appears, and the cheap
half answers the question that is actually open.

---

## 3. Module layout

| Module | Change | What |
|---|---|---|
| `:platform` | **none** | — |
| `:core` | **none** | — |
| `:core-movement` | **none** | — |
| `:core-pathfinder` | modified | `PathRenderer` moves to main sources and gains the `BlockSource` form; the legend moves with it as a public `BlockLegend` |
| `:runtime` | modified | New `PathProbe` and `ProbeReport`; two new production dependencies |
| `:movement-parkour` | **none** | — |
| Both adapters | modified | Two keybinds and one call each |

The root `build.gradle.kts` entry becomes:

```kotlin
":runtime" to setOf(":platform", ":core", ":core-movement", ":core-pathfinder",
                    ":movement-parkour")
```

`:core-movement` is listed because the probe names `CapabilitySet` and `Capability`
directly, not only through `:core-pathfinder`. Omitting it would compile through
the transitive path and misdescribe the direction the check exists to pin.

**`:movement-parkour` is a `runtimeOnly` dependency, and without it the probe is
quietly pointless.** The adapters depend on `:platform`, `:core` and `:runtime` —
never on the parkour module — so nothing puts it on the game's classpath.
`MovementRegistry.discover()` would find no parkour movement, and the probe would
request `Capability.PARKOUR` from a registry that has nothing to grant it: every
search would silently run the four built-ins, and §5.3's stated reason for enabling
the capability at all would be false. Declaring it `runtimeOnly` on `:runtime` puts
it on the game classpath transitively, and on `:runtime`'s own test runtime
classpath, without giving anything a compile-time path to it. `runtimeOnly` is one
of the configurations `checkDependencyDirection` inspects, so the allowlist entry is
required rather than optional.

This does not weaken C2's seam. The seam is that `:movement-parkour` must not
compile against `:core-pathfinder`, and it still does not. A consumer loading a
movement plugin at runtime is the seam being used as designed.

**This does not break M4's purity promise, contrary to the obvious reading.** The
roadmap's *"pure, headless, no Minecraft anywhere"* binds the core. `:runtime` is
pure Java against the SPI, has tests, and contains no Minecraft type — Minecraft
still enters only at the adapters, which do nothing but register a key and call a
method. The pathfinder's first production consumer is a headless, tested module.

---

## 4. The renderer, generalized

### 4.1 The published form

`PathRenderer` moves from `core-pathfinder/src/test/java/` to
`core-pathfinder/src/main/java/`, becomes public, and gains:

```java
public static String render(BlockSource world,
                            int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ,
                            Pos start, Pos goal, PathResult result)
```

Six bounds `int`s rather than a new value type, matching `BlockDumpWalker.dump`'s
existing shape in the module that will call this.

**All six bounds are inclusive, and `maxY` is the one to watch.** The existing
implementation loops `y < world.maxY()` because `BlockSource.maxY()` is defined as
*one past* the highest Y, while it loops `x <= world.maxX()` because `FixtureWorld`'s
own X and Z bounds are inclusive. The published form must not inherit that split —
a caller reading the signature has no way to guess that one of six parameters means
something different from the other five. The `FixtureWorld` delegate therefore
passes `world.maxY() - 1`, and a test pins that the two forms render identically.

`start` and `goal` become explicit parameters because a `BlockSource` has no
`start()` and `goal()` the way `FixtureWorld` does. The existing implementation
already needs them only for the failed-search case, where the path is empty and
there is nothing else to mark from — so this makes an existing dependency visible
rather than adding one.

### 4.2 What stays in test scope

`FixtureWorld`, `FixtureBlocks`' character-to-block parsing, and the existing
`render(FixtureWorld, PathResult)` signature all stay in
`core-pathfinder/src/test/java/`. The latter becomes a one-line delegate that
supplies the fixture's own bounds and markers. Every existing renderer test keeps
calling it unchanged.

The legend itself moves to main as a public `BlockLegend` in `:core-pathfinder`,
holding the canonical character-to-`BlockData` mapping in both directions.
`FixtureBlocks` stays in test sources and delegates to it for parsing.

**One mapping, not two, and that is structural rather than tidy.** The round-trip
in P5 holds only if the characters the renderer writes are the characters the
fixture parser reads. Two legends that happen to agree today would drift the first
time either side gained a block shape, and the drift would be silent — a rendered
map would simply re-parse as different terrain, and the pasted fixture would pose a
different routing question than the one that was captured. Sharing one definition
removes that failure mode by construction rather than by a test that has to
remember to check.

### 4.3 How it degrades against a live world, stated rather than implied

The renderer maps blocks to characters by exact-equality lookup against a legend of
eleven canonical `BlockData` values. A live world produces `BlockData` from real
block states, so the match is partial and the failure is silent-looking. This must
be written down where a reader of the output will find it.

Ordinary terrain matches: stone, dirt and leaves all classify to `FULL` with a
collision top of `1.0` and no tags, which is the legend's `STONE`; air matches;
slabs, stairs, fences, water and lava match; farmland's `0.9375` matches
`PARTIAL_FLOOR`. What does not match renders as `?`.

**`?` re-parses as `UNKNOWN`, which is impassable.** So a map pasted back as a
fixture can be *stricter* than the world it came from: a `?` that was really a
passable block becomes a wall, and the fixture may then fail to reproduce the
routing question it was captured for. A pasted map with `?` anywhere near the route
needs checking before it is trusted as a regression fixture. This is a real limit
and the class javadoc must say so — it is the same class of trap as the overlay
round-trip limit the existing javadoc already documents.

---

## 5. The probe

### 5.1 Shape

`PathProbe` is an instance rather than a static utility, because mark-then-run is a
small state machine and the point of P4 is that it gets tested:

```java
public final class PathProbe {
    public PathProbe();
    public void markGoal(int x, int y, int z);
    public ProbeReport run(BlockSource world, int startX, int startY, int startZ);
}
```

`ProbeReport` carries `summary()` — the one line that goes to the log — and
`map()`, the text art that goes to the file.

Running with no goal marked returns a report saying exactly that. It does not
throw: the caller is inside the game loop, and global rule 3 makes a throw from
there an adapter fault.

### 5.2 The node budget

The probe passes its own budget, well below `AStarPathfinder.DEFAULT_NODE_BUDGET`'s
100,000. That default was chosen as *"far above anything a fixture world can need
and far below anything that would hang a test"* — a test, not a game. 100,000
expansions against a live `IBlockView` on the client thread is a multi-second
freeze of the running game.

The probe's constant starts at **10,000** with that reasoning recorded on it. It is
a stall guard, not a search-effort policy; C4 owns the policy and this must not
pretend to.

`BUDGET_EXCEEDED` is a first-class outcome here, not a failure of the probe. It is
reported plainly and the map is still written — see §5.5.

### 5.3 Capabilities

The search runs with `CapabilitySet.of(Capability.PARKOUR)`. Parkour is the
movement with the thinnest evidence behind it and the entire reason C2 exists, so
the probe should be exercising it rather than avoiding it.

This is only true if the parkour module is actually on the classpath — see §3. A
probe that requests a capability nothing supplies looks identical to one that
exercises it, which is the same "reads as a pass, checked nothing" failure shape
`MovementContract` was fixed twice to avoid. A `:runtime` test asserts that
`AStarPathfinder.defaultRegistry()` discovers `walk.parkour`, so the classpath
wiring cannot silently regress.

### 5.4 Threading, and why C3 is not a prerequisite

Everything runs synchronously inside the tick window, on the main thread. C3's
`WorldSnapshot` is what makes world reads safe **off**-thread; nothing here goes
off-thread, so C3 buys this nothing.

That is the fact that makes this possible now, and it is not an accident: C1 put
`BlockSource` in `:core` precisely so a live implementation could satisfy it, and
`BlockLookup` — already constructed by `ContinuoCore.start()` and already memoising
classifications per state id — is that implementation.

### 5.5 Render bounds

The map is drawn over the bounding box of the start, the goal and the path, padded
by two, clamped to the world's `minY` and `maxY` and to **64 blocks per axis**,
centred on the box, so a distant goal cannot write an enormous file. A map is one
character per position per Y layer, so an unclamped box across a few hundred blocks
in each axis is hundreds of megabytes.

When the clamp fires the output says so. A silently truncated map is worse than no
map, because the route appears to stop for no reason and the reader has no way to
tell truncation from a search that gave up.

For a failed or budget-exceeded search there is no path to bound, so the box is
start and goal padded — and the map is still written. **That is the case that most
needs looking at**, and it is the one a summary line cannot explain.

---

## 6. Adapter wiring

Both adapters gain two keybinds, following `dumpKey`'s existing pattern exactly —
same category, registered the same way, polled the same way:

- `key.continuo.mark` marks the goal at the player's current block position
- `key.continuo.path` runs the search from wherever the player is standing

Default bindings are **H** and **L**. Both are free in vanilla on both versions,
and neither collides with the two keys Continuo already claims — **K** for the walk
and **J** for the dump.

**The probe reads through `ContinuoCore.blocks()`, not a fresh `BlockLookup`.** The
core already builds one at `start()` and already clears it on every level
transition, and its memo turns a repeat state id into a map lookup instead of a
`describe`-and-classify. Constructing a second lookup in the adapter would duplicate
that memo, warm it separately, and — worse — sit outside the lifecycle that
`ContinuoCore.stop()` discharges, which is exactly the "must not outlive the level
it was built against" hazard `BlockLookup`'s javadoc warns about. This means
promoting the core from a local to a field in both adapters, which is the same
change `context` already needed in the 1.7.10 adapter for the dump poll.

Both are polled on the end-of-tick event, as the dump already is, so reads land
inside `IBlockView`'s delivery window after the tick's core processing has settled.
They are unrelated to the four global rules and so are polled independently of
`AdapterRuntime`, exactly as the dump keybind is.

The verdict goes through `RuntimeLog`, which both adapters already implement. The
map is written to `continuo-path-probe.txt` in the game directory, alongside the
existing `continuo-block-dump.txt`. The whole block sits inside the same
`try`/`catch`-and-log the dump already uses.

This is the only untested code in the change, and it is structurally untestable —
adapters have no tests and cannot get any. Keeping it to *register a key, call a
method, write a file* is deliberate: every piece of judgment lives in `:runtime`
where it can be tested.

---

## 7. Testing

In `:runtime`, headless against a fake `BlockSource`:

| Guard | What it pins |
|---|---|
| Mark, then run | The route comes back and the summary names its outcome, step count and cost |
| Run with no goal marked | Reported in the summary, not thrown — the game-loop caller must never see an exception |
| A world with no route | `NO_PATH`, and the map is still rendered with start and goal markers |
| A deliberately tiny budget | `BUDGET_EXCEEDED`, distinct from `NO_PATH` in the summary |
| A goal far outside the clamp | The bounds clamp fires and the output announces it |

In `:core-pathfinder`, extending what is already there:

| Guard | What it pins |
|---|---|
| The existing renderer suite | Still passes through the delegating `FixtureWorld` form, unchanged |
| Round-trip through the `BlockSource` form | A map rendered from a `BlockSource` re-parses as a fixture — this is what protects P5's whole justification from breaking quietly |
| A block outside the legend | Renders `?`, and the round-trip guard records that it comes back as `UNKNOWN` rather than as what it was |

Adapters remain untested, as always. Say so in any dispatch.

---

## 8. Done criteria

1. `PathRenderer` and `BlockLegend` are public in `:core-pathfinder`'s main
   sources, with the `BlockSource` render form; the `FixtureWorld` form still
   exists in test sources, `FixtureBlocks` parses through `BlockLegend` rather
   than through a legend of its own, and every pre-existing renderer test passes
   unchanged.
2. `PathProbe` and `ProbeReport` exist in `:runtime`, with every guard in §7 green.
3. `:runtime`'s `allowedProjectDependencies` entry lists `:core-movement` and
   `:core-pathfinder`, and `checkDependencyDirection` passes.
4. Both adapters register the two keybinds and both build.
5. A full cold `./gradlew build --rerun-tasks` is green, and the test count is
   stated rather than assumed.
6. **The owner has run it in at least one version and looked at the output.** This
   criterion is the entire point; the others are the means. It cannot be discharged
   by an agent, and the work is not done until it is met.

---

## 9. Risks and what this decides

**The probe finds a route and it looks sane.** Then the block model, the
pathfinder and the registry work against real terrain, three sub-projects of
accumulated risk are retired, and the C3-versus-E/F question becomes a free choice
made on preference rather than on anxiety.

**The probe finds a route and it is wrong.** Then the next work is whatever this
exposed, and both C3 and E wait. This is the outcome the whole exercise is for, and
finding it now is the cheapest it will ever be found.

**The probe cannot run at all** — the classifier misreads live blocks, the budget
is exhausted crossing open ground, `BlockLookup`'s memo misbehaves across a level
transition. Each of these is a real defect in shipped work that nothing currently
could detect.

**What this does not decide.** It does not settle C3 versus pulling E and F
forward. It produces the evidence for that decision and should not be read as
having made it. The owner's underlying complaint — too much unverified work has
accumulated — is legitimate under any ordering, and remains the thing to optimise
for once the evidence is in.

**A carried risk of its own.** Publishing the legend makes an eleven-value fixture
vocabulary part of `:core-pathfinder`'s API. If a later change wants to widen the
classification space, the legend widens with it or the `?` cases grow. That is a
real coupling, accepted for the round-trip property, and worth revisiting if the
legend ever starts distorting the block model rather than describing it.
