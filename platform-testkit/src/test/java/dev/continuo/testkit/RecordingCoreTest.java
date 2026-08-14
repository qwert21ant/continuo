package dev.continuo.testkit;

import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingCoreTest {

    @Test
    void recordsEveryCallInOrder() {
        RecordingCore core = new RecordingCore();
        FakePlatformContext ctx = new FakePlatformContext();

        core.start(ctx);
        core.onClientTick(TickPhase.PRE);
        core.onClientTick(TickPhase.POST);
        core.stop();

        assertEquals(
            Arrays.asList(
                RecordingCore.Event.START,
                RecordingCore.Event.TICK_PRE,
                RecordingCore.Event.TICK_POST,
                RecordingCore.Event.STOP),
            core.events());
        assertSame(ctx, core.context());
    }

    @Test
    void recordsTheCallBeforeThrowing() {
        RecordingCore core = new RecordingCore();
        core.start(new FakePlatformContext());
        final RuntimeException boom = new RuntimeException("boom");
        core.failOnPre(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            core.onClientTick(TickPhase.PRE));

        assertSame(boom, thrown);
        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE),
            "the call must be recorded before the throw, or fault assertions cannot see it");
    }

    @Test
    void stopFailingClearsEveryProgrammedFailure() {
        RecordingCore core = new RecordingCore();
        core.failOnStart(new RuntimeException("a"));
        core.failOnStop(new RuntimeException("b"));
        core.failOnPre(new RuntimeException("c"));
        core.failOnPost(new RuntimeException("d"));

        core.stopFailing();

        core.start(new FakePlatformContext());
        core.onClientTick(TickPhase.PRE);
        core.onClientTick(TickPhase.POST);
        core.stop();

        assertEquals(4, core.events().size());
    }

    @Test
    void clearResetsTheEventListButNotTheContext() {
        RecordingCore core = new RecordingCore();
        FakePlatformContext ctx = new FakePlatformContext();
        core.start(ctx);

        core.clear();

        assertEquals(0, core.events().size());
        assertSame(ctx, core.context());
    }
}
