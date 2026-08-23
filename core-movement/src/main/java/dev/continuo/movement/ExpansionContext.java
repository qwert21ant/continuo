package dev.continuo.movement;

import dev.continuo.core.BlockSource;

/**
 * The position a movement is being asked to expand from, and the world to read.
 *
 * <p><b>MUST NOT be retained past the {@link IMovementType#expand} call that received it.</b>
 * The search passes one instance per search and moves it between positions, so a movement that
 * stashes it will later read coordinates belonging to a different node. This is a documented
 * contract rather than an enforced one — like the SPI's rule 1, there is no assertion to write
 * against a caller's own misuse.
 *
 * <p>An interface rather than four parameters because this is where node state arrives at
 * sub-project I. Widening a context is additive; changing every published movement's signature
 * is not.
 */
public interface ExpansionContext {

    /** @return the world to read; never {@code null} */
    BlockSource world();

    /** @return the X of the position being expanded */
    int x();

    /** @return the Y of the position being expanded */
    int y();

    /** @return the Z of the position being expanded */
    int z();
}
