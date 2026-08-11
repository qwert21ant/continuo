/**
 * Continuo platform SPI.
 *
 * <p>The contract between the pure core and any Minecraft version. Nothing in this package
 * may reference {@code net.minecraft}, and nothing may assume a Minecraft version newer
 * than 1.7.10. Every type added here is a future version-compatibility problem, so keep
 * the surface minimal.
 *
 * <h2>Global rules</h2>
 *
 * <p>These four rules are cross-cutting: they bind every type in this package, in both
 * directions. Per-type documentation cites them by number. Conformance tests are expected
 * to mirror this numbering, one case per rule. The keywords MUST, MUST NOT and MAY carry
 * their RFC 2119 meanings.
 *
 * <h3>Rule 1 — Threading</h3>
 *
 * <p>Every method in this package, in both directions, is called on the client main
 * thread. No implementation may block. No method may be called from any other thread.
 * Both target versions are single-threaded at the tick level; this rule exists to forbid
 * an adapter from routing calls through an asynchronous event bus.
 *
 * <h3>Rule 2 — Lifecycle</h3>
 *
 * <p>The core's {@code start} method is called exactly once per adapter lifetime, before
 * any other core method. The core's {@code stop} method may be called any number of times,
 * is idempotent, and leaves the core reusable — no second {@code start} follows it. An
 * adapter MUST call {@code stop} on world unload, on disconnect, and on client shutdown.
 *
 * <p>This rule binds adapters, not the core. The current core happens to tolerate a second
 * {@code start} call, and a test pins that behaviour, but an adapter MUST NOT rely on it.
 *
 * <h3>Rule 3 — Faults</h3>
 *
 * <p>If a core method throws, the adapter MUST catch it, log it with a stack trace, call
 * the core's {@code stop} method to release any held input, and deliver no further ticks
 * until the next world load clears the fault. A core fault MUST NOT propagate into the
 * game's tick loop. If the {@code stop} call inside the fault handler itself throws, the
 * adapter MUST log that too and still enter the faulted state: the handler must not be able
 * to fault.
 *
 * <p>A bot bug must never crash the user's game, and a half-dead core must never leave a
 * movement key held. Recovery is tied to world load because that is the same event that
 * opens the tick window under {@link dev.continuo.platform.IGameEvents#onClientTick} — one
 * event, one state transition, no separate recovery machinery.
 *
 * <h3>Rule 4 — Input persistence is not guaranteed</h3>
 *
 * <p>State set through {@link dev.continuo.platform.IActuator#setInput} may be cleared by
 * the platform at any time without notice. Any screen opening does this on both target
 * versions ({@code KeyMapping.releaseAll}; 1.7.10's {@code KeyBinding.unPressAllKeys}), as
 * does the user physically tapping the key. This SPI requires neither edge- nor
 * level-triggered actuation from core or adapter.
 *
 * <p>Resolving this is deferred to milestone M5, whose per-tick position resync will make
 * re-assertion of held inputs a special case of the same reconciliation loop. Until then
 * every adapter MUST behave identically here, so that M5 can change them all in one move.
 * The current core's assumption that a single {@code setInput(FORWARD, true)} persists for
 * forty ticks is documented-as-unguaranteed by this rule, not fixed by it.
 */
package dev.continuo.platform;
