package dev.continuo.adapter.forge;

import dev.continuo.platform.IPlayerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;

/**
 * Translates the local player's state into the SPI's vocabulary.
 *
 * <p>Pure translation: a method maps to a field. No decision is made here. If this class ever grows
 * a conditional that changes behaviour rather than resolving a name or guarding a null, that logic
 * belongs in the core.
 */
final class ForgePlayerView implements IPlayerView {

    private final Minecraft minecraft;

    ForgePlayerView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public double x() {
        return player().posX;
    }

    /**
     * <b>{@code boundingBox.minY}, deliberately, and never {@code posY}.</b>
     *
     * <p>{@link IPlayerView#y()} is defined as the bottom of the collision box. On this version
     * {@code posY} is the <i>stance</i> — 1.62 blocks higher — because {@code Entity.setPosition}
     * computes {@code minY = posY - yOffset + ySize} and {@code EntityPlayer.yOffset} is
     * {@code 1.62F}. The game's own network code settles which is which: {@code EntityClientPlayerMP}
     * sends {@code (posX, boundingBox.minY, posY, posZ)} and the protocol reads those as
     * X / feetY / stance / Z.
     *
     * <p>Using {@code posY} here would put every search start 1.62 blocks above the ground, inside
     * the player's own head, on this version only — and no test in this repository can catch it.
     */
    @Override
    public double y() {
        return player().boundingBox.minY;
    }

    @Override
    public double z() {
        return player().posZ;
    }

    @Override
    public float yaw() {
        return player().rotationYaw;
    }

    @Override
    public float pitch() {
        return player().rotationPitch;
    }

    @Override
    public boolean onGround() {
        return player().onGround;
    }

    /**
     * The one guard in this class. {@link IPlayerView}'s call window excludes the case where this
     * throws, so reaching it is a caller's contract violation rather than a state to handle.
     */
    private EntityClientPlayerMP player() {
        EntityClientPlayerMP player = minecraft.thePlayer;
        if (player == null) {
            throw new IllegalStateException(
                "IPlayerView was read outside onClientTick's delivery window: no local player");
        }
        return player;
    }
}
