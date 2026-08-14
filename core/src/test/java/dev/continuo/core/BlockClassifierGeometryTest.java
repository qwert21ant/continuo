package dev.continuo.core;

import dev.continuo.platform.BlockDescription;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockClassifierGeometryTest {

    private final BlockClassifier classifier = new BlockClassifier(BlockTable.EMPTY);

    private BlockShape shapeOf(double... boxes) {
        return classifier.classify(
            new BlockDescription("a:b", "a:b", boxes, null, false, false)).shape();
    }

    private double topOf(double... boxes) {
        return classifier.classify(
            new BlockDescription("a:b", "a:b", boxes, null, false, false)).collisionTop();
    }

    @Test
    void noBoxesIsAir() {
        assertEquals(BlockShape.AIR, shapeOf());
        assertEquals(0.0, topOf(), 0.0);
    }

    @Test
    void oneFullCubeIsFull() {
        assertEquals(BlockShape.FULL, shapeOf(0, 0, 0, 1, 1, 1));
        assertEquals(1.0, topOf(0, 0, 0, 1, 1, 1), 0.0);
    }

    @Test
    void aFullFootprintLowerHalfIsABottomSlab() {
        assertEquals(BlockShape.SLAB_BOTTOM, shapeOf(0, 0, 0, 1, 0.5, 1));
        assertEquals(0.5, topOf(0, 0, 0, 1, 0.5, 1), 0.0);
    }

    @Test
    void aFullFootprintUpperHalfIsATopSlab() {
        assertEquals(BlockShape.SLAB_TOP, shapeOf(0, 0.5, 0, 1, 1, 1));
    }

    @Test
    void aFullFootprintQuarterHighLayerIsThin() {
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 0.0625, 1));
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 0.25, 1));
    }

    @Test
    void aFullFootprintBoxBetweenThinAndSlabIsPartial() {
        assertEquals(BlockShape.PARTIAL, shapeOf(0, 0, 0, 1, 0.3, 1),
            "categories are exact-match; a near-miss must not be smoothed into a slab");
    }

    @Test
    void anythingTallerThanTheCubeIsFence() {
        assertEquals(BlockShape.FENCE, shapeOf(0.375, 0, 0.375, 0.625, 1.5, 0.625));
        assertEquals(1.5, topOf(0.375, 0, 0.375, 0.625, 1.5, 0.625), 0.0);
    }

    @Test
    void fenceWinsOverEveryOtherRuleEvenWithAFullFootprintBoxPresent() {
        assertEquals(BlockShape.FENCE, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0.375, 0, 0.375, 0.625, 1.5, 0.625));
    }

    @Test
    void aLowerHalfPlusAPartialUpperBoxIsAStair() {
        assertEquals(BlockShape.STAIR, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 1, 1, 0.5));
    }

    @Test
    void aThreeBoxStairIsStillAStair() {
        assertEquals(BlockShape.STAIR, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 0.5, 1, 0.5,
            0.5, 0.5, 0, 1, 1, 0.5));
    }

    @Test
    void aPartialFootprintBoxIsPartial() {
        assertEquals(BlockShape.PARTIAL, shapeOf(0.25, 0, 0.25, 0.75, 1, 0.75));
    }

    @Test
    void twoStackedFullFootprintBoxesAreNotAStair() {
        assertEquals(BlockShape.PARTIAL, shapeOf(
            0, 0, 0, 1, 0.5, 1,
            0, 0.5, 0, 1, 1, 1),
            "a stair's upper box must not cover the whole footprint");
    }

    @Test
    void collisionTopIsTheMaximumAcrossAllBoxes() {
        assertEquals(0.9, topOf(
            0, 0, 0, 1, 0.5, 1,
            0.25, 0.4, 0.25, 0.75, 0.9, 0.75), 1e-9);
    }

    @Test
    void toleratesFloatingPointNoiseFromSixteenthsArithmetic() {
        assertEquals(BlockShape.FULL, shapeOf(0, 0, 0, 0.9999999, 1.0000001, 1),
            "boxes built from sixteenths arrive with rounding noise and must still match");
    }

    @Test
    void classifiesWithNoTagsAndNoFluidWhenTheTableIsEmpty() {
        BlockData d = classifier.classify(
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1, 1}, null, false, false));
        assertEquals(Fluid.NONE, d.fluid());
        assertEquals(0, d.tags().size());
    }

    // --- Task 7 corrections beyond the brief's 15 ---

    @Test
    void aSingleZeroHeightFullFootprintBoxIsAirLikeOneLayerSnow() {
        // On 1.21.11, Shapes.create collapses this to no boxes at all; on 1.7.10 the adapter
        // faithfully reports a real zero-height box. Rule 0 must discard it before any other
        // rule sees it, so both versions agree it is AIR.
        assertEquals(BlockShape.AIR, shapeOf(0, 0, 0, 1, 0, 1));
        assertEquals(0.0, topOf(0, 0, 0, 1, 0, 1), 0.0);
    }

    @Test
    void aZeroThicknessVerticalPlaneIsAir() {
        // Zero extent on X here, not Y — proves rule 0 checks all three axes. It also proves
        // the filter runs before collisionTop: unfiltered, this box's maxY of 1.0 would make
        // collisionTop() report 1.0 instead of 0.0.
        assertEquals(BlockShape.AIR, shapeOf(0, 0, 0, 0, 1, 1));
        assertEquals(0.0, topOf(0, 0, 0, 0, 1, 1), 0.0);
    }

    @Test
    void aDegenerateBoxAlongsideARealFullCubeIsStillFull() {
        // Proves rule 0 removes only the degenerate box, rather than bailing out of
        // classification entirely when any degenerate box is present.
        assertEquals(BlockShape.FULL, shapeOf(
            0, 0, 0, 1, 0, 1,
            0, 0, 0, 1, 1, 1));
    }

    @Test
    void aGenuineThinLayerSurvivesRuleZerosThreshold() {
        // 0.0625 is real 1/16 geometry, far from either epsilon's boundary in absolute terms.
        // Worth asserting as ordinary behaviour, but it does NOT discriminate between the two
        // epsilon constants being merged — see aBoxJustAboveDegenerateEpsSurvivesAsThinLayer and
        // aBoxJustPastOneWithinEpsIsStillFull for the tests that actually guard the separation.
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 0.0625, 1));
    }

    @Test
    void aBoxJustAboveDegenerateEpsSurvivesAsThinLayer() {
        // Guards DEGENERATE_EPS against being loosened toward EPS. A Y extent of 5e-5 sits
        // strictly between the two constants: 5e-5 > 1e-6 (today's DEGENERATE_EPS), so rule 0
        // does not discard it and it classifies THIN_LAYER. If DEGENERATE_EPS were widened to
        // 1e-4 (EPS's value), 5e-5 <= 1e-4 would hold, the box would be discarded by rule 0, and
        // the result would flip to AIR.
        assertEquals(BlockShape.THIN_LAYER, shapeOf(0, 0, 0, 1, 5e-5, 1));
    }

    @Test
    void aBoxJustPastOneWithinEpsIsStillFull() {
        // Guards EPS against being tightened toward DEGENERATE_EPS, and pins the FENCE boundary
        // just above 1.0, which nothing else here exercises. maxY = 1.00005 is within today's
        // EPS (1e-4) of 1.0, so near() matches and this is FULL; it is also not more than
        // 1.0 + EPS, so the FENCE rule correctly does not fire. If EPS were tightened to 1e-6,
        // near() would fail (5e-5 > 1e-6) and this would fall through to PARTIAL instead.
        assertEquals(BlockShape.FULL, shapeOf(0, 0, 0, 1, 1.00005, 1));
    }

    @Test
    void anUpsideDownStairIsPartialNotStair() {
        // Deliberate, not an oversight: an upside-down stair's full-footprint box sits in the
        // upper half (y 0.5..1), so it does not match rule 7's lower-half requirement and falls
        // through to PARTIAL with collisionTop() == 1.0. That is also behaviourally correct —
        // from above it collides like a full block — and it holds on both target versions, so
        // parity is preserved. Do not "fix" rule 7 to special-case this.
        assertEquals(BlockShape.PARTIAL, shapeOf(
            0, 0.5, 0, 1, 1, 1,
            0, 0, 0, 1, 0.5, 0.5));
        assertEquals(1.0, topOf(
            0, 0.5, 0, 1, 1, 1,
            0, 0, 0, 1, 0.5, 0.5), 0.0);
    }

    @Test
    void flowingWaterIdClassifiesAsWater() {
        BlockData d = classifier.classify(
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1, 1},
                "minecraft:flowing_water", false, false));
        assertEquals(Fluid.WATER, d.fluid());
    }

    @Test
    void flowingLavaIdClassifiesAsLava() {
        BlockData d = classifier.classify(
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1, 1},
                "minecraft:flowing_lava", false, false));
        assertEquals(Fluid.LAVA, d.fluid());
    }
}
