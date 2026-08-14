# B1 — Block model design

**Date:** 2026-08-14
**Status:** Approved
**Milestone:** M3, sub-project B, first half
**Depends on:** A2b (`2026-08-13-a2b-conformance-testkit-design.md`)
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M3

---

## 0. M3 splits into B1 and B2

The roadmap's sub-project B is *"`IBlockView`/`IBlockData`, chunk snapshot cache, data-driven
per-version block property registry"*. Tallied against the design below, that is larger than
A2b, and A2b was itself split off from A2a for a principled reason. B is therefore split the
same way:

- **B1 — the block model.** This spec. The SPI addition, the classifier, the registry, the
  audit, both adapters producing raw descriptions, and the cross-adapter parity harness.
  Done when both adapters yield identical `BlockData` for the same fixture world, which is the
  roadmap's stated criterion for B, unchanged.
- **B2 — the world view.** The immutable snapshot, lazy per-section copying, the cache, and
  `isChunkLoaded`'s consumers. **Folded into M4 on 2026-08-14 — see below.**

The split runs in that direction for the same reason A1 and A2 were split: **B1 is where the
risk is, and B2 builds on top of it.** B1 is the project's first genuinely hard version spread
and the thing the M3 SPI audit judges. B2 is comparatively mechanical — copying and caching —
and it cannot even be designed until B1 settles what a snapshot stores per block, which is a
consequence of B1's interning model.

**B2 was then folded into M4, so M3 is B1 alone.** Drafting B2
([`2026-08-14-b2-world-view-design.md`](2026-08-14-b2-world-view-design.md), §9) made it clear
that it has **no consumer** until M4: A\* is the first thing that reads a snapshot, sizes a
region, or can measure whether the storage layout and fill cost are acceptable. Three of its five
risks were some form of "unmeasurable until M4", and its central design decision — a two-phase
`FILLING`/`SEALED` snapshot — would have been made with no evidence available.

This is the same reasoning that put A2 before B, and that made A2b wait until two adapters existed
before writing a conformance suite: *"writing it against one adapter risks encoding Fabric's
accidents as the contract."* Writing a snapshot with no consumer risks encoding a guess as the
design. The B2 draft is kept as **design input to M4's brainstorm**, not as an approved design.

---

## 1. What B1 is

Both adapters can report a block's raw physical facts; the core turns those facts into a
`BlockData` using one shared classifier plus a small per-version override table; and the two
adapters demonstrably agree.

**In scope:**

- Two new types in `dev.continuo.platform`, plus one signature added to `IPlatformContext`
- `BlockData` / `BlockShape` / `BlockTag` / `Fluid` and the classifier, in `:core`
- The override-table format, its loader, and the two per-version JSON files as `:core` resources
- The block audit (§4), written up as a table in this spec
- Headless classifier, loader and lookup tests
- Both adapters implementing `IBlockView`
- The `:runtime` dump walker, two checked-in dump files, and the headless parity diff

**Explicitly not in B1.** Recorded so a later session does not re-derive them:

| Deferred to | What |
|---|---|
| B2 | The snapshot, lazy section copying, the cache, `isChunkLoaded`'s consumers |
| M4 | Pathfinding, text-art fixture worlds, any *use* of `BlockData` to make a decision |
| M5 | Cache invalidation, mixins, `onPositionCorrection`, threading |
| M8 | `hardness()`, tool contexts, `THROWAWAY` — all mining and building concerns |
| Never | The source architecture's `Object raw()` escape hatch |

---

## 2. The decision: where classification lives

### 2.1 The problem

The pathfinder never asks "is this soul sand?". It asks geometric and semantic questions: can I
stand on this, can I walk into this space, will I fall, is this water or lava, can I climb it.
`BlockData` answers those in the core's own vocabulary — `BlockShape`, `BlockTag`.

**Neither Minecraft version stores anything resembling that vocabulary.** Something has to
decide, for every distinct block state on each version, which shape it is and which tags it
carries. *Where that decision lives* is the whole sub-project, because it determines whether the
adapters stay thin and whether the two versions can silently disagree.

What each version natively provides:

| | Forge 1.7.10 | Fabric 1.21.11 |
|---|---|---|
| Identity | `Block` + metadata `int` (0–15) | interned `BlockState` |
| Geometry | **a list of** AABBs, via `Block.addCollisionBoxesToList(World, x, y, z, mask, List, Entity)` | a `VoxelShape` — **a list of** AABBs |
| Semantics | `Material`, `isOpaqueCube()`, Forge's `isLadder()`, `BlockFalling` | data-driven block tags, `FluidState`, `blocksMotion()`, `FallingBlock` |
| State space | ~4096 blocks × 16 meta | ~1,100 blocks → ~26,000 states |
| Vertical range | `0 .. 256`, fixed | dimension-dependent; `-64 .. 320` in the overworld |

**Verified against decompiled sources on 2026-08-14** — see §9. The geometry row is narrower than
it first appears: 1.7.10 is *not* limited to a single AABB. `addCollisionBoxesToList` is
overridable per block and emits as many boxes as the block has, structurally matching 1.21.11's
`VoxelShape`. `BlockFence` emits up to two. **Both versions natively produce a list of boxes**,
which is a smaller version spread than this spec was originally written against.

Two consequences. First, **both versions have a finite state space with a stable native
integer key**, so classification is paid once per state and interned — 1.7.10 stores
`blockId << 4 | meta`, and 1.21.11 has a global block-state registry id precisely so states can
be sent as varints. The flyweight is native on both. Second, **the two versions carry semantics
in structurally different places**: 1.21.11 has a tag system that answers "is this a slab?"
directly, and 1.7.10 has no tags at all.

### 2.2 The three options

- **A — the adapter reports raw facts, the core classifies.** The adapter produces a
  version-neutral bag of primitives; a shared, version-independent classifier in `:core` turns
  it into `BlockData`; a small per-version JSON table supplies non-geometric exceptions.
- **B — a pure per-version data table.** JSON maps a state key straight to the answer; the
  adapter is a lookup and nothing else.
- **C — each adapter derives it natively**, using its own version's idioms.

**A was chosen.** The reasoning, recorded because it is the argument a future session will want:

1. **It is the pattern that has worked twice.** A2a's unbound-key clause and A2b's injection
   seam were both resolved by making the SPI *more uniform* — one shared implementation — rather
   than more accommodating. A shared classifier is the same move: divergence has nowhere to live.
2. **It keeps "data, not code" where the roadmap means it.** The rule is about the
   *version-specific* part. Under A that is the override JSON. Under C it is two classifiers.
3. **It makes disagreement diagnosable.** Under A, a cross-adapter mismatch is a difference in a
   collision box or a boolean — a mechanical diff, not an argument about judgement.

B was rejected on coverage and authoring: 26,000 states on 1.21.11 is not hand-writable, so the
table would need wildcards and property matching — a matching language, which is logic wearing a
data costume — and any unrecognised or modded block would need a fallback, and the only sane
fallback is derivation, i.e. A reintroduced as the default path.

C was rejected because it is judgement code in an adapter, which is exactly what the standing
H audit exists to catch, and because two implementations of "what counts as a `STAIR`" will
disagree silently, presenting as the bot pathing badly on one version only.

### 2.3 The honest shape of A

A and B are not rivals for the whole job, and describing them that way oversells A:

| | derivable from geometry? |
|---|---|
| `shape()` | **almost entirely yes** — this is what collision boxes *are* |
| `tags()` | **almost entirely no** |

Soul sand's collision box is indistinguishable from stone's; cobweb has no collision box at all
yet is one of the most movement-relevant blocks in the game; fire, cactus and magma are all
`AVOID` with nothing geometric to see. So the accurate description is **A for `shape()`, B for
`tags()`**. The classifier earns its keep on geometry and on unknown or modded blocks; the table
does the semantic work. That is why the tables stay a few dozen rows rather than thousands.

