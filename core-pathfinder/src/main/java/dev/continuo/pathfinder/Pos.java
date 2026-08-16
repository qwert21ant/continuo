package dev.continuo.pathfinder;

/**
 * An immutable block position, and the {@code long} packing the search keys nodes on.
 *
 * <p><b>The packing.</b> X and Z take 26 signed bits each and Y takes 12, which covers
 * &plusmn;33,554,432 horizontally — beyond Minecraft's world border on both versions — and
 * &minus;2048..2047 vertically, comfortably outside 1.7.10's {@code 0..256} and 1.21.11's
 * {@code -64..320}. A single {@code long} key means the open and closed collections are plain
 * maps of primitives rather than maps of objects with a hand-written hash.
 */
public final class Pos {

    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private final int x;
    private final int y;
    private final int z;

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     */
    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @return world X */
    public int x() {
        return x;
    }

    /** @return world Y */
    public int y() {
        return y;
    }

    /** @return world Z */
    public int z() {
        return z;
    }

    /** @return this position packed into a {@code long} */
    public long packed() {
        return pack(x, y, z);
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

    /**
     * @param packed a value from {@link #pack}
     * @return the position it encodes
     */
    public static Pos unpack(long packed) {
        return new Pos(unpackX(packed), unpackY(packed), unpackZ(packed));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pos)) {
            return false;
        }
        Pos other = (Pos) o;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        long packed = packed();
        return (int) (packed ^ (packed >>> 32));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
