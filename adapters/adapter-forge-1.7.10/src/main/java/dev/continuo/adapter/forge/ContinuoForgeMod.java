package dev.continuo.adapter.forge;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.continuo.core.ContinuoCore;
import dev.continuo.runtime.AdapterRuntime;
import dev.continuo.runtime.ClickSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

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
    private AdapterRuntime runtime;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        walkKey = new KeyBinding("key.continuo.walk", Keyboard.KEY_K, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(walkKey);

        final ContinuoCore core = new ContinuoCore();
        ForgePlatformContext context = new ForgePlatformContext(Minecraft.getMinecraft());

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
        }
    }
}
