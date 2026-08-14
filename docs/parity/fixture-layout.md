# Parity fixture layout

Source of truth: `docs/superpowers/specs/2026-08-14-b1-block-model-design.md` §5.2. This file is a
verbatim transcription for the owner building the fixture and running the dump keybind — if the two
ever disagree, the spec governs.

## Shape

A one-block-wide corridor **32 blocks long, running +X**, one high, one deep, at a fixed `Y`. The
**player stands at index 0** (air) and faces `+X` down the corridor. Index `i` is the block at
`(x0 + i, y0, z0)`. Build near the origin (`|x|, |z| < 1000`) — 1.7.10 computes some collision bounds
in `float` from absolute coordinates, and a sixteenth-block inset rounds away past roughly `2^21`.
Build in a temperate or cold biome with no torches or other block-light sources adjacent to indices
16, 17 and 21, or the ice and the one-layer snow melt.

Scaffolding outside the dump volume, never itself compared:

- a **floor** at `y0 - 1` for the whole run: stone, except `netherrack` under index 28, `sand` under
  index 31, and `soul_soil` under index 30 on 1.21.11;
- **walls** of stone at `z0 - 1` and `z0 + 1` for **indices 0-29 only**. Indices 30 and 31 must have
  air on both sides, or the cactus at 31 breaks;
- a `wheat` crop at `y0 + 1` above index 22, so the farmland does not revert to dirt;
- the door's upper half at `y0 + 1` above index 18.

## Index-to-block table

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
| 8 | stair, bottom half | `oak_stairs` (meta 0-3) | `oak_stairs[half=bottom,shape=straight]` | compare |
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
| 25 | *1.21-only* | — (`air`) | `magma_block` | **exclusive** |
| 26 | *1.21-only* | — (`air`) | `honey_block` | **exclusive** |
| 27 | air | `air` | `air` | compare |
| 28 | fire | `fire` | `fire` | compare |
| 29 | air | `air` | `air` | compare |
| 30 | *1.21-only* | — (`air`) | `soul_fire` | **exclusive** |
| 31 | cactus | `cactus` | `cactus` | compare |

Every index is dumped on both versions regardless of `Diff`, so nothing is silently absent. An
`exclusive` index does not exist on 1.7.10; the 1.7.10 dump must read `air` there.

## The five excluded indices

`BlockParityTest.theTwoAdaptersAgreeOnTheFixtureWorld` compares the two version dumps to each other
and **excludes exactly these five indices** — the rest of this table (27 of 32 indices) is still
compared cross-version. Both goldens still pin every index, including these five, per version
separately; only the cross-version diff skips them.

| index | block | why excluded |
|---|---|---|
| 20 | carpet | divergent — the games genuinely differ (1.7.10: no collision, `AIR`; 1.21.11: 1/16 box, `THIN_LAYER`) |
| 22 | farmland | divergent — the games genuinely differ (1.7.10: full cube, `FULL`; 1.21.11: 15/16 box, `PARTIAL`) |
| 25 | magma_block | 1.21.11-only, absent on 1.7.10 |
| 26 | honey_block | 1.21.11-only, absent on 1.7.10 |
| 30 | soul_fire | 1.21.11-only, absent on 1.7.10 |

This set must match `BlockParityTest.EXCLUDED_FROM_CROSS_VERSION_DIFF` exactly. If the two drift,
this file governs the owner's expectations and the test constant is wrong.

## Deliberately not in the fixture

Covered instead by spec §4's audit table and by headless `BlockClassifier` tests:

| Entry | Why it is out |
|---|---|
| water, flowing | A one-wide row cannot hold it; it will not stay put |
| lava, flowing | Same |
| snow layer, 2 layers | Redundant — `THIN_LAYER` at `h = 0.125` adds nothing over index 19's `h = 0.1875`, and index 21 is spent on the rule-0 case (one-layer snow, where 1.7.10 emits a degenerate box and 1.21.11 emits none) instead |
| unrecognised modded shape | No mod loads on both versions |
