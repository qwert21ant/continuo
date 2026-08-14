package dev.continuo.runtime;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockData;
import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;

import java.util.HashMap;
import java.util.Map;

/**
 * Produces the cross-adapter parity dump.
 *
 * <p>The conformance suite cannot see an adapter's block view — asserting it needs a live world,
 * which is the same structural reason adapters have no automated tests at all. This walker is
 * the substitute: an owner runs it once per version against the same fixture structure, the two
 * outputs are checked in, and a headless test diffs them.
 *
 * <p>Dev-only. Nothing calls it during normal operation.
 *
 * <p>Each line carries the raw {@code id} and {@code stateKey} alongside the classified result,
 * so a mismatch can be read as either "the classifier decided differently" or "the wrong block
 * was placed" without a second run.
 *
 * <p><b>Why this keeps its own memo instead of using {@code BlockLookup}.</b> A dump line needs
 * both the classified {@link BlockData} and the raw {@link BlockDescription}'s {@code id()} and
 * {@code stateKey()} — {@code BlockLookup} only ever hands back the former. Pairing it with a
 * second, direct {@code view.describe(...)} call per line would describe every distinct state
 * twice: once inside {@code BlockLookup} on the first sighting, and once again here for the
 * text. This class instead calls {@link IBlockView#describe} at most once per distinct state id,
 * storing both the description and the classification together, so a state seen at many
 * positions is described exactly once.
 */
public final class BlockDumpWalker {

    private BlockDumpWalker() {
    }

    /**
     * Walks a region and renders it, one line per position.
     *
     * <p>Positions are visited X fastest, then Z, then Y, and numbered from zero in that order.
     * All bounds are inclusive.
     *
     * @param view the live reader
     * @param classifier the shared classifier, built with the version's table
     * @param minX region start X
     * @param minY region start Y
     * @param minZ region start Z
     * @param maxX region end X, inclusive
     * @param maxY region end Y, inclusive
     * @param maxZ region end Z, inclusive
     * @return the dump; lines separated by {@code \n}, with no trailing newline
     */
    public static String dump(IBlockView view,
                              BlockClassifier classifier,
                              int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
        Map<Integer, Entry> byStateId = new HashMap<Integer, Entry>();
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (index > 0) {
                        out.append('\n');
                    }
                    out.append(index).append('\t').append(line(view, classifier, byStateId, x, y, z));
                    index++;
                }
            }
        }
        return out.toString();
    }

    private static String line(IBlockView view,
                               BlockClassifier classifier,
                               Map<Integer, Entry> byStateId,
                               int x, int y, int z) {
        int stateId = view.stateId(x, y, z);
        if (stateId == -1) {
            return "-\t-\t" + BlockData.UNKNOWN;
        }
        Integer key = Integer.valueOf(stateId);
        Entry entry = byStateId.get(key);
        if (entry == null) {
            BlockDescription description = view.describe(x, y, z);
            entry = new Entry(description, classifier.classify(description));
            byStateId.put(key, entry);
        }
        return entry.description.id() + '\t' + entry.description.stateKey() + '\t' + entry.data;
    }

    /** One distinct state's description paired with its classification. */
    private static final class Entry {
        private final BlockDescription description;
        private final BlockData data;

        private Entry(BlockDescription description, BlockData data) {
            this.description = description;
            this.data = data;
        }
    }
}
