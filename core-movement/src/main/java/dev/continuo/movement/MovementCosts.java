package dev.continuo.movement;

/**
 * Movement costs, in ticks.
 *
 * <p>Ticks rather than blocks or seconds because that is what the search must compare: a fall is
 * fast per block and a climb is slow, and only a time unit ranks them against each other.
 *
 * <p><b>Every value here is derived from the decompiled sources of both target versions, and
 * every one carries its citation.</b> This project's standing rule is that Minecraft behaviour is
 * evidenced, never recalled; B1 caught three silent, one-version-only wrong answers that way.
 *
 * <p><b>These numbers are admissible and self-consistent, not validated.</b> Nothing in C1
 * executes a path, so nothing in C1 can show they are realistic. M5 is the first thing that can
 * measure, and is where they should be revisited.
 *
 * <p><b>No per-version seam.</b> This module is pure and shared by both adapters. Where the two
 * versions disagree, the slower figure is taken and the disagreement is noted on the constant.
 * In practice they barely disagree at all: walk speed {@code 0.1}, the {@code +0.3}
 * multiplicative sprint modifier, block friction {@code 0.6}, the {@code 0.91} air factor, the
 * {@code 0.98} input damping, jump velocity {@code 0.42}, gravity {@code 0.08}, vertical drag
 * {@code 0.98} and a safe fall distance of {@code 3.0} are identical in 1.7.10 and 1.21.11. Only
 * the horizontal-acceleration normaliser is spelled differently — {@code 0.16277136F} with the
 * {@code 0.91} folded in against {@code 0.21600002F} with it factored out.
 *
 * <p>That difference must be evaluated in {@code float}, as the sources declare it, not in exact
 * decimal. In {@code float}, {@code 0.6f * 0.91f} rounds <i>up</i> to {@code 0.54600006}, whose
 * cube {@code 0.16277139} exceeds 1.7.10's literal {@code 0.16277136}, so 1.7.10's normaliser
 * comes out at {@code 0.9999998} — fractionally below one. 1.21.11 divides its literal by the
 * cube of {@code 0.6f} alone, which yields exactly {@code 1.0}. <b>1.7.10 therefore accelerates
 * fractionally less and is the slower version</b>, by under {@code 1e-6} ticks per block, walking
 * or sprinting.
 *
 * <p>No sharper figure is quoted, deliberately. The gap is a few units in the last place of a
 * {@code float}, so its leading digits move depending on where one rounds the speed attribute —
 * through the {@code double} pipeline the sources actually use, or straight to the {@code float}
 * literal. Two careful derivations of this task disagreed in exactly that way, at {@code 8.8e-7}
 * against {@code 8.3e-7} sprinting, while agreeing on everything that matters. The bound and the
 * direction are stable under both; the second significant figure is not, so it is not asserted.
 *
 * <p>The constants below take the slower (1.7.10) figure: {@code 3.5636} is the four-decimal
 * rounding of 1.7.10's {@code 3.5635793} and sits above both versions, so no constant needed
 * restating when this direction was corrected.
 *
 * <p><b>Every movement is costed at the sprint figure, not the walk figure.</b> That is a
 * recorded decision, not an oversight. M5's executor sprints wherever it can, so the walk rate
 * would inflate every move by 30% and systematically misrank long straight runs.
 *
 * <p>The heuristic's multiplier is no longer a constant here. It is derived per search, as a
 * minimum over the active movement set — see
 * {@link dev.continuo.movement.ActiveMovements#cheapestAxisStep()}. A static lower bound over a
 * set that is no longer static was C1's most dangerous single line, and keeping it as a second
 * source of truth would be worse than removing it. For the record, the
 * walk figure derives to {@code 4.6327} ticks per block by the same arithmetic.
 *
 * <p>A turn penalty is deliberately <b>omitted</b>. No figure for one exists in either source
 * tree, and the design permits leaving it out rather than inventing it.
 */
public final class MovementCosts {

