package dev.continuo.adapter.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import dev.continuo.core.ContinuoCore;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Wiring only. Every behavioural decision lives in {@link ContinuoCore}.
 *
 * <p>1.7.10 predates SLF4J in Minecraft, so this adapter logs through log4j2, which is what
 * the game ships. That is a logging-API difference only; the messages match the Fabric
 * adapter's deliberately, because the smoke checklists assert on them.
 */
@Mod(
    modid = ContinuoForgeMod.MOD_ID,
    name = "Continuo",
    version = "0.1.0",
    acceptableRemoteVersions = "*"
)
public final class ContinuoForgeMod {

    public static final String MOD_ID = "continuo";

    private static final Logger LOGGER = LogManager.getLogger("continuo");

    private final ContinuoCore core = new ContinuoCore();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ForgePlatformContext context = new ForgePlatformContext(Minecraft.getMinecraft());
        core.start(context);

        LOGGER.info(
            "Continuo core started on {} / {}",
            context.info().gameVersion(),
            context.info().loader()
        );
    }
}
