package dev.continuo.pathfinder;

import dev.continuo.core.PositionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PosTest {

    @Test
    void packingRoundTripsThroughTheOrigin() {
        assertRoundTrip(0, 0, 0);
    }

    @Test
    void packingRoundTripsNegativeCoordinates() {
        assertRoundTrip(-1, -1, -1);
        assertRoundTrip(-30000000, -64, -30000000);
    }

    @Test
    void packingRoundTripsBothWorldHeights() {
        assertRoundTrip(0, 255, 0);     // 1.7.10's top
        assertRoundTrip(0, 319, 0);     // 1.21.11's top
        assertRoundTrip(0, -64, 0);     // 1.21.11's floor
    }

    @Test
    void packingRoundTripsFarHorizontalCoordinates() {
        assertRoundTrip(30000000, 64, 30000000);
    }

    @Test
    void distinctPositionsPackDistinctly() {
        assertNotEquals(Pos.pack(1, 0, 0), Pos.pack(0, 0, 1));
        assertNotEquals(Pos.pack(1, 0, 0), Pos.pack(0, 1, 0));
        assertNotEquals(Pos.pack(-1, 0, 0), Pos.pack(1, 0, 0));
    }

    @Test
    void equalPositionsAreEqualAndHashAlike() {
        assertEquals(new Pos(3, -4, 5), new Pos(3, -4, 5));
        assertEquals(new Pos(3, -4, 5).hashCode(), new Pos(3, -4, 5).hashCode());
        assertNotEquals(new Pos(3, -4, 5), new Pos(3, -4, 6));
    }

    @Test
    void packingIsPositionKeysPacking() {
        // The net under C3's extraction of the bit layout into :core, where WorldSnapshot can
        // reach it. Every other test in this file checks Pos against itself and would go on
        // passing if the two definitions drifted apart; only this one would fail.
        int[][] awkward = {
            {0, 0, 0},
            {0, 64, 0},
            {-1, -1, -1},
            {0, -64, 0},
            {0, 319, 0},
            {0, 255, 0},
            {-30000000, -64, 30000000},
            {30000000, 319, -30000000},
        };

        for (int i = 0; i < awkward.length; i++) {
            int x = awkward[i][0];
            int y = awkward[i][1];
            int z = awkward[i][2];
            String where = "(" + x + ", " + y + ", " + z + ")";
            long packed = Pos.pack(x, y, z);

            assertEquals(PositionKey.pack(x, y, z), packed, "pack of " + where);
            assertEquals(PositionKey.unpackX(packed), Pos.unpackX(packed), "x of " + where);
            assertEquals(PositionKey.unpackY(packed), Pos.unpackY(packed), "y of " + where);
            assertEquals(PositionKey.unpackZ(packed), Pos.unpackZ(packed), "z of " + where);
        }
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = Pos.pack(x, y, z);
        assertEquals(x, Pos.unpackX(packed), "x");
        assertEquals(y, Pos.unpackY(packed), "y");
        assertEquals(z, Pos.unpackZ(packed), "z");
        assertEquals(new Pos(x, y, z), Pos.unpack(packed));
    }
}
