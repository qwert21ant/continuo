package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLegendTest {

    @Test
    void theLegendMapsCharactersToBlocks() {
        assertEquals(BlockLegend.AIR, BlockLegend.legend().get(Character.valueOf('.')));
        assertEquals(BlockLegend.STONE, BlockLegend.legend().get(Character.valueOf('#')));
        assertEquals(BlockLegend.CARPET, BlockLegend.legend().get(Character.valueOf('c')));
    }

    @Test
    void everyLegendEntryRoundTripsBackToItsOwnCharacter() {
        // This is the property the whole render-and-paste-back pipeline rests on. If the
        // forward and reverse directions ever disagree for one entry, a rendered map re-parses
        // as different terrain and the pasted fixture poses a different routing question than
        // the one that was captured - silently, because both halves still look well formed.
        for (java.util.Map.Entry<Character, BlockData> entry : BlockLegend.legend().entrySet()) {
            assertEquals(entry.getKey().charValue(), BlockLegend.characterFor(entry.getValue()),
                "legend character " + entry.getKey() + " does not come back from its block");
        }
    }

    @Test
    void aBlockOutsideTheLegendRendersAsUnmapped() {
        // Spec 4.3: a live world produces BlockData the legend has no character for. It must
        // render as something rather than crash, and '?' is what re-parses as UNKNOWN.
        BlockData offLegend = new BlockData(BlockShape.PARTIAL, 0.5625, Fluid.NONE,
            EnumSet.noneOf(BlockTag.class));

        assertEquals('?', BlockLegend.characterFor(offLegend));
        assertEquals(BlockLegend.UNMAPPED, BlockLegend.characterFor(offLegend));
    }

    @Test
    void theLegendCannotBeModifiedByACaller() {
        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                BlockLegend.legend().put(Character.valueOf('x'), BlockLegend.STONE);
            }
        });
    }
}