A few tags *are* natively answerable on both and are therefore description fields rather than
table rows — `FALLING` is `instanceof BlockFalling` / `FallingBlock`, climbable is `isLadder()` /
`BlockTags.CLIMBABLE`. Each costs one field and covers modded blocks for free.

### 2.4 What A buys the SPI — the strongest argument, stated last

Under A the adapter never produces a `BlockData`. It produces raw facts, and the core's
classifier produces the `BlockData`. **So `BlockData`, `BlockShape`, `BlockTag` and `Fluid` live
in `:core`, not in `dev.continuo.platform`.**

Under B or C they would have to be SPI types, and every future adapter — M9's third and fourth —
would inherit a permanent obligation to speak the core's classification vocabulary. Under A they
do not, because adapters never speak it. `dev.continuo.platform`'s own `package-info` says every
type added there is a future version-compatibility problem; A adds two types of raw fact and
zero types of judgement.

### 2.5 The bound on A's risk

A can *degrade* toward B for a specific property; it can never be *blocked* by one. The override
table is keyed by `id`, and `id` is always present in the description, so anything the classifier
cannot derive can always be tabled instead. There is no scenario where B1 gets halfway and
discovers the approach does not work — the worst case is that a property hoped to be derived
gets hardcoded in JSON, costing authoring effort, not the design.

The failure mode of A is therefore not a wall but **erosion**: `BlockDescription` accreting
fields over M4–M8 until the boundary is soft. §7 states the budget that guards against it.

---

## 3. Design

### 3.1 Module layout

```
:platform    IBlockView            live reader; stateId → describe; main-thread only
             BlockDescription      raw primitives, no judgement
             IPlatformContext      + blocks()

:core        BlockData, BlockShape, BlockTag, Fluid    the core's vocabulary
             BlockClassifier                           description + table → BlockData
             BlockLookup                               the per-session memo
             BlockTable, BlockTableLoader              the override format
             resources/blocks/1.7.10.json              version-specific data
             resources/blocks/1.21.11.json

:runtime     BlockDumpWalker       dev-only; walks a world region, writes the parity dump

adapters/*   one IBlockView each, stateless
```

`:runtime` already exists as the module both adapters delegate to, so the dump walker goes there
rather than into either adapter — for the same reason `AdapterRuntime` did.

### 3.2 The SPI addition

```java
package dev.continuo.platform;

public final class BlockDescription {

    public BlockDescription(String id,
                            String stateKey,
                            double[] collisionBoxes,
                            String fluidId,
                            boolean climbable,
                            boolean gravity);

    public String   id();              // "minecraft:oak_slab"; never null
    public String   stateKey();        // starts with id(); see below
    public double[] collisionBoxes();  // flattened 6-tuples; empty = no collision
    public String   fluidId();         // "minecraft:water"; null if none
    public boolean  climbable();
    public boolean  gravity();
}
```

**Why a class and not an interface.** This is the first non-interface, non-enum type in the
package, so the departure is deliberate. An adapter writes `return new BlockDescription(...)` —
one line. An interface would make each adapter write a six-method class or an anonymous inner
class per description. The class also lets the constructor copy the `double[]` in and the
accessor copy it out, so immutability is enforced rather than promised. `describe` is called once
per distinct state, so those copies cost nothing measurable.

**Why `stateKey` and not an `int variant`.** The override table is hand-authored, and a variant
index is opaque to whoever writes the JSON. `stateKey` is human-meaningful on both versions:

```
1.21.11   minecraft:oak_slab[type=bottom,waterlogged=false]
1.7.10    minecraft:stone_slab#8
```

Table rows key on `id` for whole-block rules — which covers nearly everything, since soul sand,
cobweb, fire and cactus are whole-block properties — and fall back to the full `stateKey` for the
rare state-specific override.

**Why `fluidId` is a string, not an enum.** It keeps a third type out of the SPI and handles
modded fluids for free. More importantly it lets the adapter report the **native** id verbatim,
with no judgement: 1.7.10 has both `minecraft:water` and `minecraft:flowing_water` as distinct
blocks, and 1.21.11 has neither as a block at all — it has a `FluidState` on whatever block is
there. Normalising those to one concept is classification, so it belongs in the table, where the
1.7.10 file carries the `flowing_water` row. The core knows the two vanilla ids directly, so a
missing table row cannot make water stop being water.

**Deliberately absent:** `fullOpaqueCube` (derivable from the boxes), `variant` (superseded by
`stateKey`), and fluid height (M4 will probably want it for flowing water; a constructor overload
then is cheap, and guessing now is not).

```java
public interface IBlockView {

    /** Cheap. Called per block, per pathfinding node. -1 if unreadable. */
    int stateId(int x, int y, int z);

    /** Expensive. Called once per distinct state id, at the position that first produced it. */
    BlockDescription describe(int x, int y, int z);

    boolean isChunkLoaded(int chunkX, int chunkZ);

    int minY();   // inclusive
    int maxY();   // exclusive
}
```

**The split of `stateId` from `describe` is the load-bearing choice here.** A* touches thousands
of blocks per search and a description carries a `double[]`; allocating one per query is garbage
the game thread pays for, and returning a shared mutable instance is a footgun that will
eventually be captured and outlive its validity. Splitting them makes the hot path an `int`, and
classification runs a few thousand times per session rather than per query.

It works because **both versions natively have exactly this id** — neither adapter has to invent
anything, and the SPI is merely naming a flyweight that already exists on both. The alternative
considered was `BlockDescription getBlock(x, y, z)` with adapter-side interning; it was rejected
because it makes every adapter implement a cache correctly forever, and that cache is adapter-side
state — the same category of thing A2b spent a whole sub-project pulling *out* of adapters.

**`describe` takes a position, not a state id — corrected 2026-08-14 after reading the sources.**
The original signature was `describe(int stateId)`, which assumed geometry is a function of state
alone. **On 1.7.10 it is not.** `addCollisionBoxesToList` takes a `World` and coordinates and
consults neighbours — `BlockFence.setBlockBoundsBasedOnState` calls `canConnectFenceTo` on all
four sides — and 1.7.10's metadata does not record connections the way a 1.21.11 `BlockState`
does. An adapter handed only a state id could not produce a fence's geometry at all, because there
is no canonical position to evaluate it at.

Taking a position costs nothing: the core calls `describe` on a cache miss, and the miss always
happens *at* a position. `at(x,y,z)` → `stateId(x,y,z)` → miss → `describe(x,y,z)`. No extra
bookkeeping, and the memo is still keyed by state id.

**The consequence, which must be stated rather than discovered:** for neighbour-dependent blocks
on 1.7.10, the cached geometry is whatever the **first observed instance** had. A disconnected
fence seen first defines the entry for every fence of that state id. This is sound only because
`FENCE` is a behavioural category and every fence variant is 1.5 tall regardless of connections —
the footprint differs, and the model deliberately does not use footprint. On 1.21.11 the same
blocks have *different state ids* per connection, so they cache separately and exactly. That
asymmetry is real, documented, and does not affect the parity test, because both versions classify
every variant as `FENCE`.

**`stateId` returns `-1` when the position is unreadable** — outside the vertical range, or in a
chunk that is not loaded. One branch on the hot path instead of three, and one condition both
adapters evaluate identically rather than two independent judgement calls about what happens at
the world edge. Same move as rule 2's level-identity condition.

`isChunkLoaded` stays because *unloaded* and *outside the world* are different facts to a
pathfinder: one is "unknown, might be solid", the other is "definitely nothing". The core maps
`-1` to a singleton `BlockData` with `BlockShape.UNKNOWN`.

`minY`/`maxY` follow modern Minecraft's own convention. 1.7.10 reports `0`/`256`; 1.21.11 reports
the current dimension's values, `-64`/`320` in the overworld.

