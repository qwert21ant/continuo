package dev.continuo.runtime;

import dev.continuo.pathfinder.PathOutcome;

/**
 * What one probe run produced.
 *
 * <p>Two channels, because they answer different questions. {@link #summary()} is the one line
 * that goes to the log and tells you whether to bother looking; {@link #map()} is the text art
 * that tells you whether the route is sane.
 *
 * <p><b>A run that never happened is a first-class state, not an error.</b> Pressing the path key
 * before the mark key is the most likely thing to happen in practice, and the caller is inside
 * the game loop where a throw would be an adapter fault under global rule 3. {@link #ran()} is
 * false in that case and {@link #summary()} explains it; {@link #outcome()} and {@link #map()}
 * throw, because there is genuinely nothing for them to return and a placeholder would be worse
 * than a refusal.
 */
public final class ProbeReport {

    private final boolean ran;
    private final PathOutcome outcome;
    private final String summary;
    private final String map;

    private ProbeReport(boolean ran, PathOutcome outcome, String summary, String map) {
        this.ran = ran;
        this.outcome = outcome;
        this.summary = summary;
        this.map = map;
    }

    /** @param summary why no search happened; never {@code null} */
    static ProbeReport notRun(String summary) {
        return new ProbeReport(false, null, summary, null);
    }

    static ProbeReport of(PathOutcome outcome, String summary, String map) {
        return new ProbeReport(true, outcome, summary, map);
    }

    /** @return whether a search actually ran */
    public boolean ran() {
        return ran;
    }

    /**
     * @return what the search returned
     * @throws IllegalStateException if no search ran
     */
    public PathOutcome outcome() {
        if (!ran) {
            throw new IllegalStateException("no search ran: " + summary);
        }
        return outcome;
    }

    /** @return one line for the log; never {@code null} */
    public String summary() {
        return summary;
    }

    /**
     * @return the text-art map, ending in a newline
     * @throws IllegalStateException if no search ran
     */
    public String map() {
        if (!ran) {
            throw new IllegalStateException("no search ran: " + summary);
        }
        return map;
    }
}
