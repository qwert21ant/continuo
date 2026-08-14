# B2 — World view design

**Date:** 2026-08-14
**Status:** 🟡 **DRAFT — not approved.** Written directly, without a brainstorm dialogue, at the
owner's request, to capture this session's decisions before they are lost. §2 marks which
decisions are settled and which were made while drafting and need sign-off. **§9 asks whether
this sub-project should exist at all.**
**Milestone:** M3, sub-project B, second half
**Depends on:** B1 (`2026-08-14-b1-block-model-design.md`) — hard dependency, see §3
**Roadmap:** [`2026-08-01-mc-automation-roadmap-design.md`](2026-08-01-mc-automation-roadmap-design.md) §3, M3

---

## 1. What B2 is

An immutable, point-in-time copy of a region of the world that the core can read without
touching Minecraft — the thing M4's A\* will actually search over, and the thing that makes
running A\* off the game thread possible later without changing the SPI.

**In scope:**

- `BlockSource` — the read interface the core codes against
- `WorldSnapshot` — an immutable region copy, built section by section
- Section storage, including the uniform-section case
- The fill protocol: which sections, when, and what happens at an unloaded chunk
- `isChunkLoaded`'s first consumer
- Headless tests against a fake `IBlockView`

**Explicitly not in B2:**

| Deferred to | What |
|---|---|
| M4 | A\*, region sizing, text-art fixture worlds, any measurement of whether this is fast enough |
| M5 | Threading. Actually running A\* off the game thread. Cache invalidation. Repathing on staleness |
| M8 | Anything about mining or building |
| Never | An SPI type for snapshots — see §2.1 |

---

## 2. Decisions

### 2.1 Settled during the 2026-08-14 brainstorm

These were decided with the owner and are not re-opened here.

- **The snapshot is a `:core` type, never an SPI type.** `dev.continuo.platform` gets only the
  live, main-thread `IBlockView` from B1. Because the snapshot is core-side, global rule 1 simply
  does not apply to it — there is nothing to carve out, and rule 1 keeps its record of being the
  one rule stated with no exceptions. This is the third time a version/contract tension has been
  resolved by keeping the SPI smaller and moving machinery core-side, after A2a's unbound keys and
  A2b's injection seam.
- **Staleness is the contract, not a bug.** A snapshot is a point-in-time copy by definition. No
  invalidation, no `onBlockChange` / `onChunkLoad` / `onChunkUnload`. **`IGameEvents` stays at one
  method.** When the world moves under a computed path, M5's position resync notices and repaths —
  which is where the equivalent actuation question was deferred to, for the same reason.
- **Filling happens through `IBlockView`, per 16×16×16 section, not per chunk.** A chunk is
  ~98,000 reads; a section is 4,096. The rejected alternative was an SPI method handing over a
  section's raw palette and index array — much faster, much more version-specific (1.7.10 is byte
  arrays plus nibble arrays, 1.21.11 is palettised sections), and a large SPI surface of exactly
  the kind the `package-info` warns against. Revisit only if M4 measures a problem.
- **Immutable by construction, so that off-thread A\* later needs no SPI change.** B2 itself
  ships no threading.

### 2.2 Made while drafting — these need your sign-off

Each is flagged in place below. Summarised here so they are not buried:

| # | Decision | Alternative |
|---|---|---|
| D1 | Two-phase snapshot: `FILLING` (main thread, lazy) then `SEALED` (immutable, any thread) | Eager whole-region copy up front |
| D2 | Sections store `int[4096]` of state ids, with a uniform-section special case | Palettised storage from the start |
| D3 | `BlockSource` is introduced as the interface both the live lookup and the snapshot implement | Snapshot exposes its own unrelated API |
| D4 | Reads outside the filled region return `UNKNOWN`, never air | Throw, or extend the region on demand |

---

## 3. The hard dependency on B1

B2 cannot be built before B1, and the reason is concrete rather than procedural: **a snapshot
stores state ids**, and the fact that a state id exists, is an `int`, is cheap to read per block,
and is native to both versions is a B1 outcome. Before B1 settles that, there is no answer to
"what does a section hold".

If B1's implementation changes the interning model — for instance if the `Block.blockRegistry`
naming assumption in B1 §9 turns out wrong and the state-key scheme has to change — **this draft
needs re-reading before use**, particularly D2.

---

## 4. The tension this draft found

**This was not surfaced during the brainstorm, and it is the most important thing in this
document.**

Two decisions from §2.1 are in tension:

- Filling is **lazy** — sections are copied on first touch, so a search that stays near the player
  never pays for distant terrain.
- The snapshot exists so that A\* can eventually run **off the game thread**.