```java
public interface IPlatformContext {
    IActuator  actuator();
    IPlatformInfo info();
    IBlockView blocks();   // never null; same instance on every call
}
```

`IPlatformContext`'s existing javadoc says it was bundled *"so that adding a capability later
changes one signature rather than every call site."* This is the first time that is collected on.

### 3.3 The core vocabulary

```java
package dev.continuo.core;

public final class BlockData {
    BlockShape        shape();
    double            collisionTop();   // highest collision Y; 0 if none, 1.0 for FULL
    Fluid             fluid();          // NONE, WATER, LAVA, OTHER
    EnumSet<BlockTag> tags();
    boolean           has(BlockTag tag);
}

public enum BlockShape {
    UNKNOWN,       // unreadable position — stateId returned -1
    AIR,           // no collision at all
    FULL,          // one box filling the cube
    SLAB_BOTTOM,   // full footprint, y 0 .. 0.5
    SLAB_TOP,      // full footprint, y 0.5 .. 1
    THIN_LAYER,    // full footprint, y 0 .. h where h <= 0.25 — carpet, snow layer
    STAIR,         // a bottom slab plus a quarter
    FENCE,         // any collision box taller than the cube
    PARTIAL        // has collision, matches no category
}

public enum BlockTag { AVOID, FALLING, CLIMBABLE, SLOW }

public enum Fluid { NONE, WATER, LAVA, OTHER }
```

**`BlockData` is a final class, not the roadmap's `IBlockData` interface.** There is exactly one
implementation, it is an immutable flyweight, and fake-world fixtures construct it directly rather
than implementing it. The roadmap's M3 line needs a one-word amendment when this spec lands.

**`FENCE` is geometrically detectable on both versions** rather than needing a table row — and
this was checked against sources rather than assumed. The rule is "collision top above 1.0".

- **1.21.11** — `FenceBlock` passes `24.0F` as its collision height to `CrossCollisionBlock`,
  which is in sixteenths, so 24/16 = **1.5**. Note it is a *separate* shape from the visual one
  (`16.0F` = 1.0), so the adapter must read `getCollisionShape`, never `getShape`.
- **1.7.10** — `BlockFence.addCollisionBoxesToList` sets `maxY` to **1.5F** for every box it
  emits. **But `setBlockBoundsBasedOnState` resets to 1.0F**, and
  `getCollisionBoundingBoxFromPool` reads those reset fields. An adapter taking the
  bounds-then-read route would see a 1.0-tall fence and classify it `FULL` — a silent wrong
  answer on one version only, which is precisely the class of bug this sub-project exists to
  prevent. **`addCollisionBoxesToList` is the only correct route on 1.7.10.**

**`PARTIAL` is the escape hatch that makes modded blocks safe.** Unrecognised geometry classifies
as `PARTIAL` with an honest `collisionTop()`, and M4's `mv-walk` can treat it conservatively
rather than guessing.

**The shape rules are exact matches, and anything unmatched is `PARTIAL`.** A single box of
`y 0 .. 0.5` with a full footprint is `SLAB_BOTTOM`; a box of `y 0 .. 0.3` matches no rule and is
`PARTIAL`. The categories are deliberately not ranges-with-tolerance — a near-miss classifying as
a slab would be a silent wrong answer, whereas `PARTIAL` with a truthful `collisionTop()` is a
correct one that simply carries less information.

`describe` is never called with `-1`. `BlockLookup` maps an unreadable position straight to the
`UNKNOWN` singleton without consulting the view again, so no adapter has to define what
`describe(-1)` means.

Trimmed from the source architecture's sketch: `WATER` and `LAVA` are now `fluid()`, and
`THROWAWAY` is a building concern belonging to M8. Also dropped, permanently:
`hardness(IToolContext)` (mining, M8) and `Object raw()` — a typed hole in core purity that the
machine-checked "no `net.minecraft` on the core classpath" invariant cannot see through.

### 3.4 The classifier

```java
public final class BlockClassifier {
    BlockClassifier(BlockTable table);
    BlockData classify(BlockDescription d);
}

public final class BlockLookup {
    BlockLookup(IBlockView view, BlockClassifier classifier);
    BlockData at(int x, int y, int z);   // stateId → BlockData[], describe on miss
    void clear();
}
```

`BlockClassifier` is a pure function of a description and a table — no Minecraft, no world, no
state. That is what makes B1 fully headless-testable and what makes cross-adapter parity
structural rather than hoped-for.

**Precedence, stated so it cannot be read two ways:**

1. **Shape** — derived from geometry, then overridden by a `blocks` row, then by a `states` row.
2. **Tags** — derived (`CLIMBABLE` from `climbable()`, `FALLING` from `gravity()`), then
   **unioned** with the `blocks` row's tags, then unioned with the `states` row's.
3. **Fluid** — the two vanilla ids are known to the core directly; a table row may override or add.

Tag *removal* is deliberately not supported. If it turns out to be needed, that is a finding worth
recording rather than a feature to speculate about now.

### 3.5 The table format

```json
{
  "version": "1.7.10",
  "blocks": {
    "minecraft:soul_sand":     { "tags": ["SLOW"] },
    "minecraft:web":           { "tags": ["SLOW"] },
    "minecraft:fire":          { "tags": ["AVOID"] },
    "minecraft:cactus":        { "tags": ["AVOID"] },
    "minecraft:flowing_water": { "fluid": "WATER" }
  },
  "states": {
    "minecraft:stone_slab#8":  { "shape": "SLAB_TOP" }
  }
}
```

Two sections — `blocks` keyed by `id()`, `states` keyed by `stateKey()`, states winning.

**A block with no row is the normal case, not an omission.** It is classified from geometry and
the description booleans alone. That is the whole point of option A, and it is why the tables stay
a few dozen rows rather than thousands.

Loading is **strict**: an unrecognised `shape`, `fluid` or tag name fails loudly at load rather
than being skipped. A silently-ignored typo in a data table is precisely the failure mode this
design exists to avoid. Rows referring to blocks that do not exist on that version cannot be
detected at load time; the dump walker reports them, which is one more reason it earns its place.

Files ship as `:core` resources and are selected at runtime by `IPlatformInfo.gameVersion()` — not
as adapter resources.

**This is not the version branching that `IPlatformInfo` forbids.** That javadoc says the version
string *"is not for feature detection"* and that branching core behaviour on it is what capability
negotiation exists for. Selecting a **data file** by version is the roadmap's own "version
differences are data, not branches" rule working as intended: no core code path changes, one table
is loaded instead of another, and a version with no table falls back to pure geometry rather than
behaving differently. The core owns classification, and the tables must be diffable and testable
headless, which they cannot be from behind a Minecraft toolchain.

No JSON library is on the core classpath today and core is `--release 8`. The format is
deliberately flat enough for a small hand-written reader; adding a dependency to the one module
whose purity is machine-checked is the worse option. Worth confirming when the plan is written.

### 3.6 Data flow

```
core logic
  └─ BlockLookup.at(x, y, z)
       ├─ view.stateId(x,y,z) ───────────────►  int          (hot path, per block)
       ├─ cache hit? ──► BlockData                            (the normal case)
       └─ miss:
            ├─ view.describe(stateId) ────────►  BlockDescription
            ├─ table.lookup(id, stateKey) ────►  override row, usually absent
            └─ classify → BlockData, stored in BlockData[] by stateId
```

### 3.7 Interaction with the global rules

The `package-info` is normative; nothing here amends it. Rule numbering is load-bearing and is
not touched.

- **Rule 1 (Threading)** — unchanged and unbent. Every method added here is main-thread and
  non-blocking. B2's snapshot will be a `:core` type, so nothing in `dev.continuo.platform` ever
  needs to be thread-safe, and rule 1 keeps its record of having no exceptions.
