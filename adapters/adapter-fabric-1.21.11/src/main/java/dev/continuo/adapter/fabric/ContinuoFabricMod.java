package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring only. Registers the keybind, builds the platform context, and forwards the game's
 * events to {@link AdapterRuntime}.
 *
 * <p>This class holds no conformance state. The four global rules documented in the
 * {@code dev.continuo.platform} package, and {@code IGameEvents.onClientTick}'s tick-window
 * and phase-ordering contract, are all discharged by {@code AdapterRuntime} — one
 * implementation shared with the 1.7.10 adapter, so the two cannot diverge and M5 can change
 * both in one move.
 */
public final class ContinuoFabricMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("continuo");

    private KeyMapping walkKey;
    private AdapterRuntime runtime;

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

        final ContinuoCore core = new ContinuoCore();
        FabricPlatformContext context = new FabricPlatformContext(Minecraft.getInstance());

        runtime = new AdapterRuntime(
            core,
            new Slf4jRuntimeLog(LOGGER),
            walkKey::consumeClick,
            () -> {
                LOGGER.info("Continuo walk requested");
                core.requestWalk();
            });
        runtime.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );

        ClientTickEvents.START_CLIENT_TICK.register(client ->
            runtime.tickStart(client.level, client.player));

        ClientTickEvents.END_CLIENT_TICK.register(client ->
            runtime.tickEnd(client.level, client.player));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runtime.clientStopping());
    }
}
