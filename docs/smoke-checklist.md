# Manual smoke checklist — both versions

Manual in-game verification. Needs a graphical client, real keypresses, and a human watching.
Run after `./gradlew build` is green. Cannot be automated or run in CI.

**Supersedes `docs/smoke-checklist-a1.md` and `docs/smoke-checklist-a2.md`**, merged 2026-09-04.
Step numbers 1–12 are unchanged from those files, so the historical records at the bottom still
refer to the steps they were written against. Plans dated before 2026-09-04 cite the old filenames.

**Never run `./gradlew clean`.** It deletes `adapters/adapter-forge-1.7.10/build/rfg/minecraft-src/`
— the decompiled sources that are the only authority for reviewing adapter code, and adapters have
no tests. `build` is the project's gate.

**Run the whole thing on both versions.** Record pass/fail per step. Any single failure blocks
sign-off.

## Keys

| key | action |
|---|---|
| `H` | mark the block you are standing on as the goal |
| `K` | walk to the marked goal (the executor) |
| `L` | run the path probe |
| `J` | write the block dump |

## Version differences

| | 1.21.11 (Fabric) | 1.7.10 (Forge) |
|---|---|---|
| run | `./gradlew :adapters:adapter-fabric-1.21.11:runClient` | `./gradlew :adapters:adapter-forge-1.7.10:runClient` |
| startup line | `Continuo core started on 1.21.11 / FABRIC` | `Continuo core started on 1.7.10 / FORGE` |
| survival | `/gamemode survival` | `/gamemode 0` |
| creative | `/gamemode creative` | `/gamemode 1` |
| teleport | `/tp @s <x> <y> <z>` | `/tp <yourname> <x> <y> <z>` |
| controls | Controls → "continuo" category (`key.continuo.mark`, `key.continuo.walk`) | Options → Controls → "Continuo" category |
| dump target | `docs/parity/blocks-1.21.11.txt` | `docs/parity/blocks-1.7.10.txt` |
| screen class | `Screen` / `KeyMapping` | `GuiScreen` / `KeyBinding` |
| tick phases | `START_CLIENT_TICK` / `END_CLIENT_TICK` | `TickEvent.Phase.START` / `.END` |

Create the world with **cheats enabled** — steps 2, 11, 16, 19–22 all use commands.

## Expected noise

- **Probe key mid-walk.** Pressing `L` while `K` is driving puts two `setLook` writers on one
  actuator, so the probe's look-round-trip and standing notices can fire spuriously. Dev-only,
  expected. Press `L` only while the bot is idle.
- **[1.7.10] OpenAL.** `java.lang.UnsatisfiedLinkError: org.lwjgl.openal.AL10.nalListenerf(IF)V`
  with a paulscode trace, on every run. The dev client runs with sound disabled deliberately
  (`b762e99`) to dodge a reproducible 1.7.10 sound-engine crash. **Do not confuse it with the
  `IllegalAccessError` in step 4** — that one is a real failure.

---

# Part 1 — Basic walk and lifecycle (steps 1–12)

**1. Startup log.**
- Do: watch the log while the client boots, before the main menu.
- Expect: the version's startup line from the table above.
- Fail: missing → the mod did not load, or `IPlatformInfo` is not wired. Check for earlier
  mod-loading errors first.

**2. World.**
- Do: create a new Superflat world with cheats on. Once spawned, switch to Survival.
- Expect: F3 or the pause menu confirms Survival.
- Why: every executor constant — turn rate, when `JUMP` is pressed on an ascend, arrival tolerance
  — is sized against Survival walking and ground collision. Creative flight bypasses gravity, so
  every walk step below is invalid in Creative.

**3. Baseline.**
- Do: F3, write down X/Y/Z.

**4. Mark and walk — flat ground.**
- Do: walk ~10 blocks from the baseline over open flat ground. Press `H` on the block you are
  standing on. Walk back past the baseline. Press `K`.
- Expect: log `Continuo: walking to (x, y, z)` naming the marked block; the player walks there
  unaided, turning gradually rather than snapping, and stops on it. Log `Continuo executor: arrived`.
