package dev.continuo.platform;

/**
 * Metadata about the platform the core is running on.
 *
 * <p>Has no consumer in A1. It is present because it costs nothing, it establishes the
 * direction capability negotiation will need later, and it gives the smoke check a way to
 * prove which adapter is actually loaded.
 */
public interface IPlatformInfo {

    /**
     * The game's release version as the loader reports it, for example {@code "1.21.11"} or
     * {@code "1.7.10"}.
     *
     * <p>This is <em>not</em> for feature detection. Branching core behaviour on a parsed
     * version string is what capability negotiation is for, and that does not exist yet.
     *
     * @return the game version, never {@code null}; {@code "unknown"} when it cannot be
     *         determined
     */
    String gameVersion();

    /**
     * The mod loader hosting this adapter.
     *
     * @return the loader; never {@code null}, and constant for the adapter's lifetime
     */
    Loader loader();
}
