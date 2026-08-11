package dev.continuo.platform;

/**
 * Events flowing from the game into the core.
 *
 * <p>Note the direction: the core implements this and the adapter calls it. The adapter
 * holds the core, never the reverse.
 *
 * <p>Subject to all four global rules in this package's documentation — in particular rule
 * 3, which makes the adapter, not the core, responsible for anything these methods throw.
 */
public interface IGameEvents {

    /**
     * Called once per client <em>tick</em>, per phase.
     *
     * <p>Adapter obligations:
     *
     * <ul>
     *   <li><b>Tick, not frame.</b> An adapter MUST NOT bind this to a frame or render
     *       event. The nominal rate is twenty calls per second per phase; it may be lower
     *       when the game cannot keep up, and MUST NOT be higher.
     *   <li><b>{@link TickPhase#PRE} MUST fire before the game reads input for that
     *       tick.</b> The consequence is the part worth stating: {@link IActuator#setInput}
     *       called during {@code PRE} of tick <i>N</i> affects the player's movement on
     *       tick <i>N</i>. This is why a forty-tick walk yields forty ticks of travel and
     *       not thirty-nine.
     *       <p>Ticks counted are not the same as ticks travelled. This callback keeps firing
     *       while the game is paused or the death screen is up — the client's tick loop still
     *       runs, and a world and local player still exist — but the level itself is frozen,
     *       so a core counting forty of these ticks can cover less than forty ticks of
     *       ground. What happens to a held input across those screens is global rule 4's
     *       subject and is deferred to M5; this note only records that the tick count and the
     *       distance are not the same quantity.
     *   <li><b>{@link TickPhase#POST} MUST fire after the game has finished processing that
     *       tick's logic</b>, and after {@code PRE} for the same tick.
     *   <li><b>Both phases MUST be delivered.</b> An adapter that delivers only {@code PRE}
     *       is not conformant, even though the current core ignores {@code POST}. Optional
     *       phase delivery would mean the core could never use {@code POST} without a
     *       capability check, which is exactly the cross-adapter divergence this contract
     *       exists to prevent.
     *       <p><b>Caveat — the one permitted exception.</b> {@code POST} MAY be suppressed
     *       for a tick whose {@code PRE} was already delivered, if and only if, at the moment
     *       {@code POST} would fire, either the tick window has closed (no world or no local
     *       player — a dimension change or a disconnect processed inside the game's own tick)
     *       or the adapter has entered the faulted state under global rule 3. {@code PRE} is
     *       then left unpaired for that tick. This is the intended resolution of the tension
     *       between this clause and the world-window and fault clauses below, not an adapter
     *       defect: firing {@code POST} with no local player, or after a fault, would violate
     *       those clauses, and they win. No other reason to skip {@code POST} is conformant,
     *       and the exception never runs the other way — {@code POST} MUST NOT be delivered
     *       without a same-tick {@code PRE}.
     *       <p>Stated so a conformance suite can encode it: for every tick, either both
     *       phases are delivered in order, or neither is, or {@code PRE} alone is and one of
     *       the two suppressing conditions held when {@code POST} was due. A recording
     *       {@link IGameEvents} that observes {@code PRE} without {@code POST} MUST therefore
     *       assert the suppressing condition rather than fail outright.
     *   <li><b>Delivered only while a world is loaded and a local player exists.</b> An
     *       adapter MUST NOT call this at the main menu, on a loading screen, or at any
     *       other time when there is no local player.
     *   <li><b>MUST NOT be delivered re-entrantly.</b> One call must return before the next
     *       begins.
     * </ul>
     *
     * @param phase which side of the game's own tick processing this call is on
     */
    void onClientTick(TickPhase phase);
}
