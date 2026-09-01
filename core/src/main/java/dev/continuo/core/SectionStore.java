package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Block storage for a snapshot: 4&times;4&times;4 sections, with the last one touched held in a
 * field.
 *
 * <p><b>Why not a map keyed by position.</b> That is what this replaces, and it cost a boxed
 * {@code Long} allocation and a hash lookup on every read — 1.84 million of them in one measured
 * in-game search, which was most of that search's time. A search reads with great locality: the
 * positions a single movement inspects are almost always within a few blocks of each other. So a
 * read here is a section-key compare that usually hits, then an array index.
 *
 * <p><b>Why 4&times;4&times;4 and not Minecraft's 16&times;16&times;16.</b> Measured over the
 * design's §3.2 sweep: the 16-cube is the fastest shape and allocates 32.6&times; the references it
 * stores, because a search touches a section sparsely and a section is allocated whole. 4&times;4
 * &times;4 keeps 35 of the 41 percentage points for a tenth of the waste, at 30% occupancy rather
 * than 3%.
 *
 * <p><b>A {@code null} slot means never read.</b> A snapshot stores {@link BlockData#UNKNOWN} — a
 * real object — for a position it read and could not answer for, so {@code null} and
 * {@code UNKNOWN} stay distinguishable and {@link #has} can tell them apart. That distinction is
 * what {@code SealedSnapshot.covers} is built on.
 *
 * <p><b>The memo makes this stateful, so an instance belongs to one reader.</b> Two threads reading
 * one store would race on the memo fields and hand each other another position's block, with no
 * exception to notice. Nothing in this project reads a store from more than one thread; this note
 * is what keeps that true.
 */
final class SectionStore {

    /** Bits of each coordinate that index within a section: 2 gives a 4&times;4&times;4 section. */
    private static final int BITS = 2;
    private static final int MASK = (1 << BITS) - 1;
    private static final int SECTION_SIZE = 1 << (BITS * 3);

    private final Map<Long, BlockData[]> sections = new HashMap<Long, BlockData[]>();

    private int size;

    /**
     * The last section looked up, and its key. {@code null} means no lookup has happened yet, which
     * is why the key alone cannot be the guard: key 0 is a real section.
     */
    private BlockData[] memoSection;
    private long memoKey;
    private boolean memoValid;

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block stored, or {@link BlockData#UNKNOWN} if nothing was stored
     */
    BlockData get(int x, int y, int z) {
        BlockData[] section = section(x, y, z);
        if (section == null) {
            return BlockData.UNKNOWN;
        }
        BlockData held = section[offset(x, y, z)];
        return held == null ? BlockData.UNKNOWN : held;
    }

    /**
     * The stored block, distinguishing "stored" from "never read" in one lookup.
     *
     * <p>{@link #get} and {@link #has} answer the same question in two calls, each recomputing the
     * section key and consulting the memo. On the hot path that redundancy measured at 6 to 13% of
     * read time, which is several milliseconds over the 1.84 million reads one in-game search
     * makes, so the one caller that needs both answers takes them together.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the stored block, or {@code null} if nothing was stored here. A stored
     *         {@link BlockData#UNKNOWN} comes back as itself, not as {@code null} — that
     *         distinction is what {@code SealedSnapshot.covers} rests on
     */
    BlockData getOrNull(int x, int y, int z) {
        BlockData[] section = section(x, y, z);
        return section == null ? null : section[offset(x, y, z)];
    }

    /**
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return whether a value was stored here, which {@link BlockData#UNKNOWN} being a real stored
     *         value does not make true on its own
     */
    boolean has(int x, int y, int z) {
        BlockData[] section = section(x, y, z);
        return section != null && section[offset(x, y, z)] != null;
    }

    /**
     * Stores a block, replacing anything already there.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @param value the block; never {@code null}, because {@code null} is this class's "never read"
     */
    void put(int x, int y, int z, BlockData value) {
        long key = sectionKey(x, y, z);
        BlockData[] section;
        if (memoValid && memoKey == key) {
            section = memoSection;
        } else {
            section = sections.get(Long.valueOf(key));
            memoKey = key;
            memoSection = section;
            memoValid = true;
        }
        if (section == null) {
            section = new BlockData[SECTION_SIZE];
            sections.put(Long.valueOf(key), section);
            // The memo was just set to the absent section; point it at the real one, or the next
            // read of this same section returns UNKNOWN for everything in it.
            memoSection = section;
        }
        int at = offset(x, y, z);
        if (section[at] == null) {
            size++;
        }
        section[at] = value;
    }

    /** @return how many positions hold a value */
    int size() {
        return size;
    }

    /** @return how many array slots are allocated, which is {@link #size} plus the waste */
    long slots() {
        return (long) sections.size() * SECTION_SIZE;
    }

    private BlockData[] section(int x, int y, int z) {
        long key = sectionKey(x, y, z);
        if (memoValid && memoKey == key) {
            return memoSection;
        }
        BlockData[] found = sections.get(Long.valueOf(key));
        memoKey = key;
        memoSection = found;
        memoValid = true;
        return found;
    }

    private static long sectionKey(int x, int y, int z) {
        return PositionKey.pack(x >> BITS, y >> BITS, z >> BITS);
    }

    private static int offset(int x, int y, int z) {
        return ((y & MASK) << (BITS * 2)) | ((z & MASK) << BITS) | (x & MASK);
    }
}
