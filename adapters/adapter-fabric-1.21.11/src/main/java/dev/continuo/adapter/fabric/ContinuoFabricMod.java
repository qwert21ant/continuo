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
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private final ContinuoCore core = new ContinuoCore();
    private KeyMapping walkKey;

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
            while (walkKey.consumeClick()) {
                LOGGER.info("Continuo walk requested");
                core.requestWalk();
            }
            core.onClientTick(TickPhase.PRE);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Continuo stopping: disconnected");
            core.stop();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("Continuo stopping: client shutting down");
            core.stop();
        });
    }
}
