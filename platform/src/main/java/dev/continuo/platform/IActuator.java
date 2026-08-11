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
     * Sets a movement input to pressed or released.
     *
     * <p>Takes effect at the game's next input read, and therefore on the current tick when
     * called from {@link TickPhase#PRE}.
     *
     * <p>Idempotent: setting an already-pressed input to pressed is a no-op from the game's
     * perspective.
     *
     * <p>The effect does not necessarily persist. Global rule 4 in this package's
     * documentation applies: the platform may clear input state at any time without notice —
     * any screen opening does so on both target versions, as does the user physically tapping
     * the key. This is a stated hazard, not an obligation: the SPI does not require either
     * side to re-assert a held input, and does not require either side to rely on one
     * persisting. Whether actuation is edge- or level-triggered is deferred to milestone M5;
     * see global rule 4 for that deferral.
     *
     * <p>Adapters MUST support every {@link Input} constant; throwing for a valid constant
     * is a conformance failure. The core MUST NOT pass {@code null}, and adapter behaviour
     * on {@code null} is unspecified.
     *
     * @param input   which movement input to change; never {@code null}
     * @param pressed {@code true} to press, {@code false} to release
     */
    void setInput(Input input, boolean pressed);
}
