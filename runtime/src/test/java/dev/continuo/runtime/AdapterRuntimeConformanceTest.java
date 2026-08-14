package dev.continuo.runtime;

import dev.continuo.platform.IPlatformContext;
import dev.continuo.testkit.AdapterConformanceTest;
import dev.continuo.testkit.AdapterUnderTest;
import dev.continuo.testkit.RecordingCore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the conformance suite against {@link AdapterRuntime}, its first subject. */
class AdapterRuntimeConformanceTest extends AdapterConformanceTest {

    /** The log of the subject created for the current test. */
    private RecordingLog log;

    @Override
    protected AdapterUnderTest newSubject(RecordingCore core) {
        log = new RecordingLog();
        return new RuntimeSubject(core, log);
    }

    /**
     * The one string this sub-project physically moved across a module boundary, out of both
     * adapters and into {@link AdapterRuntime}. Both smoke checklists' step 10 greps for it
     * verbatim, and neither has been re-run since the conversion, so this case is what pins the
     * text itself. It is deliberately not in the shared suite: log output is a concern of this
     * runtime, not of the generic {@code AdapterUnderTest} interface.
     */
    @Test
    void aTransitionToANullLevelLogsTheStringTheChecklistsGrepFor() {
        startAndClear();
        enterWorld(LEVEL_A);
        log.clear();

        subject.tickStart(null, null);

        assertEquals(
            Collections.singletonList("Continuo stopping: client level changed"),
            log.messages(),
            "the smoke checklists assert this line byte-for-byte; it must not drift");
    }

    private static final class RuntimeSubject implements AdapterUnderTest {

        private final RecordingCore core;
        private final QueuedClicks clicks = new QueuedClicks();
        private final AdapterRuntime runtime;

        private int handled;
        private int failAtClick;
        private RuntimeException clickFailure;

        RuntimeSubject(RecordingCore core, RuntimeLog log) {
            this.core = core;
            this.runtime = new AdapterRuntime(
                core,
                log,
                clicks,
                new Runnable() {
                    @Override
                    public void run() {
                        handled++;
                        if (clickFailure != null && handled == failAtClick) {
                            throw clickFailure;
                        }
                    }
                });
        }

        @Override
        public void start(IPlatformContext context) {
            runtime.start(context);
        }

        @Override
        public void tickStart(Object level, Object player) {
            runtime.tickStart(level, player);
        }

        @Override
        public void tickEnd(Object level, Object player) {
            runtime.tickEnd(level, player);
        }

        @Override
        public void clientStopping() {
            runtime.clientStopping();
        }

        @Override
        public void queueClick(int count) {
            clicks.queue(count);
        }

        @Override
        public int clicksHandled() {
            return handled;
        }

        @Override
        public void failClickNumber(int number, RuntimeException failure) {
            this.failAtClick = handled + number;
            this.clickFailure = failure;
        }

        @Override
        public RecordingCore core() {
            return core;
        }
    }

    /**
     * Keeps the {@code info} messages so a case can assert on the exact text. Error messages
     * are discarded: the inherited suite asserts on core calls rather than on log output, and
     * several of its cases fault the core deliberately.
     */
    private static final class RecordingLog implements RuntimeLog {

        private final List<String> messages = new ArrayList<String>();

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void error(String message, Throwable thrown) {
        }

        List<String> messages() {
            return messages;
        }

        void clear() {
            messages.clear();
        }
    }
}
