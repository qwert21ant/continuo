# A1 manual smoke checklist — Fabric 1.21.11

This is a manual, in-game verification. It cannot be automated or run in CI: it requires a
graphical Minecraft client, a real keypress, and a human watching the result. Run it after
`./gradlew clean build` is green.

Run: `./gradlew :adapters:adapter-fabric-1.21.11:runClient`

Work through every step below, in order, and record pass/fail for each. Any failure blocks
A1 sign-off — do not skip a step or assume it would have passed.

1. **Startup log.** Watch the launcher/game log while the client boots, before you reach the
   main menu. It must contain the line:
   `Continuo core started on 1.21.11 / FABRIC`
   *Observe:* the exact line above (game version and loader name may vary only if you're on a
   different build — for this checklist expect exactly this).
   *If it's missing:* the mod did not load at all, or `IPlatformInfo` is not wired up.
   Check the log for earlier mod-loading errors first.

2. **World.** Create a new Superflat world. It will start in whatever mode you chose at
   creation; once you've spawned, switch to Survival with `/gamemode survival`.
   *Observe:* the F3 debug screen (or the pause menu) confirms you are in Survival, not
   Creative.
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
   *If the player does not move at all:* the actuator is not reaching the key mapping, or the
   keybind is bound to something other than `K` (check the Controls screen for
   "key.continuo.walk" under the "continuo" category) — see step 5's "zero" case too.
   *If the player never stops walking:* do not wait indefinitely — this is a failure. See
   step 5's "never stopping" case.

5. **Distance.** Once the player has stopped, press F3 again and read the new XYZ. Compute
   the displacement along the facing axis you recorded in step 3 (the difference in X or Z,
   whichever was changing).
   *Observe:* displacement should be **8–9 blocks**. Forty ticks at vanilla walking speed is
   about 8.6 blocks, so this is the expected range, not exactly 8 or exactly 10.
   *Diagnostic interpretations if it's outside 8–9 blocks:*
   - Roughly double (~17 blocks) or roughly half (~4 blocks) means the core is acting on the
     wrong number of ticks. Note that the adapter is *deliberately* registered on both client
     tick phases (START_CLIENT_TICK -> PRE, END_CLIENT_TICK -> POST) and that is correct and
     required by the SPI contract. The bug to look for is the core acting on POST as well as
     PRE, or the hook being on server tick instead of client tick.
   - Zero (player never moved) means the actuator is not reaching the key mapping — the W key
     is not actually being pressed in-game even though the walk was requested.
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
   not being called on disconnect (or the key release is not reaching the game), leaving
   input state stuck across a reconnect. This is the step most likely to reveal a real
   defect — verify it properly, don't assume it passed because the earlier steps did.

9. **Title-screen keypress.** From the main menu, before loading any world, press `K` five
   or six times. Then load the world from step 2.
   *Observe:* the log must **not** contain `Continuo walk requested` from those presses, and
   the player must not start walking on its own at any point after the world loads.
   *Why this matters:* the SPI contract delivers ticks only while a world is loaded with a
   local player, and the adapter drains clicks made outside a world so they cannot fire on
   join. A walk starting the moment you spawn means the drain is missing; a
   `Continuo walk requested` line logged at the title screen means the in-world guard is
   missing.

10. **Leave a singleplayer world mid-walk.** Press `K`, and while the bot is still moving
    choose "Save and Quit to Title". Stay at the title screen this time rather than
    rejoining.
    *Observe:* the log must contain `Continuo stopping: disconnected`.
    *Why this matters:* global rule 2 requires `stop()` on world unload, and the adapter
    relies on `DISCONNECT` alone to cover it. Step 8 checks the symptom after a rejoin; this
    step checks the cause directly, in the singleplayer case that the design flagged as
    verify-don't-assume. If the line is absent, `DISCONNECT` does not fire on singleplayer
    exit and the adapter needs a separate world-unload hook.

**Not covered by this checklist:** global rule 3 (fault handling). Exercising it requires
deliberately making the core throw, which is not something to leave in the tree. Rule 3 is
implemented and knowingly unverified until M2's `platform-testkit` covers it. Do not record
this checklist as evidence that fault handling works.

Record the result (pass/fail) of each step individually. Any single failure blocks A1
sign-off, even if every other step passed.
