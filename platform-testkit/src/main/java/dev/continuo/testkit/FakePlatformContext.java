package dev.continuo.testkit;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IBlockView;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformContext implements IPlatformContext {

    private final FakeActuator actuator = new FakeActuator();
    private final IPlatformInfo info = new FakePlatformInfo("0.0-test", Loader.FABRIC);
    private final FakeBlockView blockView = new FakeBlockView();

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }

    @Override
    public IBlockView blocks() {
        return blockView;
    }

    public FakeActuator fakeActuator() {
        return actuator;
    }

    public FakeBlockView fakeBlockView() {
        return blockView;
    }
}
