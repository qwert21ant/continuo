package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import dev.continuo.testkit.FakeBlockView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockSourceTest {

    private static final double[] CUBE = {0, 0, 0, 1, 1, 1};

    @Test
    void blockLookupIsABlockSource() {
        FakeBlockView view = new FakeBlockView();
        view.put(0, 64, 0, new BlockDescription(
            "minecraft:stone", "minecraft:stone", CUBE.clone(), null, false, false));

        BlockSource source = new BlockLookup(view, new BlockClassifier(BlockTable.EMPTY));

        assertEquals(BlockShape.FULL, source.at(0, 64, 0).shape());
        assertEquals(view.minY(), source.minY());
        assertEquals(view.maxY(), source.maxY());
    }

    @Test
    void readingAnUnreadablePositionThroughTheInterfaceYieldsUnknown() {
        BlockSource source = new BlockLookup(new FakeBlockView(), new BlockClassifier(BlockTable.EMPTY));

        assertEquals(BlockShape.UNKNOWN, source.at(0, 64, 0).shape());
    }
}
