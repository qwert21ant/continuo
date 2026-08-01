package dev.continuo.platform;

/**
 * The single channel through which the core influences the game.
 *
 * <p>Implemented by adapters. Every effect the core has on the world passes through here,
 * which is what makes the core testable and what will later make input humanization a
 * single seam rather than a cross-cutting concern.
 */
public interface IActuator {

    /**
     * Sets a movement input to pressed or released. Idempotent: setting an already-pressed
     * input to pressed is a no-op from the game's perspective.
     */
    void setInput(Input input, boolean pressed);
}
