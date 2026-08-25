package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealedSnapshotTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /**
     * A sealed snapshot holding stone at (1, 70, 2) and an UNKNOWN that was genuinely read at
     * (1, 71, 2) — an unloaded chunk, say — over a world spanning -64..320.
     */
    private static SealedSnapshot fixture() {
        Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
        blocks.put(Long.valueOf(PositionKey.pack(1, 70, 2)), STONE);
        blocks.put(Long.valueOf(PositionKey.pack(1, 71, 2)), BlockData.UNKNOWN);
        return new SealedSnapshot(blocks, -64, 320, 4242);
    }

    @Test
    void aHeldPositionComesBack() {
        SealedSnapshot sealed = fixture();

        assertSame(STONE, sealed.at(1, 70, 2));
        assertTrue(sealed.covers(1, 70, 2),
            "a position read while filling that holds a real block is covered - the first row"
                + " of covers()'s four-case contract, and the one an at()-only assertion leaves"
                + " completely unguarded");
        assertEquals(-64, sealed.minY());
        assertEquals(320, sealed.maxY());
    }

    @Test
    void aPositionThatWasNeverReadIsUnknownAndIsNotCovered() {
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(9, 70, 9));
        assertFalse(sealed.covers(9, 70, 9),
            "a position this snapshot never read is a hole, and M5 must be able to tell");
    }

    @Test
    void aPositionReadAsUnknownIsUnknownAndIsCovered() {
        // THE distinction M5 needs, and the reason covers() exists at all. Both this and the test
        // above return UNKNOWN from at(), so an at()-only assertion cannot tell them apart. An
        // off-thread search must treat them completely differently: this one is terrain to route
        // around, that one is a question only the main thread can answer.
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(1, 71, 2));
        assertTrue(sealed.covers(1, 71, 2),
            "the world's own UNKNOWN was read and frozen; it is an answer, not a hole");
    }

    @Test
    void outsideTheWorldsYLimitsIsUnknownAndIsCovered() {
        SealedSnapshot sealed = fixture();

        assertSame(BlockData.UNKNOWN, sealed.at(1, -65, 2));
        assertSame(BlockData.UNKNOWN, sealed.at(1, 320, 2));
        assertTrue(sealed.covers(1, -65, 2), "out of world is permanent, not a hole");
        assertTrue(sealed.covers(1, 320, 2), "maxY is exclusive, so 320 is already outside");
        assertFalse(sealed.covers(1, 319, 2),
            "but 319 is inside the world and was never read, so it IS a hole");
    }

    @Test
    void theCountersAreTheFrozenOnesAndReadingDoesNotMoveThem() {
        SealedSnapshot sealed = fixture();

        assertEquals(2, sealed.size());
        assertEquals(4242, sealed.reads());

        sealed.at(1, 70, 2);
        sealed.at(9, 70, 9);
        sealed.covers(1, 70, 2);

        assertEquals(2, sealed.size(), "reading a sealed snapshot must not change it");
        assertEquals(4242, sealed.reads(),
            "reads() is the filling handle's count at the moment of sealing; a sealed snapshot"
                + " cannot count its own reads without mutating, and immutability is what makes"
                + " it safe to publish to another thread");
    }
}
