package dev.continuo.core.fakes;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformContext implements IPlatformContext {

    private final FakeActuator actuator = new FakeActuator();
    private final IPlatformInfo info = new FakePlatformInfo("0.0-test", Loader.FABRIC);

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }

    public FakeActuator fakeActuator() {
        return actuator;
    }
}
