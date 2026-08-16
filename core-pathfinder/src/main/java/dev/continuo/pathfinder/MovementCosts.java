package dev.continuo.pathfinder;

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
 * {@code 0.91} folded in against {@code 0.21600002F} with it factored out — which puts the two
 * versions 2e-7 ticks per block apart. The constants below take the slower (1.21.11) figure.
 *
 * <p><b>Every movement is costed at the sprint figure, not the walk figure.</b> That is a
 * recorded decision, not an oversight. M5's executor sprints wherever it can, so the walk rate
 * would inflate every move by 30% and systematically misrank long straight runs. More
 * importantly, {@link #cheapestMove()} multiplies the search's heuristic, so it must never exceed
 * a real move's cost; sprinting is the fastest a vanilla player moves on flat ground, which makes
 * the sprint figure the only admissible choice. For the record, the walk figure derives to
 * {@code 4.6327} ticks per block by the same arithmetic.
 *
 * <p>A turn penalty is deliberately <b>omitted</b>. No figure for one exists in either source
 * tree, and the design permits leaving it out rather than inventing it.
 */
public final class MovementCosts {

    /**
     * Ticks to cross one block on the level.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1618,1621,1626,1704,2021},
     * {@code Entity.java:1195,1198,1200}, {@code Block.java:453},
     * {@code PlayerCapabilities.java:20}, {@code EntityLivingBase.java:57},
     * {@code ModifiableAttributeInstance.java:188}.
     * 1.21.11: {@code LivingEntity.java:2338-2339,2357,2527-2529,2571-2572,3007-3009,159-160},
     * {@code Entity.java:1636}, {@code BlockBehaviour.java:983}, {@code Player.java:214,465},
     * {@code AttributeInstance.java:160-161}.
     *
     * <p>Both versions run the same per-tick loop: accelerate by {@code a}, move by the resulting
     * motion, then multiply the motion by the friction factor {@code f}. The move happens before
     * the friction multiply, so the distance covered in a tick settles at {@code a / (1 - f)},
     * not {@code a * f / (1 - f)}. With default block friction {@code 0.6}, {@code f} is
     * {@code 0.6 * 0.91 = 0.546}; the normaliser makes the ground acceleration equal the speed
     * attribute; and the {@code 0.98} input damping scales it. Sprinting multiplies the
     * {@code 0.1} speed attribute by {@code 1.3}, so {@code a = 0.98 * 0.13 = 0.1274} and
     * {@code (1 - 0.546) / 0.1274 = 3.5636} ticks per block, i.e. 5.612 blocks per second.
     *
     * <p>Uses the sprint figure because M5's executor sprints wherever it can, and because
     * {@link #cheapestMove()} must be a true lower bound for A* to stay admissible.
     */
    public static final double TRAVERSE = 3.5636;

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
     * {@code v = (v - 0.08) * 0.98} — gives {@code 0.4200}, {@code 0.3332}, {@code 0.2481} for
     * the first three ticks, a cumulative {@code 1.0013}. One block of altitude is gained
     * {@code 2.9946} ticks after the jump, and that is the surcharge added to the horizontal
     * crossing.
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
    public static final double ASCEND = TRAVERSE + 2.9946;

    /**
     * Ticks to cross one block diagonally.
     *
     * <p>Declared, not derived: {@link #TRAVERSE} times &radic;2, the geometric ratio.
     */
    public static final double DIAGONAL = TRAVERSE * Math.sqrt(2.0);

    /**
     * Ticks spent falling per block of drop, on top of the step that leaves the ledge.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1700} (gravity {@code 0.08}) and
     * {@code EntityLivingBase.java:1703} (vertical drag {@code 0.98}).
     * 1.21.11: {@code Attributes.java:45-47} with {@code LivingEntity.java:169,2346}, and
     * {@code LivingEntity.java:2356-2357}.
     *
     * <p>Leaving a ledge the vertical motion is already {@code -0.0784}, because standing on the
     * ground each tick ends with gravity and drag applied to a zeroed motion. Simulating from
     * there, a drop of one block completes after {@code 4.6147} ticks, two after {@code 6.7881}
     * and three after {@code 8.4687}.
     *
     * <p>A fall accelerates, so no single per-block constant can be exact. This one is the mean
     * over the deepest damage-free drop — {@code 8.4687 / 3} — because {@link #MAX_SAFE_FALL} is
     * exactly the range the descend movement is allowed to use. It is exact at that depth and an
     * approximation above it.
     */
    public static final double FALL_PER_BLOCK = 2.8229;

    /**
     * The greatest drop, in blocks, taken without damage.
     *
     * <p>1.7.10: {@code EntityLivingBase.java:1125},
     * {@code int i = MathHelper.ceiling_float_int(distance - 3.0F - f1);} — {@code f1} is the
     * Jump Boost amplifier, zero for an unbuffed player, so any drop over three blocks hurts.
     * 1.21.11: {@code Attributes.java:73-75}, a {@code safe_fall_distance} attribute defaulting
     * to {@code 3.0}, subtracted at {@code LivingEntity.java:1750-1751} and floored at
     * {@code LivingEntity.java:1747}.
     *
     * <p>The two versions round fractional distances differently — {@code ceil(d - 3)} against
     * {@code floor(d + 1e-6 - 3)} — but agree on every whole number of blocks, and every drop the
     * search plans is a whole number of blocks.
     */
    public static final int MAX_SAFE_FALL = 3;

    private MovementCosts() {
    }

    /**
     * A lower bound on the cost of any single movement.
     *
     * <p>The heuristic multiplies this by a move count, so it must never exceed the true cost of
     * any movement the search can make — that is what keeps A* admissible. When C2 makes the
     * movement set open, this must become a minimum over the active set rather than a constant.
     *
     * @return the cheapest possible single movement, in ticks
     */
    public static double cheapestMove() {
        return Math.min(Math.min(TRAVERSE, ASCEND), Math.min(DIAGONAL, TRAVERSE + FALL_PER_BLOCK));
    }
}
