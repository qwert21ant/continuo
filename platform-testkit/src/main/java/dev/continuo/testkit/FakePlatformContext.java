package dev.continuo.testkit;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IBlockView;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.IPlayerView;
import dev.continuo.platform.Loader;

public final class FakePlatformContext implements IPlatformContext {

    private final FakeActuator actuator = new FakeActuator();
    private final IPlatformInfo info = new FakePlatformInfo("0.0-test", Loader.FABRIC);
    private final FakeBlockView blockView = new FakeBlockView();
    private final FakePlayerView playerView = new FakePlayerView();

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

    @Override
    public IPlayerView player() {
        return playerView;
    }

    public FakeActuator fakeActuator() {
        return actuator;
    }

    public FakeBlockView fakeBlockView() {
        return blockView;
    }

    public FakePlayerView fakePlayerView() {
        return playerView;
    }
}
