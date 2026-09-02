package dev.continuo.adapter.forge;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IBlockView;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import dev.continuo.platform.IPlayerView;
import net.minecraft.client.Minecraft;

final class ForgePlatformContext implements IPlatformContext {

    private final IActuator actuator;
    private final IPlatformInfo info = new ForgePlatformInfo();
    private final IBlockView blocks;
    private final IPlayerView playerView;

    ForgePlatformContext(Minecraft minecraft) {
        this.actuator = new ForgeActuator(minecraft);
        this.blocks = new ForgeBlockView(minecraft);
        this.playerView = new ForgePlayerView(minecraft);
    }

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
        return blocks;
    }

    @Override
    public IPlayerView player() {
        return playerView;
    }
}
