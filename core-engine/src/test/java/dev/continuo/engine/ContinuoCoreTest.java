package dev.continuo.engine;

import dev.continuo.core.BlockShape;
import dev.continuo.core.CoreApi;
import dev.continuo.testkit.FakeActuator;
import dev.continuo.testkit.FakePlatformContext;
import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.TickPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuoCoreTest {

    private FakePlatformContext ctx;
    private FakeActuator actuator;
    private RecordingLog log;
    private ContinuoCore core;

    @BeforeEach
    void setUp() {
        ctx = new FakePlatformContext();
        actuator = ctx.fakeActuator();
        log = new RecordingLog();
        core = new ContinuoCore(log);
        core.start(ctx);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            core.onClientTick(TickPhase.PRE);
        }
    }

    /**
     * Global rule 4's second clause, stated at the core's own boundary now that the 40-tick demo
     * (rule 4's only obeying consumer) is gone and {@link PathExecutor} carries the rule instead:
     * with no walk requested, {@link ContinuoCore#onClientTick} must not touch the actuator at
     * all, not even to release an input nobody is holding.
     */
    @Test
    void writesNothingWhileIdle() {
        tick(20);

        assertEquals(0, actuator.callCount());
    }

    @Test
    void ignoresPostPhaseTicksWhileIdle() {
        core.onClientTick(TickPhase.POST);

        assertEquals(0, actuator.callCount());
    }

    /**
     * End-to-end wiring, not {@link PathExecutor}'s own behaviour -- that is
     * {@code PathExecutorSearchTest}'s job. This exists only to pin that {@link
     * ContinuoCore#walkTo} actually reaches the executor built in {@code start}, that {@link
     * ContinuoCore#onClientTick} actually drives it, and that the {@code RuntimeLog} passed into
     * the constructor is the one the executor logs to.
     *
     * <p>A fresh {@code FakeBlockView} answers every {@code stateId} query with -1, so {@code
     * BlockLookup.at()} returns {@code BlockData.UNKNOWN} everywhere -- the same property {@code
     * PathExecutorSearchTest.noPathStopsWithoutRetrying} relies on for its own {@code
     * EmptyWorld}. That makes {@code NO_PATH} deterministic here with no terrain fixture at all.
     */
    @Test
    void walkToReachesTheExecutorAndTheExecutorsLogReachesTheOneStartWasGiven() {
        ctx.fakePlayerView().set(0.5, 64.0, 0.5, 0f, 0f, true);

        core.walkTo(10, 64, 0);
        tick(20);

        assertEquals(0, actuator.callCount(),
            "NO_PATH must leave the executor idle -- nothing was ever driven to release");
        assertTrue(log.messages().toString().contains("no path"),
            "the RuntimeLog given to the constructor must be the one PathExecutor logs to");
    }

    @Test
    void stopBeforeStartFails() {
        final ContinuoCore unstarted = new ContinuoCore(new RecordingLog());

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.stop();
            }
        });
    }

    @Test
    void walkToBeforeStartFails() {
        final ContinuoCore unstarted = new ContinuoCore(new RecordingLog());

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.walkTo(0, 0, 0);
            }
        });
    }

    @Test
    void startWithNullContextFails() {
        final ContinuoCore unstarted = new ContinuoCore(new RecordingLog());

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                unstarted.start(null);
            }
        });
    }

    @Test
    void constructorRejectsANullLog() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new ContinuoCore(null);
            }
        });
    }

    /**
     * Global rule 2 states that {@code stop()} is idempotent. The core already satisfies
     * this; the test exists to pin it, so that a future change to {@code stop()} cannot
     * quietly break an adapter that calls it on both world unload and client shutdown.
     */
    @Test
    void stopIsIdempotentWhenNothingIsRunning() {
        core.stop();
        core.stop();

        assertEquals(0, actuator.callCount(),
            "repeated stop() with nothing running must not touch the actuator");
    }

    /**
     * Pins current behaviour: calling {@code start()} a second time silently replaces the
     * context rather than throwing.
     *
     * <p>Global rule 2 says an adapter calls {@code start()} exactly once per lifetime. That
     * rule binds adapters, not the core, and this leniency is deliberately not promoted to a
     * guarantee — an adapter MUST NOT rely on it. If the core's behaviour here ever changes,
     * this test should change with it rather than be deleted silently.
     */
    @Test
    void startTwiceReplacesContext() {
        FakePlatformContext secondCtx = new FakePlatformContext();
        secondCtx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        core.start(secondCtx);

        assertEquals(BlockShape.FULL, core.blocks().at(0, 64, 0).shape(),
            "blocks() must read through the second context, not the first");
        assertEquals(0, ctx.fakeBlockView().describeCallCount(),
            "the original context's block view must not be used after a second start()");
    }

    /**
     * Pins the A2b seam: an adapter runtime holds the core through {@link CoreApi} so a
     * recording fake can be substituted. If this stops compiling, the seam is gone and
     * {@code platform-testkit} can no longer observe anything.
     */
    @Test
    void continuoCoreIsUsableThroughTheCoreApiSeam() {
        CoreApi seam = new ContinuoCore(new RecordingLog());
        seam.start(new FakePlatformContext());
        seam.onClientTick(TickPhase.PRE);
        seam.stop();
    }

    @Test
    void exposesAClassifyingLookupOverThePlatformsBlockView() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        assertEquals(BlockShape.FULL, core.blocks().at(0, 64, 0).shape());
    }

    @Test
    void theLookupIsTheSameInstanceAcrossCalls() {
        assertSame(core.blocks(), core.blocks());
    }

    @Test
    void stopClearsTheBlockMemoSoStateIdsCannotOutliveTheirLevel() {
        ctx.fakeBlockView().put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));

        core.blocks().at(0, 64, 0);
        assertEquals(1, ctx.fakeBlockView().describeCallCount());

        core.blocks().at(0, 64, 0);
        assertEquals(1, ctx.fakeBlockView().describeCallCount(), "the memo must still be live before stop()");

        core.stop();
        core.blocks().at(0, 64, 0);

        assertEquals(2, ctx.fakeBlockView().describeCallCount(),
            "global rule 2 requires stop() on every level transition, and state ids are session-scoped");
    }

    @Test
    void blocksBeforeStartIsAnError() {
        ContinuoCore fresh = new ContinuoCore(new RecordingLog());
        assertThrows(IllegalStateException.class, fresh::blocks);
    }
}
