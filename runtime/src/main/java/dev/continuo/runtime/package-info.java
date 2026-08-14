/**
 * The adapter's side of the platform contract, expressed once for every Minecraft version.
 *
 * <p>Before A2b this machinery existed twice, in {@code ContinuoFabricMod} and
 * {@code ContinuoForgeMod}, as version-independent Java that happened to be written twice.
 * It touches the game through four things only: an identity-compared level object, a
 * null-checked player object, a boolean click poll, and a logger. None of them is a game
 * type, which is why the three behaviours both smoke checklists disclaim — global rule 3
 * fault handling, the click drain, and PRE/POST pairing — became testable offline the moment
 * the machinery moved out of classes that import {@code net.minecraft}.
 *
 * <p>This package holds no bot behaviour. What Continuo decides to do is {@code dev.continuo.core}'s
 * subject; how a host discharges the four global rules is this one's.
 */
package dev.continuo.runtime;