They are incompatible as stated. The moment A\* runs off-thread and touches an uncopied section,
the lazy fill would have to call `IBlockView` from the wrong thread — a direct violation of global
rule 1, in the one place the whole design was arranged to avoid it.

B2 ships no threading, so this does not bite in B2. It bites at M5. But the entire justification
for building a snapshot rather than just reading `BlockLookup` directly is to make M5 cheap, so
discovering this at M5 would mean having paid for a goal that was never reachable.

### D1 — the resolution this draft proposes

**A two-phase snapshot.**

```java
public final class WorldSnapshot implements BlockSource {
    // phase FILLING — main thread only, sections copied lazily on first touch
    BlockData at(int x, int y, int z);

    void seal();   // one-way; after this the object is immutable and thread-safe

    // phase SEALED — any thread, no further IBlockView calls ever
    // a read outside the filled region returns UNKNOWN (D4)
}
```

While `FILLING`, it behaves exactly as the lazy design intends and is main-thread-only. `seal()`
is one-way and makes it genuinely immutable, after which it can be read from anywhere and **never
calls the SPI again**. Rule 1 is never bent in either phase.

B2 uses it single-threaded and may seal immediately or never. M5's transition becomes: pre-warm
the region on the main thread, seal, hand off. That is a change to M5's executor, not to this
class and not to the SPI.

**The alternative, for the record:** an eager whole-region copy decided up front, with no lazy
phase at all. Honestly off-thread-safe from day one and simpler to reason about. Rejected because
the region size that A\* actually needs is unknown until M4, and eager copying forces that guess
now — a 5-chunk radius over 1.21.11's full height is ~2,900 sections, which is not a number to
commit to before anything measures it.

**The residual risk D1 accepts:** a sealed snapshot whose region is too small returns `UNKNOWN` at
the edges, and M5 must pre-warm correctly or A\* will path badly near the boundary. That is a real
M5 obligation this draft creates, and it should be written into M5's spec rather than discovered
there.

---

## 5. Design

### 5.1 `BlockSource` — D3

```java
// :core
public interface BlockSource {
    BlockData at(int x, int y, int z);
    int minY();   // inclusive
    int maxY();   // exclusive
}
```

Implemented by B1's `BlockLookup` (live, classifying, main-thread) and by `WorldSnapshot`
(frozen). The core codes against this, not against either concrete type.

The payoff lands at M4: **text-art fixture worlds implement `BlockSource` directly**, so
headless pathfinding tests need neither an `IBlockView`, nor the classifier, nor a table — they
construct `BlockData` values and hand them over. That is the "feed the core a fake world" property
the source architecture §8 calls a first-class goal, and this is the type that delivers it.

### 5.2 Section storage — D2

Sections are addressed by `(sectionX, sectionY, sectionZ)` and held in a map keyed by a packed
`long`. The Y range comes from `IBlockView.minY()`/`maxY()`, so 1.7.10's `0..256` and 1.21.11's
`-64..320` need no special casing.

```java
final class Section {
    // exactly one of these is used
    private final int   uniformStateId;   // when the whole section is one state
    private final int[] stateIds;         // 4096 entries, y*256 + z*16 + x
}
```

**The uniform-section case is not an optimisation, it is the common case.** Most sections in a
loaded world are entirely air or entirely stone. Detecting it costs one comparison per block
during a copy that is already happening, and it collapses 16 KB to 4 bytes for the majority of
sections.

Full palettisation — a per-section palette plus packed indices, as Minecraft itself uses — is
**deliberately deferred**. It is a real memory win beyond the uniform case, but B2 has no consumer
that can show it is needed. The trigger to revisit is M4 measuring snapshot memory as a problem.

### 5.3 The fill protocol

While `FILLING`, a read for a position in an uncopied section copies that section first:

1. `isChunkLoaded(cx, cz)` — if false, the section is recorded as **unknown** and every read from
   it returns `BlockShape.UNKNOWN`. It is not recorded as air. Treating unloaded terrain as air is
   how a pathfinder walks confidently into a mountain it has not been told about.
2. Otherwise, 4,096 `stateId` calls fill the section, with the uniform case detected as it goes.
3. `stateId` returning `-1` for individual positions is stored verbatim and classifies as
   `UNKNOWN` on read, so partial availability inside an otherwise loaded section is preserved
   rather than smoothed over.

Sections outside `minY`/`maxY` are never copied and read as `UNKNOWN` — **D4**: outside the
filled region the answer is always `UNKNOWN`, never air, and never an exception. One rule, no
position-dependent special cases, consistent with B1's choice to have `stateId` return `-1`
rather than have callers pre-check bounds.

### 5.4 Interaction with the global rules

- **Rule 1** — `WorldSnapshot` is a `:core` type. While `FILLING` it calls `IBlockView` on the
  main thread; once `SEALED` it never calls the SPI again. Rule 1 is untouched and gains no
  exception.
