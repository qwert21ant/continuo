package dev.continuo.adapter.forge;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IBlockView;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import net.minecraft.client.Minecraft;

final class ForgePlatformContext implements IPlatformContext {

    private final IActuator actuator;
    private final IPlatformInfo info = new ForgePlatformInfo();
    private final IBlockView blocks;

    ForgePlatformContext(Minecraft minecraft) {
        this.actuator = new ForgeActuator(minecraft);
        this.blocks = new ForgeBlockView(minecraft);
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
}
