package dev.continuo.platform;

/**
 * Events flowing from the game into the core.
 *
 * <p>Note the direction: the core implements this and the adapter calls it. The adapter
 * holds the core, never the reverse.
 */
public interface IGameEvents {

    /** Called once per client tick, per phase. */
    void onClientTick(TickPhase phase);
}
