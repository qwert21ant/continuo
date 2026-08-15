package dev.continuo.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-adapter parity: both adapters must classify the same fixture world identically.
 *
 * <p>The real dumps are produced by a human running each client, so the cases that read them are
 * skipped until those files exist. The mechanism itself is proved against synthetic dumps, which
 * run always.
 *
 * <p><b>Two goldens, not one.</b> A single golden asserted against both versions cannot exist:
 * carpet (index 20) and farmland (index 22) genuinely behave differently between 1.7.10 and
 * 1.21.11 (see spec §4 and §5.2), so no one file can match both dumps. Instead there is one golden
 * per version, {@code golden-1.7.10.txt} and {@code golden-1.21.11.txt}, each mirroring its own
 * dump file one-for-one. The golden comparison checks <em>every</em> index on each side — nothing
 * is skipped there, which is what pins each version's own answer, including at the divergent and
 * version-exclusive indices, and what still catches "both versions broke in the same way".
 *
 * <p><b>The cross-version comparison skips five indices.</b> {@link #theTwoAdaptersAgreeOnTheFixtureWorld}
 * compares the two dumps to each other and excludes {@link #EXCLUDED_FROM_CROSS_VERSION_DIFF} — two
 * divergent blocks where the games genuinely differ, and three blocks that exist only on 1.21.11.
 * Without the skip, a correct run on the real client dumps would fail at those five indices for
 * reasons that are correct behaviour, not a bug.
 */
class BlockParityTest {

    /**
     * The indices excluded from the cross-version diff, because the two games' physics genuinely
     * differ (carpet, farmland — see spec §4's "Two genuine cross-version divergences") or because
     * the block exists only on 1.21.11 (magma_block, honey_block, soul_fire).
     *
     * <p>Authority: {@code docs/superpowers/specs/2026-08-14-b1-block-model-design.md} §5.2's
     * fixture table, the {@code divergent} and {@code exclusive} rows. The same five indices, with
     * the same reasons, are recorded in {@code docs/parity/fixture-layout.md} — if the two ever
     * drift apart, the spec governs and this file is wrong.
     */
    private static final Set<Integer> EXCLUDED_FROM_CROSS_VERSION_DIFF = Collections.unmodifiableSet(
        new HashSet<Integer>(Arrays.asList(20, 22, 25, 26, 30)));

    /** One dump line: index, native id, native state key, and the classified data as text. */
    private static final class Entry {
        final int index;
        final String id;
        final String stateKey;
        final String data;

        Entry(int index, String id, String stateKey, String data) {
            this.index = index;
            this.id = id;
            this.stateKey = stateKey;
            this.data = data;
        }
    }

    private static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<Entry>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                    "dump line " + (i + 1) + " has " + parts.length + " fields, expected 4: " + line);
            }
            entries.add(new Entry(Integer.parseInt(parts[0]), parts[1], parts[2], parts[3]));
        }
        return entries;
    }

    private static String resource(String name) {
        InputStream in = BlockParityTest.class.getResourceAsStream("/parity/" + name);
        assertTrue(in != null, "missing test resource /parity/" + name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Compares two dumps on the classified data only, at every index. Native ids and state keys
     * legitimately differ between versions and are never compared — only reported when something
     * else does.
     */
    private static void assertParity(String leftName, String left, String rightName, String right) {
        assertParityExcluding(leftName, left, rightName, right, Collections.<Integer>emptySet());
    }

    /**
     * Compares two dumps on the classified data only, skipping any index in {@code excludedIndices}
     * entirely (not read, not asserted). Used by the cross-version comparison, which must not
     * assert equality at indices the two games are known to disagree on or that exist on only one
     * side.
     */
    private static void assertParityExcluding(
            String leftName, String left, String rightName, String right, Set<Integer> excludedIndices) {
        List<Entry> a = parse(left);
        List<Entry> b = parse(right);
        assertEquals(a.size(), b.size(), leftName + " and " + rightName + " have different lengths");
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i);
            Entry y = b.get(i);
            assertEquals(x.index, y.index, "index mismatch at line " + (i + 1));
            if (excludedIndices.contains(x.index)) {
                continue;
            }
            assertEquals(x.data, y.data,
                "index " + x.index + " differs: " + leftName + " has " + x.id + " (" + x.stateKey + ") -> " + x.data
                    + "; " + rightName + " has " + y.id + " (" + y.stateKey + ") -> " + y.data);
        }
    }

    private static Path dump(String name) {
        return Paths.get("..", "docs", "parity", name);
    }

    /**
     * Gates the two {@code @EnabledIf} tests below. All four files it checks are produced by a
     * human running a client (Task 17); until then, this returns false and those tests report as
     * skipped rather than silently passing on absent data.
     */
    static boolean realDumpsExist() {
        return Files.exists(dump("blocks-1.7.10.txt"))
            && Files.exists(dump("blocks-1.21.11.txt"))
            && Files.exists(dump("golden-1.7.10.txt"))
            && Files.exists(dump("golden-1.21.11.txt"));
    }

    private static String readDump(String name) {
        try {
            return new String(Files.readAllBytes(dump(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + dump(name), e);
        }
    }

    @Test
    void identicalClassificationsAgreeEvenWhenNativeNamesDiffer() {
        assertParity("sample-a", resource("sample-a.txt"), "sample-b", resource("sample-b.txt"));
    }

    @Test
    void aDifferingClassificationIsADisagreement() {
        AssertionError e = assertThrows(AssertionError.class, () ->
            assertParity("sample-a", resource("sample-a.txt"),
                "sample-mismatch", resource("sample-mismatch.txt")));
        assertTrue(e.getMessage().contains("index 1"), e.getMessage());
        assertTrue(e.getMessage().contains("minecraft:oak_fence"),
            "the failure must name the native blocks so it can be diagnosed without a rerun");
    }

    @Test
    void aMalformedDumpLineIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> parse("0\tonly\ttwo"));
    }

    @Test
    void aTruncatedDumpIsADisagreement() {
        assertThrows(AssertionError.class, () ->
            assertParity("full", resource("sample-a.txt"), "short", "0\tminecraft:stone\tminecraft:stone\tFULL top=1.0 fluid=NONE tags=[]"));
    }

    /**
     * Proves the exclusion mechanism actually works: sample-a and sample-mismatch differ only at
     * index 1, and excluding exactly that index must make the comparison pass.
     */
    @Test
    void anExcludedIndexIsSkipped() {
        assertParityExcluding("sample-a", resource("sample-a.txt"),
            "sample-mismatch", resource("sample-mismatch.txt"),
            Collections.singleton(1));
    }

    /**
     * Proves the exclusion mechanism is not over-broad: excluding an index other than the one that
     * actually differs must still catch the real mismatch at index 1. Without this test, a bug that
     * excluded too much (or the wrong indices) would silently hide a real disagreement.
     */
    @Test
    void anExclusionSetMissingTheMismatchStillFails() {
        AssertionError e = assertThrows(AssertionError.class, () ->
            assertParityExcluding("sample-a", resource("sample-a.txt"),
                "sample-mismatch", resource("sample-mismatch.txt"),
                Collections.singleton(0)));
        assertTrue(e.getMessage().contains("index 1"), e.getMessage());
    }

    @Test
    @EnabledIf("realDumpsExist")
    void theTwoAdaptersAgreeOnTheFixtureWorld() {
        assertParityExcluding("1.7.10", readDump("blocks-1.7.10.txt"),
            "1.21.11", readDump("blocks-1.21.11.txt"),
            EXCLUDED_FROM_CROSS_VERSION_DIFF);
    }

    @Test
    @EnabledIf("realDumpsExist")
    void bothAdaptersMatchTheGolden() {
        assertParity("golden-1.7.10", readDump("golden-1.7.10.txt"), "1.7.10", readDump("blocks-1.7.10.txt"));
        assertParity("golden-1.21.11", readDump("golden-1.21.11.txt"), "1.21.11", readDump("blocks-1.21.11.txt"));
    }
}
