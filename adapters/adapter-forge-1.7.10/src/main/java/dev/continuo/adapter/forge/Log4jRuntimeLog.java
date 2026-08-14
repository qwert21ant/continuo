package dev.continuo.adapter.forge;

import dev.continuo.runtime.RuntimeLog;
import org.apache.logging.log4j.Logger;

/**
 * Bridges {@link RuntimeLog} to log4j2. 1.7.10 predates SLF4J in Minecraft, so this adapter
 * logs through what the game ships. A logging-API difference only — the messages themselves
 * come from the shared runtime and are identical on both versions.
 */
final class Log4jRuntimeLog implements RuntimeLog {

    private final Logger logger;

    Log4jRuntimeLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        logger.error(message, thrown);
    }
}