- Fail — `Continuo: no goal marked -- stand on the destination and press the mark key`: the mark key
  did not register. Check the Controls entry.
- Fail — logs "walking to" but no movement: the actuator is not reaching the key mapping, or `K` is
  bound elsewhere.
- Fail — never stops, well past the mark: real failure. Do not wait indefinitely.
- Fail — **[1.7.10]** `IllegalAccessError` on `K`: `ForgeActuator.setInput` writes `KeyBinding.pressed`
  (`field_74513_e`) directly, which works only because `META-INF/continuo_at.cfg` widens it. The
  access transformer is proven at compile time; this is the only check that it takes effect at
  runtime. Record it as exactly that finding, not as "walk doesn't work".

**4a. [1.7.10] Unbound Forward key.** Run after step 5.
- Do: Options → Controls, set vanilla **Forward** to "Not bound", leaving `K` bound. Mark a nearby
  goal, press `K`, let it finish. Rebind Forward afterwards.
- Expect: the player still walks to the mark and stops on it.
- Why: `ForgeActuator` addresses the `Forward` `KeyBinding` instance directly rather than going
  through keycode-addressed `KeyBinding.setKeyBindState`, so movement does not depend on the user's
  binding. **A2a deleted `IActuator`'s unbound-key clause on the strength of this.** If it fails,
  flag it explicitly — that deletion has to be reconsidered.

**5. Arrival.**
- Do: once stopped, F3 and read X/Y/Z.
- Expect: the block position matches the marked block exactly. The executor walks to a named block,
  so there is no expected range.
- Fail — stops short: a repath may still be in flight; wait a few seconds before recording a failure.
- Fail — overshoots or oscillates around the mark: the follower's arrival check is misbehaving.

**6. Repeat.**
- Do: walk a short distance in a different direction, `H`, `K`.
- Expect: walks to the new mark and stops on it.
- Fail — nothing happens, or it walks to the old mark: state is not resetting, or the mark is not
  being replaced.

**7. Re-trigger mid-walk.**
- Do: mark a distant goal, press `K`, and **while still moving** mark a different goal where you
  currently are (`H`) and press `K` again.
- Expect: the first destination is abandoned; the player walks to the second mark.
- Fail — the second press is ignored, or it ends at the first mark. `walkTo` is specified to replace.

**8. Disconnect mid-walk.**
- Do: mark a distant goal, press `K`, and while moving choose "Save and Quit to Title". Rejoin the
  same world.
- Expect: after rejoining the player is not drifting and `W` is not stuck down — you can stand still.
- Fail: `core.stop()` is not being called on world unload, or the key release is not reaching the
  game. **This is the step most likely to reveal a real defect** — verify it properly.

**9. Title-screen keypress.**
- Do: from the main menu, before loading any world, press `K` five or six times. Then load the step-2
  world (reuse it).
- Expect: no `Continuo: walking to` and no `Continuo: no goal marked` from those presses, and no walk
  starts on its own after the world loads.
- Why: `onClientTick` delivers ticks only while a world is loaded with a local player. Either line at
  the title screen means something is driving the core outside the tick window.
- **Does not verify the click drain or the in-world guard.** Both live in the shared `AdapterRuntime`.
  Minecraft only accumulates key clicks while no screen is open, and the title screen is a screen, so
  nothing is queued — this step passes identically against a build with either mechanism deleted. It
  is a tripwire for a walk appearing from nowhere, nothing more.

**10. Leave a world mid-walk.**
- Do: mark a distant goal, press `K`, and while moving choose "Save and Quit to Title". Stay at the
  title screen.
- Expect: `Continuo stopping: client level changed` appears **freshly**, after you quit.
- Caveat: the level watch logs that same line on every level-identity change, including the ordinary
  world load in step 2. Its presence somewhere in the log is not proof it fired here.
- Fail: the level watch is not observing the transition to a null level.

**11. Dimension change mid-walk.**
- Do: build a nether portal near spawn (Creative for materials; **back to Survival before `K`**).
  Mark a goal on the far side of the portal, press `K`, step into the portal while moving.
