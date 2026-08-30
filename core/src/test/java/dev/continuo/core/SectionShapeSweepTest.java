package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table behind {@code SectionStore}'s 4x4x4 shape, in the shape of C4's MinProgressSweepTest:
 * a committed calibration rather than a paragraph.
 *
 * <p>This asserts <b>occupancy</b>, not speed. A wall-clock assertion in the suite would be flaky
 * on CI and on a loaded machine, and the design's §3.2 already records that absolute times from a
 * benchmark like this are not comparable between runs — its at() call site goes megamorphic where
 * production's does not. Occupancy is exact, deterministic, and is the axis on which 4x4x4 was
 * chosen over Baritone's 16x16x16.
 */
class SectionShapeSweepTest {

    private static final BlockData STONE = new BlockData(
        BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** A store whose section shape is a parameter, for the sweep only. */
    private static final class ShapedStore {

        private final Map<Long, Object> sections = new HashMap<Long, Object>();
        private final int bits;
        private final int sectionSize;
        private int size;

        ShapedStore(int bits) {
            this.bits = bits;
            this.sectionSize = 1 << (bits * 3);
        }

        void put(int x, int y, int z) {
            Long key = Long.valueOf(PositionKey.pack(x >> bits, y >> bits, z >> bits));
            boolean[] section = (boolean[]) sections.get(key);
            if (section == null) {
                section = new boolean[sectionSize];
                sections.put(key, section);
            }
            int mask = (1 << bits) - 1;
            int at = ((y & mask) << (bits * 2)) | ((z & mask) << bits) | (x & mask);
            if (!section[at]) {
                size++;
            }
            section[at] = true;
        }

        long slots() {
            return (long) sections.size() * sectionSize;
        }

        double occupancy() {
            return 100.0 * size / slots();
        }
    }

    /**
     * The positions a search actually touches, in the shape real terrain produces: a thin
     * horizontal band a few blocks tall over a wide area, which is what makes a tall section waste
     * so much. Deterministic, so the table is reproducible.
     */
    private static List<int[]> corridor() {
        List<int[]> positions = new ArrayList<int[]>();
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                for (int y = 62; y <= 66; y++) {
                    positions.add(new int[] {x, y, z});
                }
            }
        }
        return positions;
    }

    @Test
    void fourIsTheShapeThatKeepsMostOfTheSpeedForLeastOfTheWaste() {
        List<int[]> positions = corridor();

        ShapedStore four = new ShapedStore(2);
        ShapedStore eight = new ShapedStore(3);
        ShapedStore sixteen = new ShapedStore(4);
        for (int i = 0; i < positions.size(); i++) {
            int[] p = positions.get(i);
            four.put(p[0], p[1], p[2]);
            eight.put(p[0], p[1], p[2]);
            sixteen.put(p[0], p[1], p[2]);
        }

        // The finding, as an assertion rather than a comment: a bigger section wastes more, and
        // Baritone's 16-cube wastes most. Baritone does not pay this because it reads Minecraft's
        // already-allocated chunks and stores nothing per block; we allocate our own copy.
        assertTrue(four.occupancy() > eight.occupancy(),
            "4x4x4 " + four.occupancy() + "% vs 8x8x8 " + eight.occupancy() + "%");
        assertTrue(eight.occupancy() > sixteen.occupancy(),
            "8x8x8 " + eight.occupancy() + "% vs 16x16x16 " + sixteen.occupancy() + "%");
        assertTrue(sixteen.slots() > four.slots() * 3,
            "the 16-cube must allocate several times what the 4-cube does, or the trade this"
                + " constant was chosen on has changed; 4x4x4 " + four.slots()
                + " vs 16x16x16 " + sixteen.slots());
    }

    @Test
    void theShippedStoreUsesTheSweptShape() {
        // Pins the constant to the sweep. SectionStore's BITS is private, so this asserts it
        // through the only thing that observes it: one position allocates exactly 64 slots.
        SectionStore store = new SectionStore();
        store.put(0, 0, 0, STONE);

        assertEquals(64, store.slots(),
            "SectionStore must be 4x4x4, the shape the sweep above picks. Changing BITS without"
                + " re-running that sweep is what this assertion exists to stop");
    }
}
