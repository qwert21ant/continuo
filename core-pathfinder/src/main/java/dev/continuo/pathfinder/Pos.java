package dev.continuo.pathfinder;

import dev.continuo.core.PositionKey;

/**
 * An immutable block position, and the {@code long} packing the search keys nodes on.
 *
 * <p><b>The packing is {@link PositionKey}'s</b>, which lives in {@code :core} because
 * {@code WorldSnapshot} keys its cache the same way and cannot reach this module. The methods
 * here delegate and exist so the search reads naturally; the bit layout and its ranges are
 * documented on {@code PositionKey}. The maps are {@code HashMap<Long, PathNode>}, so the keys
 * are still boxed; a primitive map would be a C4 concern, not a claim this packing already makes
 * good on.
 */
public final class Pos {

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
        return PositionKey.pack(x, y, z);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the X coordinate, sign restored
     */
    public static int unpackX(long packed) {
        return PositionKey.unpackX(packed);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Y coordinate, sign restored
     */
    public static int unpackY(long packed) {
        return PositionKey.unpackY(packed);
    }

    /**
     * @param packed a value from {@link #pack}
     * @return the Z coordinate, sign restored
     */
    public static int unpackZ(long packed) {
        return PositionKey.unpackZ(packed);
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
