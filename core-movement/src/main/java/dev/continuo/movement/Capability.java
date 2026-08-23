package dev.continuo.movement;

/**
 * Something a movement needs before the search may use it.
 *
 * <p><b>One value, because one has a consumer.</b> The source architecture names three sources
 * for the active set — the platform's world capabilities, the player's current capabilities, and
 * the caller's settings. <b>C2 supplies only the third.</b> Neither of the other two exists:
 * there is no {@code IPlayerState} in the codebase, and {@code IPlatformInfo} carries only a
 * version string and a loader, with its own javadoc stating that the version is not for feature
 * detection.
 *
 * <p>So this enum must not be read as evidence that platform negotiation exists. It does not. The
 * first movement that genuinely needs a version-dependent capability — elytra is the architecture
 * doc's own example — is when the SPI addition earns its cost, and it should be batched with the
 * slipperiness and fluid-height work C1 deferred, so that the two untestable adapter modules are
 * edited once rather than twice.
 */
public enum Capability {

    /**
     * The caller permits jumping gaps.
     *
     * <p>A policy bit, not a platform fact: every Minecraft version can jump a one-block gap.
     * It exists so that enabling parkour is a decision the caller makes rather than a silent
     * change to every search.
     */
    PARKOUR
}
