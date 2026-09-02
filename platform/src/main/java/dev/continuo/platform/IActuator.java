package dev.continuo.platform;

/**
 * The single channel through which the core influences the game.
 *
 * <p>Implemented by adapters. Every effect the core has on the world passes through here,
 * which is what makes the core testable and what will later make input humanization a
 * single seam rather than a cross-cutting concern.
 */
public interface IActuator {

    /**
     * Sets a movement input to pressed or released.
     *
     * <p>Takes effect at the game's next input read, and therefore on the current tick when
     * called from {@link TickPhase#PRE}.
     *
     * <p>Idempotent: setting an already-pressed input to pressed is a no-op from the game's
     * perspective.
     *
     * <p>The effect does not necessarily persist. Global rule 4 in this package's
     * documentation applies: the platform may clear input state at any time without notice —
     * any screen opening does so on both target versions, as does the user physically tapping
     * the key. This is a stated hazard, not an obligation: the SPI does not require an
     * <em>adapter</em> to re-assert a held input. Global rule 4 resolves what the core does about
     * it: while driving, the core re-states its full desired input set every tick, so an adapter
     * is never required to re-assert anything.
     *
     * <p>Adapters MUST support every {@link Input} constant; throwing for a valid constant
     * is a conformance failure. The core MUST NOT pass {@code null}, and adapter behaviour
     * on {@code null} is unspecified.
     *
     * <p><b>Caveat — 1.7.10 has no per-instance setter.</b> "Takes effect at the game's next
     * input read" assumes the adapter can address one binding and set its state. Forge
     * 1.7.10's only public route is the static, keycode-addressed
     * {@code KeyBinding.setKeyBindState(int, boolean)}: it addresses whichever binding
     * currently holds that keycode rather than one the adapter chose, and on a key the user
     * has left unbound it silently does nothing. That route is therefore <b>not conformant</b>.
     * A conformant 1.7.10 adapter reaches the per-instance field through an access transformer
     * or reflection, as Fabric's {@code KeyMapping#setDown} does natively.
     *
     * <p>Because a conformant adapter addresses the binding instance rather than a keycode, an
     * unbound key is not a failure mode on either target: 1.7.10 reads movement through
     * {@code keyBindForward.getIsKeyPressed()} rather than by polling the keyboard, so a
     * directly-set field moves the player whether or not a key is bound to it. This concerns
     * whether the input takes effect at all, not whether it lasts; persistence is global
     * rule 4's subject, above.
     *
     * @param input   which movement input to change; never {@code null}
     * @param pressed {@code true} to press, {@code false} to release
     */
    void setInput(Input input, boolean pressed);

    /**
     * Points the player in a direction.
     *
     * <p>Absolute, not relative: this sets the facing rather than turning by an amount. Deltas
     * compound rounding across ticks and go wrong the moment the server rewrites rotation, which it
     * does — a position correction calls the platform's own set-position-and-rotation path and
     * overwrites yaw and pitch along with the position.
     *
     * <p>Yaw and pitch are combined into one call because both target versions apply rotation as one
     * write, and because a caller changing only its heading passes {@link IPlayerView#pitch()}
     * straight back.
     *
     * <p><b>Conventions are {@link IPlayerView#yaw()}'s and {@link IPlayerView#pitch()}'s</b>, which
     * are facts about both target versions rather than inventions of this SPI: yaw {@code 0} faces
     * {@code +Z}, {@code 90} faces {@code -X}; pitch {@code -90} is straight up and {@code +90}
     * straight down.
     *
     * <p><b>Takes effect at the game's next input read</b>, and therefore on the current tick when
     * called from {@link TickPhase#PRE} — the same timing {@link #setInput} has.
     *
     * <p><b>No round trip is guaranteed.</b> {@link IPlayerView#yaw()} is not required to return what
     * was last passed here: the user's mouse writes rotation too, and so does the server. This is
     * global rule 4's principle applied to rotation rather than to key state.
     *
     * <p>The core MUST pass a {@code pitch} in {@code [-90, 90]}; adapter behaviour outside that
     * range is unspecified, exactly as it is for a {@code null} {@link Input}. <b>The two target
     * versions genuinely differ there, which is why it is unspecified rather than defined.</b>
     * 1.7.10's {@code rotationPitch} is a public field an adapter writes directly, with no clamp on
     * that path, so an out-of-range pitch survives to the renderer and to the server. 1.21.11 has no
     * direct write at all — {@code xRot} is private, and {@code setXRot} stores
     * {@code Math.clamp(f % 360, -90, 90)} — so the same call is silently corrected. An
     * out-of-range pitch is therefore a rendering defect and a loud server-side plausibility signal
     * on one version and invisible on the other, which makes it a core-side bug that only one
     * adapter can ever expose. Do not pass one.
     *
     * <p>{@code yaw} may be any finite value: both versions' movement arithmetic is periodic in it,
     * and neither normalises what an adapter writes. <b>Finite is a real requirement, not a
     * formality</b> — 1.21.11's {@code setYRot} discards a non-finite value and logs it, while
     * 1.7.10 writes it straight to the field, turning the player's motion into {@code NaN}.
     *
     * <p><b>Adapter obligation.</b> An implementation MUST also write the platform's
     * previous-rotation field — {@code prevRotationYaw}/{@code prevRotationPitch} on 1.7.10,
     * {@code yRotO}/{@code xRotO} on 1.21.11 — so the local camera does not visibly interpolate
     * across the change. Both games snapshot the previous rotation once per tick and their own
     * set-rotation paths write it; an adapter that skips it leaves the renderer interpolating from a
     * stale angle. Cosmetic rather than behavioural, and stated as an obligation only so that one
     * adapter cannot quietly do it while the other does not.
     *
     * @param yaw   the heading in degrees; any finite value
     * @param pitch the elevation in degrees; MUST be in {@code [-90, 90]}
     */
    void setLook(float yaw, float pitch);
}