- Expect: once the Nether has loaded, the player is not still walking and not drifting.
- Why: A2a settled that a dimension change counts as a world unload. Both adapters implement it
  through one level-identity condition, so a failure on either version means the two have diverged.
- Fail: `AdapterRuntime.updateLevel` is not reached before the in-world guard, or the level is being
  compared by value rather than identity.

**12. Block dump.** **Do steps 13–22 first — this step ends in a different world.**
- Do: from the title screen create a **new** Superflat world in **Creative**. Build the 32-block
  fixture row from `docs/parity/fixture-layout.md` (that file, not this one, is the source of truth
  for the corridor and its index-to-block table). Stand at index 0, the west end (bare air), face
  **+X, east**. Press `J`.
- Expect: `Continuo: wrote block dump to <path>` naming an absolute path ending in
  `continuo-block-dump.txt` in the run directory. Copy it into the repo as the version's dump target
  from the table above, overwriting what is there.
- Fail: look for `Continuo: could not write the block dump` — the write is wrapped in try/catch.
- **A green dump does not prove the block model is correct**, only that the two adapters agree. See
  the disclaimer at the bottom.

---

# Part 2 — Executor criteria (steps 13–18)

D2 spec §11, criteria 4–8. Run these in the step-2 world. Switch to Creative to build, **back to
Survival before every `K`**.

**13. Off a drop.**
- Do: dig a pit at least 3 deep about 10 blocks from where you stand, with an open (non-vertical)
  approach. Stand at the bottom, press `H`. Climb out, walk back, press `K`.
- Expect: the player walks to the edge, descends, and stops on the marked block. `Continuo executor:
  arrived`.
- Fail — stops at the lip and never descends, or oscillates at the edge.
- Fail — repeated `Continuo executor: off path, repathing` on the descent: the fall carries the
  player further from the path than `OFF_PATH_RADIUS`. Record how far it fell before the line
  appeared — that is a real figure for that constant.

**14. Up a staircase — at least four consecutive ascends.**
- Do: build a straight staircase of **at least 6** single-block steps (four is the minimum that can
  expose the throttle; more makes the timing readable). Mark the top block with `H`, stand at the
  bottom, press `K`.
- Expect: the player climbs continuously, one step after another, and stops on the top block.
- Fail — **climbs one block then pauses roughly half a second before the next**: this is the ten-tick
  jump cooldown throttling a held `JUMP`. It means `ASCEND`'s `onGround()` gate is not producing the
  release that clears the cooldown. Record the per-step interval.
- Fail — does not climb at all: `onGround()` never reports true on this version, or `ASCEND` is not
  being driven.
- Why four: §3.9's cooldown is ten ticks, and a single ascend cannot expose a throttle. This is also
  the first real evidence about `onGround()` on either version, which D2 made load-bearing.
- **If the two versions differ here, that is the finding** — record what each did rather than
  averaging them.

**15. Inventory mid-walk.**
- Do: mark a distant goal (30+ blocks), press `K`, and while moving open the inventory (`E`), hold it
  open ~2 seconds, close it.
- Expect: the player keeps walking the whole time — at most one lost tick — and arrives.
- Fail — the player stops when the screen opens and does not resume: level-triggered actuation is
  not re-stating the input set each tick. Opening a screen clears held key state on both versions, so
  a core that wrote inputs only on change would truncate the walk here.
- Why: rule 4's regression check, re-pointed at the executor now the 40-tick walk demo is gone.
  **This is the only in-game evidence that level-triggering does what it claims.**

**16. `/tp` mid-walk — the `onPositionCorrection` criterion.**
- Do, large displacement: mark a goal 40+ blocks away, press `K`, and while moving `/tp` yourself
  ~10 blocks sideways onto open ground.
- Expect: `Continuo executor: off path, repathing`, then the player resumes and arrives at the mark.
- Do, small displacement: repeat, but `/tp` about 1 block sideways.
- Expect: **no** `off path` line — the anchor absorbs it and the walk continues unbroken.
- Do, binary search: repeat the sideways `/tp` at 1, 1.5, 2, 2.5, 3 blocks. Record the smallest that
  produced a repath and the largest that did not. That brackets `OFF_PATH_RADIUS`.
