package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDataTest {

    @Test
    void exposesWhatItWasGiven() {
        BlockData d = new BlockData(
            BlockShape.SLAB_BOTTOM, 0.5, Fluid.NONE, EnumSet.of(BlockTag.SLOW));
        assertEquals(BlockShape.SLAB_BOTTOM, d.shape());
        assertEquals(0.5, d.collisionTop(), 0.0);
        assertEquals(Fluid.NONE, d.fluid());
        assertTrue(d.has(BlockTag.SLOW));
        assertFalse(d.has(BlockTag.AVOID));
    }

    @Test
    void tagsAreNotModifiableThroughTheAccessor() {
        BlockData d = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
        assertThrows(UnsupportedOperationException.class, () -> d.tags().add(BlockTag.AVOID));
    }

    @Test
    void copiesTheTagSetIn() {
        EnumSet<BlockTag> caller = EnumSet.of(BlockTag.SLOW);
        BlockData d = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, caller);
        caller.add(BlockTag.AVOID);
        assertFalse(d.has(BlockTag.AVOID), "mutating the caller's set must not change the data");
    }

    @Test
    void unknownIsTheSingletonForUnreadablePositions() {
        assertEquals(BlockShape.UNKNOWN, BlockData.UNKNOWN.shape());
        assertEquals(0.0, BlockData.UNKNOWN.collisionTop(), 0.0);
        assertEquals(Fluid.NONE, BlockData.UNKNOWN.fluid());
        assertTrue(BlockData.UNKNOWN.tags().isEmpty());
    }

    @Test
    void rejectsANullShape() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(null, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class)));
    }

    @Test
    void rejectsANullFluid() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(BlockShape.AIR, 0.0, null, EnumSet.noneOf(BlockTag.class)));
    }

    @Test
    void rejectsANullTagSet() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, null));
    }

    @Test
    void equalValuesAreEqual() {
        BlockData a = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.of(BlockTag.SLOW));
        BlockData b = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.of(BlockTag.SLOW));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differingValuesAreNotEqual() {
        BlockData a = new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
        BlockData b = new BlockData(BlockShape.FULL, 1.0, Fluid.WATER, EnumSet.noneOf(BlockTag.class));
        assertFalse(a.equals(b));
    }

    @Test
    void toStringIsStableAndCarriesEveryField() {
        BlockData d = new BlockData(BlockShape.STAIR, 1.0, Fluid.WATER, EnumSet.of(BlockTag.AVOID));
        assertEquals("STAIR top=1.0 fluid=WATER tags=[AVOID]", d.toString());
    }
}
