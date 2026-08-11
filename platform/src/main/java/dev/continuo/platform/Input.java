package dev.continuo.platform;

/**
 * Abstract movement inputs, named for intent rather than for any keyboard layout or
 * Minecraft version's internal naming.
 *
 * <p>Adapters MUST map every constant here to a real game input. Adding a constant is a
 * breaking change for every adapter.
 */
public enum Input {
    FORWARD,
    BACK,
    LEFT,
    RIGHT,
    JUMP,
    SNEAK,
    SPRINT
}
