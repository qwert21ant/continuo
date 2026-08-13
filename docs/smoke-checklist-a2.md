# A2 manual smoke checklist — Forge 1.7.10

This is a manual, in-game verification. It cannot be automated or run in CI: it requires a
graphical Minecraft client, a real keypress, and a human watching the result. Run it after
`./gradlew clean build` is green.

Run: `./gradlew :adapters:adapter-forge-1.7.10:runClient`

Work through every step below, in order, and record pass/fail for each. Any failure blocks
A2 sign-off — do not skip a step or assume it would have passed.

1. **Startup log.** Watch the launcher/game log while the client boots, before you reach the
   main menu. It must contain the line:
   `Continuo core started on 1.7.10 / FORGE`
   *Observe:* the exact line above (game version and loader name may vary only if you're on a
   different build — for this checklist expect exactly this).
   *If it's missing:* the mod did not load at all, or `IPlatformInfo` is not wired up.
   Check the log for earlier mod-loading errors first.

2. **World.** Create a new Superflat world. It will start in whatever mode you chose at
   creation; once you've spawned, switch to Survival with `/gamemode 0` (1.7.10 identifies
   game modes by number rather than by name — `0` is Survival; there is no `/gamemode
   survival` form here).
   *Observe:* the F3 debug screen (or the pause menu) confirms you are in Survival, not
   Creative. The command's own chat response ("Set own game mode to Survival Mode") is also
   valid confirmation if the debug screen is ambiguous.
   *Why this matters:* Creative flight and Creative's movement differ from vanilla walking
   speed. If you're not in Survival, the distance measured in step 5 is meaningless and the
   checklist result is not valid — go back and fix the game mode before continuing.

3. **Baseline.** Press F3 to open the debug overlay. Record the XYZ coordinates shown and
   note which axis you're facing (the "Facing" line shows a compass direction and which of
   X/Z is changing as you look that way).
   *Observe:* write down the starting X, Y, Z and the facing axis (X or Z) before doing
   anything else.

4. **Walk.** Press `K`. Expected: the player walks forward on its own, with no further input
   from you, and stops by itself after a short walk. The log should contain the line
   `Continuo walk requested`.
   *If the player does not move at all:* the actuator is not reaching the key binding, or the
   keybind is bound to something other than `K` — check Options → Controls for the entry
   named **"Walk Forward 40 Ticks"** under the **"Continuo"** category (this module ships a
   language file, so the display name shows, unlike the Fabric adapter's raw
   `key.continuo.walk`) — see step 5's "zero" case too.
   *If the player never stops walking:* do not wait indefinitely — this is a failure. See
   step 5's "never stopping" case.
   *If pressing `K` produces an `IllegalAccessError` in the log/console instead of movement:*
   this is a specific, known risk for this adapter, not an unexplained bug to puzzle over.
   `ForgeActuator.setInput` writes the `pressed` field on the chosen `KeyBinding` instance
   directly, which only compiles — and only works at runtime — because
   `META-INF/continuo_at.cfg` widens that private field (`field_74513_e`) to public. The
   access transformer is proven to work at compile time; whether it actually takes effect
   under `runClient` has never been observed before this checklist is run. An
   `IllegalAccessError` here means it did not take effect at runtime — record it as exactly
   that finding, not as a generic "walk doesn't work."
   *Sub-check — unbound Forward key.* Perform this after you have completed the walk above and
   step 5's distance check. Open Options → Controls and unbind the vanilla **Forward** key
   (set it to "Not bound"), leaving "Walk Forward 40 Ticks" still bound to `K`. Press F3 to
   note your position, then press `K` and let the walk run to completion.
   *Observe:* the player must still walk forward and stop after roughly the same distance as
   the step 5 baseline. It does not need to be re-measured to step 5's precision — the point
   under test is that movement happens at all with Forward unbound, not the exact figure.
   *Why this result matters beyond a pass/fail tick:* `ForgeActuator` addresses the `Forward`
   `KeyBinding` instance directly, rather than going through 1.7.10's keycode-addressed
   `KeyBinding.setKeyBindState`, specifically so that movement does not depend on what (if
   anything) the user has Forward bound to. A later task in this plan deletes a clause from
   the SPI contract on the strength of this behaviour holding. **If the player does not move
   with Forward unbound, that deletion must not happen** — flag this result explicitly rather
   than recording a quiet fail and moving on.
   Rebind Forward to its default key before continuing to step 6, so later steps' movement
   isn't affected by a missing default control.

