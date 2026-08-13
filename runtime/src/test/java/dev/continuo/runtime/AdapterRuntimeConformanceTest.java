package dev.continuo.runtime;

import dev.continuo.platform.IPlatformContext;
import dev.continuo.testkit.AdapterConformanceTest;
import dev.continuo.testkit.AdapterUnderTest;
import dev.continuo.testkit.RecordingCore;

/** Runs the conformance suite against {@link AdapterRuntime}, its first subject. */
class AdapterRuntimeConformanceTest extends AdapterConformanceTest {

    @Override
    protected AdapterUnderTest newSubject(RecordingCore core) {
        return new RuntimeSubject(core);
    }

    private static final class RuntimeSubject implements AdapterUnderTest {

        private final RecordingCore core;
        private final QueuedClicks clicks = new QueuedClicks();
        private final AdapterRuntime runtime;

        private int handled;
        private int failAtClick;
        private RuntimeException clickFailure;

        RuntimeSubject(RecordingCore core) {
            this.core = core;
            this.runtime = new AdapterRuntime(
                core,
                new DiscardingLog(),
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

    /** The suite asserts on core calls, never on log output. */
    private static final class DiscardingLog implements RuntimeLog {
        @Override
        public void info(String message) {
        }

        @Override
        public void error(String message, Throwable thrown) {
        }
    }
}
