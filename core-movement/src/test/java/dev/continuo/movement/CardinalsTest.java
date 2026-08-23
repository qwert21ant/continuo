package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardinalsTest {

    @Test
    void theFourStepsAreNorthEastSouthWestInThatOrder() {
        assertEquals(4, Cardinals.count());

        assertEquals(0, Cardinals.dx(0));
        assertEquals(-1, Cardinals.dz(0));

        assertEquals(1, Cardinals.dx(1));
        assertEquals(0, Cardinals.dz(1));

        assertEquals(0, Cardinals.dx(2));
        assertEquals(1, Cardinals.dz(2));

        assertEquals(-1, Cardinals.dx(3));
        assertEquals(0, Cardinals.dz(3));
    }
}
