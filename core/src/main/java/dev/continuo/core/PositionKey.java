package dev.continuo.core;

/**
 * The {@code long} that identifies a block position.
 *
 * <p><b>The packing.</b> X and Z take 26 signed bits each and Y takes 12, which covers
 * &plusmn;33,554,432 horizontally — beyond Minecraft's world border on both versions — and
 * &minus;2048..2047 vertically, comfortably outside 1.7.10's {@code 0..256} and 1.21.11's
 * {@code -64..320}. A single {@code long} gives each position one identity that a map can key on
 * directly, with no hand-written {@code hashCode} or {@code equals} over a composite key to get
 * wrong.
 *
 * <p><b>Why this lives in {@code :core} rather than beside the search.</b> Two consumers need it:
 * {@code dev.continuo.pathfinder.Pos}, which keys the search's node maps, and
 * {@code WorldSnapshot}, which keys its cache. {@code :core-pathfinder} depends on {@code :core}
 * and not the other way round, so the shared definition has to sit here. Two independent copies
 * of a bit layout is a silent aliasing bug waiting for someone to change one of them.
 */
public final class PositionKey {

    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private PositionKey() {
    }

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the three coordinates packed into one {@code long}
     */
    public static long pack(int x, int y, int z) {
        return ((long) x & XZ_MASK) << 38
            | ((long) z & XZ_MASK) << 12
            | ((long) y & Y_MASK);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the X coordinate, sign restored
     */
    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Y coordinate, sign restored
     */
    public static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Z coordinate, sign restored
     */
    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
