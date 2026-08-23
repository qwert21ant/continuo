/**
 * A parkour jump, as a movement plugin.
 *
 * <p><b>This module is the evidence that the movement seam works.</b> It depends on
 * {@code :core-movement} and not on {@code :core-pathfinder}, so it is written with no access to
 * the search's internals, and {@code checkDependencyDirection} fails the build if that ever
 * changes. It is found at runtime through {@code META-INF/services}, so nothing in the core names
 * it.
 */
package dev.continuo.movement.parkour;
