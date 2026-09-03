package dev.continuo.engine;

import dev.continuo.platform.Input;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlayerView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanizedActuatorTest {

    private final FakeActuator delegate = new FakeActuator();
    private final FakePlayerView player = new FakePlayerView();
    private final HumanizedActuator actuator = new HumanizedActuator(delegate, player);

    @Test
    void aSmallTurnIsPassedThroughWhole() {
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        actuator.setLook(10.0f, 0.0f);

        assertEquals(1, delegate.lookCalls().size());
        assertEquals(10.0f, delegate.lookCalls().get(0).yaw, 0.001f);
    }

    @Test
    void aLargeTurnIsClampedToTheRate() {
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        actuator.setLook(180.0f, 0.0f);

        assertEquals(HumanizedActuator.MAX_DEG_PER_TICK, delegate.lookCalls().get(0).yaw, 0.001f,
            "a 180 degree request must not arrive in one frame");
    }

    @Test
    void aReversalTakesSixTicksAndNoFewer() {
        // The behavioural claim in one assertion: 180 degrees at 30 per tick is six ticks. The
        // executor re-states its target every tick, so this loop is what actually happens.
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);

        for (int tick = 0; tick < 6; tick++) {
            actuator.setLook(180.0f, 0.0f);
            // The executor does not move the player here; the adapter would. Feed the written
            // yaw back as the player's actual yaw, which is what a real client does.
            player.set(0.0, 64.0, 0.0, delegate.lookCalls().get(tick).yaw, 0.0f, true);
        }

        assertEquals(180.0f, delegate.lookCalls().get(5).yaw, 0.001f, "six ticks reaches it");
        assertTrue(Math.abs(delegate.lookCalls().get(4).yaw) < 180.0f, "five ticks does not");
    }

    @Test
    void theTurnTakesTheShortestArcAcrossTheDiscontinuity() {
        // From -179 toward 179 is 2 degrees the short way and 358 the long way. An implementation
        // that subtracted without wrapping would step +30 -- the wrong direction entirely.
        player.set(0.0, 64.0, 0.0, -179.0f, 0.0f, true);

        actuator.setLook(179.0f, 0.0f);

        assertEquals(-181.0f, delegate.lookCalls().get(0).yaw, 0.001f,
            "two degrees anticlockwise from -179, not thirty degrees clockwise");
    }

    @Test
    void theStepIsComputedFromTheLivePlayerYawNotThePreviousRequest() {
        // The assertion that pins statelessness. A stateful implementation would remember it had
        // asked for 90 and step on from there; this one must start from where the player now
        // actually is, which is how a server rotation correction gets absorbed.
        player.set(0.0, 64.0, 0.0, 0.0f, 0.0f, true);
        actuator.setLook(90.0f, 0.0f);
        assertEquals(30.0f, delegate.lookCalls().get(0).yaw, 0.001f);

        // The server yanks the player somewhere else entirely.
        player.set(0.0, 64.0, 0.0, -90.0f, 0.0f, true);
        actuator.setLook(90.0f, 0.0f);

        assertEquals(-60.0f, delegate.lookCalls().get(1).yaw, 0.001f,
            "thirty degrees on from -90, not from the 30 it last wrote");
    }

    @Test
    void pitchIsPassedThroughUnchanged() {
        player.set(0.0, 64.0, 0.0, 0.0f, 42.0f, true);

        actuator.setLook(0.0f, 42.0f);

        assertEquals(42.0f, delegate.lookCalls().get(0).pitch, 0.001f);
    }

    @Test
    void inputIsPassedThroughUntouched() {
        actuator.setInput(Input.FORWARD, true);
        actuator.setInput(Input.JUMP, false);

        assertEquals(2, delegate.calls().size());
        assertEquals(Input.FORWARD, delegate.calls().get(0).input);
        assertTrue(delegate.calls().get(0).pressed);
        assertEquals(Input.JUMP, delegate.calls().get(1).input);
        assertEquals(false, delegate.calls().get(1).pressed);
    }
}
