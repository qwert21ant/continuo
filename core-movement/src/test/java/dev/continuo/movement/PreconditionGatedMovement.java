package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement that offers only when the block it stands in matches one chosen precondition, and
 * over-declares fourfold whenever it does.
 *
 * <p>Two jobs, and they are the same experiment run at different gates. Over a gate
 * {@link MovementContract}'s palette can produce, the audit reaches the movement and reports the
 * cost lie — which is what witnesses that the palette really does generate that kind of block, not
 * merely that it lists it. Over a gate the palette cannot produce, the audit reaches nothing and
 * must say so; before the no-offer branch existed it returned an empty list, which every caller
 * reads as a pass.
 *
 * <p>The gates are the preconditions the movements named as next in line read: a fence and an
 * unreadable block for anything that has to refuse terrain, water for swimming, a harmful block for
 * hazard avoidance, and {@link BlockTag#CLIMBABLE} for ladders — the last of which the palette
 * deliberately still cannot produce, so it stays the standing witness for the vacuous case.
 */
final class PreconditionGatedMovement implements IMovementType {

    /** What the origin block must be for this movement to offer anything. */
    enum Gate {
        /** Collision above the cube. */
        FENCE,
        /** Unreadable terrain. */
        UNKNOWN,
        /** Carries {@link BlockTag#AVOID}. */
        HARMFUL,
        /** Occupied by {@link Fluid#WATER}. */
        WATER,
        /** Carries {@link BlockTag#CLIMBABLE}, which no palette block does. */
        CLIMBABLE
    }

    /**
     * Declared per axis step, then charged for a four-step offer — a real 1.2 per step, wrong by
     * four. Exactly the mistake {@code aWideMovementDeclaringItsWholeCostRatherThanItsPerStepCost}
     * exercises, so a gate the audit can reach produces a recognisable cost violation rather than
     * a novel one.
     */
    private static final double DECLARED = 4.8;

    private static final int SPAN = 4;

    private final String id;
    private final Gate gate;

    PreconditionGatedMovement(String id, Gate gate) {
        this.id = id;
        this.gate = gate;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return DECLARED;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        if (!matches(ctx.world().at(ctx.x(), ctx.y(), ctx.z()))) {
            return;
        }
        sink.offer(ctx.x() + SPAN, ctx.y(), ctx.z(), DECLARED);
    }

    private boolean matches(BlockData block) {
        switch (gate) {
            case FENCE:
                return block.shape() == BlockShape.FENCE;
            case UNKNOWN:
                return block.shape() == BlockShape.UNKNOWN;
            case HARMFUL:
                return block.has(BlockTag.AVOID);
            case WATER:
                return block.fluid() == Fluid.WATER;
            case CLIMBABLE:
                return block.has(BlockTag.CLIMBABLE);
            default:
                throw new IllegalStateException("unhandled gate " + gate);
        }
    }
}
