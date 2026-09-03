package dev.continuo.core;

/**
 * Yaw arithmetic in the convention both target versions share.
 *
 * <p><b>The convention is a fact about both games, not an invention of this project.</b> Both derive
 * forward motion as {@code (-sin yaw, cos yaw)} — 1.7.10's {@code moveFlying} and 1.21.11's
 * {@code getInputVector} reduce to the identical delta — so on both, yaw {@code 0} faces {@code +Z}
 * and {@code 90} faces {@code -X}. See {@code IPlayerView.yaw()} for the full table.
 *
 * <p>This lives in {@code core} rather than beside either of its callers because
 * {@code dev.continuo.runtime}'s probe and {@code dev.continuo.engine}'s executor are sibling
 * modules, and {@code core} is the only one both can see. Two copies of this arithmetic is exactly
 * the divergence the SPI exists to prevent.
 */
public final class Yaw {

    private Yaw() {
    }

    /**
     * The yaw that points from a position toward a block's centre.
     *
     * <p>{@code yaw = -atan2(dx, dz)}, which is the inverse of the forward-motion delta above.
     *
     * @param fromX where the looker is
     * @param fromZ where the looker is
     * @param toX   the target block's X
     * @param toZ   the target block's Z
     * @return the yaw in degrees, unnormalised
     */
    public static float toward(double fromX, double fromZ, int toX, int toZ) {
        double dx = (toX + 0.5) - fromX;
        double dz = (toZ + 0.5) - fromZ;
        return (float) Math.toDegrees(-Math.atan2(dx, dz));
    }

    /**
     * Degrees folded into {@code [-180, 180)}, so 359 and -1 are one degree apart.
     *
     * <p>Applied to a difference between two angles, this is what makes a turn take the shortest
     * arc: {@code wrap(179 - (-179))} is {@code -2} rather than {@code 358}.
     *
     * @param degrees any finite angle
     * @return the same direction, in {@code [-180, 180)}
     */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
