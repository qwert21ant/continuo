/**
 * The pathfinder: an A* search over an implicit graph of block positions.
 *
 * <p>Pure and headless. The only view of the world is {@link dev.continuo.core.BlockSource},
 * so everything here can be tested against a fixture world with no game, no adapter and no
 * classifier involved.
 *
 * <p><b>Block facts become movement facts in exactly one place</b> —
 * {@link dev.continuo.pathfinder.Standability}. Nothing else in this package reads
 * {@code collisionTop}, a {@code BlockShape} or a {@code Fluid} directly, so the rules about
 * what can be stood on live in one file rather than being restated per movement.
 */
package dev.continuo.pathfinder;
