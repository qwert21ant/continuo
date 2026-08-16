package dev.continuo.pathfinder;

import dev.continuo.core.BlockSource;

/** Walking one block to a cardinal neighbour at the same height. */
final class TraverseMove implements Move {

    @Override
    public void expand(BlockSource world, int x, int y, int z, MoveSink sink) {
        for (int i = 0; i < CARDINALS.length; i++) {
            int nx = x + CARDINALS[i][0];
            int nz = z + CARDINALS[i][1];
            if (Standability.standable(world, nx, y, nz)) {
                sink.offer(nx, y, nz, MovementCosts.TRAVERSE);
            }
        }
    }
}
