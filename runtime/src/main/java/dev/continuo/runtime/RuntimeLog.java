package dev.continuo.runtime;

/**
 * The runtime's log, abstracted because 1.7.10 predates SLF4J in Minecraft and logs through
 * log4j2.
 *
 * <p>Global rules 2 and 3 log through this, so both versions emit byte-identical text. The
 * smoke checklists assert on those strings, so this strengthens them.
 */
public interface RuntimeLog {

    void info(String message);

    void error(String message, Throwable thrown);
}
