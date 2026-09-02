package dev.continuo.testkit;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;

import java.util.ArrayList;
import java.util.List;

/** Records every actuator call so tests can assert on exact call sequences. */
public final class FakeActuator implements IActuator {

    public static final class Call {
        public final Input input;
        public final boolean pressed;

        public Call(Input input, boolean pressed) {
            this.input = input;
            this.pressed = pressed;
        }

        @Override
        public String toString() {
            return input + "=" + pressed;
        }
    }

    /** One recorded {@code setLook} call. */
    public static final class LookCall {
        public final float yaw;
        public final float pitch;

        public LookCall(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public String toString() {
            return "look(" + yaw + ", " + pitch + ")";
        }
    }

    private final List<Call> calls = new ArrayList<Call>();
    private final List<LookCall> lookCalls = new ArrayList<LookCall>();

    @Override
    public void setInput(Input input, boolean pressed) {
        calls.add(new Call(input, pressed));
    }

    @Override
    public void setLook(float yaw, float pitch) {
        lookCalls.add(new LookCall(yaw, pitch));
    }

    public List<Call> calls() {
        return calls;
    }

    public List<LookCall> lookCalls() {
        return lookCalls;
    }

    public int callCount() {
        return calls.size();
    }

    public void clear() {
        calls.clear();
        lookCalls.clear();
    }
}
