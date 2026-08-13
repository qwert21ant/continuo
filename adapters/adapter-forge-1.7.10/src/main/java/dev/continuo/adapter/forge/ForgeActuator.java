package dev.continuo.adapter.forge;

import dev.continuo.platform.IActuator;
import dev.continuo.platform.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Translates abstract {@link Input} values into Minecraft key bindings.
 *
 * <p>Pure translation: an enum maps to a field. No decision is made here. If this class ever
 * grows a conditional that changes behaviour rather than resolving a name, that logic belongs
 * in the core.
 *
 * <p>Writes {@code pressed} on the binding instance, which an access transformer makes
 * accessible. This is the per-instance equivalent of Fabric's {@code KeyMapping#setDown}; the
 * instance is addressed, rather than a keycode, so that {@code setInput} affects one chosen
 * binding regardless of what key (if any) the user has bound it to.
 *
 * <p>Reading the decompiled source, {@code MovementInputFromOptions.updatePlayerMoveState()}
 * drives movement via each binding's {@code getIsKeyPressed()} rather than by polling the
 * keyboard, which would suggest an unbound key is not a failure mode here. That conclusion is
 * source-level reasoning, not an observed result, and is pending in-game confirmation (Task 4
 * Step 7).
 */
final class ForgeActuator implements IActuator {

    private final Minecraft minecraft;

    ForgeActuator(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void setInput(Input input, boolean pressed) {
        bindingFor(input).pressed = pressed;
    }

    private KeyBinding bindingFor(Input input) {
        switch (input) {
            case FORWARD: return minecraft.gameSettings.keyBindForward;
            case BACK:    return minecraft.gameSettings.keyBindBack;
            case LEFT:    return minecraft.gameSettings.keyBindLeft;
            case RIGHT:   return minecraft.gameSettings.keyBindRight;
            case JUMP:    return minecraft.gameSettings.keyBindJump;
            case SNEAK:   return minecraft.gameSettings.keyBindSneak;
            case SPRINT:  return minecraft.gameSettings.keyBindSprint;
            default:      throw new IllegalArgumentException("Unmapped input: " + input);
        }
    }
}
