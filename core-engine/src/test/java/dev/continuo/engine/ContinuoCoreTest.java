package dev.continuo.engine;

import dev.continuo.core.BlockShape;
import dev.continuo.core.CoreApi;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlatformContext;
import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuoCoreTest {

    private FakePlatformContext ctx;
    private FakeActuator actuator;
    private ContinuoCore core;

    @BeforeEach
    void setUp() {
        ctx = new FakePlatformContext();
        actuator = ctx.fakeActuator();
        core = new ContinuoCore();
        core.start(ctx);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            core.onClientTick(TickPhase.PRE);
        }
    }

    @Test
    void pressesForwardOnFirstTickAfterRequest() {
        core.requestWalk();
        tick(1);

        assertEquals(1, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(0).input);
        assertTrue(actuator.calls().get(0).pressed);
    }

    /** How many of the recorded calls were presses. */
    private int pressCount() {
        int n = 0;
        for (FakeActuator.Call call : actuator.calls()) {
            if (call.pressed) {
                n++;
            }
        }
        return n;
    }

    private FakeActuator.Call lastCall() {
        assertTrue(actuator.callCount() > 0, "expected at least one actuator call");
        return actuator.calls().get(actuator.callCount() - 1);
    }

    /**
     * Global rule 4's whole content, as one assertion. The core re-states its desired input every
     * tick rather than pressing once and trusting the press to persist — which both target versions
     * break whenever a screen opens.
     */
    @Test
    void reAssertsForwardOnEveryTickOfTheWalk() {
        core.requestWalk();
        tick(40);

        assertEquals(40, actuator.callCount(), "level-triggered: one call per tick, not one in total");
        assertEquals(40, pressCount());
        for (FakeActuator.Call call : actuator.calls()) {
            assertEquals(Input.FORWARD, call.input);
            assertTrue(call.pressed);
        }
    }

    @Test
    void releasesExactlyOnceOnTickFortyOne() {
        core.requestWalk();
        tick(40);
        assertEquals(40, actuator.callCount(), "guard: the walk must still be running at tick 40");

        tick(1);

        assertEquals(41, actuator.callCount());
        assertEquals(Input.FORWARD, lastCall().input);
        assertEquals(false, lastCall().pressed);
        assertEquals(40, pressCount(), "exactly one release, and no extra press on tick 41");
    }

    /**
     * Global rule 4's second clause. An idle core writes nothing at all — it must not hold every
     * input at {@code false} every tick, which would fight the user's own keyboard whenever the bot
     * is not running.
     *
     * <p>Both halves are also covered by {@code doesNothingBeforeAnyWalkIsRequested} and
     * {@code doesNothingAfterTheWalkCompletes}; this states them together as one clause of rule
     * 4, which is the form the contract is written in.
     */
    @Test
    void writesNothingWhileIdle() {
        tick(20);
        assertEquals(0, actuator.callCount(), "before any walk is requested");

        core.requestWalk();
        tick(41);
        actuator.clear();
        tick(20);

        assertEquals(0, actuator.callCount(), "after the walk has finished");
    }

    /**
     * The same clause on the path that reaches idleness through {@code stop()} rather than through
     * the walk running out. Level-triggering makes this newly worth pinning: a core that re-stated
     * its inputs unconditionally would keep writing here, where before D1 there was no per-tick
     * write that could.
     */
    @Test
    void writesNothingOnTicksAfterStop() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        tick(20);

        assertEquals(0, actuator.callCount(), "stop() ends the walk, and an idle core is silent");
    }

    /**
     * Re-requesting mid-walk is still ignored. Asserted through the walk's *length* rather than
     * through a call count, because under level-triggering every tick produces a call and a count
     * can no longer distinguish "ignored" from "restarted".
     */
    @Test
    void reRequestingMidWalkDoesNotRestartOrExtendIt() {
        core.requestWalk();
        tick(10);

        core.requestWalk();
        tick(30);

        assertEquals(40, pressCount(), "guard: 40 presses so far");
        assertTrue(lastCall().pressed, "guard: still walking at tick 40");

        tick(1);

        assertEquals(false, lastCall().pressed,
            "released on tick 41 -- a re-request that restarted or extended the walk would "
                + "still be pressing here");
    }

    @Test
    void doesNothingAfterTheWalkCompletes() {
        core.requestWalk();
        tick(41);
        actuator.clear();

        tick(4);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void neverTouchesAnyInputOtherThanForward() {
        core.requestWalk();
        tick(45);

        assertTrue(actuator.callCount() > 0, "walk must produce actuator calls");
        for (FakeActuator.Call call : actuator.calls()) {
            assertEquals(Input.FORWARD, call.input);
        }
    }

    @Test
    void doesNothingBeforeAnyWalkIsRequested() {
        tick(20);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void ignoresPostPhaseTicks() {
        core.requestWalk();
        core.onClientTick(TickPhase.POST);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void stopReleasesForwardMidWalk() {
        core.requestWalk();
        tick(20);
        actuator.clear();

        core.stop();

        assertEquals(1, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(0).input);
        assertEquals(false, actuator.calls().get(0).pressed);
    }

    @Test
    void stopWhenNotWalkingReleasesNothing() {
        core.stop();

        assertEquals(0, actuator.callCount());
    }

    /**
     * Global rule 2 states that {@code stop()} is idempotent. The core already satisfies
     * this; the test exists to pin it, so that a future change to {@code stop()} cannot
     * quietly break an adapter that calls it on both world unload and client shutdown.
     */
    @Test
    void stopIsIdempotent() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        core.stop();
        core.stop();

        assertEquals(0, actuator.callCount(), "repeated stop() must not touch the actuator");
    }

    @Test
    void canWalkAgainAfterStop() {
        core.requestWalk();
        tick(20);
        core.stop();
        actuator.clear();

        core.requestWalk();
        tick(1);

        assertEquals(1, actuator.callCount());
        assertTrue(actuator.calls().get(0).pressed);
    }

    @Test
    void requestWalkBeforeStartFails() {
        ContinuoCore unstarted = new ContinuoCore();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.requestWalk();
            }
        });
    }

    @Test
    void stopBeforeStartFails() {
        ContinuoCore unstarted = new ContinuoCore();

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.stop();
            }
        });
    }

    @Test
    void startWithNullContextFails() {
        final ContinuoCore unstarted = new ContinuoCore();

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.start(null);
            }
        });
    }

    /**
     * Pins current behaviour: calling {@code start()} a second time silently replaces the
     * context rather than throwing.
     *
     * <p>Global rule 2 says an adapter calls {@code start()} exactly once per lifetime. That
     * rule binds adapters, not the core, and this leniency is deliberately not promoted to a
     * guarantee — an adapter MUST NOT rely on it. If the core's behaviour here ever changes,
     * this test should change with it rather than be deleted silently.
     */
    @Test
    void startTwiceReplacesContext() {
        FakePlatformContext secondCtx = new FakePlatformContext();
        FakeActuator secondActuator = secondCtx.fakeActuator();

        core.start(secondCtx);
        core.requestWalk();
        tick(1);

        assertEquals(0, actuator.callCount(), "original context's actuator must not be used");
        assertEquals(1, secondActuator.callCount());
        assertEquals(Input.FORWARD, secondActuator.calls().get(0).input);
        assertTrue(secondActuator.calls().get(0).pressed);
    }

    /**
     * Pins the A2b seam: an adapter runtime holds the core through {@link CoreApi} so a
     * recording fake can be substituted. If this stops compiling, the seam is gone and
     * {@code platform-testkit} can no longer observe anything.
     */
    @Test
    void continuoCoreIsUsableThroughTheCoreApiSeam() {
        CoreApi seam = new ContinuoCore();
        seam.start(new FakePlatformContext());
        seam.onClientTick(TickPhase.PRE);
        seam.stop();
    }

    @Test
    void exposesAClassifyingLookupOverThePlatformsBlockView() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        assertEquals(BlockShape.FULL, core.blocks().at(0, 64, 0).shape());
    }

    @Test
    void theLookupIsTheSameInstanceAcrossCalls() {
        assertSame(core.blocks(), core.blocks());
    }

    @Test
    void stopClearsTheBlockMemoSoStateIdsCannotOutliveTheirLevel() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        core.blocks().at(0, 64, 0);
        assertEquals(1, ctx.fakeBlockView().describeCallCount());

        core.blocks().at(0, 64, 0);
        assertEquals(1, ctx.fakeBlockView().describeCallCount(), "the memo must still be live before stop()");

        core.stop();
        core.blocks().at(0, 64, 0);

        assertEquals(2, ctx.fakeBlockView().describeCallCount(),
            "global rule 2 requires stop() on every level transition, and state ids are session-scoped");
    }

    @Test
    void blocksBeforeStartIsAnError() {
        ContinuoCore fresh = new ContinuoCore();
        assertThrows(IllegalStateException.class, fresh::blocks);
    }
}
