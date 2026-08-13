package dev.continuo.testkit;

import dev.continuo.core.CoreApi;
import dev.continuo.platform.IPlatformContext;
import dev.continuo.platform.TickPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CoreApi} that records every call in order and can be programmed to throw from any
 * of them.
 *
 * <p>The programmable throwing is what makes global rule 3 testable at all. A2a could not
 * exercise rule 3 because doing so required a deliberate throw, and a deliberate throw is not
 * something to leave in shipped adapter code. Here it is the point.
 *
 * <p>Every call is recorded <em>before</em> any programmed failure is thrown, so an assertion
 * can still see that the call happened.
 */
public final class RecordingCore implements CoreApi {

    /** One observed call, in the order it arrived. */
    public enum Event { START, STOP, TICK_PRE, TICK_POST }

    private final List<Event> events = new ArrayList<Event>();

    private IPlatformContext context;
    private RuntimeException startFailure;
    private RuntimeException stopFailure;
    private RuntimeException preFailure;
    private RuntimeException postFailure;

    @Override
    public void start(IPlatformContext context) {
        events.add(Event.START);
        this.context = context;
        if (startFailure != null) {
            throw startFailure;
        }
    }

    @Override
    public void stop() {
        events.add(Event.STOP);
        if (stopFailure != null) {
            throw stopFailure;
        }
    }

    @Override
    public void onClientTick(TickPhase phase) {
        events.add(phase == TickPhase.PRE ? Event.TICK_PRE : Event.TICK_POST);
        if (phase == TickPhase.PRE && preFailure != null) {
            throw preFailure;
        }
        if (phase == TickPhase.POST && postFailure != null) {
            throw postFailure;
        }
    }

    /** Every call so far, oldest first. Live, not a copy. */
    public List<Event> events() {
        return events;
    }

    /** How many times {@code event} has been observed. */
    public int count(Event event) {
        int total = 0;
        for (Event seen : events) {
            if (seen == event) {
                total++;
            }
        }
        return total;
    }

    /** Discards the recorded events. Programmed failures and the context are unaffected. */
    public void clear() {
        events.clear();
    }

    /** The context passed to the most recent {@code start} call, or {@code null}. */
    public IPlatformContext context() {
        return context;
    }

    public void failOnStart(RuntimeException failure) {
        startFailure = failure;
    }

    public void failOnStop(RuntimeException failure) {
        stopFailure = failure;
    }

    public void failOnPre(RuntimeException failure) {
        preFailure = failure;
    }

    public void failOnPost(RuntimeException failure) {
        postFailure = failure;
    }

    /** Clears every programmed failure. */
    public void stopFailing() {
        startFailure = null;
        stopFailure = null;
        preFailure = null;
        postFailure = null;
    }
}
