package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockTableLoader;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import dev.continuo.runtime.BlockDumpWalker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private KeyMapping dumpKey;
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

        // Dev-only: dumps a fixture row to disk for cross-adapter parity checks. Shares the
        // walk key's category; unrelated to the four global rules, so it is polled separately
        // below rather than threaded through AdapterRuntime.
        dumpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.dump",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
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

        // Polled independently of the walk key, on END so the dump reflects state after this
        // tick's core processing has already settled. context is effectively final here, so
        // the lambda can capture it directly.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!dumpKey.consumeClick() || client.player == null) {
                return;
            }
            try {
                BlockPos at = client.player.blockPosition();
                String text = BlockDumpWalker.dump(
                    context.blocks(),
                    new BlockClassifier(BlockTableLoader.forVersion(context.info().gameVersion())),
                    at.getX(), at.getY(), at.getZ(),
                    at.getX() + 31, at.getY(), at.getZ());
                Path out = client.gameDirectory.toPath().resolve("continuo-block-dump.txt");
                Files.write(out, text.getBytes(StandardCharsets.UTF_8));
                LOGGER.info("Continuo: wrote block dump to {}", out);
            } catch (Exception e) {
                LOGGER.error("Continuo: could not write the block dump", e);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runtime.clientStopping());
    }
}
