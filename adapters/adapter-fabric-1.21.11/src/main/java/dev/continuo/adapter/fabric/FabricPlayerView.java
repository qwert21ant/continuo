package dev.continuo.adapter.fabric;

import dev.continuo.platform.IPlayerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Translates the local player's state into the SPI's vocabulary.
 *
 * <p>Pure translation: a method maps to an accessor. No decision is made here. If this class ever
 * grows a conditional that changes behaviour rather than resolving a name or guarding a null, that
 * logic belongs in the core.
 *
 * <p>{@code getY()} is already the bottom of the collision box on this version, so
 * {@link IPlayerView#y()}'s feet definition needs no adjustment here. The 1.7.10 adapter is where
 * that costs something.
 */
final class FabricPlayerView implements IPlayerView {

    private final Minecraft minecraft;

    FabricPlayerView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public double x() {
        return player().getX();
    }

    @Override
    public double y() {
        return player().getY();
    }

    @Override
    public double z() {
        return player().getZ();
    }

    @Override
    public float yaw() {
        return player().getYRot();
    }

    @Override
    public float pitch() {
        return player().getXRot();
    }

    @Override
    public boolean onGround() {
        return player().onGround();
    }

    /**
     * The one guard in this class. {@link IPlayerView}'s call window excludes the case where this
     * throws, so reaching it is a caller's contract violation rather than a state to handle.
     */
    private LocalPlayer player() {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException(
                "IPlayerView was read outside onClientTick's delivery window: no local player");
        }
        return player;
    }
}
