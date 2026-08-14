package dev.continuo.adapter.fabric;

import dev.continuo.runtime.RuntimeLog;
import org.slf4j.Logger;

/** Bridges {@link RuntimeLog} to the SLF4J logger 1.21.11 ships. */
final class Slf4jRuntimeLog implements RuntimeLog {

    private final Logger logger;

    Slf4jRuntimeLog(Logger logger) {
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
