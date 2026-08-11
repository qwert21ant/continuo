package dev.continuo.core.fakes;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;

import java.util.ArrayList;
import java.util.List;

/** Records every actuator call so tests can assert on exact call sequences. */
public final class FakeActuator implements IActuator {

    public static final class Call {
        public final Input input;
        public final boolean pressed;

        Call(Input input, boolean pressed) {
            this.input = input;
            this.pressed = pressed;
        }

        @Override
        public String toString() {
            return input + "=" + pressed;
        }
    }

    private final List<Call> calls = new ArrayList<Call>();

    @Override
    public void setInput(Input input, boolean pressed) {
        calls.add(new Call(input, pressed));
    }

    public List<Call> calls() {
        return calls;
    }

    public int callCount() {
        return calls.size();
    }

    public void clear() {
        calls.clear();
    }
}
