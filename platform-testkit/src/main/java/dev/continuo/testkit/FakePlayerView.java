package dev.continuo.testkit;

import dev.continuo.platform.IPlayerView;

/** A settable {@link IPlayerView}. Starts at the origin, facing {@code +Z}, not on the ground. */
public final class FakePlayerView implements IPlayerView {

    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean onGround;

    /** Sets every field at once, so a test cannot leave a stale half-state behind. */
    public void set(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public boolean onGround() {
        return onGround;
    }
}
