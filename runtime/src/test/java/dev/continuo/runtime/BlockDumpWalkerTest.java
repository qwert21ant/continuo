package dev.continuo.runtime;

import dev.continuo.core.BlockClassifier;
import dev.continuo.core.BlockTable;
import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDumpWalkerTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private final BlockClassifier classifier = new BlockClassifier(BlockTable.EMPTY);

    private static BlockDescription stone() {
        return new BlockDescription("minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false);
    }

    @Test
    void emitsOneLinePerPositionInIndexOrder() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());

        String dump = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 64, 0);
        String[] lines = dump.split("\n");

        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("0\t"), lines[0]);
        assertTrue(lines[1].startsWith("1\t"), lines[1]);
    }

    @Test
    void eachLineCarriesTheIdTheStateKeyAndTheClassifiedData() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());

        String line = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 0, 64, 0);

        assertEquals("0\tminecraft:stone\tminecraft:stone\tFULL top=1.0 fluid=NONE tags=[]", line);
    }

    @Test
    void anUnreadablePositionIsRecordedRatherThanSkipped() {
        FakeBlockView view = new FakeBlockView();

        String line = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 0, 64, 0);

        assertEquals("0\t-\t-\tUNKNOWN top=0.0 fluid=NONE tags=[]", line,
            "a hole in the dump must be visible, not absent");
    }

    @Test
    void walksXThenZThenY() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        view.put(0, 64, 1, stone());
        view.put(0, 65, 0, stone());

        String[] lines = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 65, 1).split("\n");

        assertEquals(8, lines.length, "2x2x2 region");
        assertTrue(lines[0].startsWith("0\t"));
        assertTrue(lines[7].startsWith("7\t"));
    }

    @Test
    void doesNotDescribeAStateItHasAlreadySeen() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());

        BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 64, 0);

        assertEquals(1, view.describeCallCount(),
            "the walker must reuse the core's memo rather than reclassifying every position");
    }
}
