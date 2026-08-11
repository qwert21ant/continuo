package dev.continuo.platform;

/**
 * Everything the adapter hands the core at startup.
 *
 * <p>Bundled into one type so that adding a capability later changes one signature rather
 * than every call site.
 *
 * <p>Valid for the adapter's entire lifetime. Both accessors MUST NOT return {@code null},
 * and MUST return the same instance on every call — the core may therefore cache what they
 * return.
 */
public interface IPlatformContext {

    /**
     * The actuator for this platform.
     *
     * @return the actuator; never {@code null}, and the same instance on every call
     */
    IActuator actuator();

    /**
     * Metadata about this platform.
     *
     * @return the platform info; never {@code null}, and the same instance on every call
     */
    IPlatformInfo info();
}
