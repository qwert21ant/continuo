package dev.continuo.adapter.forge;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockTableLoader;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import dev.continuo.runtime.BlockDumpWalker;
import dev.continuo.runtime.ClickSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Wiring only. Registers the keybind, builds the platform context, and forwards the game's
 * events to {@link AdapterRuntime}.
 *
 * <p>This class holds no conformance state. The four global rules documented in the
 * {@code dev.continuo.platform} package, and {@code IGameEvents.onClientTick}'s tick-window
 * and phase-ordering contract, are all discharged by {@code AdapterRuntime} — one
 * implementation shared with the 1.21.11 adapter, so the two cannot diverge and M5 can change
 * both in one move.
 *
 * <p>There is no {@code clientStopping()} call here. Forge 1.7.10 exposes no main-thread
 * client-stopping event; the customary JVM shutdown hook runs off the main thread and would
 * collide with global rule 1. Rule 2 makes that clause MUST-where-available, so this adapter
 * is conformant by omission.
 */
// clientSideOnly is deliberately omitted: the FML build this module actually compiles
// against (the decompiled cpw.mods.fml.common.Mod in build/rfg/minecraft-src, which is
// what compileJava resolves, not a newer binary) has no such element on @Mod. The
// attribute was added to FML for MC 1.8+ and never backported to 1.7.10, confirmed by
// direct inspection of the decompiled annotation. This is metadata only (server-side load
// exclusion); acceptableRemoteVersions = "*" below covers the same purpose.
@Mod(
    modid = ContinuoForgeMod.MOD_ID,
    name = "Continuo",
    version = "0.1.0",
    acceptableRemoteVersions = "*"
)
public final class ContinuoForgeMod {

    public static final String MOD_ID = "continuo";

    private static final Logger LOGGER = LogManager.getLogger("continuo");

    private KeyBinding walkKey;
    private KeyBinding dumpKey;
    private AdapterRuntime runtime;

    /**
     * Promoted from a local so {@link #onClientTick} — a separate method from {@link #init} —
     * can reach it when polling {@link #dumpKey}. Nothing else needs it to be a field.
     */
    private ForgePlatformContext context;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        walkKey = new KeyBinding("key.continuo.walk", Keyboard.KEY_K, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(walkKey);

        // Dev-only: dumps a fixture row to disk for cross-adapter parity checks. Shares the
        // walk key's category; unrelated to the four global rules, so it is polled separately
        // in onClientTick rather than threaded through AdapterRuntime.
        dumpKey = new KeyBinding("key.continuo.dump", Keyboard.KEY_J, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(dumpKey);

        final ContinuoCore core = new ContinuoCore();
        context = new ForgePlatformContext(Minecraft.getMinecraft());

        runtime = new AdapterRuntime(
            core,
            new Log4jRuntimeLog(LOGGER),
            new ClickSource() {
                @Override
                public boolean consumeClick() {
                    return walkKey.isPressed();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
            });
        runtime.start(context);

        // TickEvent.ClientTickEvent is posted on the FML bus, not the Forge event bus.
        FMLCommonHandler.instance().bus().register(this);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            runtime.tickStart(client.theWorld, client.thePlayer);
        } else {
            runtime.tickEnd(client.theWorld, client.thePlayer);
            // This event fires for both tick phases; the dump poll is guarded to the else
            // branch above (Phase.END) so it cannot run twice in one tick. END is chosen so the
            // dump reflects state after this tick's core processing has already settled.
            pollDumpKey(client);
        }
    }

    /**
     * Polls the dump key and, on a click, writes the fixture-row dump to disk.
     *
     * <p>Runs inside a tick callback, so the whole operation — the walk as well as the file
     * write — is wrapped in a single {@code try}/{@code catch}. An escaping exception here
     * would violate the spirit of global rule 3 even though that rule binds core faults
     * specifically; a dev tool that kills the client on a bad region is worse than one that
     * logs and carries on.
     */
    private void pollDumpKey(Minecraft client) {
        if (!dumpKey.isPressed() || client.thePlayer == null) {
            return;
        }
        try {
            int px = MathHelper.floor_double(client.thePlayer.posX);
            int py = MathHelper.floor_double(client.thePlayer.posY);
            int pz = MathHelper.floor_double(client.thePlayer.posZ);
            String text = BlockDumpWalker.dump(
                context.blocks(),
                new BlockClassifier(BlockTableLoader.forVersion(context.info().gameVersion())),
                px, py, pz,
                px + 31, py, pz);
            File out = new File(client.mcDataDir, "continuo-block-dump.txt");
            OutputStream stream = null;
            try {
                stream = new FileOutputStream(out);
                stream.write(text.getBytes("UTF-8"));
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // Already written or already failed; nothing useful to do.
                    }
                }
            }
            LOGGER.info("Continuo: wrote block dump to " + out.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Continuo: could not write the block dump", e);
        }
    }
}
