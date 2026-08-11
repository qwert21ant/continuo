package dev.continuo.platform;

/**
 * The mod loaders Continuo can run under.
 *
 * <p>Adding a constant breaks exhaustive switches in adapters and is therefore a breaking
 * change to this package.
 */
public enum Loader {
    FABRIC,
    FORGE,
    NEOFORGE
}