- **Rule 2 (Lifecycle)** — the `stateId → BlockData` memo is cleared in `stop()`. State ids are
  session-scoped, and rule 2 already mandates `stop()` on every level transition, so the memo
  cannot outlive the level whose ids it was built from. **No new machinery and no new condition**
  — this rides on `stop()`, which the existing `updateLevel` watch already calls. Note the memo is
  an obligation of `stop()`, not a fourth obligation of the level-identity condition itself; the
  `package-info`'s "one observable condition, three obligations" count is unchanged.
- **Rules 3 and 4** — untouched. Nothing here holds input or changes fault handling.
- **New clause** — `IBlockView`'s methods MUST only be called while `onClientTick`'s delivery
  window is open (a world loaded and a local player present). Outside that window the behaviour is
  unspecified. This deliberately **reuses** the existing window rather than stating a new
  condition, so there is nothing new for an adapter to evaluate or get wrong.

---

## 4. The block audit — a deliverable, not a preliminary

Before the classifier is written, enumerate the blocks `mv-walk` will actually care about and
check each on both versions: **geometry-derivable, tableable, or neither.** "Neither" is the only
answer that threatens the design, and it is the B1 gate tripping.

Minimum list: air, stone, bottom slab, top slab, stairs, fence, wall, glass pane, ladder, vine,
water (source and flowing), lava, gravel, sand, cobweb, soul sand, ice, packed ice, door,
trapdoor, carpet, snow layer, farmland, cactus, fire, magma (1.21 only), chest, leaves, honey
block (1.21 only), and one deliberately unrecognised modded-shaped block.

The result is a table with a verdict per block per version. It is **the evidence the whole design
rests on**, and it doubles as the correspondence list the parity fixture is built from (§5.2). It
is the first task of the implementation plan, and its output is **added to this section by
amendment** when it is run — this spec is written before the audit exists, deliberately, so that
the audit has a design to test rather than the other way round.

This is asked in earnest because the M2 gate explicitly does not cover it. The roadmap records:
*"It says nothing about the block model M3 will need, which is where the version spread is
genuinely hard, and it is not a promise that the SPI will hold there."* Do not cite the M2 gate as
evidence the SPI is fine here.

#### Audit results — 2026-08-14

Read from the decompiled sources on disk: RetroFuturaGradle's
`adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/java` (MCP names) and Loom's cached
1.21.11 Mojmap sources. Neither tree is in git.

**How the boxes were obtained.** On 1.7.10, `addCollisionBoxesToList(world, x, y, z, mask, list,
null)` with a mask large enough to admit every box — never `setBlockBoundsBasedOnState` followed by
a bounds read (§3.3 explains why). On 1.21.11, `state.getCollisionShape(level, pos).toAabbs()` —
never `getShape`. **All figures below are block-relative**, which is what the SPI carries: the
1.7.10 adapter subtracts `x`/`y`/`z` from the absolute `AxisAlignedBB`, and `VoxelShape.toAabbs()`
is already block-relative. A full cube is therefore `0,0,0 → 1,1,1` on both sides.

Every box shown is for the state actually named in the fixture (§5.2) — for the neighbour-dependent
blocks that means the connections the fixture corridor produces, not the isolated case.

