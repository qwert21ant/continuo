package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical character-to-block mapping shared by the renderer and the fixture parser.
 *
 * <p>Values are chosen to match what B1's audit actually recorded, so a fixture exercises the
 * real numbers rather than round ones. {@link #PARTIAL_FLOOR} is farmland's 1.21.11 value and
 * {@link #CARPET} is carpet's; both are the version-divergent cases the predicates are designed
 * to reconcile.
 *
 * <p><b>One definition, deliberately, and it is structural rather than tidy.</b> The renderer
 * writes characters and the fixture parser reads them, and a rendered map is only pasteable back
 * as a fixture while those two agree. Two mappings that agree today would drift the first time
 * either side gained a block shape, and the drift would be silent: the map would still look well
 * formed and would simply re-parse as different terrain. Sharing one definition removes that
 * failure mode rather than testing for it.
 *
 * <p><b>{@link #characterFor} conflates two different things, and that is the documented
 * behaviour.</b> A block the legend has no character for renders as {@link #UNMAPPED}, which is
 * also {@link #UNKNOWN}'s own character. A live world produces such blocks routinely — see the
 * limits recorded on {@code PathRenderer}.
 */
public final class BlockLegend {

    /**
     * The character for a block the legend does not name.
     *
     * <p>It is {@link #UNKNOWN}'s character too, so a map cannot distinguish "unreadable" from
     * "not in this legend" once written. Both re-parse as {@code UNKNOWN}, which is impassable.
     */
    public static final char UNMAPPED = '?';

    /** Empty space. */
    public static final BlockData AIR = plain(BlockShape.AIR, 0.0);

    /** A full cube: stone, dirt, leaves, and most of a world. */
    public static final BlockData STONE = plain(BlockShape.FULL, 1.0);

    /** A slab occupying the lower half. */
    public static final BlockData BOTTOM_SLAB = plain(BlockShape.SLAB_BOTTOM, 0.5);

    /** A slab occupying the upper half, so its collision top is a whole block up. */
    public static final BlockData TOP_SLAB = plain(BlockShape.SLAB_TOP, 1.0);

    /** A stair. */
    public static final BlockData STAIR = plain(BlockShape.STAIR, 1.0);

    /** Carpet's measured height. */
    public static final BlockData CARPET = plain(BlockShape.THIN_LAYER, 0.0625);

    /** Farmland's measured height. */
    public static final BlockData PARTIAL_FLOOR = plain(BlockShape.PARTIAL, 0.9375);

    /** A fence: collision above the cube, so neither passable nor a floor. */
    public static final BlockData FENCE = plain(BlockShape.FENCE, 1.5);

    /** Unreadable. */
    public static final BlockData UNKNOWN = BlockData.UNKNOWN;

    /** No collision, occupied by water. */
    public static final BlockData WATER =
        new BlockData(BlockShape.AIR, 0.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));

    /** No collision, occupied by lava, and refused on the tag. */
    public static final BlockData LAVA =
        new BlockData(BlockShape.AIR, 0.0, Fluid.LAVA, EnumSet.of(BlockTag.AVOID));

    private static final Map<Character, BlockData> LEGEND = buildLegend();
    private static final Map<BlockData, Character> REVERSE = buildReverse();

    private BlockLegend() {
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
     * The reverse direction, built once. First character wins, so a block reachable by two
     * characters renders as the one declared first in {@link #buildLegend}.
     */
    private static Map<BlockData, Character> buildReverse() {
        Map<BlockData, Character> reverse = new HashMap<BlockData, Character>();
        for (Map.Entry<Character, BlockData> entry : LEGEND.entrySet()) {
            if (!reverse.containsKey(entry.getValue())) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reverse);
    }

    /**
     * @return the character-to-block legend, unmodifiable, in a stable iteration order so that
     *         the reverse lookup is deterministic
     */
    public static Map<Character, BlockData> legend() {
        return LEGEND;
    }

    /**
     * @param data the block to render; never {@code null}
     * @return its character, or {@link #UNMAPPED} if the legend does not name it
     */
    public static char characterFor(BlockData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Character ch = REVERSE.get(data);
        return ch == null ? UNMAPPED : ch.charValue();
    }
}
