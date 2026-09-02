package dev.continuo.adapter.fabric;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
        mappingFor(input).setDown(pressed);
    }

    @Override
    public void setLook(float yaw, float pitch) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
        // Written alongside the rotation itself, per IActuator#setLook's adapter obligation: the
        // game snapshots the previous rotation once per tick, so a write landing after that
        // snapshot would leave the camera interpolating from a stale angle.
        player.yRotO = yaw;
        player.xRotO = pitch;
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
            default:      throw new IllegalArgumentException("Unmapped input: " + input);
        }
    }
}
