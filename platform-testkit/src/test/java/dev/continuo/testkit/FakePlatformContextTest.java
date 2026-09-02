package dev.continuo.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code IPlatformContext}'s same-instance clause for the accessor D1 adds, and that the
 * accessor returns something.
 *
 * <p>The same-instance clause is what lets the core cache what an accessor returns. It has
 * always been stated and never asserted; the fourth accessor is a cheap place to start
 * asserting it.
 *
 * <p>The non-null assertions are load-bearing, not decoration: {@code assertSame(null, null)}
 * passes, so a same-instance test alone would be satisfied by an accessor that returns
 * {@code null} on every call — pinning the same-instance clause while silently saying nothing
 * about the accessor actually producing a value. Each {@code assertNotNull} below runs before
 * its paired {@code assertSame} so that the same-instance check cannot vacuously pass this way.
 */
class FakePlatformContextTest {

    @Test
    void playerReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        // Must come before assertSame: assertSame(null, null) passes, so without this the
        // same-instance check below could vacuously pass on an accessor returning null.
        assertNotNull(ctx.player());
        assertSame(ctx.player(), ctx.player());
        assertSame(ctx.fakePlayerView(), ctx.player());
    }

    @Test
    void everyAccessorReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertNotNull(ctx.actuator());
        assertSame(ctx.actuator(), ctx.actuator());
        assertNotNull(ctx.info());
        assertSame(ctx.info(), ctx.info());
        assertNotNull(ctx.blocks());
        assertSame(ctx.blocks(), ctx.blocks());
    }

    @Test
    void theFakePlayerViewReportsWhatWasSet() {
        FakePlatformContext ctx = new FakePlatformContext();

        ctx.fakePlayerView().set(1.5, 64.0, -2.25, 90.0f, -12.5f, true);

        assertEquals(1.5, ctx.player().x());
        assertEquals(64.0, ctx.player().y());
        assertEquals(-2.25, ctx.player().z());
        assertEquals(90.0f, ctx.player().yaw());
        assertEquals(-12.5f, ctx.player().pitch());
        assertTrue(ctx.player().onGround());
    }

    @Test
    void theFakePlayerViewStartsAtTheOrigin() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertEquals(0.0, ctx.player().x());
        assertEquals(0.0, ctx.player().y());
        assertEquals(0.0, ctx.player().z());
        assertEquals(0.0f, ctx.player().yaw());
        assertEquals(0.0f, ctx.player().pitch());
        assertFalse(ctx.player().onGround());
    }
}