5. **Distance.** Once the player has stopped, press F3 again and read the new XYZ. Compute
   the displacement along the facing axis you recorded in step 3 (the difference in X or Z,
   whichever was changing).
   *Observe:* displacement should be **8–9 blocks**. Forty ticks at vanilla walking speed is
   about 8.6 blocks, so this is the expected range, not exactly 8 or exactly 10.
   *Diagnostic interpretations if it's outside 8–9 blocks:*
   - Roughly double (~17 blocks) or roughly half (~4 blocks) means the core is acting on the
     wrong number of ticks. Note that the adapter is *deliberately* registered on both halves
     of the client tick (`TickEvent.Phase.START` -> PRE, `TickEvent.Phase.END` -> POST) and
     that is correct and required by the SPI contract. The bug to look for is the core acting
     on `POST` as well as `PRE`, or the hook being on the server tick
     (`TickEvent.ServerTickEvent`) instead of the client tick.
   - Zero (player never moved) means the actuator is not reaching the key binding — the
     `Forward` binding's `pressed` field is not actually being set even though the walk was
     requested. If this is the very first `K` press of the session, check step 4's
     `IllegalAccessError` note first.
   - Never stopping (movement continues past a reasonable point, e.g. well past 9 blocks and
     still going) means the tick counter is not advancing or the stop condition never fires.

6. **Repeat.** Press `K` again from wherever you ended up. Expected: the walk repeats
   identically — forward movement starts, then stops on its own after the same ~8–9 blocks.
   *If it does not repeat (e.g. nothing happens, or the walk is a different length):* internal
   state is not resetting between walks.

7. **Re-trigger mid-walk.** Press `K` to start a walk, then press `K` again while the bot is
   still moving (partway through, not after it has stopped).
   *Observe:* the bot must walk the same 8–9 blocks total from where the first `K` was
   pressed, not further than that.
   *If the second press extends or restarts the walk:* re-triggering is specified as ignored
   while a walk is in progress, so this is a failure — the mid-walk `K` press is being
   accepted when it should be dropped.

8. **Disconnect mid-walk.** Press `K` to start a walk, and while the bot is still moving
   (before it has stopped on its own), open the pause menu and choose "Save and Quit to
   Title". Rejoin the same world.
   *Observe:* after rejoining, the player must **not** be drifting forward on its own, and
   the W key must not be stuck down (movement should behave completely normally — you should
   be able to stand still).
   *If the player keeps moving forward after rejoining, or W appears stuck:* `core.stop()` is
   not being called on world unload (or the key release is not reaching the game), leaving
   input state stuck across a reconnect. This is the step most likely to reveal a real
   defect — verify it properly, don't assume it passed because the earlier steps did.

9. **Title-screen keypress.** From the main menu, before loading any world, press `K` five
   or six times. Then load the world you created in step 2 (reuse it — do not create a new
   one).
   *Observe:* the log must **not** contain `Continuo walk requested` from those presses, and
   the player must not start walking on its own at any point after the world loads.
   *Why this matters:* the SPI's `onClientTick` contract delivers ticks only while a world is
   loaded with a local player. A `Continuo walk requested` line logged at the title screen, or
   a walk starting on its own after the world loads, means something is driving the core
   outside the tick window — a real defect, whatever its cause.
   *What this step does NOT verify — read before recording a pass:* it exercises neither the
   adapter's out-of-world click drain nor its in-world guard. Minecraft only accumulates
   `KeyBinding` clicks while no `GuiScreen` is open, and the title screen is a screen, so
   nothing is queued — neither for the drain to discard nor for the guard to hold back. This
   step therefore passes identically against a build with either mechanism deleted. It is a
   tripwire for a walk appearing from nowhere, not evidence that either mechanism works. The
   drain's only reachable path is the faulted one, which is in-world; see the coverage note
   below for why this checklist cannot reach it.

