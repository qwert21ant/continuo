package dev.continuo.engine;

import dev.continuo.core.RuntimeLog;

import java.util.ArrayList;
import java.util.List;

/** Captures log lines so a test can assert the executor explained why it stopped. */
final class RecordingLog implements RuntimeLog {

    private final List<String> messages = new ArrayList<String>();

    @Override
    public void info(String message) {
        messages.add(message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        messages.add(message);
    }

    List<String> messages() {
        return messages;
    }
}