| Logical block | 1.7.10 class + boxes | 1.21.11 class + boxes | Expected `BlockShape` | Verdict |
|---|---|---|---|---|
| air | `BlockAir`; `getCollisionBoundingBoxFromPool` → `null`; no boxes | `AirBlock`; `getShape` → `Shapes.empty()`; no boxes | `AIR` | `geometry` |
| stone | `Block`; `0,0,0 → 1,1,1` | `Block`; `Shapes.block()` → `0,0,0 → 1,1,1` | `FULL` | `geometry` |
| bottom slab | `BlockSlab`/`BlockStoneSlab`; overrides `addCollisionBoxesToList`; `0,0,0 → 1,0.5,1` | `SlabBlock`; `Block.column(16,0,8)` → `0,0,0 → 1,0.5,1` | `SLAB_BOTTOM` | `geometry` |
| top slab | `BlockSlab`; `0,0.5,0 → 1,1,1` | `SlabBlock`; `Block.column(16,8,16)` → `0,0.5,0 → 1,1,1` | `SLAB_TOP` | `geometry` |
| stairs (bottom half, straight, no adjacent stair) | `BlockStairs`; overrides `addCollisionBoxesToList`; **2 boxes** — `0,0,0 → 1,0.5,1` then `0.5,0.5,0 → 1,1,1` (meta 0; the second box rotates with facing) | `StairBlock`; `Shapes.or(column(16,0,8), box(0,8,0,8,16,8))` + a Y-90 copy; `toAabbs` merges to **2 boxes** — `0,0,0 → 1,0.5,1` then `0,0.5,0 → 1,1,0.5` (facing north) | `STAIR` | `geometry` |
| fence (connected N+S) | `BlockFence`; overrides `addCollisionBoxesToList`; `0.375,0,0 → 0.625,1.5,1` | `FenceBlock`→`CrossCollisionBlock`; post `column(4,0,24)` ∪ two `boxZ(4,0,24,0,8)` arms; `toAabbs` → `0.375,0,0 → 0.625,1.5,1` | `FENCE` | `geometry` |
| wall (connected N+S) | `BlockWall`; `getCollisionBoundingBoxFromPool` forces `maxY = 1.5D`; `0.3125,0,0 → 0.6875,1.5,1` | `WallBlock`; `collisionShapes = makeShapes(24,24)`; `0.3125,0,0 → 0.6875,1.5,1` | `FENCE` | `geometry` |
| glass pane (connected N+S) | `BlockPane`; overrides `addCollisionBoxesToList`; `0.4375,0,0 → 0.5625,1,1` | `IronBarsBlock`→`CrossCollisionBlock`; `0.4375,0,0 → 0.5625,1,1` | `PARTIAL`, top `1.0` | `geometry` |
| ladder | `BlockLadder`; `f = 0.125F`; meta 2 → `0,0,0.875 → 1,1,1` | `LadderBlock`; `boxZ(16,13,16)` → `0,0,0.8125 → 1,1,1` | `PARTIAL`, top `1.0`, `CLIMBABLE` | `geometry` |
| vine | `BlockVine`; `getCollisionBoundingBoxFromPool` → `null`; no boxes | `VineBlock`; `noCollision()` → no boxes | `AIR`, `CLIMBABLE` | `geometry` |
| water (source) | `BlockStaticLiquid`→`BlockLiquid`; `null`; no boxes | `LiquidBlock`; with `CollisionContext.empty()` → `Shapes.empty()`; no boxes | `AIR`, fluid `WATER` | `table` |
| water (flowing) | `BlockDynamicLiquid`→`BlockLiquid`; `null`; no boxes. **A distinct block id**, `minecraft:flowing_water` | `LiquidBlock`, same block id `minecraft:water`, `LEVEL > 0`; no boxes. **A distinct *fluid* id**, `minecraft:flowing_water` | `AIR`, fluid `WATER` | `table` |
| lava (source) | `BlockStaticLiquid`→`BlockLiquid`; `null`; no boxes | `LiquidBlock`; no boxes | `AIR`, fluid `LAVA`, `AVOID` | `table` |
| lava (flowing) | `BlockDynamicLiquid`→`BlockLiquid`; `null`; no boxes. **A distinct block id**, `minecraft:flowing_lava` | `LiquidBlock`, same block id `minecraft:lava`, `LEVEL > 0`; no boxes. **A distinct *fluid* id**, `minecraft:flowing_lava` | `AIR`, fluid `LAVA`, `AVOID` | `table` |
| gravel | `BlockGravel`→`BlockFalling`; `0,0,0 → 1,1,1` | `ColoredFallingBlock`→`FallingBlock`; `0,0,0 → 1,1,1` | `FULL`, `FALLING` | `geometry` |
| sand | `BlockSand`→`BlockFalling`; `0,0,0 → 1,1,1` | `SandBlock`→`ColoredFallingBlock`→`FallingBlock`; `0,0,0 → 1,1,1` | `FULL`, `FALLING` | `geometry` |
| cobweb | `BlockWeb`; `null`; no boxes | `WebBlock`; `noCollision()` → no boxes | `AIR`, `SLOW` | `table` |
| soul sand | `BlockSoulSand`; `0,0,0 → 1,0.875,1` | `SoulSandBlock`; `column(16,0,14)` → `0,0,0 → 1,0.875,1` | `PARTIAL`, top `0.875`, `SLOW` | `table` |
| ice | `BlockIce`→`BlockBreakable`; `0,0,0 → 1,1,1` | `IceBlock`→`HalfTransparentBlock`; `0,0,0 → 1,1,1` | `FULL` | `geometry` |
| packed ice | `BlockPackedIce`; `0,0,0 → 1,1,1` | `Block`; `0,0,0 → 1,1,1` | `FULL` | `geometry` |
| door (lower half, closed) | `BlockDoor`; `f = 0.1875F`; e.g. `0,0,0 → 1,1,0.1875` | `DoorBlock`; `boxZ(16,13,16)` → e.g. `0,0,0.8125 → 1,1,1` | `PARTIAL`, top `1.0` | `geometry` |
| trapdoor (bottom half, closed) | `BlockTrapDoor`; `f = 0.1875F`; `0,0,0 → 1,0.1875,1` | `TrapDoorBlock`; `rotateAll(boxZ(16,13,16)).get(UP)` → `0,0,0 → 1,0.1875,1` | `THIN_LAYER` | `geometry` |
| carpet | `BlockCarpet`; `getCollisionBoundingBoxFromPool` hardcodes `b0 = 0`, so `maxY = y`; **one degenerate box** `0,0,0 → 1,0,1` → discarded by rule 0 → no boxes | `CarpetBlock`/`WoolCarpetBlock`; `column(16,0,1)` → `0,0,0 → 1,0.0625,1` | **1.7.10 `AIR` / 1.21.11 `THIN_LAYER`** — genuine divergence, see below | `geometry` |
| snow layer (2 layers) | `BlockSnow`; `maxY = (meta & 7) * 0.125`; `snow_layer#1` → `0,0,0 → 1,0.125,1` | `SnowLayerBlock`; `getCollisionShape` = `SHAPES[layers-1]` = `column(16,0,2)` → `0,0,0 → 1,0.125,1` | `THIN_LAYER` | `geometry` |
| snow layer (1 layer) | `snow_layer#0` → `maxY = 0`; **one degenerate box**, discarded by rule 0 | `snow[layers=1]` → `column(16,0,0)`; `Shapes.create` collapses it to `empty()`; no boxes | `AIR` on both — **only because of rule 0** | `geometry` |
| farmland | `BlockFarmland`; `getCollisionBoundingBoxFromPool` returns the **full cube** `0,0,0 → 1,1,1` (the `0.9375` in the constructor is render bounds only) | `FarmBlock`; `column(16,0,15)` → `0,0,0 → 1,0.9375,1` | **1.7.10 `FULL` / 1.21.11 `PARTIAL`, top `0.9375`** — genuine divergence, see below | `geometry` |
| cactus | `BlockCactus`; `0.0625,0,0.0625 → 0.9375,0.9375,0.9375` | `CactusBlock`; `SHAPE_COLLISION = column(14,0,15)` → `0.0625,0,0.0625 → 0.9375,0.9375,0.9375` | `PARTIAL`, top `0.9375`, `AVOID` | `table` |
| fire | `BlockFire`; `null`; no boxes | `FireBlock`→`BaseFireBlock`; `noCollision()` → no boxes | `AIR`, `AVOID` | `table` |
| soul fire *(1.21 only)* | — | `SoulFireBlock`→`BaseFireBlock`; `noCollision()`; no boxes | `AIR`, `AVOID` | `table` |
| magma block *(1.21 only)* | — | `MagmaBlock`; `0,0,0 → 1,1,1` | `FULL`, `AVOID` | `table` |
| chest (single) | `BlockChest`; `0.0625,0,0.0625 → 0.9375,0.875,0.9375` | `ChestBlock`; `column(14,0,14)` → `0.0625,0,0.0625 → 0.9375,0.875,0.9375` | `PARTIAL`, top `0.875` | `geometry` |
| leaves | `BlockLeaves`→`BlockLeavesBase`; `0,0,0 → 1,1,1` | `LeavesBlock`/`TintedParticleLeavesBlock`; `0,0,0 → 1,1,1` | `FULL` | `geometry` |
| honey block *(1.21 only)* | — | `HoneyBlock`; `column(14,0,15)` → `0.0625,0,0.0625 → 0.9375,0.9375,0.9375` | `PARTIAL`, top `0.9375`, `SLOW` | `table` |
| unrecognised / modded shape | any block whose boxes match no rule | same | `PARTIAL` with a truthful `collisionTop()` | `geometry` |

**34 rows audited: 23 `geometry`, 11 `table`, 0 `neither`. The B1 gate (§6.1) does not trip on
shape or on tags.** Option A survives the audit: nothing in the movement-relevant set needs a
mechanism that does not already exist.

##### Two genuine cross-version divergences

These are not modelling failures. The two games really do behave differently, and the classifier
reports each one truthfully:

- **carpet.** 1.7.10 carpet has *no* collision — you stand at `y+0`. 1.21.11 carpet has a 1/16 box
  — you stand at `y+0.0625`.
- **farmland.** 1.7.10 farmland collides as a full cube. 1.21.11 farmland is 15/16 tall, which is
  why a modern player visibly steps down onto it.

Both are expressible as a table row (`minecraft:carpet` → `THIN_LAYER` on 1.7.10, or
`minecraft:farmland` → `FULL` on 1.21.11), so neither is a `neither` verdict. **But a shape
override would still leave `collisionTop()` disagreeing, and it would make the classifier lie about
one version's physics to satisfy a test.** The recommendation, implemented in §5.2, is the opposite:
leave the classifier truthful and mark these two fixture rows **divergent** — listed, dumped, and
pinned by the per-version golden, but not asserted equal across versions. That is the same
mechanism version-exclusive rows already use.

##### Rule 0, new: discard degenerate boxes

**Before any of §3.3's rules run, drop every box with zero extent on any axis**
(`maxX - minX <= 1e-6`, or the same on Y or Z).

This is not tidiness; without it the versions disagree on a block the owner will place by accident.
1.21.11 canonicalises degenerate boxes away inside `Shapes.create` (`if (!(g - d < 1.0E-7) && …)`
→ `empty()`), so a one-layer snow reports **no** boxes. 1.7.10 does not: `BlockSnow` emits a box
with `maxY = y`, and `AxisAlignedBB.intersectsWith` still admits it against a mask that straddles
`y`, so the adapter reports **one** box of zero height. Same block, same visible state, different
box count. Rule 0 removes the difference at its source rather than papering over it per-block.

##### The eight rules, checked against the sources

