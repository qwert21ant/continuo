package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

/**
 * Turns block facts into movement facts. The only place in the pathfinder that reads a
 * {@link BlockShape}, a {@link Fluid} or a collision top.
 *
 * <p><b>These are measurements, not category switches, and that is deliberate.</b> An earlier
 * draft decided support with a shape test over {@code FULL}, {@code SLAB_TOP} and {@code STAIR}.
 * That is wrong in two ways at once. {@link BlockShape#PARTIAL} is a catch-all covering
 * unrecognised modded geometry, so a category test refuses to walk on anything it does not
 * recognise. And farmland is classified {@code FULL} on 1.7.10 but {@code PARTIAL} with a
 * collision top of {@code 0.9375} on 1.21.11 — so a category test would walk across a legacy
 * farm and refuse a modern one. Reading the number makes both versions agree, which is what
 * {@link BlockShape}'s own documentation directs for code that needs a real measurement.
 *
 * <p>Shape is still consulted where the number cannot answer: {@link BlockShape#UNKNOWN} has a
 * collision top of {@code 0} that is indistinguishable from air while meaning "unreadable,
 * might be solid", and {@link BlockShape#FENCE} means "cannot be walked over" regardless of
 * geometry.
 */
public final class Standability {

    /**
     * The highest collision top a block may have and still be entered.
     *
     * <p>Set at the {@link BlockShape#THIN_LAYER} ceiling, so carpets and snow layers are walked
     * through rather than around. The player's feet then rest up to a quarter of a block above
     * where the search assumes; Minecraft's own step-up absorbs that.
     */
    public static final double PASSABLE_MAX_TOP = 0.25;

    /** The lowest collision top that counts as a floor. Admits farmland's {@code 0.9375}. */
    public static final double SUPPORT_MIN_TOP = 0.9;

    /**
     * The highest collision top that counts as a floor.
     *
     * <p>Anything above a full cube is a fence or a wall by B1's classification rule, which is
     * why this bound and the {@link BlockShape#FENCE} exclusion in {@link #supports} agree
     * today. Both are kept: this bound makes the predicate a self-contained measurement, and
     * the shape check preserves the behavioural intent if that rule ever changes.
     */
    public static final double SUPPORT_MAX_TOP = 1.0;

    private Standability() {
    }

    /**
     * Whether the player's body can occupy this block.
     *
     * @param block the block; never {@code null}
     * @return whether it can be moved into
     */
    public static boolean passable(BlockData block) {
        if (block.shape() == BlockShape.UNKNOWN) {
            return false;
        }
        if (block.fluid() != Fluid.NONE) {
            return false;
        }
        if (block.tags().contains(BlockTag.AVOID)) {
            return false;
        }
        return block.collisionTop() <= PASSABLE_MAX_TOP;
    }

    /**
     * Whether this block is a floor the player can stand on top of.
     *
     * @param block the block; never {@code null}
     * @return whether it supports standing
     */
    public static boolean supports(BlockData block) {
        if (block.shape() == BlockShape.UNKNOWN || block.shape() == BlockShape.FENCE) {
            return false;
        }
        if (block.fluid() != Fluid.NONE) {
            return false;
        }
        if (block.tags().contains(BlockTag.AVOID)) {
            return false;
        }
        double top = block.collisionTop();
        return top >= SUPPORT_MIN_TOP && top <= SUPPORT_MAX_TOP;
    }

    /**
     * Whether the player can stand with their feet in this block.
     *
     * @param world the world to read; never {@code null}
     * @param x feet X
     * @param y feet Y
     * @param z feet Z
     * @return whether standing here is possible
     */
    public static boolean standable(BlockSource world, int x, int y, int z) {
        return passable(world.at(x, y, z))
            && passable(world.at(x, y + 1, z))
            && supports(world.at(x, y - 1, z));
    }
}
