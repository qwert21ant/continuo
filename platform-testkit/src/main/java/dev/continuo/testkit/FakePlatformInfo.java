package dev.continuo.testkit;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

public final class FakePlatformInfo implements IPlatformInfo {

    private final String gameVersion;
    private final Loader loader;

    public FakePlatformInfo(String gameVersion, Loader loader) {
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public Loader loader() {
        return loader;
    }
}
