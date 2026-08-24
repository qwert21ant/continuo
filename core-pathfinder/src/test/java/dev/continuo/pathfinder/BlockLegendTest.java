package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void noLegendCharacterCollidesWithAnOverlayCharacter() {
        // The round trip rests on overlays being distinguishable from terrain. A legend entry
        // that took 'S', 'G', '*' or '+' would render as a marker and re-parse as that block, so
        // a pasted-back fixture would silently gain terrain where the search had drawn its own
        // route - and the map would still look perfectly well formed. Nothing else guards this;
        // both sides are edited by hand and neither knows about the other.
        char[] overlays = new char[] {
            PathRenderer.START, PathRenderer.GOAL, PathRenderer.PATH, PathRenderer.EXPANDED,
        };
        for (Character legendChar : BlockLegend.legend().keySet()) {
            for (int i = 0; i < overlays.length; i++) {
                assertNotEquals(overlays[i], legendChar.charValue(),
                    "legend character " + legendChar + " collides with an overlay character, so a"
                        + " rendered map would re-parse that overlay as terrain");
            }
        }
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
