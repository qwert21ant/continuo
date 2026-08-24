package dev.continuo.adapter.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockTableLoader;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import dev.continuo.runtime.BlockDumpWalker;
import dev.continuo.runtime.PathProbe;
import dev.continuo.runtime.ProbeReport;
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
    private KeyMapping markKey;
    private KeyMapping pathKey;
    private AdapterRuntime runtime;
    private ContinuoCore core;
    private final PathProbe probe = new PathProbe();

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

        // Dev-only, like the dump key: mark a destination, then path to it from wherever you
        // stand. Unrelated to the four global rules, so polled separately below.
        markKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.mark",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            category
        ));
        pathKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.continuo.path",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            category
        ));

        core = new ContinuoCore();
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

        // Polled on END for the same reason the dump is: the read reflects state after this
        // tick's core processing has settled. The core's own BlockLookup is used rather than a
        // fresh one, so the classification memo is shared and its level-transition lifecycle is
        // the one ContinuoCore.stop() already discharges.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Before the keys, so a mark made on the same tick as a transition belongs to the
            // level it was pressed in. This is the level instance AdapterRuntime already compares
            // to decide when to stop the core, so the marked goal and the BlockLookup beside it
            // are discharged by one event.
            probe.onLevel(client.level);
            // Both keys are polled unconditionally: consumeClick() drains a queued press as a side
            // effect, so returning early on a null player would leave a title-screen press queued to
            // fire on the first tick after the world loads. The dump key above drains for the same
            // reason.
            boolean mark = markKey.consumeClick();
            boolean path = pathKey.consumeClick();
            if (client.player == null) {
                return;
            }
            try {
                BlockPos at = client.player.blockPosition();
                if (mark) {
                    probe.markGoal(at.getX(), at.getY(), at.getZ());
                    LOGGER.info("Continuo: path goal marked at {} {} {}",
                        at.getX(), at.getY(), at.getZ());
                }
                if (path) {
                    ProbeReport report = probe.run(
                        core.blocks(), at.getX(), at.getY(), at.getZ());
                    LOGGER.info(report.summary());
                    if (report.ran()) {
                        Path out = client.gameDirectory.toPath()
                            .resolve("continuo-path-probe.txt");
                        Files.write(out, report.map().getBytes(StandardCharsets.UTF_8));
                        LOGGER.info("Continuo: wrote path probe map to {}", out);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Continuo: the path probe failed", e);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runtime.clientStopping());
    }
}
