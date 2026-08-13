package dev.continuo.adapter.forge;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.continuo.core.ContinuoCore;
import dev.continuo.platform.TickPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

/**
 * Wiring only. Translates a keypress into a core method call and a client tick into a core
 * tick. Every behavioural decision lives in {@link ContinuoCore}.
 *
 * <p>Implements the four global rules documented in the {@code dev.continuo.platform} package,
 * plus {@link dev.continuo.platform.IGameEvents#onClientTick}'s own tick-window and
 * phase-ordering contract. The machinery mirrors the Fabric adapter's deliberately, so that
 * M5 can change both in one move: the tick window ({@link #inWorld}), rule 3's fault handling
 * ({@link #guarded} and {@link #faulted}), the PRE/POST pairing latch ({@link #preDelivered}),
 * and the click drain ({@link #drainClicks}).
 *
 * <p>Lifecycle is driven by a single level-identity condition ({@link #updateLevel}) rather
 * than by connection events: global rule 2's world-unload and disconnect triggers and global
 * rule 3's recovery are all the same observable transition, and expressing them one way is
 * what keeps this adapter and the 1.21.11 one from diverging on a dimension change.
 *
 * <p>1.7.10 predates SLF4J in Minecraft, so this adapter logs through log4j2, which is what
 * the game ships. That is a logging-API difference only; the messages match the Fabric
 * adapter's deliberately, because the smoke checklists assert on them.
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

    private final ContinuoCore core = new ContinuoCore();
    private KeyBinding walkKey;

    /**
     * Set when a core call throws, per global rule 3. While set, no ticks are delivered.
     * Cleared on the next world load.
     */
    private boolean faulted;

    /**
     * Set when {@link TickPhase#PRE} is delivered for the current tick; cleared the moment the
     * {@code END} phase next runs, whether or not it goes on to deliver {@link TickPhase#POST}.
     * {@link #inWorld} and {@link #faulted} are re-read independently by each phase, so either
     * can change between the two halves of one tick. This latch is what stops {@code POST} from
     * ever firing without a same-tick {@code PRE}. It cannot wedge across ticks: it is
     * unconditionally cleared on every {@code END} phase.
     */
    private boolean preDelivered;

    /**
     * The client level instance last seen by the tick handler, compared by identity. Holding
     * it does not leak an unloaded world: it is overwritten with the current level the moment
     * a change is detected, so it only ever names the level that is loaded now, or
     * {@code null}.
     */
    private Object lastLevel;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        walkKey = new KeyBinding("key.continuo.walk", Keyboard.KEY_K, "key.categories.continuo");
        ClientRegistry.registerKeyBinding(walkKey);

        ForgePlatformContext context = new ForgePlatformContext(Minecraft.getMinecraft());
        core.start(context);

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
        if (event.phase == TickEvent.Phase.START) {
            onTickStart();
        } else {
            onTickEnd();
        }
    }

    private void onTickStart() {
        Minecraft client = Minecraft.getMinecraft();
        updateLevel(client.theWorld);
        if (!inWorld(client)) {
            // Drain clicks made outside a world so a title-screen keypress cannot fire a walk
            // the instant the next world loads.
            drainClicks();
            return;
        }
        if (faulted) {
            // Same invariant while faulted: a click must not survive to be replayed once the
            // fault clears on the next world load.
            drainClicks();
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                while (walkKey.isPressed()) {
                    LOGGER.info("Continuo walk requested");
                    core.requestWalk();
                }
                core.onClientTick(TickPhase.PRE);
                preDelivered = true;
            }
        });
        // If the block above ran to completion, walkKey is already empty and this is a no-op.
        // If requestWalk() threw partway through the loop, this discards whatever clicks were
        // still queued so they cannot leak into a tick after the fault clears.
        drainClicks();
    }

    private void onTickEnd() {
        boolean deliverPost = preDelivered;
        preDelivered = false;
        if (!deliverPost || !inWorld(Minecraft.getMinecraft()) || faulted) {
            return;
        }
        guarded(new Runnable() {
            @Override
            public void run() {
                core.onClientTick(TickPhase.POST);
            }
        });
    }

    /**
     * Global rule 2's world-unload trigger and global rule 3's recovery trigger, which are the
     * same observable condition: the client level instance being replaced or becoming
     * {@code null}. A dimension change replaces it without ending the session and counts.
     */
    private void updateLevel(Object level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;

        // Clear the fault BEFORE stopping, never after. If stop() throws, guarded() sets
        // faulted again and it must stay set — clearing afterwards would let the fault handler
        // swallow its own fault, which rule 3 forbids.
        if (level != null && faulted) {
            LOGGER.info("Continuo fault cleared by world load");
            faulted = false;
        }

        LOGGER.info("Continuo stopping: client level changed");
        guarded(new Runnable() {
            @Override
            public void run() {
                // Not redundant on a world load: in the ordinary case the core was already
                // stopped by the transition to null and this is a no-op, but if that earlier
                // stop() threw, this is what clears the stale state.
                core.stop();
            }
        });
    }

    /**
     * The tick window from {@link dev.continuo.platform.IGameEvents#onClientTick}: ticks are
     * delivered only while a world is loaded and a local player exists. Global rule 2 is
     * lifecycle only and does not state this window.
     */
    private static boolean inWorld(Minecraft client) {
        return client.theWorld != null && client.thePlayer != null;
    }

    /**
     * Discards any clicks queued on {@link #walkKey} without feeding them to the core. Called
     * on all three tick-start paths — out of world, faulted, and after a delivered {@code PRE}
     * that may have aborted mid-loop. Deliberately not called from the {@code END} phase:
     * clicks are consumed only in the {@code PRE} handler, so that tick's queue was already
     * dealt with, and draining again would swallow a keypress the user makes between the two
     * halves of a tick.
     */
    private void drainClicks() {
        while (walkKey.isPressed()) {
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
