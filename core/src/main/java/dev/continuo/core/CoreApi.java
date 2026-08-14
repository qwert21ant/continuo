package dev.continuo.core;

import dev.continuo.platform.IGameEvents;
import dev.continuo.platform.IPlatformContext;

/**
 * Everything an adapter runtime calls on the core.
 *
 * <p>This is the A2b injection seam. It exists so that a conformance suite can substitute a
 * recording implementation for {@link ContinuoCore} and observe an adapter runtime's
 * behaviour without a running game. It deliberately lives here rather than in
 * {@code dev.continuo.platform}: that package is the contract between the core and every
 * Minecraft version, and a testing concern must not become a permanent obligation on every
 * future adapter.
 *
 * <p>{@code start} and {@code stop} are the methods global rules 2 and 3 bind, and neither is
 * declared on any type in {@code dev.continuo.platform}. A suite encoding those rules has to
 * name a core-side type; this is that type.
 *
 * <p>Deliberately absent: {@code requestWalk}. It is bot behaviour, not conformance. An
 * adapter runtime dispatches a consumed click to a supplied {@code Runnable} instead, so the
 * runtime never learns what a walk is.
 */
public interface CoreApi extends IGameEvents {

    /**
     * Called once per adapter lifetime, before any other method on this interface.
     *
     * @param context everything the adapter hands the core at startup; never {@code null}
     */
    void start(IPlatformContext context);

    /** Releases any held input and resets state. Idempotent; leaves the core reusable. */
    void stop();
}