10. **Leave a singleplayer world mid-walk.** Press `K`, and while the bot is still moving
    choose "Save and Quit to Title". Stay at the title screen this time rather than
    rejoining.
    *Observe:* the log must contain `Continuo stopping: client level changed`.
    *Why this matters:* global rule 2 requires `stop()` on world unload, and A2a settled that
    trigger as a level-identity change rather than a connection event. Step 8 checks the
    symptom after a rejoin; this step checks the cause directly. If the line is absent, the
    level watch is not observing the transition to a null level.
    *Caveat on this line as evidence:* the level watch logs this same line on **every**
    level-identity change, including the ordinary transition when you first loaded the world
    in step 2 — so the line's mere presence somewhere in the log is not by itself proof it
    fired here. Confirm it appears freshly, after you choose "Save and Quit to Title", not
    just that it exists earlier in the log.

11. **Dimension change mid-walk.** Build a nether portal near spawn (you will need to switch
    to Creative for materials; switch back to Survival before pressing `K`). Press `K` and
    step into the portal while the bot is still moving.
    *Observe:* once the loading screen clears and you are in the Nether, the player must
    **not** still be walking forward, and must not be drifting.
    *Why this matters:* global rule 2 was settled in A2a so that a dimension change counts as
    a world unload, because the core's state describes a position in a level that no longer
    exists. Both adapters implement this through one level-identity condition, so a failure
    here on either version means the two have diverged — the exact thing the contract exists
    to prevent.
    *If the player keeps walking after the dimension change:* `updateLevel` is not being
    reached before the in-world guard, or the level instance is being compared by value rather
    than by identity.

**Not covered by this checklist:** global rule 3 (fault handling). Exercising it requires
deliberately making the core throw, which is not something to leave in the tree. Rule 3 is
implemented and knowingly unverified until M2's `platform-testkit` covers it. Do not record
this checklist as evidence that fault handling works.

Also not covered: the adapter's click drain (`drainClicks`). Every tester-reachable moment
with no world loaded also has a `Screen` open — the title screen, the world-selection list,
the world-loading screens — and Minecraft only accumulates `KeyMapping` clicks while no screen
is open. No manual sequence available here queues a click that the out-of-world drain then has
to discard, which is why step 9 explicitly disclaims it. The one drain path that is genuinely
reachable in play is the *faulted* path, which happens in-world with no screen up; that is out
of scope for the same reason rule 3 above is. The drain is implemented and knowingly
unverified until M2's `platform-testkit` covers it. Do not record this checklist as evidence
that the drain works.

Also not covered: PRE/POST phase pairing across a mid-tick world change (dimension change or
a disconnect processed during the tick). The adapter delivers both phases deliberately, and
includes a `preDelivered` latch to ensure `POST` is paired only when `PRE` was delivered in
that same tick. The latch closes one direction only. In the other direction, `PRE` **can go
unpaired**: if the tick window closes or a fault is set between `START_CLIENT_TICK` and
`END_CLIENT_TICK` of the same tick, `PRE` has already been delivered and `POST` is then
correctly suppressed. That is the exception the SPI's `onClientTick` contract explicitly
permits, not a defect — but it means a `PRE` without a `POST` is a state this adapter can
reach, and nothing in this checklist observes it. The pairing is unobservable in practice
either way, because `ContinuoCore` ignores the `POST` phase entirely, so there is no in-game
symptom to verify. It will become observable and worth a dedicated step as soon as any core
behaviour starts acting on `POST`. Until then, do not record this checklist as evidence that
phase pairing is correct in either direction.

Record the result (pass/fail) of each step individually. Any single failure blocks A2
sign-off, even if every other step passed.
