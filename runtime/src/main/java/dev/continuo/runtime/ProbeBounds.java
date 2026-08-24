package dev.continuo.runtime;

import dev.continuo.core.BlockSource;
import dev.continuo.pathfinder.Pos;

import java.util.List;

/**
 * The region a probe run draws.
 *
 * <p>The box covers the start, the goal and every position on the returned route, padded so the
 * terrain immediately around them is visible, then clamped so a distant goal cannot ask for an
 * enormous file. A map is one character per position per Y layer, so an unclamped box a few
 * hundred blocks on a side is hundreds of megabytes.
 *
 * <p><b>{@link #clamped} means the reader is missing something, and only that.</b> Reducing an
 * axis to {@link #MAX_EXTENT} throws terrain away, so the output has to say so — a silently
 * truncated map looks like a search that stopped for no reason, and there is nothing in it to
 * tell the two apart. Clamping to the world's own Y limits is not the same thing and does not
 * set the flag: there is no terrain outside them to lose.
 */
final class ProbeBounds {

    /** Blocks of terrain drawn around the region of interest. */
    static final int PADDING = 2;

    /** The most blocks any one axis may span. */
    static final int MAX_EXTENT = 64;

    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;

    /** Whether an axis was reduced to {@link #MAX_EXTENT}, throwing terrain away. */
    final boolean clamped;

    private ProbeBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        boolean clamped) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.clamped = clamped;
    }

    /**
     * @param world the world being drawn, for its Y limits; never {@code null}
     * @param start where the search began; never {@code null}
     * @param goal what it was trying to reach; never {@code null}
     * @param path the returned route, empty for a failed search; never {@code null}
     * @return the region to draw
     */
    static ProbeBounds around(BlockSource world, Pos start, Pos goal, List<Pos> path) {
        if (world == null || start == null || goal == null || path == null) {
            throw new IllegalArgumentException("no argument may be null");
        }

        int minX = Math.min(start.x(), goal.x());
        int maxX = Math.max(start.x(), goal.x());
        int minY = Math.min(start.y(), goal.y());
        int maxY = Math.max(start.y(), goal.y());
        int minZ = Math.min(start.z(), goal.z());
        int maxZ = Math.max(start.z(), goal.z());

        for (int i = 0; i < path.size(); i++) {
            Pos pos = path.get(i);
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minY = Math.min(minY, pos.y());
            maxY = Math.max(maxY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
        }

        minX -= PADDING;
        maxX += PADDING;
        minY -= PADDING;
        maxY += PADDING;
        minZ -= PADDING;
        maxZ += PADDING;

        boolean clamped = false;
        int[] x = clampAxis(minX, maxX);
        int[] y = clampAxis(minY, maxY);
        int[] z = clampAxis(minZ, maxZ);
        clamped = x[2] == 1 || y[2] == 1 || z[2] == 1;

        // The world's own limits come last, so they cannot be undone by the extent clamp. maxY()
        // is one past the top and this box's maxY is inclusive, hence the subtraction.
        int lowY = Math.max(y[0], world.minY());
        int highY = Math.min(y[1], world.maxY() - 1);
        if (highY < lowY) {
            highY = lowY;
        }

        return new ProbeBounds(x[0], lowY, z[0], x[1], highY, z[1], clamped);
    }

    /**
     * @return {@code {min, max, wasClamped}}, keeping the centre of the original span
     */
    private static int[] clampAxis(int min, int max) {
        int span = max - min + 1;
        if (span <= MAX_EXTENT) {
            return new int[] {min, max, 0};
        }
        int centre = min + span / 2;
        int half = MAX_EXTENT / 2;
        int newMin = centre - half;
        return new int[] {newMin, newMin + MAX_EXTENT - 1, 1};
    }
}
