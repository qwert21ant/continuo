package dev.continuo.core;

import dev.continuo.platform.BlockDescription;

import java.util.EnumSet;

/**
 * Turns an adapter's raw {@code BlockDescription} into the core's {@link BlockData}.
 *
 * <p>A pure function of a description and a {@link BlockTable} — no Minecraft, no world, no
 * mutable state. That is what makes this fully headless-testable, and it is what makes
 * cross-adapter agreement structural rather than hoped-for: both adapters' descriptions run
 * through this same code, so the two cannot disagree about what a given set of collision boxes
 * means.
 *
 * <p>Shape comes from geometry, then a whole-block table row, then a state-specific row. Tags
 * are the union of what the description implies and what the rows add. See the class's tests
 * for the exact geometry rules.
 */
public final class BlockClassifier {

    /**
     * Collision boxes are built from sixteenths on both target versions, so exact equality
     * against values like {@code 0.5} would fail on rounding noise. One ten-thousandth is far
     * below the smallest distinction any rule makes — a sixteenth is {@code 0.0625} — and far
     * above the error such arithmetic produces.
     *
     * <p>This is a <em>loose</em> tolerance for comparing two meaningful values, and must not be
     * confused with {@link #DEGENERATE_EPS}, which is a tight threshold for detecting a box that
     * has no extent at all.
     */
    private static final double EPS = 1e-4;

    /**
     * A box with an extent no greater than this on any single axis is degenerate and is
     * discarded by rule 0 before any other rule runs — see {@link #discardDegenerateBoxes}.
     *
     * <p>Deliberately much tighter than {@link #EPS}: a genuine 1/16-thick carpet or snow-layer
     * box ({@code 0.0625}) must never be mistaken for degenerate, while a box that is truly
     * zero-thickness (a one-layer snow's {@code maxY == minY}, or a zero-width plane) must
     * always be caught. This value also matches Minecraft's own {@code Shapes.BIG_EPSILON}.
     */
    private static final double DEGENERATE_EPS = 1e-6;

    /** A {@link BlockShape#THIN_LAYER} reaches no higher than this. */
    private static final double THIN_LAYER_MAX = 0.25;

    private final BlockTable table;

