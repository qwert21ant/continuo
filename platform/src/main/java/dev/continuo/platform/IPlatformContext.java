package dev.continuo.platform;

/**
 * Everything the adapter hands the core at startup.
 *
 * <p>Bundled into one type so that adding a capability later changes one signature rather
 * than every call site.
 */
public interface IPlatformContext {

    IActuator actuator();

    IPlatformInfo info();
}