| # | Rule as §3.3 states it | Verdict |
|---|---|---|
| 1 | no boxes → `AIR` | ✅ **Confirmed.** air, vine, cobweb, fire, water and lava all reach it on both versions |
| 2 | any box with `maxY > 1.0` → `FENCE` | ✅ **Confirmed exactly.** Fence and wall are `1.5` on both versions, and by different routes — 1.7.10's fence through `addCollisionBoxesToList`, 1.7.10's wall through a `this.maxY = 1.5D` assignment inside `getCollisionBoundingBoxFromPool`. Nothing else in the audit set exceeds `1.0` |
| 3 | single box, full footprint, `y 0..1` → `FULL` | ✅ **Confirmed** |
| 4 | single box, full footprint, `y 0..0.5` → `SLAB_BOTTOM` | ✅ **Confirmed.** No table row is needed for slabs on either version — §3.5's illustrative `"minecraft:stone_slab#8": { "shape": "SLAB_TOP" }` is a format example, not a required row |
| 5 | single box, full footprint, `y 0.5..1` → `SLAB_TOP` | ✅ **Confirmed** |
| 6 | single box, full footprint, `y 0..h`, `h <= 0.25` → `THIN_LAYER` | ✅ **Confirmed**, and it also catches a **closed bottom trapdoor** (`h = 0.1875`) on both versions. Accepted: the two versions agree, and `THIN_LAYER` with a truthful `collisionTop()` is the right answer for something you stand on at 3/16 |
| 7 | full-footprint `y 0..0.5` box **and** ≥1 non-full-footprint box with `y 0.5..1` → `STAIR` | ✅ **Confirmed as written** — the rule the spec doubted is the one that held. A bottom-half stair is **2 boxes** on 1.7.10 (`func_150147_e` then `func_150145_f`) and **2 boxes** on 1.21.11 after `toAabbs` greedy-merges the slab and the two quarters; an inner corner is **3** on 1.21.11 and a stair beside another stair is **3** on 1.7.10. All four cases match the rule. **Clarification, not a correction:** the rule deliberately does not match an *upside-down* stair, whose full-footprint box is `y 0.5..1`. Those classify `PARTIAL` with `collisionTop() == 1.0` on **both** versions, which is the behaviourally correct answer — from above an upside-down stair is a full block, not a step |
| 8 | anything else with collision → `PARTIAL` | ✅ **Confirmed.** pane, ladder, door, soul sand, cactus, chest, honey block |

Two further clarifications Task 8 must implement:

- **"Exact match" means within `1e-6`, not `==`.** Every coordinate in the audit set is a
  sixteenth, which is an exact binary fraction, so `==` would in fact work near the origin. An
  epsilon is still specified because 1.7.10 builds its bounds in `float` from *absolute*
  coordinates: `BlockCactus` computes `(float)x + 0.0625F`, and beyond about `|x| = 2^21` the
  `float` ulp exceeds `1/16` and the inset vanishes entirely. The fixture must therefore be built
  near the origin (§5.2), and the classifier must not depend on bit-exact equality.
- **Box order is not significant.** 1.7.10 emits in the order the block's `addCollisionBoxesToList`
  happens to run; 1.21.11 emits in `BitSetDiscreteVoxelShape.forAllBoxes` order, which iterates Y
  outermost and merges along Z, then X, then Y. Rules 7 and 2 must scan the list, not index it.

##### Findings that belong to other sections

Recorded here because the audit is where they were found; each is a change the implementing task
must make, not a change this amendment makes.

1. **The core must know *four* vanilla fluid ids, not two. Affects §3.2 and §3.4.** 1.21.11's
   fluid registry names the flowing variants separately (`Fluids.FLOWING_WATER` is registered as
   `"flowing_water"`), so a flowing water block's `fluidId()` is `minecraft:flowing_water`, not
   `minecraft:water`. 1.7.10 has the same split at the *block* level. Two places assert the
   two-id model and both are falsified: **§3.4**'s precedence rule 3 (*"the two vanilla ids are
   known to the core directly"*) and — more directly — **§3.2**'s promise that *"The core knows the
   two vanilla ids directly, so a missing table row cannot make water stop being water"*, which is
   simply untrue for flowing water on **both** versions. Knowing all four — `water`,
   `flowing_water`, `lava`, `flowing_lava` — makes the fluid rows in both tables redundant rather
   than load-bearing. The prose fix belongs to the implementing task; this amendment only flags the
   contradiction and does not edit §3.2 or §3.4.
2. **The 1.7.10 adapter must call `setBlockBoundsBasedOnState` *before*
   `addCollisionBoxesToList`.** `Block.addCollisionBoxesToList` reads the block's mutable
   `minX..maxZ` fields and does **not** refresh them; blocks such as `BlockChest` override
   `setBlockBoundsBasedOnState` but neither `addCollisionBoxesToList` nor
   `getCollisionBoundingBoxFromPool`, so without the priming call the adapter reads whatever the
   last unrelated caller left in the fields. The order is safe for every audited block: the four
   that override `addCollisionBoxesToList` (`BlockSlab`, `BlockStairs`, `BlockFence`, `BlockPane`)
   set their own bounds afterwards, and the fence's `1.5F` is applied after the `1.0F` reset, not
   before it.
3. **Nothing in the model expresses slipperiness.** `ice` and `packed_ice` classify identically to
   `stone`, and `BlockTag` has no member for it. Correct for B1 — no consumer exists — but M4's
   `mv-walk` will want it, and it is a `describe`-field candidate under §7's budget: both versions
   answer it natively (`Block.slipperiness` / `BlockBehaviour.Properties.friction`) and it covers
   modded blocks. Recorded as an opened question in §6.3.

---

## 5. Verification

### 5.1 Headless tests — TDD applies in full

Unlike A2a and A2b, almost all of B1 is pure functions over data. There is no "TDD does not apply
here" carve-out to write into the plan except for the adapters themselves, which still cannot run
without Minecraft.

- **`BlockClassifier`** — description in, `BlockData` out. Every shape rule, every precedence
  rule, every table interaction. The bulk of the suite.
- **`BlockTableLoader`** — strictness. A bad tag name, a bad shape name, malformed JSON: each must
  fail loudly, and a test must assert that it does.
- **`BlockLookup`** — memoisation actually memoises, `-1` maps to `UNKNOWN`, `clear()` empties it,
  `ContinuoCore.stop()` calls `clear()`.
- **The parity test** — §5.2.

**Mutation-test the parity test specifically.** A2b found two conformance cases that had passing
assertions and asserted nothing, because control flow returned before the code under test was
reachable — and a full review had already approved them. A test whose subject is "these two files
agree" has exactly that shape. Delete a line from one dump and confirm the test fails, and that
only that test fails.

### 5.2 The fixture world and the dump

**A registry walk does not work, and the alternative is better.** `IBlockView` has no way to
enumerate states or to look up a state by registry name, and adding either would put a pure test
concern into the SPI — the thing the `package-info` most warns against. So the dump is driven by a
**fixture structure placed in the world**.

That solves the correspondence problem for free. A 1.7.10 save and a 1.21.11 save are not the same
artifact and many blocks exist on only one side, so parity needs an explicit correspondence.
Defining it **positionally** — index 2 is "the top slab" on both versions — is more robust than
trying to match registry names across fifteen years of renames.

The fixture is a documented one-wide row of blocks at a fixed height, with an index-to-block
mapping per version, derived from §4's audit. **The layout below is the fixture**, written into
this spec by the 2026-08-14 audit amendment. A later task reproduces it verbatim as
`docs/parity/fixture-layout.md`, which is what the owner builds from; this section stays the
source of truth.

**Shape of the fixture.** A one-block-wide corridor 32 long, running **+X** from the player's feet
at a fixed `Y`. The dump walks exactly those 32 positions — one high, one deep — so index `i` is at
`(x0 + i, y0, z0)`. Everything else is scaffolding **outside** the dump volume and is never
compared:

- a **floor** at `y0 - 1` for the whole run: stone, except `netherrack` under index 28,
  `sand` under index 31, and `soul_soil` under index 30 on 1.21.11;
- **walls** of stone at `z0 - 1` and `z0 + 1` for **indices 0–29 only**. Indices 30 and 31 must
  have air on both sides or the cactus at 31 breaks;
