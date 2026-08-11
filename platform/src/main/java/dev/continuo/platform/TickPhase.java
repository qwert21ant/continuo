package dev.continuo.platform;

/**
 * Which side of the game's own tick processing a callback is running on.
 *
 * <p>Adapters MUST deliver both constants, and MUST deliver {@link #PRE} before
 * {@link #POST} for the same tick. See {@link IGameEvents#onClientTick} for the full
 * obligation.
 */
public enum TickPhase {

    /**
     * Before the game reads input for this tick. An {@link IActuator#setInput} call made
     * during this phase takes effect on this same tick.
     */
    PRE,

    /**
     * After the game has finished processing this tick's logic. No core behaviour uses this
     * phase yet; adapters MUST deliver it regardless, so that it is available without a
     * capability check when the core does.
     */
    POST
}
