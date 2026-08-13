package dev.continuo.adapter.forge;

import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.Loader;

/**
 * Reports the game version via FML's Minecraft mod container rather than via a Minecraft
 * class, mirroring the Fabric adapter's reasoning: the loader API is stable across versions
 * and the game's own version constants are not.
 */
final class ForgePlatformInfo implements IPlatformInfo {

    @Override
    public String gameVersion() {
        // cpw.mods.fml.common.Loader collides by simple name with dev.continuo.platform.Loader,
        // which this class also uses, so it is fully qualified rather than imported.
        String version =
            cpw.mods.fml.common.Loader.instance().getMinecraftModContainer().getVersion();
        return version == null ? "unknown" : version;
    }

    @Override
    public Loader loader() {
        return Loader.FORGE;
    }
}