- a `wheat` crop at `y0 + 1` above index 22 so the farmland does not revert to dirt;
- the door's upper half at `y0 + 1` above index 18.

The walls do the work that would otherwise need air gaps: they give the ladder and the vine
something to attach to, they keep the two liquids from flowing, and — usefully — they make the
fence, wall and pane connect **north and south on both versions**, which is the case where the two
versions' boxes come out numerically identical.

Build it **near the origin** (say `|x|, |z| < 1000`). §4 records why: 1.7.10 computes some bounds in
`float` from absolute coordinates, and past roughly `2^21` a sixteenth-block inset rounds away.
Build in a temperate or cold biome with no torches or other block-light sources adjacent to indices
16, 17 and 21, or the ice and the snow layer melt.

| # | Logical block | 1.7.10 | 1.21.11 | Diff |
|---|---|---|---|---|
| 0 | air (player stands here) | `air` | `air` | compare |
| 1 | solid | `stone` | `stone` | compare |
| 2 | water source | `water` (id 9) | `water[level=0]` | compare |
| 3 | falling solid | `gravel` | `gravel` | compare |
| 4 | lava source | `lava` | `lava[level=0]` | compare |
| 5 | falling solid | `sand` | `sand` | compare |
| 6 | bottom slab | `stone_slab#0` | `smooth_stone_slab[type=bottom]` | compare |
| 7 | top slab | `stone_slab#8` | `smooth_stone_slab[type=top]` | compare |
| 8 | stair, bottom half | `oak_stairs` (meta 0–3) | `oak_stairs[half=bottom,shape=straight]` | compare |
| 9 | fence | `fence` | `oak_fence` | compare |
| 10 | wall | `cobblestone_wall` | `cobblestone_wall` | compare |
| 11 | glass pane | `glass_pane` | `glass_pane` | compare |
| 12 | ladder | `ladder#2` (on the `z+1` wall) | `ladder[facing=north]` | compare |
| 13 | vine | `vine` on the `z+1` face | `vine[south=true]` | compare |
| 14 | cobweb | `web` | `cobweb` | compare |
| 15 | soul sand | `soul_sand` | `soul_sand` | compare |
| 16 | ice | `ice` | `ice` | compare |
| 17 | packed ice | `packed_ice` | `packed_ice` | compare |
| 18 | door, lower half, closed | `wooden_door` | `oak_door[half=lower,open=false]` | compare |
| 19 | trapdoor, bottom half, closed | `trapdoor` | `oak_trapdoor[half=bottom,open=false]` | compare |
| 20 | carpet | `carpet#0` | `white_carpet` | **divergent** |
| 21 | snow layer, 1 layer | `snow_layer#0` | `snow[layers=1]` | compare |
| 22 | farmland | `farmland` | `farmland` | **divergent** |
| 23 | chest, single | `chest` | `chest[type=single]` | compare |
| 24 | leaves | `leaves#0` | `oak_leaves` | compare |
| 25 | *1.21-only* | — | `magma_block` | **exclusive** |
| 26 | *1.21-only* | — | `honey_block` | **exclusive** |
| 27 | air | `air` | `air` | compare |
| 28 | fire | `fire` | `fire` | compare |
| 29 | air | `air` | `air` | compare |
| 30 | *1.21-only* | — | `soul_fire` | **exclusive** |
| 31 | cactus | `cactus` | `cactus` | compare |

The owner builds it once per version in creative. Three `Diff` values, and the dump lists **every**
index on both versions regardless, so nothing is silently absent:

- **compare** — the cross-version diff asserts the two `BlockData` are equal, and the golden pins
  the value.
- **exclusive** — the block does not exist on 1.7.10. The cross-version diff skips the index; the
  golden still pins the 1.21.11 value, and the 1.7.10 dump must read `air` there.
- **divergent** — the block exists on both and the two games genuinely behave differently (§4).
  The cross-version diff skips the index; **the golden pins both versions' values separately**, so
  the divergence is asserted rather than merely tolerated, and a change to either side fails the
  test. Adding an index here is a spec amendment, not a test fix.

**The golden is two per-version files, not one file with per-version fields** — it mirrors the two
dump files one-for-one, so `divergent` and `exclusive` need no special encoding at all (each side
simply has its own line) and the cross-version comparison stays a separate assertion from the
golden comparison, rather than one schema trying to be both.

**Index 21 is one-layer snow deliberately, and it is the only row that exercises rule 0.** 1.7.10
emits a degenerate zero-height box there and 1.21.11 emits none, and both must land on `AIR`; get
rule 0 wrong and this index is the one that catches it. Two-layer snow is an ordinary `THIN_LAYER`
case already covered by the closed bottom trapdoor at index 19 (`h = 0.1875`), so it earns no slot.
The light constraint below still applies unchanged — a one-layer snow melts at block light > 11 on
both versions exactly as a two-layer one does.

**Four audited entries are deliberately not in the fixture**, and the plan must not read their
absence as an oversight. All four are covered by §4's table and by headless `BlockClassifier` tests
instead:

| Entry | Why it is out |
|---|---|
| water, flowing | A one-wide row cannot hold it; it will not stay put |
| lava, flowing | Same |
| snow layer, 2 layers | Redundant — `THIN_LAYER` at `h = 0.125` adds nothing over index 19's `h = 0.1875`, and index 21 is spent on the rule-0 case instead |
| unrecognised modded shape | No mod loads on both versions |

Two consequences of the corridor worth stating so nobody "fixes" them later. Neither affects a
`compare` row's verdict, because the diff compares `BlockData`, never the raw boxes:

- **`stateKey` differs by design** between versions — that is §7's recorded, intended behaviour.
- **Connection predicates differ.** A 1.21.11 wall connects to a glass pane and a 1.7.10 wall does
  not, so indices 10 and 11 have different footprints across versions. Both still classify `FENCE`
  and `PARTIAL` with the same `collisionTop()`, which is the whole point of `FENCE` being a
  behavioural category (§3.2).

```java
// :runtime
String dump(IBlockView view, BlockClassifier classifier,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
```

One canonical line per position: index, `id()`, `stateKey()`, and the full classified `BlockData`.
Triggered by a dev-only keybind, written to `docs/parity/blocks-1.7.10.txt` and
`docs/parity/blocks-1.21.11.txt`, both checked in.

Dumping the raw `id`/`stateKey` alongside the classified result is what makes a failure
diagnosable: it distinguishes "the classifier decided differently" from "the owner placed the
wrong block".

The headless test parses both files, compares per index, **and** compares against a checked-in
golden. The golden matters: without it, a change that breaks both versions identically would pass
a two-way diff.

### 5.3 What a green B1 suite does not mean

Stated plainly here, and to be restated wherever the tests live, following A2b's precedent of
recording gaps rather than leaving them silent.

- **No `platform-testkit` cases are added, deliberately.** The testkit asserts `AdapterRuntime`,
  which both adapters delegate to. `IBlockView` is implemented by each adapter *directly*, and
  asserting it needs a live world — the same structural reason adapters have no automated tests at
  all. The dump is the substitute, and it is a manual step.
- **A green classifier suite says nothing about whether an adapter reports the truth.** If the
  Forge adapter reads the wrong collision bounds, every core test still passes. Only the dump
  catches it, and only for the blocks in the fixture.
- **Nothing covers blocks outside the fixture**, including every modded block. `PARTIAL` is the
  designed-in safety net, not a tested guarantee.

---

## 6. Gates and open questions

### 6.1 The B1 gate

> **If either adapter cannot produce a faithful `BlockDescription` without judgement logic, or if
> any field can be answered honestly on only one version, stop and redesign.**

Evaluated against both adapters **as built, not predicted** — the standard A2a was held to. The
finding is written into the roadmap alongside the M2 one, including what it does *not* cover.

### 6.2 The standing SPI audit

