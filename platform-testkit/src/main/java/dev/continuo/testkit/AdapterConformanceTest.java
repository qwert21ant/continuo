package dev.continuo.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
