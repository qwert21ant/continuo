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

    /**
     * A description whose id encodes its own coordinates, so a traversal-order test can assert
     * exactly which position landed at which index rather than merely counting lines.
     */
    private static BlockDescription at(int x, int y, int z) {
        String id = "test:x" + x + "y" + y + "z" + z;
        return new BlockDescription(id, id, CUBE.clone(), null, false, false);
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
        // Every position gets its own description so each line is individually identifiable —
        // a shared description (as in doesNotDescribeAStateItHasAlreadySeen) would let the
        // memo mask a reordering. This deliberately forgoes memo sharing; that is fine here,
        // the memo has its own dedicated test.
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, at(0, 64, 0));
        view.put(1, 64, 0, at(1, 64, 0));
        view.put(0, 64, 1, at(0, 64, 1));
        view.put(1, 64, 1, at(1, 64, 1));
        view.put(0, 65, 0, at(0, 65, 0));
        view.put(1, 65, 0, at(1, 65, 0));
        view.put(0, 65, 1, at(0, 65, 1));
        view.put(1, 65, 1, at(1, 65, 1));

        String[] lines = BlockDumpWalker.dump(view, classifier, 0, 64, 0, 1, 65, 1).split("\n");

        assertEquals(8, lines.length, "2x2x2 region");
        assertTrue(lines[0].startsWith("0\ttest:x0y64z0\t"), lines[0]);
        assertTrue(lines[2].startsWith("2\ttest:x0y64z1\t"), lines[2]);
        assertTrue(lines[4].startsWith("4\ttest:x0y65z0\t"), lines[4]);
        assertTrue(lines[7].startsWith("7\ttest:x1y65z1\t"), lines[7]);
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