    /**
     * Ticks to cross one block on the level.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1618,1621,1626,2021},
     * {@code Entity.java:1195,1198,1200}, {@code Block.java:453},
     * {@code PlayerCapabilities.java:20}, {@code EntityLivingBase.java:57},
     * {@code ModifiableAttributeInstance.java:188}.
     * 1.21.11: {@code LivingEntity.java:2338-2339,2571-2572,3007-3009,159-160},
     * {@code Entity.java:1636}, {@code BlockBehaviour.java:983}, {@code Player.java:214,465},
     * {@code AttributeInstance.java:160-161}.
     *
     * <p>Both versions run the same per-tick loop: accelerate by {@code a}, move by the resulting
     * motion, then multiply the motion by the friction factor {@code f}. The move happens before
     * the friction multiply, so the distance covered in a tick settles at {@code a / (1 - f)},
     * not {@code a * f / (1 - f)}. That ordering is worth a factor of 1.8 in the result, so it is
     * cited line by line rather than asserted: 1.7.10 accelerates at
     * {@code EntityLivingBase.java:1633} ({@code moveFlying}), displaces at
     * {@code EntityLivingBase.java:1680} ({@code moveEntity}) and only then applies friction at
     * {@code EntityLivingBase.java:1704}; 1.21.11 accelerates at {@code LivingEntity.java:2528}
     * ({@code moveRelative}), displaces at {@code LivingEntity.java:2530} ({@code move}) and
     * applies friction at {@code LivingEntity.java:2357}.
     *
     * <p>With default block friction {@code 0.6}, {@code f} is
     * {@code 0.6 * 0.91 = 0.546}; the normaliser makes the ground acceleration equal the speed
     * attribute; and the {@code 0.98} input damping scales it. Sprinting multiplies the
     * {@code 0.1} speed attribute by {@code 1.3}, so {@code a = 0.98 * 0.13 = 0.1274} and
     * {@code (1 - 0.546) / 0.1274 = 3.5636} ticks per block, i.e. 5.612 blocks per second.
     *
     * <p>Uses the sprint figure because M5's executor sprints wherever it can, and because it is
     * the smallest per-block figure any movement in this class is built from, which is what makes
     * it a lower bound over these constants.
     */
    public static final double TRAVERSE = 3.5636;

    /**
     * The ticks a jump adds on top of the horizontal crossing.
     *
     * <p>Named rather than folded into {@link #ASCEND} as a literal because more than one
     * movement pays it: any movement that leaves the ground clears a block on the same
     * simulated rise. The derivation and its citations are on {@link #ASCEND}.
     */
    public static final double JUMP_SURCHARGE = 2.9946;

    /**
     * Ticks to cross one block while climbing one.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1557} (jump velocity {@code 0.41999998688697815}),
     * {@code EntityLivingBase.java:1700} (gravity {@code 0.08}),
     * {@code EntityLivingBase.java:1703} (vertical drag {@code 0.98}).
     * 1.21.11: {@code Attributes.java:48-50} (jump strength {@code 0.42F}),
     * {@code LivingEntity.java:2253-2254,2266} (applied), {@code BlockBehaviour.java:985}
     * (default jump factor {@code 1.0}), {@code Attributes.java:45-47} and
     * {@code LivingEntity.java:169,2346} (gravity {@code 0.08}),
     * {@code LivingEntity.java:2356-2357} (vertical drag {@code 0.98}).
     *
     * <p>Simulating the rise — displace by the vertical motion, then apply
     * {@code v = (v - 0.08) * 0.98} — gives displacements {@code 0.42000}, {@code 0.33320},
     * {@code 0.24814} for the first three ticks and cumulative rises {@code 0.42000},
     * {@code 0.75320}, {@code 1.00134}. The block is cleared during tick 3, at
     * {@code 2 + (1 - 0.75320) / 0.24814 = 2.9946} ticks, and that is the surcharge added to the
     * horizontal crossing. (Five decimals are printed because four do not reproduce the quotient:
     * the third tick's {@code 0.2481} would give {@code 2.9948}.)
     *
     * <p>The horizontal block is costed at the sprint figure like a {@link #TRAVERSE}: both
     * versions add a forward impulse when a sprinting entity jumps
     * ({@code EntityLivingBase.java:1564-1568}, {@code LivingEntity.java:2267-2269}), and
     * airborne horizontal motion decays at {@code 0.91} rather than {@code 0.546}, so sprint
     * speed is carried through the hop rather than lost.
     *
     * <p>Adding the surcharge rather than overlapping it is an upper bound — the rise and the
     * horizontal crossing really do happen at the same time. It is taken deliberately: the design
     * requires a climb to cost more than level ground, and only M5 can measure how much more.
     */
    public static final double ASCEND = TRAVERSE + JUMP_SURCHARGE;

    /**
     * Ticks to cross one block diagonally.
     *
     * <p>Declared, not derived: {@link #TRAVERSE} times &radic;2, the geometric ratio.
     */
    public static final double DIAGONAL = TRAVERSE * Math.sqrt(2.0);

