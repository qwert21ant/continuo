/**
 * The conformance suite for the platform contract.
 *
 * <p>Extend {@code AdapterConformanceTest} and return a subject from {@code newSubject}. The
 * cases are organised by the global rule numbering in {@code dev.continuo.platform}'s
 * {@code package-info}, which is load-bearing and must not change.
 *
 * <h2>What a green run does not mean</h2>
 *
 * <p>This is the part a future session most needs, because a green suite is easy to overread.
 * A green run does <b>not</b> show:
 *
 * <ul>
 *   <li>that an adapter passes the correct level or player object — an adapter reading the
 *       wrong field passes every case here;
 *   <li>that {@code IActuator.setInput} moves the player;
 *   <li>that {@code PRE} genuinely precedes the game's own input read for that tick;
 *   <li>that the tick source is a tick and not a frame;
 *   <li>anything about global rule 1 or global rule 4, neither of which reduces to a test;
 *   <li>anything about {@code IGameEvents.onClientTick}'s "MUST NOT be delivered
 *       re-entrantly" clause — that is a property of the adapter's event source, and a runtime
 *       cannot stop its own caller from re-entering it, so the suite has no case for it.
 * </ul>
 *
 * <p>Those remain the job of {@code docs/smoke-checklist-a1.md} and
 * {@code docs/smoke-checklist-a2.md}. This suite covers the shared logic; the checklists cover
 * the platform binding. <b>Neither subsumes the other, and a green run of either is not
 * evidence about the other's subject.</b>
 *
 * <h2>B1 adds no conformance cases here, deliberately</h2>
 *
 * <p>This suite asserts {@code AdapterRuntime}, which both the Forge 1.7.10 and Fabric 1.21.11
 * adapters delegate to. {@code IBlockView} is different: each adapter implements it directly,
 * and asserting it needs a live world — the same structural reason the adapters themselves have
 * no automated tests at all. {@code FakeBlockView} in this package is a fixture for headless
 * core tests, <b>not</b> a conformance harness, and a green run of anything in this package says
 * nothing about whether either adapter reports a block truthfully. The cross-adapter dump under
 * {@code docs/parity/} is the substitute for that missing coverage, and it is a manual step —
 * see {@code docs/smoke-checklist-a1.md} and {@code docs/smoke-checklist-a2.md} again, this
 * time for the block-dump steps rather than the walk steps.
 */
package dev.continuo.testkit;
