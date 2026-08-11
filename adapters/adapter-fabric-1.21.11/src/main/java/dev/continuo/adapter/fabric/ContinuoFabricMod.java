package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.ContinuoCore;
import dev.continuo.platform.TickPhase;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring only. Translates a keypress into a core method call and a client tick into a core
 * tick. Every behavioural decision lives in {@link ContinuoCore}.
 *
 * <p>Implements the four global rules documented in the {@code dev.continuo.platform}
 * package, plus {@link dev.continuo.platform.IGameEvents#onClientTick}'s own tick-window and
 * phase-ordering contract. The machinery: {@code onClientTick}'s own tick window (the
 * {@link #inWorld} guard), rule 3's fault
 * handling ({@link #guarded} and {@link #faulted}), the PRE/POST pairing latch ({@link
 * #preDelivered}) that stops {@code POST} from ever firing without a same-tick {@code PRE}
 * when {@link #inWorld} or {@link #faulted} changes mid-tick — the converse is deliberately
 * not prevented, and a {@code PRE} left unpaired that way is the exception the
 * {@code onClientTick} contract permits — and the click drain ({@link #drainClicks}) that
 * keeps a queued click from surviving a transition into or out of a ticked state.
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private final ContinuoCore core = new ContinuoCore();
    private KeyMapping walkKey;

    /**
     * Set when a core call throws, per global rule 3. While set, no ticks are delivered.
     * Cleared on the next world load.
     */
    private boolean faulted;

    /**
     * Set when {@link TickPhase#PRE} is delivered for the current tick; cleared the moment
     * {@code END_CLIENT_TICK} next runs, whether or not it goes on to deliver {@link
     * TickPhase#POST}. {@link #inWorld} and {@link #faulted} are re-read independently by
     * each phase's handler, so either can change between {@code START_CLIENT_TICK} and
     * {@code END_CLIENT_TICK} of the same tick (a mid-tick dimension change or a disconnect
     * processed inside {@code Minecraft.tick()}). This latch is what stops {@code POST} from
     * ever firing without a same-tick {@code PRE}. It cannot wedge across ticks: it is
     * unconditionally cleared on every {@code END_CLIENT_TICK} call, so a {@code PRE} that
     * loses its {@code POST} mid-tick never leaves the latch set for the next one.
     */
    private boolean preDelivered;

    @Override
    public void onInitializeClient() {
        // 1.21.11 replaced the old String-literal keybind category with a registered
        // KeyMapping.Category keyed by an Identifier. This is a name/type correction only;
        // the category still exists purely to label the controls screen entry.
        KeyMapping.Category category =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("continuo", "main"));

        walkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.walk",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            category
        ));

        FabricPlatformContext context = new FabricPlatformContext(Minecraft.getInstance());
        core.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!inWorld(client)) {
                // Drain clicks made outside a world so a title-screen keypress cannot fire
                // a walk the instant the next world loads.
                drainClicks();
                return;
            }
            if (faulted) {
                // Same invariant while faulted: a click must not survive to be replayed once
                // the fault clears on the next world load.
                drainClicks();
                return;
            }
            guarded(() -> {
                while (walkKey.consumeClick()) {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
                core.onClientTick(TickPhase.PRE);
                preDelivered = true;
            });
            // If the block above ran to completion, walkKey is already empty and this is a
            // no-op. If requestWalk() threw partway through the loop, this discards whatever
            // clicks were still queued so they cannot leak into a tick after the fault clears.
            drainClicks();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean deliverPost = preDelivered;
            preDelivered = false;
            if (!deliverPost || !inWorld(client) || faulted) {
                return;
            }
            guarded(() -> core.onClientTick(TickPhase.POST));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (faulted) {
                LOGGER.info("Continuo fault cleared by world load");
                faulted = false;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Continuo stopping: disconnected");
            guarded(core::stop);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("Continuo stopping: client shutting down");
            guarded(core::stop);
        });
    }

    /**
     * The tick window from {@link dev.continuo.platform.IGameEvents#onClientTick}: ticks are
     * delivered only while a world is loaded and a local player exists. Global rule 2 is
     * lifecycle only and does not state this window.
     */
    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    /**
     * Discards any clicks queued on {@link #walkKey} without feeding them to the core. Called
     * on all three {@code START_CLIENT_TICK} paths — out of world, faulted, and after a
     * delivered {@code PRE} that may have aborted mid-loop — so a click made while out of
     * world, while faulted, or immediately before a mid-loop fault can never survive into a
     * later, successful tick. Deliberately not called from {@code END_CLIENT_TICK}: clicks
     * are consumed only in the {@code PRE} handler, so that tick's queue was already dealt
     * with, and draining again would swallow a keypress the user makes between the two
     * halves of a tick.
     */
    private void drainClicks() {
        while (walkKey.consumeClick()) {
            // discarded deliberately
        }
    }

    /**
     * Runs a core call under global rule 3. Nothing the core throws reaches the game's tick
     * loop, the core is stopped so it cannot leave a movement key held, and no further ticks
     * are delivered until the next world load.
     */
    private void guarded(Runnable coreCall) {
        try {
            coreCall.run();
        } catch (Throwable thrown) {
            // Set before stopping: if stop() throws too, the faulted state must still hold.
            faulted = true;
            LOGGER.error("Continuo core faulted; no further ticks until the next world load", thrown);
            try {
                core.stop();
            } catch (Throwable stopFailure) {
                LOGGER.error("Continuo core.stop() also failed while handling a fault", stopFailure);
            }
        }
    }
}
