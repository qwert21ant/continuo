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
     *   <li><b>{@link TickPhase#POST} MUST fire after the game has finished processing that
     *       tick's logic</b>, and after {@code PRE} for the same tick.
     *   <li><b>Both phases MUST be delivered.</b> An adapter that delivers only {@code PRE}
     *       is not conformant, even though the current core ignores {@code POST}. Optional
     *       phase delivery would mean the core could never use {@code POST} without a
     *       capability check, which is exactly the cross-adapter divergence this contract
     *       exists to prevent.
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
