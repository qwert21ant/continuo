package dev.continuo.adapter.fabric;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Reports the game version via the loader rather than via a Minecraft class, because the
 * loader API is stable across versions and {@code SharedConstants} is not.
 */
final class FabricPlatformInfo implements IPlatformInfo {

    @Override
    public String gameVersion() {
        return FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public Loader loader() {
        return Loader.FABRIC;
    }
}
