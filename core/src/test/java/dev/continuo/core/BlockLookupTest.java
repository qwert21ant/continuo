package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLookupTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    private FakeBlockView view;
    private BlockLookup lookup;

    private static BlockDescription stone() {
        return new BlockDescription("minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false);
    }

    @BeforeEach
    void setUp() {
        view = new FakeBlockView();
        lookup = new BlockLookup(view, new BlockClassifier(BlockTable.EMPTY));
    }

    @Test
    void classifiesABlockItHasNotSeenBefore() {
        view.put(0, 64, 0, stone());
        assertEquals(BlockShape.FULL, lookup.at(0, 64, 0).shape());
    }

    @Test
    void describesEachStateOnlyOnce() {
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        view.put(2, 64, 0, stone());

        lookup.at(0, 64, 0);
        lookup.at(1, 64, 0);
        lookup.at(2, 64, 0);

        assertEquals(1, view.describeCallCount(), "three positions, one state, one describe");
        assertEquals(3, view.stateIdCallCount(), "stateId is the hot path and is called every time");
    }

    @Test
    void returnsTheSameInternedInstanceForTheSameState() {
        view.put(0, 64, 0, stone());
        view.put(1, 64, 0, stone());
        assertSame(lookup.at(0, 64, 0), lookup.at(1, 64, 0));
    }

    @Test
    void anUnreadablePositionIsUnknownAndIsNotDescribed() {
        assertSame(BlockData.UNKNOWN, lookup.at(0, 64, 0));
        assertEquals(0, view.describeCallCount(), "describe must never be called for a -1 state id");
    }

    @Test
    void aPositionInAnUnloadedChunkIsUnknown() {
        view.put(0, 64, 0, stone());
        view.setChunkLoaded(0, 0, false);
        assertSame(BlockData.UNKNOWN, lookup.at(0, 64, 0));
    }

    @Test
    void aPositionOutsideTheVerticalRangeIsUnknown() {
        view.setVerticalRange(0, 256);
        assertSame(BlockData.UNKNOWN, lookup.at(0, 300, 0));
        assertSame(BlockData.UNKNOWN, lookup.at(0, -1, 0));
    }

    @Test
    void clearForgetsWhatItHadClassified() {
        view.put(0, 64, 0, stone());
        lookup.at(0, 64, 0);
        assertEquals(1, view.describeCallCount());

        lookup.at(0, 64, 0);
        assertEquals(1, view.describeCallCount(), "the second read must come from the memo");

        lookup.clear();
        lookup.at(0, 64, 0);

        assertEquals(2, view.describeCallCount(), "clear() must force a fresh describe");
    }

    @Test
    void exposesTheViewsVerticalRange() {
        view.setVerticalRange(-64, 320);
        assertEquals(-64, lookup.minY());
        assertEquals(320, lookup.maxY());
    }

    @Test
    void rejectsANullView() {
        assertThrows(IllegalArgumentException.class,
            () -> new BlockLookup(null, new BlockClassifier(BlockTable.EMPTY)));
    }

    @Test
    void rejectsANullClassifier() {
        assertThrows(IllegalArgumentException.class, () -> new BlockLookup(new FakeBlockView(), null));
    }
}
