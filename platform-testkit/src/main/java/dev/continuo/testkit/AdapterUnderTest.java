package dev.continuo.testkit;

import dev.continuo.platform.IPlatformContext;

/**
 * Everything the conformance suite needs to be able to do to a subject.
 *
 * <p>This is the reusability boundary, and its limit is worth stating plainly: a suite that
 * runs without Minecraft cannot test an adapter that binds directly to Minecraft. "Reusable
 * by any adapter" means any adapter that can be driven through this interface — which is
 * every adapter routing its conformance obligations through a version-independent object, and
 * is not every conceivable adapter.
 *
 * <p>Level and player are {@code Object} deliberately. The suite compares levels by identity
 * and null-checks players, which is exactly what the contract's level-identity condition
 * requires and all it requires.
 */
public interface AdapterUnderTest {

    /** Drives the subject's startup. */
    void start(IPlatformContext context);

    /** Drives the game's tick-start hook. */
    void tickStart(Object level, Object player);

    /** Drives the game's tick-end hook. */
    void tickEnd(Object level, Object player);

    /** Drives a main-thread client-stopping event. */
    void clientStopping();

    /** Queues {@code count} unconsumed clicks on the subject's click source. */
    void queueClick(int count);

    /** How many queued clicks the subject has dispatched to its click handler. */
    int clicksHandled();

    /**
     * Makes the {@code number}-th click dispatch (1-based, counted from now) throw.
     *
     * @param number  which dispatch throws; 1 is the next one
     * @param failure what it throws
     */
    void failClickNumber(int number, RuntimeException failure);

    /** The recording core this subject was built with. */
    RecordingCore core();
}
