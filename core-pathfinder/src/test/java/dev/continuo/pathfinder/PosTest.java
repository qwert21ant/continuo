package dev.continuo.pathfinder;

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

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = Pos.pack(x, y, z);
        assertEquals(x, Pos.unpackX(packed), "x");
        assertEquals(y, Pos.unpackY(packed), "y");
        assertEquals(z, Pos.unpackZ(packed), "z");
        assertEquals(new Pos(x, y, z), Pos.unpack(packed));
    }
}
