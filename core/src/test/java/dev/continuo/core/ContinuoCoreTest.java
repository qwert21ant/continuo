package dev.continuo.core;

import dev.continuo.core.fakes.FakeActuator;
import dev.continuo.core.fakes.FakePlatformContext;
import dev.continuo.platform.Input;
import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void holdsForwardForFortyTicksThenReleasesOnTickFortyOne() {
        core.requestWalk();
        tick(40);

        assertEquals(1, actuator.callCount(), "no release before tick 41");

        tick(1);

        assertEquals(2, actuator.callCount());
        assertEquals(Input.FORWARD, actuator.calls().get(1).input);
        assertEquals(false, actuator.calls().get(1).pressed);
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
    void ignoresRequestWalkWhileAlreadyWalking() {
        core.requestWalk();
        tick(10);
        actuator.clear();

        core.requestWalk();
        tick(10);

        assertEquals(0, actuator.callCount(), "re-triggering mid-walk must be ignored");
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
     * context rather than throwing. This is not a stated contract, only what the class does
     * today; if that ever changes, this test should change with it rather than be deleted
     * silently.
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
}
