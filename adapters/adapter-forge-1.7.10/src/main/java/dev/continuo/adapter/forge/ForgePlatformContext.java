package dev.continuo.adapter.forge;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import net.minecraft.client.Minecraft;

final class ForgePlatformContext implements IPlatformContext {

    private final IActuator actuator;
    private final IPlatformInfo info = new ForgePlatformInfo();

    ForgePlatformContext(Minecraft minecraft) {
        this.actuator = new ForgeActuator(minecraft);
    }

    @Override
    public IActuator actuator() {
        return actuator;
    }

    @Override
    public IPlatformInfo info() {
        return info;
    }
}