*H is a property, not a phase.* Count adapter lines that are **logic** rather than **translation**.
Each adapter gains one `IBlockView`, and the design deliberately routes every judgement away from
it: fluid normalisation to the table, shape derivation to the classifier, state ids to the native
registry. If an adapter's `IBlockView` has grown an `if` about block identity, that is the audit
failing, not a detail.

### 6.3 Carried forward, unresolved

- **The client-shutdown soft spot.** Rule 2's clause is capability-conditional while `IGameEvents`
  states the anti-capability-check principle absolutely. **B1 does not bear on it** — it adds no
  lifecycle obligations — so it carries forward untouched. Recorded explicitly so no reader infers
  that the B1 audit resolved it. See A2b spec §6.1.
- **M5 actuation**, edge- vs level-triggered. Untouched by B1.
- **`guarded(core::stop)`** in `AdapterRuntime`. Readability only, owner's call, nothing depends
  on it.

Newly opened by B1:

- **Fluid height** for flowing water. Left out deliberately; M4 will probably want it.
- **All of B2** — snapshot, section copying, cache, `isChunkLoaded`'s consumers.
- **The `BlockData` naming amendment** to the roadmap's M3 line.

Newly opened by the 2026-08-14 audit (§4):

- **Slipperiness.** Nothing in `BlockData` distinguishes ice from stone. Deliberate for B1 — no
  consumer exists — but M4's `mv-walk` will want it, and it meets §7's field budget on both counts.
- **The two divergent fixture rows**, carpet and farmland (§5.2). They are pinned per version
  rather than reconciled. If M4 finds the divergence matters behaviourally, the answer is a table
  row, not a classifier change.

---

## 7. Risks

| Risk | Severity | Mitigation / status |
|---|---|---|
| 1.7.10's neighbour-dependent geometry (fences, walls, panes) breaks the per-state flyweight. **Confirmed in source, not merely suspected:** `addCollisionBoxesToList` takes a `World` and coordinates, `BlockFence` calls `canConnectFenceTo` on four neighbours, and 1.7.10's metadata does not record connections | **Medium — the main structural risk** | Resolved as far as B1 needs: `describe` takes a position (§3.2), `FENCE` is a behavioural category, and the cached entry is the first observed instance. `mv-walk` needs "cannot walk over it, cannot jump it", not the millimetres. Residual risk if some block needs *footprint* accuracy per position — §4's audit is what finds out |
| **The 1.7.10 AABB pool.** `getCollisionBoundingBoxFromPool`'s own javadoc says the returned box "can change after the pool has been cleared to be reused" | **Medium — a live correctness hazard** | The adapter MUST copy the six doubles out immediately and never retain the `AxisAlignedBB`. `BlockDescription` already copies its `double[]` on construction, so doing the read inline satisfies this — but it is easy to get wrong and produces corruption that looks like a classifier bug |
| The audit turns up a block that is neither geometry-derivable nor tableable | **Medium** | The one outcome that threatens option A. It is also the B1 gate tripping, so it has a defined response rather than an improvised one |
| `BlockDescription` accretes fields over M4–M8 until the boundary is soft | Medium | **Stated budget:** a field earns its place only if **both** versions answer it natively **and** it covers modded blocks. Otherwise it is a table row |
| ~~`Block.blockRegistry` on 1.7.10 may not yield `minecraft:`-prefixed names~~ | **Closed 2026-08-14** | Verified in source. `RegistryNamespaced.ensureNamespaced` prepends `minecraft:` to any unprefixed name, and FML's `FMLControlledNamespacedRegistry.add` *throws* on a name without a prefix. `getNameForObject` returns `minecraft:stone` |
| No JSON parser on the core classpath; core is `--release 8` | Low | Format is deliberately flat enough for a small hand-written reader |
| `stateKey` strings differ in shape between versions | Low | Intended. Tables are per-version; the parity diff compares `BlockData`, never `stateKey` across versions. Stated so nobody "fixes" it |
| Dev-only dump code ships in the adapter jars | Low | Accepted. It lives in `:runtime`, is inert unless triggered, and the manual step is already the project's normal cost of doing business |

---

## 8. Done criteria

1. `./gradlew clean build` green, including `checkCorePurity`, `checkCoreBytecode` and
   `checkDependencyDirection`. Use `--rerun-tasks` for verification runs; A2b recorded `:core:test`
   reporting `UP-TO-DATE` on a first task and nearly producing a false green.
2. The classifier, loader and lookup suites pass, and the parity test's non-vacuity has been
   demonstrated by mutation.
3. §4's audit table is written up with a verdict per block per version.
4. Both fixture rows are built and dumped by the owner, both files are checked in, and the parity
   test passes against them **and** against the golden.
5. The B1 gate (§6.1) and the standing SPI audit (§6.2) have been evaluated and recorded in the
   roadmap.
6. **Both existing smoke checklists re-run green.** B1 changes `IPlatformContext` and both
   adapters. The A2b precedent applies exactly: the automated suite cannot see the platform
   binding, and only a live client can show it is still wired correctly.

---

## 9. Honest uncertainties

- **The three load-bearing Minecraft API assumptions were verified against decompiled sources on
  2026-08-14**, and one of them was wrong. Sources read: RetroFuturaGradle's
  `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src` and Loom's cached 1.21.11 sources.

  | # | Assumption | Verdict |
  |---|---|---|
  | 1 | 1.7.10's `Block.blockRegistry` yields `minecraft:`-prefixed names | ✅ **Confirmed.** `RegistryNamespaced.ensureNamespaced` prepends; FML's `add()` throws without a prefix |
  | 2 | Both versions report a 1.5-tall fence collision box | ⚠️ **True, but not via the API this spec originally named.** 1.21.11: `FenceBlock` → `24.0F`/16 = 1.5 via `getCollisionShape`. 1.7.10: 1.5F, but only through `addCollisionBoxesToList` — `setBlockBoundsBasedOnState` resets to 1.0F. §3.2 and §3.3 corrected |
  | 3 | 1.21.11's block-state registry id is reachable and session-stable | ✅ **Confirmed.** `Block.BLOCK_STATE_REGISTRY` is a `public static final IdMapper<BlockState>`, with `Block.getId(BlockState)` and `Block.stateById(int)` |

  Assumption 2's correction cascaded into an SPI signature change (`describe` now takes a
  position) and into the risk table (the AABB pool hazard). Both are recorded in place.

  Confirmed in passing and now relied on: `World.chunkExists(cx, cz)` and `World.blockExists`
  (which also pins 1.7.10's range at `0 .. 256`); Forge's
  `Block.isLadder(IBlockAccess, x, y, z, EntityLivingBase)`; `BlockFalling` and `FallingBlock`;
  `BlockTags.CLIMBABLE`; `BlockBehaviour.BlockStateBase.getFluidState`; and that 1.7.10 registers
  `flowing_water` (id 8) and `water` (id 9) as **distinct blocks**, which is what makes §3.5's
  table-based fluid normalisation necessary rather than merely tidy.
- **The audit has not been run.** The design is believed sound, and §2.5 argues its risk is
  bounded, but the evidence that no block falls into "neither" does not yet exist. That is what
  §4 is for, and it is why §4 is the first task rather than a preliminary.
- **`BlockShape`'s member list is a first cut.** `THIN_LAYER`'s 0.25 threshold and the `STAIR`
  two-box rule are guesses at what the geometry actually looks like on both versions; the audit
  may move them.
- **Performance is unmeasured.** The `stateId`/`describe` split is designed to make classification
  rare, but nothing has been profiled, and B1 has no consumer that would show a problem — M4 is
  the first.
- **The injection strategy** is recorded separately in [`docs/injection-strategy.md`](../../injection-strategy.md),
  decided ahead of need during this brainstorm. B1 needs no mixins; M5 is the first that does.
