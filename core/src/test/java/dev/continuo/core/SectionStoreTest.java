package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionStoreTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData AIR = new BlockData(
        BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    @Test
    void aStoredBlockComesBackFromTheSamePosition() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);

        assertSame(STONE, store.get(3, 70, 4));
        assertTrue(store.has(3, 70, 4));
        assertEquals(1, store.size());
    }

    @Test
    void anUnstoredPositionIsUnknownAndNotHeld() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);

        assertSame(BlockData.UNKNOWN, store.get(9, 70, 9));
        assertFalse(store.has(9, 70, 9),
            "never-read and read-as-UNKNOWN must stay distinguishable; covers() is built on it");
    }

    @Test
    void aStoredUnknownIsHeldRatherThanLookingUnread() {
        // The distinction covers() exists for. An unloaded chunk answered UNKNOWN at the moment it
        // was read, and that is a real answer; a position nobody looked at is not.
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, BlockData.UNKNOWN);

        assertSame(BlockData.UNKNOWN, store.get(3, 70, 4));
        assertTrue(store.has(3, 70, 4), "a stored UNKNOWN is covered; an absent one is not");
        assertEquals(1, store.size());
    }

    @Test
    void theThreeAxesAreNotInterchangeable() {
        // The offset packs three coordinates into one array index, and a transposed pair is
        // invisible to any test whose coordinates are symmetric. These three positions share a
        // section and differ only in which axis carries the odd value.
        SectionStore store = new SectionStore();
        store.put(1, 0, 0, STONE);
        store.put(0, 1, 0, AIR);

        assertSame(STONE, store.get(1, 0, 0), "x=1 must not alias y=1 or z=1");
        assertSame(AIR, store.get(0, 1, 0), "y=1 must not alias x=1 or z=1");
        assertSame(BlockData.UNKNOWN, store.get(0, 0, 1), "z=1 was never stored");
        assertFalse(store.has(0, 0, 1));
    }

    @Test
    void negativeCoordinatesLandInTheirOwnSections() {
        // Arithmetic shift floors toward negative infinity, so -1 >> 2 is -1 and 0 >> 2 is 0:
        // -1 and 0 are in different sections. A store using division would put them in the same
        // one and silently overwrite.
        SectionStore store = new SectionStore();
        store.put(-1, -1, -1, STONE);
        store.put(0, 0, 0, AIR);

        assertSame(STONE, store.get(-1, -1, -1));
        assertSame(AIR, store.get(0, 0, 0));
        assertEquals(2, store.size());
    }

    @Test
    void overwritingAPositionDoesNotCountItTwice() {
        SectionStore store = new SectionStore();
        store.put(3, 70, 4, STONE);
        store.put(3, 70, 4, AIR);

        assertSame(AIR, store.get(3, 70, 4));
        assertEquals(1, store.size(), "size counts positions, not writes");
    }

    @Test
    void readingTheSameSectionRepeatedlyIsStillCorrect() {
        // The memo is the whole point of this class, and a memo that goes stale returns another
        // position's block. Sixty-four positions in one section, read in an order that revisits.
        SectionStore store = new SectionStore();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    store.put(x, y, z, new BlockData(
                        BlockShape.FULL, x * 100 + y * 10 + z, Fluid.NONE,
                        EnumSet.noneOf(BlockTag.class)));
                }
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        assertEquals(x * 100 + y * 10 + z, store.get(x, y, z).collisionTop(),
                            1.0e-9, "(" + x + ", " + y + ", " + z + ") on pass " + pass);
                    }
                }
            }
        }
    }

    @Test
    void crossingBetweenTwoSectionsRepeatedlyDoesNotStickToOne() {
        // The memo failure that a single-section test cannot see: hold the first section and never
        // update it, and every read in the second returns UNKNOWN.
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);
        store.put(4, 0, 0, AIR);

        for (int i = 0; i < 5; i++) {
            assertSame(STONE, store.get(0, 0, 0), "pass " + i);
            assertSame(AIR, store.get(4, 0, 0), "pass " + i);
        }
    }

    @Test
    void slotsCountsWhatWasAllocatedRatherThanWhatWasStored() {
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);

        assertEquals(1, store.size());
        assertEquals(64, store.slots(), "one 4x4x4 section is allocated whole");
    }
}
