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
 * directions. Per-type documentation cites them by number. The conformance suite in
 * {@code platform-testkit} is organised by this numbering, so <b>the numbering is
 * load-bearing and must not change</b>. Not every rule reduces to a test: rule 1's "no
 * implementation may block" and rule 4's "may be cleared at any time" have no assertion to
 * write, and neither has {@code onClientTick}'s "MUST NOT be delivered re-entrantly", which is
 * a property of an adapter's event source that no runtime can enforce on its own caller. The
 * suite records those gaps in its own documentation rather than leaving them silent. Rules 2 and 3 bind {@code start} and {@code stop}, which are declared on no type in
 * this package, so the suite asserts them against the core-side interface that does declare
 * them. The keywords MUST, MUST NOT and MAY carry their RFC 2119 meanings.
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
 * adapter MUST call {@code stop} on world unload and on disconnect, both of which the
 * level-identity condition below covers, and on client shutdown where the platform allows.
 *
 * <p>This rule binds adapters, not the core. The current core happens to tolerate a second
 * {@code start} call, and a test pins that behaviour, but an adapter MUST NOT rely on it.
 *
 * <p><b>What counts as a world unload.</b> Settled in A2a. An adapter MUST call {@code stop}
 * on each of these transitions in the client level instance it last ticked against:
 *
 * <ul>
 *   <li>To {@code null} — a world unload, a disconnect, or a quit to title.
 *   <li>Between two different non-{@code null} instances — a dimension change, which replaces
 *       the client's level object without ending the session, and therefore <em>is</em> a
 *       world unload under this rule.
 *   <li>From {@code null} to non-{@code null} — a world load. In the ordinary case the
 *       preceding transition already called {@code stop}, so this call is a no-op under rule
 *       2's idempotency; the obligation is not merely defensive, because it is also the call
 *       that clears stale core state if that earlier {@code stop} itself threw before the new
 *       level loaded.
 * </ul>
 *
 * <p>This is stated as an observable condition rather than as a per-platform event on
 * purpose. Naming each platform's hook separately is what let the two adapters disagree on
 * walking through a portal — Fabric's {@code DISCONNECT} does not fire on a dimension change
 * and 1.7.10's {@code WorldEvent.Unload} does — which is the exact cross-adapter divergence
 * this contract exists to prevent. A condition both adapters evaluate identically cannot
 * drift, and it gives a conformance suite something uniform to assert.
 *
 * <p>The stricter reading wins on the asymmetry of its failure mode. Stopping too often is a
 * visible, harmless abort. Continuing across a portal is a silent wrong-distance bug, on a
 * core whose state describes a position in a level that no longer exists, with held input the
 * platform has already cleared behind the loading screen under rule 4.
 *
 * <p>The same condition carries rule 3's recovery trigger: a transition to a non-{@code null}
 * level is the world load that clears a fault. One observable condition, three obligations,
 * no separate machinery.
 *
 * <p><b>Client shutdown.</b> {@code stop} MUST be called on client shutdown where the
 * platform exposes a main-thread client-stopping event, and MAY be omitted where none exists.
 * Forge 1.7.10 exposes none: the customary route is a JVM shutdown hook, which runs off the
 * main thread and would collide head-on with rule 1.
 *
 * <p>The omission is safe, not a concession, because {@code stop}'s only observable effects —
 * releasing held input and resetting in-memory core state — cannot outlive the process. On
 * client shutdown the obligation is hygiene rather than a defended failure mode. Fabric keeps
 * its {@code CLIENT_STOPPING} handler because it costs nothing to keep. Rule 1 therefore
 * stays exception-free, which is the point: an obligation with no observable effect is not
 * worth the first exception to the one rule stated without any.
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
