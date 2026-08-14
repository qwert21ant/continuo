package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockTableLoaderTest {

    @Test
    void parsesAnEmptyTable() {
        BlockTable t = BlockTableLoader.parse("{\"version\": \"test\", \"blocks\": {}, \"states\": {}}");
        assertNull(t.forBlock("minecraft:stone"));
        assertNull(t.forState("minecraft:stone"));
    }

    @Test
    void parsesABlockRowWithTags() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"minecraft:soul_sand\": {\"tags\": [\"SLOW\"]}}, \"states\": {}}");
        BlockTable.Row row = t.forBlock("minecraft:soul_sand");
        assertNotNull(row);
        assertTrue(row.tags().contains(BlockTag.SLOW));
        assertNull(row.shape());
        assertNull(row.fluid());
    }

    @Test
    void parsesABlockRowWithAFluid() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"minecraft:flowing_water\": {\"fluid\": \"WATER\"}}, \"states\": {}}");
        assertEquals(Fluid.WATER, t.forBlock("minecraft:flowing_water").fluid());
    }

    @Test
    void parsesAStateRowWithAShape() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {}, \"states\": {\"minecraft:stone_slab#8\": {\"shape\": \"SLAB_TOP\"}}}");
        assertEquals(BlockShape.SLAB_TOP, t.forState("minecraft:stone_slab#8").shape());
    }

    @Test
    void aRowWithNoTagsHasAnEmptyTagSet() {
        BlockTable t = BlockTableLoader.parse(
            "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shape\": \"FULL\"}}, \"states\": {}}");
        assertTrue(t.forBlock("a:b").tags().isEmpty());
    }

    @Test
    void rejectsAnUnknownTagName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"tags\": [\"NOPE\"]}}, \"states\": {}}"));
        assertTrue(e.getMessage().contains("NOPE"), e.getMessage());
    }

    @Test
    void rejectsAnUnknownShapeName() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shape\": \"WOBBLY\"}}, \"states\": {}}"));
    }

    @Test
    void rejectsAnUnknownFluidName() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"fluid\": \"SYRUP\"}}, \"states\": {}}"));
    }

    @Test
    void rejectsAnUnknownKeyInARow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"shpae\": \"FULL\"}}, \"states\": {}}"));
        assertTrue(e.getMessage().contains("shpae"), "a typo must be named, not ignored: " + e.getMessage());
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": \"test\", \"blocks\": {}, \"states\": {}, \"extra\": {}}"));
    }

    @Test
    void rejectsAMissingBlocksSection() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": \"test\", \"states\": {}}"));
    }

    @Test
    void rejectsAMissingVersion() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"blocks\": {}, \"states\": {}}"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> BlockTableLoader.parse("{"));
    }

    @Test
    void loadsTheShippedTableFor1710() {
        BlockTable t = BlockTableLoader.forVersion("1.7.10");
        assertNotNull(t.forBlock("minecraft:flowing_water"),
            "1.7.10 registers flowing_water as a distinct block and the table must normalise it");
        assertEquals(Fluid.WATER, t.forBlock("minecraft:flowing_water").fluid());
    }

    @Test
    void loadsTheShippedTableFor12111() {
        assertNotNull(BlockTableLoader.forVersion("1.21.11").forBlock("minecraft:soul_sand"));
    }

    @Test
    void anUnknownVersionYieldsAnEmptyTableRatherThanAnError() {
        assertNull(BlockTableLoader.forVersion("1.99.99").forBlock("minecraft:stone"));
    }

    @Test
    void rejectsVersionWhenItIsAnObjectInsteadOfAString() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": {}, \"blocks\": {}, \"states\": {}}"));
    }

    @Test
    void rejectsBlocksWhenItIsAStringInsteadOfAnObject() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"version\": \"test\", \"blocks\": \"oops\", \"states\": {}}"));
    }

    @Test
    void rejectsTagsWhenItIsAStringInsteadOfAnArray() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse(
                "{\"version\": \"test\", \"blocks\": {\"a:b\": {\"tags\": \"SLOW\"}}, \"states\": {}}"));
    }

    @Test
    void exceptionMessageFromMissingVersionIsUsefulAndNotMasked() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            BlockTableLoader.parse("{\"blocks\": {}, \"states\": {}}"));
        assertTrue(e.getMessage().contains("version"), "message should mention 'version': " + e.getMessage());
    }
}
