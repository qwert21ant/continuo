package dev.continuo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YawTest {

    @Test
    void towardUsesTheConventionBothVersionsShare() {
        // Both target versions derive forward motion as (-sin yaw, cos yaw), which inverts to
        // yaw = -atan2(dx, dz). Verified against 1.7.10's moveFlying and 1.21.11's getInputVector
        // in D1 design section 3.4.
        assertEquals(0.0f, Yaw.toward(0.5, 0.5, 0, 10), 0.001f, "+Z is yaw 0");
        assertEquals(90.0f, Yaw.toward(0.5, 0.5, -10, 0), 0.001f, "-X is yaw 90");
        assertEquals(-90.0f, Yaw.toward(0.5, 0.5, 10, 0), 0.001f, "+X is yaw -90");
        // Due north comes back as -180 rather than +180: atan2(+0.0, -z) is +pi, and toward
        // negates it. The two name the same direction; this pins which representation is used.
        assertEquals(-180.0f, Yaw.toward(0.5, 0.5, 0, -10), 0.001f, "-Z is yaw -180");
    }

    @Test
    void towardAimsAtTheBlockCentreNotItsCorner() {
        // From the centre of block 0, block 1 due +X is 1.0 away in X and 0 in Z. If toward
        // aimed at the corner instead, dz would be -0.5 and the answer would not be -90.
        assertEquals(-90.0f, Yaw.toward(0.5, 0.5, 1, 0), 0.001f);
    }

    @Test
    void wrapFoldsIntoTheHalfOpenRange() {
        assertEquals(0.0f, Yaw.wrap(0.0f), 0.001f);
        assertEquals(-1.0f, Yaw.wrap(359.0f), 0.001f, "359 and -1 are one degree apart");
        assertEquals(-170.0f, Yaw.wrap(190.0f), 0.001f);
        assertEquals(170.0f, Yaw.wrap(-190.0f), 0.001f);
        assertEquals(-180.0f, Yaw.wrap(180.0f), 0.001f, "the range is half open at +180");
        assertEquals(-180.0f, Yaw.wrap(-180.0f), 0.001f, "and closed at -180");
    }

    @Test
    void wrapMakesTheShortestArcAcrossTheDiscontinuity() {
        // The property HumanizedActuator depends on: turning from -179 to 179 is 2 degrees, not
        // 358. A wrap that returned the raw difference would give 358 here.
        assertEquals(-2.0f, Yaw.wrap(179.0f - (-179.0f)), 0.001f);
    }
}