- Fail — the player keeps walking toward the old path, stands still indefinitely, or ends with
  `Continuo executor: giving up after 3 repaths without progress`.
- **Why this one matters most:** it is what discharges dissolved `onPositionCorrection`. If it
  passes, D1 §3.5's argument holds. If the executor has to be *told* it was moved, the question
  reopens as "add a mixin and a coremod" — an expensive answer, so establish this carefully rather
  than charitably. Run it several times, on both versions.

**17. Gradual turning.**
- Do: mark a goal roughly 20 blocks **directly behind** you, so the required turn is near 180°. Face
  away from it, press `K`.
- Expect: the view rotates smoothly over roughly a third of a second, not in a single frame.
- Record: the time for the full turn, to the nearest tenth of a second. 180° at 30°/tick is 6 ticks
  = 0.30 s.
- Fail — an instant snap: the humanizer is not in the path, or `MAX_DEG_PER_TICK` is far larger than
  shipped.

**18. Standing invariant.**
- Do: stand still (bot idle — do not press `L` mid-walk) and press `L` on each of three spots: plain
  flat ground, the bottom of step 13's pit, and a step partway up step 14's staircase.
- Expect: the `Continuo path probe:` summary line contains **no** notice beginning `onGround is true
  but the block below the feet`.
- Fail: on plain ground that notice means `IPlayerView.y()` is not the bottom of the collision box —
  on 1.7.10, `posY` (the stance, 1.62 blocks high) where it should be `boundingBox.minY`.
- Do not test this on a slab, stair, fence post, ladder, boat, block boundary, or in fluid — the
  notice is legitimate there.

---

# Part 3 — Constants (steps 19–22)

**All seven constants D2 shipped are extrapolated.** Each javadoc must be rewritten with what this
run actually shows, exactly as `Run.SLICE_NODES` was after D1. **A constant whose javadoc still
states a prediction after this run is an unmet done criterion, not a tidy-up.**

Be honest about which of these is a *measurement* and which is only an *observation that the shipped
value was adequate* — write the javadoc to say which it is.

| constant | shipped | file | from | what you can record |
|---|---|---|---|---|
| `MAX_DEG_PER_TICK` | `30.0f` | `HumanizedActuator.java:48` | step 17 | **measurement** — seconds for a 180° turn ÷ 0.05 s = ticks; 180 ÷ ticks = deg/tick |
| `OFF_PATH_RADIUS` | `2.0` | `PathFollower.java:47` | steps 13, 16 | **measurement** — the bracket between the largest absorbed `/tp` and the smallest that repathed |
| `STUCK_TICKS` | `40` | `PathExecutor.java:74` | step 19 | **measurement** — seconds from the player ceasing to progress to the `stuck, repathing` line |
| `MAX_CONSECUTIVE_REPATHS` | `3` | `PathExecutor.java:82` | step 20 | **measurement** — repath lines before the give-up line (expect 4) |
| `ARRIVE_RADIUS` | `0.5` | `PathFollower.java:54` | steps 5, 6, 13, 14 | **observation** — whether the final block matched the mark exactly, every run |
| `PLAN_AHEAD_STEPS` | `20` | `PathExecutor.java:65` | step 21 | **observation** — whether a multi-segment walk ever stalled |
| `W` | `6` | `PathFollower.java:39` | step 22 | **measurement of the thing it is sized against** — the counted revolution length of a real staircase |

**19. Stuck detection.**
- Do: mark a goal ~25 blocks away over flat ground and press `K`. While the player is walking,
  switch to Creative long enough to place a solid wall **3 blocks high and wide enough not to be
  walked around in one step** directly in its path, then note the moment it stops making progress.
- Expect: about **2 seconds** later, `Continuo executor: stuck, repathing`. The new search sees the
  wall and routes around or over it; the player continues.
- Record: the interval, to the nearest half second. That is enough to tell 40 ticks from 20 or 80.
  The log's own timestamps give the line's exact moment.
- Fail — no `stuck` line at all: the detector measures path progress rather than position, so a
  player shuffling against a block boundary should still count as stuck.

