package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical block values behind the fixture legend.
 *
 * <p>Values are chosen to match what B1's audit actually recorded, so a fixture exercises the
 * real numbers rather than round ones. {@link #PARTIAL_FLOOR} is farmland's 1.21.11 value and
 * {@link #CARPET} is carpet's; both are the version-divergent cases the predicates are designed
 * to reconcile.
 */
final class FixtureBlocks {

    static final BlockData AIR = plain(BlockShape.AIR, 0.0);
    static final BlockData STONE = plain(BlockShape.FULL, 1.0);
    static final BlockData BOTTOM_SLAB = plain(BlockShape.SLAB_BOTTOM, 0.5);
    static final BlockData TOP_SLAB = plain(BlockShape.SLAB_TOP, 1.0);
    static final BlockData STAIR = plain(BlockShape.STAIR, 1.0);
    static final BlockData CARPET = plain(BlockShape.THIN_LAYER, 0.0625);
    static final BlockData PARTIAL_FLOOR = plain(BlockShape.PARTIAL, 0.9375);
    static final BlockData FENCE = plain(BlockShape.FENCE, 1.5);
    static final BlockData UNKNOWN = BlockData.UNKNOWN;

    static final BlockData WATER =
        new BlockData(BlockShape.AIR, 0.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));

    static final BlockData LAVA =
        new BlockData(BlockShape.AIR, 0.0, Fluid.LAVA, EnumSet.of(BlockTag.AVOID));

    private static final Map<Character, BlockData> LEGEND = buildLegend();

    private FixtureBlocks() {
    }

    private static BlockData plain(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    }

    private static Map<Character, BlockData> buildLegend() {
        Map<Character, BlockData> legend = new LinkedHashMap<Character, BlockData>();
        legend.put(Character.valueOf('.'), AIR);
        legend.put(Character.valueOf('#'), STONE);
        legend.put(Character.valueOf('_'), BOTTOM_SLAB);
        legend.put(Character.valueOf('^'), TOP_SLAB);
        legend.put(Character.valueOf('>'), STAIR);
        legend.put(Character.valueOf('c'), CARPET);
        legend.put(Character.valueOf('p'), PARTIAL_FLOOR);
        legend.put(Character.valueOf('f'), FENCE);
        legend.put(Character.valueOf('?'), UNKNOWN);
        legend.put(Character.valueOf('~'), WATER);
        legend.put(Character.valueOf('!'), LAVA);
        return Collections.unmodifiableMap(legend);
    }

    /**
     * @return the character-to-block legend, in a stable iteration order so that the renderer's
     *         reverse lookup is deterministic
     */
    static Map<Character, BlockData> legend() {
        return LEGEND;
    }
}
