package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData AIR = new BlockData(
        BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** A source whose answer at one position changes on every read. */
    private static final class ShiftingSource implements BlockSource {
        private int served;

        @Override
        public BlockData at(int x, int y, int z) {
            served++;
            return new BlockData(BlockShape.FULL, served, Fluid.NONE,
                EnumSet.noneOf(BlockTag.class));
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    @Test
    void aFillingSnapshotAnswersExactlyAsTheLiveSourceDoes() {
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        live.put(3, 71, 4, AIR);

        WorldSnapshot snapshot = new WorldSnapshot(live);

        for (int y = 68; y < 74; y++) {
            for (int x = 1; x < 6; x++) {
                for (int z = 2; z < 7; z++) {
                    assertSame(live.at(x, y, z), snapshot.at(x, y, z),
                        "(" + x + ", " + y + ", " + z + ")");
                }
            }
        }
        assertEquals(live.minY(), snapshot.minY());
        assertEquals(live.maxY(), snapshot.maxY());
    }

    @Test
    void everyPositionIsReadFromTheLiveSourceExactlyOnce() {
        // The claim the whole design rests on: measured on real terrain, a search reads each
        // position it touches between 4 and 16 times, and a snapshot turns all of those into one
        // SPI call. Asserting per position, not in total - a total would pass on a snapshot that
        // read one position twice and another never.
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        WorldSnapshot snapshot = new WorldSnapshot(live);

        for (int i = 0; i < 20; i++) {
            snapshot.at(3, 70, 4);
            snapshot.at(3, 71, 4);
        }

        assertEquals(1, live.callsAt(3, 70, 4));
        assertEquals(1, live.callsAt(3, 71, 4));
        assertEquals(2, live.calls(), "two distinct positions, two live reads");
        assertEquals(2, snapshot.size());
        assertEquals(40, snapshot.reads(), "forty reads served from two live ones");
    }

    @Test
    void theFirstAnswerIsTheOnlyAnswerEvenWhenTheWorldMoves() {
        // The stability property C3 was chosen for. A search that spans more than one tick can
        // have a chunk load or a block break under it; a snapshot means one search sees one
        // world. ShiftingSource returns a different block on every single call, so anything that
        // re-reads is caught immediately.
        ShiftingSource live = new ShiftingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        BlockData first = snapshot.at(0, 70, 0);

        for (int i = 0; i < 50; i++) {
            assertSame(first, snapshot.at(0, 70, 0),
                "read " + i + " disagreed with the first, so the snapshot is not a snapshot");
        }
    }

    @Test
    void anUnknownIsStoredAndNeverAskedAgain() {
        // "Unloaded is not air", and the reason it matters twice over. An unloaded chunk is a
        // real answer at the moment it was read, so storing it preserves stability - and it is
        // also what stops the repeat factor from re-hitting the SPI on exactly the positions a
        // search probes hardest, the edges of what it can see.
        //
        // This is the test that targets the store-UNKNOWN guarantee directly. A mutation that
        // stops storing UNKNOWN breaks three other tests in this file too, but only incidentally:
        // their fixtures happen to rely on an unread position reading back as UNKNOWN. It is one
        // guarantee cascading, not four independent ones - if this test starts failing, look here
        // first.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        assertSame(BlockData.UNKNOWN, snapshot.at(8, 70, 8));

        live.put(8, 70, 8, STONE);

        for (int i = 0; i < 10; i++) {
            assertSame(BlockData.UNKNOWN, snapshot.at(8, 70, 8),
                "the chunk loading later must not change what this snapshot already read");
        }
        assertEquals(1, live.callsAt(8, 70, 8));
        assertEquals(1, snapshot.size(), "a stored UNKNOWN occupies an entry like any other");
    }

    @Test
    void outsideTheWorldsYLimitsCostsNothingAndStoresNothing() {
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);

        assertSame(BlockData.UNKNOWN, snapshot.at(0, -65, 0));
        assertSame(BlockData.UNKNOWN, snapshot.at(0, 320, 0));

        assertEquals(0, live.calls(), "out of world is computable; do not ask the world");
        assertEquals(0, snapshot.size(), "and do not fill the map with entries carrying nothing");
        assertEquals(2, snapshot.reads(), "but they were still reads this snapshot served");
    }

    @Test
    void sealingKeepsTheAnswersAndStopsTouchingTheWorld() {
        RecordingSource live = new RecordingSource();
        live.put(3, 70, 4, STONE);
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 71, 4);

        SealedSnapshot sealed = snapshot.seal();
        live.refuseFurtherReads();

        assertSame(STONE, sealed.at(3, 70, 4));
        assertSame(BlockData.UNKNOWN, sealed.at(3, 71, 4));
        assertTrue(sealed.covers(3, 71, 4));
        assertSame(BlockData.UNKNOWN, sealed.at(99, 70, 99));
        assertFalse(sealed.covers(99, 70, 99),
            "a position the search never reached is a hole, and reading it must not go looking");
    }

    @Test
    void theSealedCountersAreTheFillingHandlesLastValues() {
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 70, 4);
        snapshot.at(3, 71, 4);

        int size = snapshot.size();
        int reads = snapshot.reads();
        SealedSnapshot sealed = snapshot.seal();

        assertEquals(2, size);
        assertEquals(3, reads);
        assertEquals(size, sealed.size());
        assertEquals(reads, sealed.reads());
    }

    @Test
    void aSealedHandleRefusesEverythingThatWouldLie() {
        // Returning UNKNOWN from a sealed-out handle would present to the caller as terrain, and
        // a phantom wall that appears only after a seal is the worst failure this design has.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.at(3, 70, 4);
        snapshot.seal();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.at(3, 70, 4);
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.size();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.reads();
            }
        });
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                snapshot.seal();
            }
        });
    }

    @Test
    void aSealedHandleStillKnowsTheWorldsLimits() {
        // minY and maxY are values captured at construction, not state that seal() gives away, so
        // they keep answering. Nothing can be misled by them: at() is the method that would lie.
        RecordingSource live = new RecordingSource();
        WorldSnapshot snapshot = new WorldSnapshot(live);
        snapshot.seal();

        assertEquals(-64, snapshot.minY());
        assertEquals(320, snapshot.maxY());
    }

    @Test
    void aNullLiveSourceIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    new WorldSnapshot(null);
                }
            });
    }
}
