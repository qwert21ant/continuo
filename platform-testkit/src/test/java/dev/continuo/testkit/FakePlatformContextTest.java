package dev.continuo.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code IPlatformContext}'s same-instance clause for the accessor D1 adds.
 *
 * <p>The clause is what lets the core cache what an accessor returns. It has always been stated
 * and never asserted; the fourth accessor is a cheap place to start asserting it.
 */
class FakePlatformContextTest {

    @Test
    void playerReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertSame(ctx.player(), ctx.player());
        assertSame(ctx.fakePlayerView(), ctx.player());
    }

    @Test
    void everyAccessorReturnsTheSameInstanceOnEveryCall() {
        FakePlatformContext ctx = new FakePlatformContext();

        assertSame(ctx.actuator(), ctx.actuator());
        assertSame(ctx.info(), ctx.info());
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
        assertEquals(false, ctx.player().onGround());
    }
}
