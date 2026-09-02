package dev.continuo.platform;

/**
 * Reads the local player's position and facing from the live game.
 *
 * <p>Note the direction: the adapter implements this and the core calls it — the same direction as
 * {@link IBlockView}, and the opposite of {@link IGameEvents}.
 *
 * <p><b>Call window.</b> Every method here MUST only be called while
 * {@link IGameEvents#onClientTick}'s delivery window is open — a world loaded and a local player
 * present. Outside that window the behaviour is unspecified. This deliberately reuses that existing
 * condition rather than stating a new one, so there is nothing extra for an adapter to evaluate or
 * get wrong.
 *
 * <p><b>Values describe the current tick and MUST NOT be cached across ticks.</b> A core that wants
 * the previous tick's position stores it itself.
 *
 * <p>Subject to all four global rules in this package's documentation, in particular rule 1: these
 * are main-thread calls and no implementation may block.
 *
 * <p><b>The field budget is deliberately six.</b> Velocity, eye height, bounding-box dimensions,
 * fluid state, and sneak/sprint state are all natively available on both target versions and are
 * all deliberately absent, because nothing consumes them yet. This package's documentation says
 * every type added here is a future version-compatibility problem; the same applies to every
 * method. Widening this interface when a real consumer appears is a two-file change.
 */
public interface IPlayerView {

    /**
     * The player's X, in world coordinates.
     *
     * @return the X coordinate of the centre of the player's collision box
     */
    double x();

    /**
     * The player's Y, in world coordinates — <b>the feet</b>.
     *
     * <p><b>Defined as the bottom of the player's collision box</b>, not as "the player's Y", which
     * is ambiguous and resolves differently on the two target versions.
     *
     * <p><b>Caveat — 1.7.10's obvious field is the wrong one.</b> On 1.7.10 {@code Entity.posY} is
     * the <i>stance</i>, 1.62 blocks above the feet: {@code setPosition} computes the collision box
     * as {@code minY = posY - yOffset + ySize} with {@code EntityPlayer.yOffset = 1.62F}, and the
     * client's own movement packet sends {@code boundingBox.minY} as its feet and {@code posY} as
     * its stance. A conformant 1.7.10 implementation therefore returns
     * {@code boundingBox.minY}, <b>not {@code posY}</b>. On 1.21.11 {@code getY()} is already the
     * feet and needs no adjustment.
     *
     * <p>Getting this wrong is a silent 1.62-block error on exactly one version, with nothing in the
     * build able to catch it.
     *
     * @return the Y coordinate of the bottom of the player's collision box
     */
    double y();

    /**
     * The player's Z, in world coordinates.
     *
     * @return the Z coordinate of the centre of the player's collision box
     */
    double z();

    /**
     * Which way the player is facing, in degrees.
     *
     * <p><b>The convention is a fact about both target versions, not one this SPI invents.</b> Both
     * derive movement direction from yaw by identical arithmetic — {@code Δ = (−sin yaw, cos yaw)}
     * for forward motion — so on both:
     *
     * <ul>
     *   <li>{@code 0} faces <b>+Z</b>
     *   <li>{@code 90} faces <b>−X</b>
     *   <li>{@code 180} faces <b>−Z</b>
     *   <li>{@code 270} faces <b>+X</b>
     * </ul>
     *
     * <p><b>Not normalised.</b> This returns whatever the platform currently holds, which may be any
     * finite value, including one outside {@code [-180, 180)}. A core needing a normalised angle
     * normalises it; requiring the adapter to do so would be arithmetic in an adapter for no gain.
     *
     * @return the yaw in degrees, unnormalised
     */
    float yaw();

    /**
     * How far up or down the player is looking, in degrees.
     *
     * <p>{@code -90} is straight up, {@code 0} is the horizon, {@code +90} is straight down, on both
     * target versions.
     *
     * @return the pitch in degrees
     */
    float pitch();

    /**
     * Whether the platform considers the player to be standing on something.
     *
     * <p>Reported, not computed: both target versions maintain their own ground flag and this
     * returns it. The two have not been audited against each other to the depth {@link #y()} has —
     * if they are found to disagree about, say, standing on a fence, that is a per-version note
     * rather than a reason for either adapter to start deciding.
     *
     * @return the platform's own ground flag
     */
    boolean onGround();
}
