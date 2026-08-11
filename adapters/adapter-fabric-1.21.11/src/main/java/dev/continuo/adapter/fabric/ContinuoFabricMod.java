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
 * package. Two of them need machinery here: rule 2's tick window (the {@link #inWorld}
 * guard) and rule 3's fault handling ({@link #guarded} and {@link #faulted}).
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
                while (walkKey.consumeClick()) {
                    // discarded deliberately
                }
                return;
            }
            if (faulted) {
                return;
            }
            guarded(() -> {
                while (walkKey.consumeClick()) {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
                core.onClientTick(TickPhase.PRE);
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!inWorld(client) || faulted) {
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
     * Global rule 2's tick window: ticks are delivered only while a world is loaded and a
     * local player exists.
     */
    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
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