- **Rule 2** — a snapshot is discarded on `stop()` along with B1's memo. State ids are
  session-scoped, so a snapshot cannot outlive the level it was copied from. This rides on the
  existing `updateLevel` condition; no new machinery.
- **Rules 3 and 4** — untouched.
- **B1's window clause applies unchanged.** A snapshot may only be *filled* while
  `onClientTick`'s delivery window is open. A **sealed** snapshot may be read at any time,
  because it holds no reference to anything the platform owns — which is the strongest argument
  for making sealing explicit rather than implicit.

---

## 6. Verification

Entirely headless. No Minecraft, no manual step, **no smoke-checklist re-run** — B2 adds nothing
to `dev.continuo.platform` and changes neither adapter, which makes it the first sub-project since
A1 with no in-game verification obligation. That is worth stating, because every previous
sub-project has had one and a reader may reasonably expect one here.

- A fake `IBlockView` over an array-backed world; assert `WorldSnapshot.at` matches
  `BlockLookup.at` for every position in the region.
- Uniform sections are detected, and a section with one differing block is not.
- Unloaded chunks read as `UNKNOWN`, not air. **This is the test most worth mutating** — its
  subject is "Y does not happen", exactly the shape A2b found two vacuous tests in.
- `seal()` is one-way; a filling read after sealing does not call `IBlockView`. Assert this
  against a fake view that fails the test if touched after sealing, rather than by inspection.
- Reads outside the region, outside `minY`/`maxY`, and at `-1` positions all yield `UNKNOWN`.

---

## 7. Risks

| Risk | Severity | Mitigation / status |
|---|---|---|
| The lazy/off-thread tension (§4) is resolved by a mechanism nothing exercises until M5 | **Medium** | D1's two-phase design. The risk is not that it fails but that it is *wrong in a way only M5 reveals* — M5 must pre-warm before sealing, and that obligation must land in M5's spec, not be discovered |
| Snapshot memory at a realistic A\* radius | Medium, unmeasurable now | Uniform-section case handles the bulk. Palettisation deferred with an explicit trigger. **B2 cannot measure this; M4 is the first thing that can** |
| Region sizing is a guess until M4 exists | Medium | Why D1 chose lazy over eager. The guess is deferred rather than made |
| B1's interning model changes during implementation | Low | §3. Re-read D2 if it does |
| Fill cost: 4,096 SPI calls per section on the game thread could cause a tick spike | Low–Medium, unmeasured | Not addressed in this draft. Spreading fills across ticks is the obvious answer and is **deliberately not designed here** — it needs a consumer to shape it |

---

## 8. Done criteria

1. `./gradlew clean build` green, including all three machine-checked invariants. Use
   `--rerun-tasks`.
2. Headless suite passes; the unloaded-chunk test and the sealing test have had their non-vacuity
   demonstrated by mutation.
3. `BlockSource` is implemented by both `BlockLookup` and `WorldSnapshot`, and core code reads
   through it rather than through either concrete type.
4. No new SPI types, no new `IGameEvents` methods, no adapter changes — verifiable by inspecting
   the diff, and a stated success condition rather than an outcome.
5. M5's pre-warm-before-seal obligation (§4) is recorded in the roadmap.

---

## 9. Should B2 exist? — the question this draft raises

Stated plainly, because drafting made it hard to ignore.

**B2 has no consumer.** M4's A\* is the first thing that reads a snapshot, the first thing that
can size a region, and the first thing that can measure whether any of this is fast enough or
small enough. B2 builds a cache, chooses a storage layout, and picks a fill strategy with nothing
able to exercise any of those choices.

Three of §7's five risks are some form of "unmeasurable until M4".

**The case for keeping B2 separate:** it is genuinely headless and fully testable on its own; the
roadmap names it as part of B; and it keeps M4 focused on pathfinding rather than on pathfinding
plus a storage layer.

**The case for folding it into M4:** everything it defers is something M4 immediately answers.
Region sizing, memory, fill cost, and whether lazy-then-seal was the right shape are all M4
measurements. Building it inside M4 means each choice is made with a consumer in front of it —
which is the same reasoning that put A2 before B in the first place, and the same reasoning that
made A2b wait until two adapters existed before writing a conformance suite. *"Writing it against
one adapter risks encoding Fabric's accidents as the contract"* has an exact analogue here:
writing a snapshot with no consumer risks encoding a guess as the design.

**This draft's recommendation is to fold B2 into M4** and let B1 be the whole of M3. The
counter-argument is real and the owner's call. If B2 stays separate, §4's D1 is the decision most
worth challenging, because it is the one made furthest from any evidence.
