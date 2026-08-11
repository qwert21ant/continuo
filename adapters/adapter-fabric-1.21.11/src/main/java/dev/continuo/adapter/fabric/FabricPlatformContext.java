package dev.continuo.adapter.fabric;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.IPlatformInfo;
import net.minecraft.client.Minecraft;

final class FabricPlatformContext implements IPlatformContext {

    private final IActuator actuator;
    private final IPlatformInfo info = new FabricPlatformInfo();

    FabricPlatformContext(Minecraft minecraft) {
        this.actuator = new FabricActuator(minecraft);
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