**20. Repath budget.**
- Do: mark a goal 40+ blocks away, press `K`, then `/tp` yourself ~10 blocks sideways **four times in
  a row**, each time before the player has had a chance to advance along the new path (a second or
  two apart).
- Expect: `Continuo executor: off path, repathing` after each of the first three, then
  `Continuo executor: giving up after 3 repaths without progress`. The player then stands still with
  every input released — it must not drift.
- Record: how many repath lines preceded the give-up line.
- Note: the counter resets on any anchor advance, so if you leave too long a gap between teleports
  the budget will not be reached. That is correct behaviour, not a failure — a long route that
  repaths while making real progress is not supposed to be killed.

**21. Plan-ahead over a long walk.**
- Do: mark a goal **100+ blocks** away over open ground (walk out, `H`, walk back, `K`). Watch the
  whole walk.
- Expect: continuous movement to the goal. Multi-segment searches happen while walking, so there
  should be **no visible pause** anywhere along the route.
- Fail — a visible stall mid-route, or `Continuo executor: stuck, repathing` with nothing in the way:
  the path ran out before the next segment was ready, which means `PLAN_AHEAD_STEPS` is too small.
- Fail — `Continuo executor: search exceeded its budget with nothing to show for it`: `BUDGET_EXCEEDED`
  was reached. This handling is untested in fixtures and was judged near-unreachable; **if you see it,
  that is a finding worth recording in detail** — all multi-segment executor behaviour rides on the
  same gap.
- Record: route length, and any stall.

**22. The `W` mutation — execute it, do not reason about it.**

D1 §15.2 established that executing one mutation in a client is worth more than any amount of arguing
about it. The unit test demonstrates the property on a synthetic self-crossing route; the revolution
length of a *real* staircase is reasoned, not counted.

- **22a. Build a self-crossing climb.** Either a 3×3 spiral staircase of at least two full
  revolutions, or — much cheaper to build — a **switchback**: run up 6 steps east, turn 180°, run up
  6 steps west directly above the first run with a single block of floor between them, and repeat.
  The tighter the two runs are vertically, the better this tests what `W` is for.
- **22b. Count the revolution.** Count the number of path steps between a block and the block
  directly above it on the next run. **Write this number down.**
  **If it is six or fewer, `W = 6` is already too large and the shipped constant is wrong** — that is
  a finding on its own, before any mutation.
- **22c. Baseline.** Mark the top block, stand at the bottom, press `K`.
  Expect: the player climbs the whole structure and stops on the mark.
- **22d. Mutate.** Edit `core-engine/src/main/java/dev/continuo/engine/PathFollower.java:39` —
  `static final int W = 6;` → roughly **three times** the number counted in 22b. Then
  `./gradlew build` and restart the client.
- **22e. Re-run 22c.** Expect the walk to break on the self-crossing structure. **Do not assume which
  way it breaks.** The unit test's failure mode is the anchor jumping *backwards* (it asserted 21 and
  got 1), so the bot re-walking a stretch it already climbed is as plausible as it driving at a node
  a revolution ahead and into a wall. **Record which one actually happened.**
- **If raising `W` changes nothing, that is a finding, not a pass.** It means either the structure
  does not cross itself tightly enough — rebuild it tighter and retry — or `W` is not doing what
  §3 of the spec claims it does. Report it rather than moving on.
- **22f. Revert, and verify the revert.**
  `git checkout -- core-engine/src/main/java/dev/continuo/engine/PathFollower.java`, then
  `git status` — the tree must be clean, and open the file to confirm `W = 6`. A subagent left a
  mutation in the working tree after D1's in-game run; check rather than assume.

---

# Writing up the results

Follow D1's precedent — `docs/superpowers/specs/2026-09-02-d1-spi-player-state-design.md` §15 — and
write this run up as **§15 of `docs/superpowers/specs/2026-09-03-d2-path-executor-design.md`**:

1. A table of what passed per version.
2. The measured figures, and the seven javadocs rewritten to match.
3. **Anything that landed off its prediction, stated as a correction rather than silently patched.**
   D1's cold slice ran 24% over its arithmetic and saying so was worth more than the number.

