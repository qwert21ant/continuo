package dev.continuo.testkit;

import dev.continuo.platform.Input;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the recording fake, which is the only observer any core-side test of actuation has. */
class FakeActuatorTest {

    @Test
    void recordsLookCallsInOrder() {
        FakeActuator actuator = new FakeActuator();

        actuator.setLook(90.0f, -12.5f);
        actuator.setLook(-45.0f, 0.0f);

        assertEquals(2, actuator.lookCalls().size());
        assertEquals(90.0f, actuator.lookCalls().get(0).yaw);
        assertEquals(-12.5f, actuator.lookCalls().get(0).pitch);
        assertEquals(-45.0f, actuator.lookCalls().get(1).yaw);
        assertEquals(0.0f, actuator.lookCalls().get(1).pitch);
    }

    @Test
    void looksAndInputsAreRecordedSeparately() {
        FakeActuator actuator = new FakeActuator();

        actuator.setInput(Input.FORWARD, true);
        actuator.setLook(1.0f, 2.0f);

        assertEquals(1, actuator.callCount(), "a look must not count as an input call");
        assertEquals(1, actuator.lookCalls().size());
    }

    @Test
    void clearEmptiesBothRecordings() {
        FakeActuator actuator = new FakeActuator();
        actuator.setInput(Input.FORWARD, true);
        actuator.setLook(1.0f, 2.0f);

        actuator.clear();

        assertEquals(0, actuator.callCount());
        assertEquals(0, actuator.lookCalls().size(),
            "clear() must reset looks too, or a test that clears between phases sees stale ones");
    }
}
