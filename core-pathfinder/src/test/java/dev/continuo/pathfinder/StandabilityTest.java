package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandabilityTest {

    private static BlockData block(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    }

    private static BlockData fluid(BlockShape shape, double top, Fluid f) {
        return new BlockData(shape, top, f, EnumSet.noneOf(BlockTag.class));
    }

    private static BlockData avoid(BlockShape shape, double top) {
        return new BlockData(shape, top, Fluid.NONE, EnumSet.of(BlockTag.AVOID));
    }

    private static final BlockData AIR = block(BlockShape.AIR, 0.0);
    private static final BlockData STONE = block(BlockShape.FULL, 1.0);
    private static final BlockData TOP_SLAB = block(BlockShape.SLAB_TOP, 1.0);
    private static final BlockData STAIR = block(BlockShape.STAIR, 1.0);
    private static final BlockData BOTTOM_SLAB = block(BlockShape.SLAB_BOTTOM, 0.5);
    private static final BlockData FENCE = block(BlockShape.FENCE, 1.5);
    private static final BlockData CARPET_LEGACY = block(BlockShape.AIR, 0.0);
    private static final BlockData CARPET_MODERN = block(BlockShape.THIN_LAYER, 0.0625);
    private static final BlockData FARMLAND_LEGACY = block(BlockShape.FULL, 1.0);
    private static final BlockData FARMLAND_MODERN = block(BlockShape.PARTIAL, 0.9375);

    // --- passable -------------------------------------------------------

    @Test
    void airIsPassable() {
        assertTrue(Standability.passable(AIR));
    }

    @Test
    void solidBlocksAreNotPassable() {
        assertFalse(Standability.passable(STONE));
        assertFalse(Standability.passable(TOP_SLAB));
        assertFalse(Standability.passable(STAIR));
        assertFalse(Standability.passable(FENCE));
    }

    @Test
    void unknownIsNeverPassable() {
        assertFalse(Standability.passable(BlockData.UNKNOWN));
    }

    @Test
    void waterIsNotPassableEvenThoughItHasNoCollision() {
        assertFalse(Standability.passable(fluid(BlockShape.AIR, 0.0, Fluid.WATER)));
    }

    @Test
    void aWaterloggedSlabIsNotPassable() {
        assertFalse(Standability.passable(fluid(BlockShape.SLAB_TOP, 1.0, Fluid.WATER)));
    }

    @Test
    void avoidTaggedBlocksAreNotPassable() {
        assertFalse(Standability.passable(avoid(BlockShape.AIR, 0.0)));
    }

    // --- supports -------------------------------------------------------

    @Test
    void fullBlocksSlabTopsAndStairsSupport() {
        assertTrue(Standability.supports(STONE));
        assertTrue(Standability.supports(TOP_SLAB));
        assertTrue(Standability.supports(STAIR));
    }

    @Test
    void airDoesNotSupport() {
        assertFalse(Standability.supports(AIR));
    }

    @Test
    void unknownNeverSupports() {
        assertFalse(Standability.supports(BlockData.UNKNOWN));
    }

    @Test
    void aFenceIsNotAFloorDespiteItsCollisionTop() {
        assertFalse(Standability.supports(FENCE));
    }

    @Test
    void aWaterloggedSlabIsNotAFloor() {
        assertFalse(Standability.supports(fluid(BlockShape.SLAB_TOP, 1.0, Fluid.WATER)));
    }

    @Test
    void magmaIsNotAFloor() {
        assertFalse(Standability.supports(avoid(BlockShape.FULL, 1.0)));
    }

    // --- the two B1 divergences -----------------------------------------

    @Test
    void carpetIsPassableOnBothVersionsValues() {
        assertTrue(Standability.passable(CARPET_LEGACY));
        assertTrue(Standability.passable(CARPET_MODERN));
    }

    @Test
    void farmlandSupportsOnBothVersionsValues() {
        assertTrue(Standability.supports(FARMLAND_LEGACY));
        assertTrue(Standability.supports(FARMLAND_MODERN));
    }

    // --- the deliberate C1 limitation -----------------------------------

    @Test
    void aBottomSlabIsAnObstacleNeitherEnterableNorStandable() {
        assertFalse(Standability.passable(BOTTOM_SLAB));
        assertFalse(Standability.supports(BOTTOM_SLAB));
    }

    // --- standable ------------------------------------------------------

    @Test
    void standingNeedsAFloorFeetRoomAndHeadRoom() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        BlockSource source = source(world);

        assertTrue(Standability.standable(source, 0, 64, 0));
    }

    @Test
    void standingFailsWithoutAFloor() {
        assertFalse(Standability.standable(source(new HashMap<Long, BlockData>()), 0, 64, 0));
    }

    @Test
    void standingFailsWhenTheHeadIsBlocked() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        world.put(Pos.pack(0, 65, 0), STONE);

        assertFalse(Standability.standable(source(world), 0, 64, 0));
    }

    @Test
    void standingFailsWhenTheFeetBlockIsOccupied() {
        Map<Long, BlockData> world = new HashMap<Long, BlockData>();
        world.put(Pos.pack(0, 63, 0), STONE);
        world.put(Pos.pack(0, 64, 0), STONE);

        assertFalse(Standability.standable(source(world), 0, 64, 0));
    }

    /** A map-backed source. Absent positions are air; nothing here needs a real fixture yet. */
    private static BlockSource source(final Map<Long, BlockData> blocks) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(Long.valueOf(Pos.pack(x, y, z)));
                return found == null ? AIR : found;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int maxY() {
                return 256;
            }
        };
    }
}