    /**
     * @param table the per-version overrides; never {@code null}, use {@link BlockTable#EMPTY}
     */
    public BlockClassifier(BlockTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null; use BlockTable.EMPTY");
        }
        this.table = table;
    }

    /**
     * @param description the adapter's raw facts; never {@code null}
     * @return the classified block; never {@code null}
     */
    public BlockData classify(BlockDescription description) {
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        double[] boxes = discardDegenerateBoxes(description.collisionBoxes());

        BlockShape shape = shapeFromGeometry(boxes);
        double collisionTop = collisionTop(boxes);
        Fluid fluid = fluidFromId(description.fluidId());
        EnumSet<BlockTag> tags = tagsFromDescription(description);

        BlockTable.Row blockRow = table.forBlock(description.id());
        BlockTable.Row stateRow = table.forState(description.stateKey());

        shape = overrideShape(shape, blockRow, stateRow);
        fluid = overrideFluid(fluid, blockRow, stateRow);
        addRowTags(tags, blockRow);
        addRowTags(tags, stateRow);

        return new BlockData(shape, collisionTop, fluid, tags);
    }

    /**
     * Rule 0. Drops every box with zero extent — within {@link #DEGENERATE_EPS} — on any axis,
     * before either shape derivation or {@link #collisionTop} sees the array.
     *
     * <p>Both must run on the filtered array, not just shape derivation: an unfiltered
     * zero-thickness box would still count toward {@code collisionTop}, e.g. a zero-width
     * vertical plane spanning {@code y 0..1} would wrongly report a collision top of {@code 1.0}
     * instead of the correct {@code 0.0}.
     *
     * <p>This exists because a one-layer snow reports no collision boxes at all on 1.21.11
     * (thinner-than-{@code 1e-7} shapes collapse to empty), but a real, zero-height box on
     * 1.7.10 ({@code maxY == minY} at meta 0). Without this rule the two versions would disagree
     * about an ordinary block.
     */
    private static double[] discardDegenerateBoxes(double[] boxes) {
        int count = boxes.length / 6;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (isDegenerate(boxes, i)) {
                continue;
            }
            kept++;
        }
        if (kept == count) {
            return boxes;
        }
        double[] result = new double[kept * 6];
        int dest = 0;
        for (int i = 0; i < count; i++) {
            if (isDegenerate(boxes, i)) {
                continue;
            }
            System.arraycopy(boxes, i * 6, result, dest * 6, 6);
            dest++;
        }
        return result;
    }

    private static boolean isDegenerate(double[] boxes, int index) {
        int b = index * 6;
        double extentX = boxes[b + 3] - boxes[b];
        double extentY = boxes[b + 4] - boxes[b + 1];
        double extentZ = boxes[b + 5] - boxes[b + 2];
        return extentX <= DEGENERATE_EPS || extentY <= DEGENERATE_EPS || extentZ <= DEGENERATE_EPS;
    }

    private static BlockShape shapeFromGeometry(double[] boxes) {
        int count = boxes.length / 6;
        if (count == 0) {
            return BlockShape.AIR;
        }
        if (collisionTop(boxes) > 1.0 + EPS) {
            return BlockShape.FENCE;
        }
        if (count == 1) {
            BlockShape single = singleBoxShape(boxes, 0);
            if (single != null) {
                return single;
            }
            return BlockShape.PARTIAL;
        }
        if (isStair(boxes, count)) {
            return BlockShape.STAIR;
        }
        return BlockShape.PARTIAL;
    }

    private static BlockShape singleBoxShape(double[] boxes, int index) {
        if (!fullFootprint(boxes, index)) {
            return null;
        }
        double minY = boxes[index * 6 + 1];
        double maxY = boxes[index * 6 + 4];
        if (near(minY, 0.0) && near(maxY, 1.0)) {
            return BlockShape.FULL;
        }
        if (near(minY, 0.0) && near(maxY, 0.5)) {
            return BlockShape.SLAB_BOTTOM;
        }
        if (near(minY, 0.5) && near(maxY, 1.0)) {
            return BlockShape.SLAB_TOP;
        }
        if (near(minY, 0.0) && maxY <= THIN_LAYER_MAX + EPS) {
            return BlockShape.THIN_LAYER;
        }
        return null;
    }

    /**
     * A full-footprint lower half, plus at least one box in the upper half that does
     * <em>not</em> cover the whole footprint. Two stacked full-footprint halves are a full
     * cube expressed oddly, not a stair.
     *
     * <p>Deliberately does not match an upside-down stair: its full-footprint box sits in the
     * upper half ({@code y 0.5..1}), never the lower half this rule requires, so it falls
     * through to {@link BlockShape#PARTIAL}. That is intentional, not an oversight — see
     * {@code anUpsideDownStairIsPartialNotStair} in this class's test.
     */
    private static boolean isStair(double[] boxes, int count) {
        boolean lowerHalf = false;
        boolean partialUpper = false;
        for (int i = 0; i < count; i++) {
            double minY = boxes[i * 6 + 1];
            double maxY = boxes[i * 6 + 4];
            if (fullFootprint(boxes, i) && near(minY, 0.0) && near(maxY, 0.5)) {
                lowerHalf = true;
            } else if (near(minY, 0.5) && near(maxY, 1.0) && !fullFootprint(boxes, i)) {
                partialUpper = true;
            }
        }
        return lowerHalf && partialUpper;
    }

    private static boolean fullFootprint(double[] boxes, int index) {
        int b = index * 6;
        return near(boxes[b], 0.0)
            && near(boxes[b + 2], 0.0)
            && near(boxes[b + 3], 1.0)
            && near(boxes[b + 5], 1.0);
    }

    private static double collisionTop(double[] boxes) {
        double top = 0.0;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (boxes[i + 4] > top) {
                top = boxes[i + 4];
            }
        }
        return top;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static Fluid fluidFromId(String fluidId) {
        if (fluidId == null) {
            return Fluid.NONE;
        }
        if ("minecraft:water".equals(fluidId) || "minecraft:flowing_water".equals(fluidId)) {
            return Fluid.WATER;
        }
        if ("minecraft:lava".equals(fluidId) || "minecraft:flowing_lava".equals(fluidId)) {
            return Fluid.LAVA;
        }
        return Fluid.OTHER;
    }

    private static EnumSet<BlockTag> tagsFromDescription(BlockDescription description) {
        EnumSet<BlockTag> tags = EnumSet.noneOf(BlockTag.class);
        if (description.climbable()) {
            tags.add(BlockTag.CLIMBABLE);
        }
        if (description.gravity()) {
            tags.add(BlockTag.FALLING);
        }
        return tags;
    }

    private static BlockShape overrideShape(BlockShape shape, BlockTable.Row block, BlockTable.Row state) {
        BlockShape result = shape;
        if (block != null && block.shape() != null) {
            result = block.shape();
        }
        if (state != null && state.shape() != null) {
            result = state.shape();
        }
        return result;
    }

    private static Fluid overrideFluid(Fluid fluid, BlockTable.Row block, BlockTable.Row state) {
        Fluid result = fluid;
        if (block != null && block.fluid() != null) {
            result = block.fluid();
        }
        if (state != null && state.fluid() != null) {
            result = state.fluid();
        }
        return result;
    }

    private static void addRowTags(EnumSet<BlockTag> tags, BlockTable.Row row) {
        if (row != null) {
            tags.addAll(row.tags());
        }
    }
}
