package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Audits a movement's {@link IMovementType#minCostPerAxisStep()} against what it actually offers.
 *
 * <p>That figure is a declaration, and a wrong one is silently fatal: it scales the search's
 * heuristic, so declaring too high a value costs admissibility with no other test failing. This
 * turns the declaration into a checked claim, for every movement rather than only the built-in
 * ones.
 *
 * <p>Returns violations rather than asserting them, which keeps JUnit off a production compile
 * classpath while letting any module's tests call it. A movement module runs this over its own
 * movement; {@code :core-pathfinder} runs it over the four built-ins.
 *
 * <p><b>An empty result means the declaration was exercised and held, never merely that nothing
 * went wrong.</b> An audit that cannot reach a movement's preconditions checks nothing, and
 * returning "no violations" for that would be the worst possible answer: it reads as a pass and is
 * indistinguishable from one. Such a movement is reported as a violation in its own right, and the
 * palette the seeded worlds are drawn from carries the block variety that keeps the audit real for
 * the movements it can reach.
 */
public final class MovementContract {

    /** Enough worlds to hit each movement's preconditions from several directions. */
    private static final int WORLDS = 200;

    /** A cube big enough for a movement of any plausible span to have room to offer. */
    private static final int EXTENT = 6;

    /**
     * The blocks the seeded worlds are built from.
     *
     * <p><b>Breadth here is what decides whether an audit is real or vacuous.</b> A movement can
     * only be checked at preconditions the palette can actually produce, so anything missing from
     * this list is a class of movement that silently receives no audit at all. It originally held
     * air, a full block, a top slab and a thin layer — enough for the walking movements and
     * nothing else, which meant a movement gated on, say, a fence returned zero violations no
     * matter how wrong its declaration was. The unreadable block, the fence, the harmful block and
     * the water were added for that reason: they are the preconditions the movements named as next
     * in line read. Anything still unreachable is reported by {@link #violations} rather than
     * passed over — see the no-offer branch there.
     *
     * <p>The four repeats of air and the two of stone are load-bearing, not padding. Standing
     * requires two passable blocks over one supporting one, so the probability of any position
     * being usable at all goes as roughly the cube of the palette's composition; adding four
     * categories without them would thin every movement's audit by an order of magnitude, and
     * parkour — which needs a standable origin, a passable gap and a standable landing — would be
     * the first to go vacuous.
     *
     * <p>Index {@code 0} is air, and {@link #randomWorld} relies on that for out-of-bounds reads.
     */
    private static final BlockData[] PALETTE = {
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.SLAB_TOP, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        new BlockData(BlockShape.THIN_LAYER, 0.0625, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        // Unreadable: a collision top of 0 that must not be mistaken for air.
        new BlockData(BlockShape.UNKNOWN, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        // Collision above the cube, so neither passable nor a floor.
        new BlockData(BlockShape.FENCE, 1.5, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
        // Solid but harmful, so it is refused on the tag rather than on its geometry.
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.of(BlockTag.AVOID)),
        // No collision, but occupied by a fluid — what a swimming movement would key on.
        new BlockData(BlockShape.AIR, 0.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class))
    };

    private MovementContract() {
    }

    /**
     * @param type the movement to audit; never {@code null}
     * @return a single message describing the first neighbour whose cost falls below the declared
     *         figure, or a message saying the offer was the movement's own position and so has no
     *         per-step cost, or a message saying the audit never elicited an offer at all, or an
     *         empty list when the declaration was exercised and held.
     *         <b>One counterexample, not all of them</b> — the same declaration is wrong at every
     *         position a movement can offer from, so collecting them all would bury the one that
     *         matters under hundreds of copies, and the fix is identical either way
     */
    public static List<String> violations(IMovementType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        final double declared = type.minCostPerAxisStep();
        final List<String> violations = new ArrayList<String>();
        // Whether the audit ever got the movement to offer anything. Without this an empty
        // result means either "the declaration holds" or "nothing was ever checked", and every
        // caller reads it as the first.
        final int[] offers = {0};

        for (int seed = 0; seed < WORLDS && violations.isEmpty(); seed++) {
            final BlockSource world = randomWorld(seed);
            MutableExpansionContext ctx = new MutableExpansionContext(world);

            for (int y = -EXTENT + 2; y <= EXTENT - 2 && violations.isEmpty(); y++) {
                ctx.moveTo(0, y, 0);
                final int originY = y;
                type.expand(ctx, new MoveSink() {
                    @Override
                    public void offer(int nx, int ny, int nz, double cost) {
                        offers[0]++;
                        if (!violations.isEmpty()) {
                            return;
                        }
                        // The context always sits at x = 0, z = 0, so nx and nz are already
                        // offsets from the origin; only Y needs subtracting.
                        int span = Math.max(Math.abs(nx),
                            Math.max(Math.abs(ny - originY), Math.abs(nz)));
                        // A movement offering the position it was asked to expand from. This
                        // guard is NOT redundant with the comparison below, which is why it was
                        // deleted once and restored: cost / 0 is Infinity (or NaN at cost 0),
                        // and neither is less than the declared figure, so the comparison passes
                        // a degenerate movement in silence. The no-offer branch does not catch
                        // it either — the counter above has already incremented, so as far as
                        // that branch can tell the audit was exercised. Without this, a movement
                        // whose only edge is a self-offer is the one shape that reads as a clean
                        // audit while having been checked against nothing.
                        if (span == 0) {
                            violations.add(type.id() + " offered its own position (" + nx + ", "
                                + ny + ", " + nz + "), which is not a move: an edge spanning zero"
                                + " axis steps has no per-step cost to check the declared"
                                + " minCostPerAxisStep of " + declared + " against. A* would also"
                                + " expand it as a zero-length neighbour of itself. Fix expand()"
                                + " so it never offers the position it was given.");
                            return;
                        }
                        double perStep = cost / span;
                        if (perStep < declared - 1.0e-9) {
                            violations.add(type.id() + " declares minCostPerAxisStep " + declared
                                + " but offered (" + nx + ", " + ny + ", " + nz + ") from (0, "
                                + originY + ", 0) for " + cost + " across " + span
                                + " axis steps, which is " + perStep + " per step; the heuristic"
                                + " would overestimate and A* would stop returning shortest"
                                + " paths");
                        }
                    }
                });
            }
        }

        if (violations.isEmpty() && offers[0] == 0) {
            violations.add(type.id() + " offered nothing anywhere in " + WORLDS + " seeded"
                + " worlds, so its declared minCostPerAxisStep of " + declared + " was never"
                + " checked against a single edge. THIS IS NOT A PASS: the audit could not reach"
                + " the preconditions expand() requires, so it has no evidence either way, and an"
                + " empty result here would otherwise be indistinguishable from an honest"
                + " declaration. The worlds are random cubes drawn from a fixed palette — air,"
                + " full blocks, a top slab, a thin layer, an unreadable block, a fence, a"
                + " harmful block and water — so a movement gated on anything outside it (a"
                + " climbable block, lava, a stair, a specific tag) can never fire. Widen this"
                + " class's palette so it can produce what the movement needs, or check the"
                + " declaration against a hand-built world in the movement's own tests.");
        }
        return violations;
    }

    /**
     * A cube of randomly chosen blocks, seeded so a violation is always reproducible.
     *
     * <p>Random rather than hand-written text art because this audit wants breadth, not
     * legibility: it has to reach whatever combination of preconditions lets a movement offer its
     * cheapest edge. C1's Dijkstra oracle established the same approach over 400 seeded worlds.
     */
    private static BlockSource randomWorld(int seed) {
        final Random random = new Random(seed);
        final BlockData[] palette = PALETTE;
        final int side = 2 * EXTENT + 1;
        final BlockData[] cells = new BlockData[side * side * side];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = palette[random.nextInt(palette.length)];
        }
        final BlockData air = palette[0];

        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                if (x < -EXTENT || x > EXTENT || y < -EXTENT || y > EXTENT
                    || z < -EXTENT || z > EXTENT) {
                    return air;
                }
                int index = ((x + EXTENT) * side + (y + EXTENT)) * side + (z + EXTENT);
                return cells[index];
            }

            @Override
            public int minY() {
                return -EXTENT;
            }

            @Override
            public int maxY() {
                return EXTENT + 1;
            }
        };
    }
}
