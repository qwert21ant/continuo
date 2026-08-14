package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockClassifierTableTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private static BlockClassifier with(String json) {
        return new BlockClassifier(BlockTableLoader.parse(json));
    }

    private static BlockDescription desc(String id, String stateKey) {
        return new BlockDescription(id, stateKey, CUBE.clone(), null, false, false);
    }

    @Test
    void aBlockWithNoRowIsClassifiedFromGeometryAlone() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{},\"states\":{}}")
            .classify(desc("a:unknown", "a:unknown"));
        assertEquals(BlockShape.FULL, d.shape());
        assertTrue(d.tags().isEmpty());
    }

    @Test
    void aBlockRowAddsTagsWithoutChangingShape() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:soul\":{\"tags\":[\"SLOW\"]}},\"states\":{}}")
            .classify(desc("a:soul", "a:soul"));
        assertEquals(BlockShape.FULL, d.shape());
        assertTrue(d.has(BlockTag.SLOW));
    }

    @Test
    void aBlockRowCanOverrideGeometrysShape() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:odd\":{\"shape\":\"PARTIAL\"}},\"states\":{}}")
            .classify(desc("a:odd", "a:odd"));
        assertEquals(BlockShape.PARTIAL, d.shape());
    }

    @Test
    void aStateRowBeatsABlockRowOnShape() {
        BlockData d = with("{\"version\":\"t\","
            + "\"blocks\":{\"a:s\":{\"shape\":\"FULL\"}},"
            + "\"states\":{\"a:s#8\":{\"shape\":\"SLAB_TOP\"}}}")
            .classify(desc("a:s", "a:s#8"));
        assertEquals(BlockShape.SLAB_TOP, d.shape());
    }

    @Test
    void aStateRowDoesNotApplyToADifferentState() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{},"
            + "\"states\":{\"a:s#8\":{\"shape\":\"SLAB_TOP\"}}}")
            .classify(desc("a:s", "a:s#0"));
        assertEquals(BlockShape.FULL, d.shape());
    }

    @Test
    void tagsFromBothRowsAreUnioned() {
        BlockData d = with("{\"version\":\"t\","
            + "\"blocks\":{\"a:s\":{\"tags\":[\"SLOW\"]}},"
            + "\"states\":{\"a:s#8\":{\"tags\":[\"AVOID\"]}}}")
            .classify(desc("a:s", "a:s#8"));
        assertTrue(d.has(BlockTag.SLOW), "the block row's tag must survive the state row");
        assertTrue(d.has(BlockTag.AVOID));
    }

    @Test
    void aRowCannotRemoveATagTheDescriptionImplied() {
        BlockDescription climbable =
            new BlockDescription("a:ladder", "a:ladder", new double[0], null, true, false);
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:ladder\":{\"tags\":[\"SLOW\"]}},\"states\":{}}")
            .classify(climbable);
        assertTrue(d.has(BlockTag.CLIMBABLE), "tag removal is deliberately unsupported");
        assertTrue(d.has(BlockTag.SLOW));
    }

    @Test
    void climbableInTheDescriptionBecomesTheClimbableTag() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:l", "a:l", new double[0], null, true, false));
        assertTrue(d.has(BlockTag.CLIMBABLE));
        assertFalse(d.has(BlockTag.FALLING));
    }

    @Test
    void gravityInTheDescriptionBecomesTheFallingTag() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:g", "a:g", CUBE.clone(), null, false, true));
        assertTrue(d.has(BlockTag.FALLING));
    }

    @Test
    void theFourVanillaFluidIdsAreKnownWithoutATableRow() {
        BlockClassifier c = new BlockClassifier(BlockTable.EMPTY);
        assertEquals(Fluid.WATER, c.classify(
            new BlockDescription("a:w", "a:w", new double[0], "minecraft:water", false, false)).fluid());
        assertEquals(Fluid.WATER, c.classify(
            new BlockDescription("a:fw", "a:fw", new double[0], "minecraft:flowing_water", false, false)).fluid());
        assertEquals(Fluid.LAVA, c.classify(
            new BlockDescription("a:l", "a:l", new double[0], "minecraft:lava", false, false)).fluid());
        assertEquals(Fluid.LAVA, c.classify(
            new BlockDescription("a:fl", "a:fl", new double[0], "minecraft:flowing_lava", false, false)).fluid());
    }

    @Test
    void anUnrecognisedFluidIdBecomesOther() {
        BlockData d = new BlockClassifier(BlockTable.EMPTY).classify(
            new BlockDescription("a:x", "a:x", new double[0], "mod:syrup", false, false));
        assertEquals(Fluid.OTHER, d.fluid());
    }

    @Test
    void aTableRowSuppliesAFluidTheDescriptionDidNotReport() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"minecraft:flowing_water\":{\"fluid\":\"WATER\"}},\"states\":{}}")
            .classify(new BlockDescription(
                "minecraft:flowing_water", "minecraft:flowing_water", new double[0], null, false, false));
        assertEquals(Fluid.WATER, d.fluid(),
            "this is exactly how 1.7.10's separate flowing_water block is normalised");
    }

    @Test
    void aTableRowCanOverrideAReportedFluid() {
        BlockData d = with("{\"version\":\"t\",\"blocks\":{\"a:x\":{\"fluid\":\"LAVA\"}},\"states\":{}}")
            .classify(new BlockDescription("a:x", "a:x", new double[0], "minecraft:water", false, false));
        assertEquals(Fluid.LAVA, d.fluid());
    }

    @Test
    void theShipped1710TableClassifiesFlowingWaterAsWater() {
        BlockClassifier c = new BlockClassifier(BlockTableLoader.forVersion("1.7.10"));
        BlockData d = c.classify(new BlockDescription(
            "minecraft:flowing_water", "minecraft:flowing_water", new double[0], null, false, false));
        assertEquals(Fluid.WATER, d.fluid());
        assertEquals(BlockShape.AIR, d.shape());
    }

    @Test
    void theShipped12111TableTagsSoulSandSlow() {
        BlockClassifier c = new BlockClassifier(BlockTableLoader.forVersion("1.21.11"));
        assertTrue(c.classify(desc("minecraft:soul_sand", "minecraft:soul_sand")).has(BlockTag.SLOW));
    }
}
