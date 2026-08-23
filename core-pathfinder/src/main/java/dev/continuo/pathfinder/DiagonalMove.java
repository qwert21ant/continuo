package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;
import dev.continuo.movement.Capability;
import dev.continuo.movement.ExpansionContext;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.Standability;

import java.util.EnumSet;
import java.util.Set;

/**
 * Walking one block diagonally on the level.
 *
 * <p><b>Both orthogonal sides of the corner must be clear, at feet and at head height.</b>
 * Minecraft does not let a player squeeze between two blocks meeting at a corner, and a search
 * that allows it produces paths that look shorter and cannot be walked. Checking only the
 * destination is the classic version of this bug.
 */
final class DiagonalMove implements IMovementType {

    /** North-east, south-east, south-west, north-west as {@code {dx, dz}}. */
    private static final int[][] DIAGONALS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    @Override
    public String id() {
        return "walk.diagonal";
    }

    @Override
    public Set<Capability> requires() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public double minCostPerAxisStep() {
        return MovementCosts.DIAGONAL;
    }

    @Override
    public void expand(ExpansionContext ctx, MoveSink sink) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        for (int i = 0; i < DIAGONALS.length; i++) {
            int nx = x + DIAGONALS[i][0];
            int nz = z + DIAGONALS[i][1];

            if (!Standability.standable(ctx.world(), nx, y, nz)) {
                continue;
            }
            if (!clear(ctx.world(), nx, y, z) || !clear(ctx.world(), x, y, nz)) {
                continue;
            }
            sink.offer(nx, y, nz, MovementCosts.DIAGONAL);
        }
    }

    /** Whether a two-block-tall body fits at this column, feet at {@code y}. */
    private static boolean clear(BlockSource world, int x, int y, int z) {
        return Standability.passable(world.at(x, y, z))
            && Standability.passable(world.at(x, y + 1, z));
    }
}
