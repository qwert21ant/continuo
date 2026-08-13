package dev.continuo.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance suite. Extend it and return a subject from {@link #newSubject}.
 *
 * <p>Organised by the global rule numbering in {@code dev.continuo.platform}'s
 * {@code package-info}. <b>That numbering is load-bearing and must not change</b>; the
 * javadoc states that conformance tests are expected to mirror it.
 *
 * <h2>Rules with no cases, and why</h2>
 *
 * <p><b>Rule 1 (Threading)</b> — "no implementation may block" is unfalsifiable as a test.
 * The package javadoc says so; this suite records the gap rather than leaving it silent.
 *
 * <p><b>Rule 4 (Input persistence)</b> — a hazard statement, not an obligation. That
 * {@code setInput}'s effect may not persist is precisely what the SPI declines to require
 * either side to handle before M5.
 *
 * <p><b>{@code onClientTick}'s "MUST NOT be delivered re-entrantly"</b> — a property of the
 * adapter's event source, not of anything this suite can drive. A runtime cannot stop its own
 * caller from re-entering it, and adding a guard would exceed what A2b's extraction permits.
 *
 * <h2>What a green run does not mean</h2>
 *
 * <p>See this package's {@code package-info}. In short: it says nothing about whether an
 * adapter passes the correct level or player object, whether {@code setInput} moves the
 * player, whether {@code PRE} genuinely precedes the game's input read, or whether the tick
 * source is a tick rather than a frame. Those remain the smoke checklists' job.
 */
public abstract class AdapterConformanceTest {

    /** A subject wired to {@code core}. Called once per test. */
    protected abstract AdapterUnderTest newSubject(RecordingCore core);

    protected RecordingCore core;
    protected AdapterUnderTest subject;

    /** Two distinct, non-null stand-ins for client level instances. */
    protected static final Object LEVEL_A = new Object();
    protected static final Object LEVEL_B = new Object();
    /** A non-null stand-in for a local player. */
    protected static final Object PLAYER = new Object();

    @BeforeEach
    void createSubject() {
        core = new RecordingCore();
        subject = newSubject(core);
    }

    /** Starts the subject and clears the recorded {@code START}, for tests that don't assert on it. */
    protected void startAndClear() {
        subject.start(new FakePlatformContext());
        core.clear();
    }

    /** Brings the subject into a loaded world with the fault (if any) cleared, then clears events. */
    protected void enterWorld(Object level) {
        subject.tickStart(level, PLAYER);
        subject.tickEnd(level, PLAYER);
        core.clear();
    }

    // ---- Global rule 2 — Lifecycle ----

    @Test
    void startCallsCoreStartExactlyOnce() {
        FakePlatformContext ctx = new FakePlatformContext();

        subject.start(ctx);

        assertEquals(1, core.count(RecordingCore.Event.START));
        assertSame(ctx, core.context());
    }

    @Test
    void startIsTheFirstCoreCall() {
        subject.start(new FakePlatformContext());
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(RecordingCore.Event.START, core.events().get(0));
    }

    @Test
    void noCoreCallsHappenBeforeStart() {
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.clientStopping();

        assertEquals(0, core.events().size(), "a tick before start() must reach no core method");
    }

    @Test
    void secondStartIsRejected() {
        subject.start(new FakePlatformContext());

        assertThrows(IllegalStateException.class, () -> subject.start(new FakePlatformContext()));
        assertEquals(1, core.count(RecordingCore.Event.START));
    }

    @Test
    void stopsOnTransitionFromNullToNonNullLevel() {
        startAndClear();

        subject.tickStart(LEVEL_A, PLAYER);

        assertTrue(core.count(RecordingCore.Event.STOP) >= 1,
            "a world load must call stop(), which clears state left by a stop() that threw");
    }

    @Test
    void stopsOnTransitionToNullLevel() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(null, null);

        assertEquals(1, core.count(RecordingCore.Event.STOP));
    }

    @Test
    void stopsOnTransitionBetweenTwoNonNullLevels() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_B, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a dimension change replaces the level without ending the session and IS a world unload");
    }

    @Test
    void doesNotStopWhenTheLevelIsUnchanged() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.STOP));
    }

    @Test
    void clientStoppingCallsStop() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.clientStopping();

        assertEquals(1, core.count(RecordingCore.Event.STOP));
    }

    // ---- Global rule 3 — Faults ----

    @Test
    void aThrowingPreStopsTheCore() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a faulting core must be stopped so it cannot leave a movement key held");
    }

    @Test
    void nothingPropagatesOutOfTickStart() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));

        // A bot bug must never crash the user's game.
        assertDoesNotThrow(() -> subject.tickStart(LEVEL_A, PLAYER));
    }

    @Test
    void nothingPropagatesOutOfTickEnd() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.tickStart(LEVEL_A, PLAYER);
        core.failOnPost(new RuntimeException("core bug"));

        assertDoesNotThrow(() -> subject.tickEnd(LEVEL_A, PLAYER));
    }

    @Test
    void noTicksAreDeliveredWhileFaulted() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();
        core.clear();

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void aThrowingStopInsideTheFaultHandlerStillLeavesTheRuntimeFaulted() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        core.failOnStop(new RuntimeException("stop is broken too"));

        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();
        core.clear();

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the fault handler must not be able to fault its way out of the faulted state");
    }

    @Test
    void theFaultClearsOnTheNextWorldLoadAndTicksResume() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();

        subject.tickStart(null, null);
        core.clear();
        subject.tickStart(LEVEL_B, PLAYER);
        subject.tickEnd(LEVEL_B, PLAYER);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE),
            "the next world load clears the fault and reopens the tick window");
        assertEquals(1, core.count(RecordingCore.Event.TICK_POST));
    }

    // ---- IGameEvents.onClientTick — tick window and phase pairing ----

    @Test
    void preIsDeliveredBeforePostWithinATick() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(
            Arrays.asList(RecordingCore.Event.TICK_PRE, RecordingCore.Event.TICK_POST),
            core.events());
    }

    @Test
    void noTicksAreDeliveredWithNoLevel() {
        startAndClear();

        subject.tickStart(null, null);
        subject.tickEnd(null, null);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void noTicksAreDeliveredWithNoLocalPlayer() {
        startAndClear();

        subject.tickStart(LEVEL_A, null);
        subject.tickEnd(LEVEL_A, null);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the tick window requires a world AND a local player");
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void postIsNeverDeliveredWithoutASameTickPre() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_POST),
            "the exception never runs this way: POST without a same-tick PRE is never conformant");
    }

    @Test
    void theLatchDoesNotWedgeAcrossTicks() {
        startAndClear();
        enterWorld(LEVEL_A);

        // A PRE whose POST is suppressed by the window closing mid-tick.
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(null, null);
        core.clear();

        // The next tick's END must not fire a POST left over from the previous tick's PRE.
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_POST));
    }

    @Test
    void unpairedPreIsPermittedOnlyWhenASuppressingConditionHeld() {
        startAndClear();
        enterWorld(LEVEL_A);

        // A dimension change or disconnect processed inside the game's own tick closes the
        // window between the two halves of one tick.
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(null, null);

        assertEquals(1, core.count(RecordingCore.Event.TICK_PRE));
        assertEquals(0, core.count(RecordingCore.Event.TICK_POST),
            "the contract permits an unpaired PRE exactly when the window closed or the "
                + "runtime faulted before POST was due; here the window closed");
    }

    // ---- The click drain ----

    @Test
    void aQueuedClickIsDispatchedInsideTheTickWindow() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.queueClick(2);

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(2, subject.clicksHandled());
    }

    @Test
    void aClickQueuedOutOfWorldIsDiscarded() {
        startAndClear();
        subject.queueClick(3);

        subject.tickStart(null, null);
        subject.tickStart(LEVEL_A, PLAYER);
        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, subject.clicksHandled(),
            "a title-screen keypress must not fire the instant the next world loads");
    }

    @Test
    void aClickQueuedWhileFaultedIsDiscarded() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);
        core.stopFailing();

        subject.queueClick(3);
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(0, subject.clicksHandled(),
            "a click must not survive to be replayed once the fault clears");
    }

    @Test
    void clicksStillQueuedWhenTheHandlerThrowsAreDiscarded() {
        startAndClear();
        enterWorld(LEVEL_A);
        subject.queueClick(4);
        subject.failClickNumber(2, new RuntimeException("handler bug"));

        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(2, subject.clicksHandled(),
            "the loop aborts on the second click and the remaining two are drained, not handled");
        assertEquals(1, core.count(RecordingCore.Event.STOP),
            "a throw from the click handler faults exactly as a throw from the core does");
        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "the fault aborts the tick before PRE is reached");
    }

    @Test
    void clicksAreNotDrainedBetweenPreAndPost() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        subject.queueClick(1);
        subject.tickEnd(LEVEL_A, PLAYER);
        subject.tickStart(LEVEL_A, PLAYER);

        assertEquals(1, subject.clicksHandled(),
            "a keypress made between the two halves of a tick must survive to the next one");
    }

    // ---- Coverage gaps found in review ----

    @Test
    void theFaultClearOrderingSurvivesAStopThatThrowsOnTheWorldLoad() {
        startAndClear();
        enterWorld(LEVEL_A);
        core.failOnPre(new RuntimeException("core bug"));
        subject.tickStart(LEVEL_A, PLAYER);

        // The world-load stop() itself throws. updateLevel clears the fault BEFORE calling
        // stop(), so guarded() must re-set it and it must stay set. Were the clear to happen
        // after stop(), the fault handler would swallow its own fault and ticks would resume.
        core.stopFailing();
        core.failOnStop(new RuntimeException("stop is broken on the world load too"));
        subject.tickStart(LEVEL_B, PLAYER);
        core.stopFailing();
        core.clear();

        subject.tickStart(LEVEL_B, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_PRE),
            "a world-load stop() that throws must leave the runtime faulted, not recovered");
    }

    @Test
    void postIsSuppressedWhenAFaultArrivesBetweenTheTwoHalvesOfATick() {
        startAndClear();
        enterWorld(LEVEL_A);

        subject.tickStart(LEVEL_A, PLAYER);
        // A fault raised after PRE was delivered and the latch armed: clientStopping() is the
        // reachable route, since it runs a guarded core call outside the tick handlers.
        core.failOnStop(new RuntimeException("stop is broken"));
        subject.clientStopping();
        core.stopFailing();
        core.clear();

        subject.tickEnd(LEVEL_A, PLAYER);

        assertEquals(0, core.count(RecordingCore.Event.TICK_POST),
            "tickEnd's faulted check must suppress POST even with the latch armed");
    }
}
