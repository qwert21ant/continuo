package dev.continuo.runtime;

/**
 * A poll that consumes one queued keybind click per call.
 *
 * <p>Both target versions already satisfy this shape — 1.21.11's
 * {@code KeyMapping.consumeClick()} and 1.7.10's {@code KeyBinding.isPressed()} — so an
 * adapter supplies a one-line implementation. The runtime sees only the boolean, which is why
 * the differing method names stop being a divergence risk.
 */
public interface ClickSource {

    /** @return {@code true} if a queued click was consumed; {@code false} when none remain */
    boolean consumeClick();
}
