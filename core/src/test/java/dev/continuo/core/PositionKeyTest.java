package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PositionKeyTest {

    /** Positions chosen to exercise both signs on every axis and both Y limits of both versions. */
    private static final int[][] AWKWARD = {
        {0, 0, 0},
        {0, 64, 0},
        {1, 0, 0},
        {0, 0, 1},
        {-1, -1, -1},
        {-1, 0, 0},
        {0, -64, 0},
        {0, 319, 0},
        {0, 255, 0},
        {-30000000, -64, 30000000},
        {30000000, 319, -30000000},
        {33554431, 2047, -33554432},
        {-33554432, -2048, 33554431},
    };

    @Test
    void theBitLayoutIsExactlyWhatPosUsedBeforeTheExtraction() {
        // Y occupies the low 12 bits, Z the next 26, X the top 26. These three literals pin the
        // layout itself: an extraction that shifted an axis by even one bit would still round
        // trip, and would still pass every other test in this file, while silently aliasing two
        // different positions onto one snapshot entry.
        assertEquals(64L, PositionKey.pack(0, 64, 0));
        assertEquals(1L << 12, PositionKey.pack(0, 0, 1));
        assertEquals(1L << 38, PositionKey.pack(1, 0, 0));
    }

    @Test
    void everyAwkwardPositionRoundTrips() {
        for (int i = 0; i < AWKWARD.length; i++) {
            int x = AWKWARD[i][0];
            int y = AWKWARD[i][1];
            int z = AWKWARD[i][2];
            long packed = PositionKey.pack(x, y, z);
            String where = "(" + x + ", " + y + ", " + z + ")";

            assertEquals(x, PositionKey.unpackX(packed), "X of " + where);
            assertEquals(y, PositionKey.unpackY(packed), "Y of " + where);
            assertEquals(z, PositionKey.unpackZ(packed), "Z of " + where);
        }
    }

    @Test
    void distinctPositionsGetDistinctKeys() {
        // The property a snapshot actually depends on. Without it two blocks share one cache
        // entry and the snapshot answers for the wrong one, which no round-trip test would see.
        for (int i = 0; i < AWKWARD.length; i++) {
            for (int j = i + 1; j < AWKWARD.length; j++) {
                assertNotEquals(
                    PositionKey.pack(AWKWARD[i][0], AWKWARD[i][1], AWKWARD[i][2]),
                    PositionKey.pack(AWKWARD[j][0], AWKWARD[j][1], AWKWARD[j][2]),
                    "positions " + i + " and " + j + " collided");
            }
        }
    }
}
