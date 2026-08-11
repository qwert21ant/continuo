package dev.continuo.platform;

/**
 * Metadata about the platform the core is running on.
 *
 * <p>Has no consumer in A1. It is present because it costs nothing, it establishes the
 * direction capability negotiation will need later, and it gives the smoke check a way to
 * prove which adapter is actually loaded.
 */
public interface IPlatformInfo {

    /** Human-readable game version, for example {@code "1.21.11"}. */
    String gameVersion();

    /** The mod loader hosting this adapter. */
    Loader loader();
}