Commit it on `master` as the criteria-closing follow-up to D2's merge — the convention is a `--no-ff`
merge per sub-project (`git show 55e0141`) followed by a criteria-closing commit (`git show e4547f1`
for D1's).

---

# Not covered by this checklist

Do not record a green run as evidence for any of these.

- **Global rule 3 (fault handling).** Exercising it requires deliberately making the core throw.
- **The click drain (`drainClicks`).** Every tester-reachable moment with no world loaded also has a
  screen open, and Minecraft only accumulates key clicks while no screen is open — so no manual
  sequence here queues a click for the out-of-world drain to discard. Its one genuinely reachable
  path is the *faulted* one, which is in-world, and is out of scope for the same reason rule 3 is.
- **PRE/POST phase pairing across a mid-tick world change.** `AdapterRuntime`'s `preDelivered` latch
  closes one direction only: `PRE` **can** go unpaired if the tick window closes or a fault is set
  between the two phases. That is the exception the `onClientTick` contract permits, not a defect.
  It is unobservable in play either way, because `ContinuoCore` ignores `POST` entirely. It becomes
  worth a step as soon as any core behaviour acts on `POST`.

Those three are covered — **for the shared logic only** — by the `platform-testkit` conformance suite
added in A2b. **The suite and this checklist are complements.** A green suite says nothing about
whether an adapter passes the correct level or player object, whether `setInput` moves the player, or
whether `PRE` precedes the game's input read. Neither is evidence about the other's subject.

- **Whether the block model is correct (step 12).** `BlockParityTest` is a diff, not a judgement: it
  can only show the two adapters agreeing or disagreeing with each other, never with the game. Both
  could misreport the same block the same way and the diff would still be green. That is what the
  per-version goldens in `docs/parity/` exist to catch — each is a human's line-by-line review of one
  version's dump, checked in separately because the versions genuinely disagree on two of the
  thirty-two rows (carpet's collision box, farmland's depth), so no single golden could match both.
  A golden is only as good as the audit behind it.

---

# Records

**Limits that apply to every record below**, stated once rather than repeated: the owner gave a
one-line summary rather than a per-step table each time, so each is the owner's statement that the
checklist passed — **not a set of individually transcribed step results**, and no sub-check is
separately attested except where noted. None of them reported a displacement figure except
2026-08-13.

| date | version | scope | result |
|---|---|---|---|
| 2026-08-15 | both | post-B1: `blocks()` on `IPlatformContext`, per-version block views, the step-12 dump keybind | **everything OK**; block dumps produced, reviewed by eye, signed off as `blocks-*.txt` + `golden-*.txt`; `BlockParityTest` 8 tests / 0 skipped, adapters agree on all 27 compared indices |
| 2026-08-14 | both | post-A2b: conformance machinery moved into the shared `AdapterRuntime` | **everything still working**; closed A2b's done criteria 3 and 4 |
| 2026-08-13 | both | post-A2a: level-identity replaced the `JOIN`/`DISCONNECT` handlers | **all steps passed**, portal step included; keybinding changes behaved as expected |

**Detail worth keeping from 2026-08-13, the only run where sub-checks were separately attested:**

- 1.7.10 displacement was **8–9 blocks**; the Fabric figure of **8 blocks** dates from 2026-08-11.
  These are the only measured figures on record.
- **Step 4a's unbound-Forward sub-check passed, explicitly confirmed.** With vanilla Forward set to
  NONE the bot still walked. This is the load-bearing observation behind A2a's deletion of
  `IActuator`'s unbound-key clause (design §3.2, §5.3).
- **The access transformer is confirmed to work at runtime**, not merely at compile time: no
  `IllegalAccessError` occurred, so `META-INF/continuo_at.cfg`'s widening of `KeyBinding.pressed`
  (`field_74513_e`) took effect under `runClient`. In the 2026-08-14 and 2026-08-15 records this
  follows from the run passing as a whole rather than from a separate observation.

Each record covers the adapter as it stood on its date; the later record supersedes the earlier for
anything about the current code. **None of the three covers the executor** — all three predate D2,
and steps 13–22 have never been run.
