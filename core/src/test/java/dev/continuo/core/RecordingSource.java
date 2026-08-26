package dev.continuo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BlockSource} for tests: programmable per position, and counting every call.
 *
 * <p>Counting <em>per position</em> rather than in total is what lets a test assert that a
 * snapshot read the world exactly once for each position it holds — the claim the whole design
 * rests on. A total-only counter would pass on a snapshot that read one position twice and
 * another never.
 */
final class RecordingSource implements BlockSource {

    private final Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
    private final Map<Long, Integer> callsPerPosition = new HashMap<Long, Integer>();
    private int calls;
    private boolean refusing;

    /** Puts a block at a position. Anything not put reads as {@link BlockData#UNKNOWN}. */
    void put(int x, int y, int z, BlockData data) {
        blocks.put(Long.valueOf(PositionKey.pack(x, y, z)), data);
    }

    /** Makes every later read throw, so a test can prove nothing touched this source. */
    void refuseFurtherReads() {
        refusing = true;
    }

    /** @return how many times {@link #at} has been called in total */
    int calls() {
        return calls;
    }

    /** @return how many times {@link #at} has been called for one position */
    int callsAt(int x, int y, int z) {
        Integer n = callsPerPosition.get(Long.valueOf(PositionKey.pack(x, y, z)));
        return n == null ? 0 : n.intValue();
    }

    @Override
    public BlockData at(int x, int y, int z) {
        if (refusing) {
            throw new AssertionError(
                "the live source was read at (" + x + ", " + y + ", " + z + ")");
        }
        calls++;
        Long key = Long.valueOf(PositionKey.pack(x, y, z));
        Integer seen = callsPerPosition.get(key);
        callsPerPosition.put(key, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        BlockData held = blocks.get(key);
        return held == null ? BlockData.UNKNOWN : held;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 320;
    }
}
