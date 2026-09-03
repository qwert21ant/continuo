package dev.continuo.core;

/**
 * Where the core and the adapter runtime log, abstracted because 1.7.10 predates SLF4J in
 * Minecraft and logs through log4j2.
 *
 * <p>Global rules 2 and 3 log through this, so both versions emit byte-identical text. The
 * smoke checklists assert on those strings, so this strengthens them.
 *
 * <p>It lives in {@code core} rather than in {@code runtime} because {@code dev.continuo.engine} is
 * {@code runtime}'s sibling and needs it too. The alternative was a second logging interface.
 */
public interface RuntimeLog {

    void info(String message);

    void error(String message, Throwable thrown);
}