    /**
     * Ticks spent falling, indexed by drop in blocks, on top of the step that leaves the ledge.
     * Index {@code n - 1} holds the cost of an {@code n} block drop; see {@link #fallTicks(int)}.
     */
    private static final double[] FALL_TICKS = {4.6147, 6.7881, 8.4687};

    /**
     * The greatest drop, in blocks, taken without damage.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1125},
     * {@code int i = MathHelper.ceiling_float_int(distance - 3.0F - f1);} — {@code f1} is
     * {@code amplifier + 1} of an active Jump Boost effect and zero when there is none
     * ({@code EntityLivingBase.java:1124}), so for an unbuffed player any drop over three blocks
     * hurts.
     * 1.21.11: {@code Attributes.java:73-75}, a {@code safe_fall_distance} attribute defaulting
     * to {@code 3.0}, subtracted at {@code LivingEntity.java:1750-1751} and floored at
     * {@code LivingEntity.java:1747}. That line also scales by {@code FALL_DAMAGE_MULTIPLIER},
     * which defaults to {@code 1.0} ({@code Attributes.java:37-40}) and so does not move the
     * threshold.
     *
     * <p>The two versions round fractional distances differently — {@code ceil(d - 3)} against
     * {@code floor(d + 1e-6 - 3)} — but agree on every whole number of blocks, and every drop the
     * search plans is a whole number of blocks.
     */
    public static final int MAX_SAFE_FALL = 3;

    static {
        if (FALL_TICKS.length != MAX_SAFE_FALL) {
            throw new IllegalStateException("FALL_TICKS covers " + FALL_TICKS.length
                + " depths but MAX_SAFE_FALL is " + MAX_SAFE_FALL
                + "; both are derived, so both must be re-derived together");
        }
    }

    private MovementCosts() {
    }

    /**
     * Ticks spent falling a whole number of blocks, on top of the step that leaves the ledge.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1700} (gravity {@code 0.08}) and
     * {@code EntityLivingBase.java:1703} (vertical drag {@code 0.98}).
     * 1.21.11: {@code Attributes.java:45-47} with {@code LivingEntity.java:169,2346}, and
     * {@code LivingEntity.java:2356-2357}. The vertical motion is a {@code double} in both, and
     * 1.7.10's drag literal {@code 0.9800000190734863} is exactly 1.21.11's {@code 0.98F}
     * widened, so the two versions fall identically.
     *
     * <p>Leaving a ledge the vertical motion is already {@code -0.0784}, because standing on the
     * ground each tick ends with gravity and drag applied to a motion the ground collision
     * zeroed. Simulating from there — displace by {@code v}, <em>then</em> apply
     * {@code v = (v + 0.08) * 0.98} — which is the order the sources run it in
     * ({@code EntityLivingBase.java:1680} calls {@code moveEntity} before the gravity at
     * {@code :1700} and the drag at {@code :1703}), and the order the table below is built on:
     * the first tick displaces by the initial {@code v} of {@code 0.0784}, not by a value already
     * accelerated:
     *
     * <pre>
     * tick   displacement   cumulative drop
     *   1      0.078400        0.078400
     *   2      0.155232        0.233632
     *   3      0.230527        0.464159
     *   4      0.304317        0.768476
     *   5      0.376630        1.145107   &lt;- one block cleared at 4.6147
     *   6      0.447498        1.592605
     *   7      0.516948        2.109553   &lt;- two blocks cleared at 6.7881
     *   8      0.585009        2.694562
     *   9      0.651709        3.346270   &lt;- three blocks cleared at 8.4687
     * </pre>
     *
     * <p>These are the exact per-depth costs, not a per-block rate multiplied out. A fall
     * accelerates, so the marginal cost of a block falls away sharply — 4.6147, then 2.1734, then
     * 1.6806 — and any single per-block constant would misprice the common cases. A one-block
     * drop is the commonest descend in the game, and pricing it at a mean rate would bill it
     * roughly 39% under its real cost, which would make the search prefer dropping to walking
     * around.
     *
     * @param blocks the drop in whole blocks, from 1 to {@link #MAX_SAFE_FALL} inclusive
     * @return the ticks spent falling that far
     * @throws IllegalArgumentException if {@code blocks} is outside 1 to {@link #MAX_SAFE_FALL}
     */
    public static double fallTicks(int blocks) {
        if (blocks < 1 || blocks > MAX_SAFE_FALL) {
            throw new IllegalArgumentException(
                "fall of " + blocks + " blocks is outside 1.." + MAX_SAFE_FALL);
        }
        return FALL_TICKS[blocks - 1];
    }
}
