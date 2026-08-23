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
 */
public final class MovementContract {

    /** Enough worlds to hit each movement's preconditions from several directions. */
    private static final int WORLDS = 200;

    /** A cube big enough for a movement of any plausible span to have room to offer. */
    private static final int EXTENT = 6;

    private MovementContract() {
    }

    /**
     * @param type the movement to audit; never {@code null}
     * @return a single message describing the first neighbour whose cost falls below the declared
     *         figure, or an empty list when the declaration holds everywhere this could check.
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

        for (int seed = 0; seed < WORLDS && violations.isEmpty(); seed++) {
            final BlockSource world = randomWorld(seed);
            MutableExpansionContext ctx = new MutableExpansionContext(world);

            for (int y = -EXTENT + 2; y <= EXTENT - 2 && violations.isEmpty(); y++) {
                ctx.moveTo(0, y, 0);
                final int originY = y;
                type.expand(ctx, new MoveSink() {
                    @Override
                    public void offer(int nx, int ny, int nz, double cost) {
                        if (!violations.isEmpty()) {
                            return;
                        }
                        // The context always sits at x = 0, z = 0, so nx and nz are already
                        // offsets from the origin; only Y needs subtracting.
                        int span = Math.max(Math.abs(nx),
                            Math.max(Math.abs(ny - originY), Math.abs(nz)));
                        if (span == 0) {
                            violations.add(type.id() + " offered its own position, which is not"
                                + " a move");
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
        final BlockData[] palette = {
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.SLAB_TOP, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)),
            new BlockData(BlockShape.THIN_LAYER, 0.0625, Fluid.NONE,
                EnumSet.noneOf(BlockTag.class))
        };
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
