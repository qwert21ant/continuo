package dev.continuo.adapter.fabric;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Translates abstract {@link Input} values into Minecraft key mappings.
 *
 * <p>Pure translation: an enum maps to an enum. No decision is made here. If this class
 * ever grows a conditional that changes behaviour rather than resolving a name, that logic
 * belongs in the core.
 */
final class FabricActuator implements IActuator {

    private final Minecraft minecraft;

    FabricActuator(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void setInput(Input input, boolean pressed) {
        KeyMapping mapping = mappingFor(input);
        if (mapping != null) {
            mapping.setDown(pressed);
        }
    }

    private KeyMapping mappingFor(Input input) {
        switch (input) {
            case FORWARD: return minecraft.options.keyUp;
            case BACK:    return minecraft.options.keyDown;
            case LEFT:    return minecraft.options.keyLeft;
            case RIGHT:   return minecraft.options.keyRight;
            case JUMP:    return minecraft.options.keyJump;
            case SNEAK:   return minecraft.options.keyShift;
            case SPRINT:  return minecraft.options.keySprint;
            default:      return null;
        }
    }
}
