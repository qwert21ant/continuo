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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLegendTest {

    @Test
    void everyLegendValueHasTheGeometryItsJavadocClaims() {
        // Pinned by nothing before this. The class javadoc says values "match what B1's audit
        // actually recorded, so a fixture exercises the real numbers rather than round ones",
        // and names PARTIAL_FLOOR as farmland's 1.21.11 value and CARPET as carpet's - but every
        // one of these could be changed to a round number and the whole suite stayed green.
        // A fixture built on a wrong height still parses, still renders, and still round-trips;
        // it simply stops exercising the version-divergent cases the predicates exist to
        // reconcile. Nothing but this test would notice.
        assertGeometry(BlockLegend.AIR, BlockShape.AIR, 0.0);
        assertGeometry(BlockLegend.STONE, BlockShape.FULL, 1.0);
        assertGeometry(BlockLegend.BOTTOM_SLAB, BlockShape.SLAB_BOTTOM, 0.5);
        assertGeometry(BlockLegend.TOP_SLAB, BlockShape.SLAB_TOP, 1.0);
        assertGeometry(BlockLegend.STAIR, BlockShape.STAIR, 1.0);

        // 1/16. Carpet is one of the two version-divergent heights B1 §4.3 records.
        assertGeometry(BlockLegend.CARPET, BlockShape.THIN_LAYER, 0.0625);

        // 15/16. Farmland, the other one, and the value B1 §4.3 names explicitly.
        assertGeometry(BlockLegend.PARTIAL_FLOOR, BlockShape.PARTIAL, 0.9375);

        // Above the cube, which is what makes a fence neither passable nor a floor.
        assertGeometry(BlockLegend.FENCE, BlockShape.FENCE, 1.5);
    }

    @Test
    void theFluidValuesCarryTheFluidAndTheTagThatGoWithIt() {
        assertGeometry(BlockLegend.WATER, BlockShape.AIR, 0.0);
        assertEquals(Fluid.WATER, BlockLegend.WATER.fluid());
        assertTrue(BlockLegend.WATER.tags().isEmpty(), "water is not avoided, only wet");

        assertGeometry(BlockLegend.LAVA, BlockShape.AIR, 0.0);
        assertEquals(Fluid.LAVA, BlockLegend.LAVA.fluid());
        assertTrue(BlockLegend.LAVA.tags().contains(BlockTag.AVOID),
            "lava is refused on the tag, not on its geometry - it has none");

        assertEquals(BlockShape.UNKNOWN, BlockLegend.UNKNOWN.shape());
    }

    private static void assertGeometry(BlockData block, BlockShape shape, double collisionTop) {
        assertEquals(shape, block.shape(), "shape");
        assertEquals(collisionTop, block.collisionTop(), 0.0,
            "collision top of a " + shape + " legend value");
    }

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
