package dev.continuo.platform;

/**
 * Which side of the game's own tick processing a callback is running on.
 *
 * <p>A1 only uses {@link #PRE}. {@link #POST} exists so that the {@code onClientTick}
 * signature does not have to change when both phases are needed.
 */
public enum TickPhase {
    PRE,
    POST
}
