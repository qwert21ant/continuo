/**
 * The published movement API.
 *
 * <p>A movement is a plugin, not an enum member. This package is what a movement compiles
 * against: the contract, the cost table, the standability predicates, and the registry that
 * filters and orders them. It deliberately does <b>not</b> expose the search — a movement has no
 * business knowing how A* works, and the dependency-direction check enforces that a movement
 * module cannot see {@code :core-pathfinder}.
 *
 * <h2>Stability</h2>
 *
 * <p><b>Not yet stable for out-of-tree authors.</b> Two known additions are coming, and both are
 * additive: M5 adds an executor to {@code IMovementType} as a default
 * method, and sub-project I widens {@code ExpansionContext} with node
 * state. Nothing here promises source compatibility before M5.
 *
 * <h2>Purity</h2>
 *
 * <p>Nothing in this module may reference {@code net.minecraft}, and the build fails if it does.
 * The movement API takes no capability from the platform SPI: the active set is decided by the
 * caller, which is what keeps M4 headless. See {@link dev.continuo.movement.Capability}.
 */
package dev.continuo.movement;
