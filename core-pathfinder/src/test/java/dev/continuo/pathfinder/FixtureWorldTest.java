package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixtureWorldTest {

    private static final String FLAT =
        "origin: 0,64,0\n"
            + "--- y=64\n"
            + "###\n"
            + "###\n"
            + "--- y=65\n"
            + "S.G\n"
            + "...\n";

    @Test
    void columnsRunEastAndRowsRunSouth() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(BlockShape.FULL, world.at(2, 64, 1).shape());
        assertEquals(BlockShape.AIR, world.at(1, 65, 0).shape());
    }

    @Test
    void startAndGoalComeFromTheArtAndTheirCellsAreAir() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(new Pos(0, 65, 0), world.start());
        assertEquals(new Pos(2, 65, 0), world.goal());
        assertEquals(BlockShape.AIR, world.at(0, 65, 0).shape());
        assertEquals(BlockShape.AIR, world.at(2, 65, 0).shape());
    }

    @Test
    void theOriginOffsetsEveryCoordinate() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: -5,-64,10\n"
                + "--- y=-64\n"
                + "#\n");

        assertEquals(BlockShape.FULL, world.at(-5, -64, 10).shape());
        assertEquals(BlockShape.UNKNOWN, world.at(-4, -64, 10).shape());
    }

    @Test
    void readsOutsideTheDeclaredExtentAreUnknownNotAir() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(BlockShape.UNKNOWN, world.at(-1, 64, 0).shape(), "west of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(3, 64, 0).shape(), "east of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 64, -1).shape(), "north of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 64, 2).shape(), "south of the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 63, 0).shape(), "below the extent");
        assertEquals(BlockShape.UNKNOWN, world.at(0, 66, 0).shape(), "above the extent");
    }

    @Test
    void theVerticalExtentBecomesMinYAndMaxY() {
        FixtureWorld world = FixtureWorld.parse(FLAT);

        assertEquals(64, world.minY());
        assertEquals(66, world.maxY(), "maxY is exclusive, following IBlockView");
    }

    @Test
    void carriageReturnsAreToleratedSoWindowsCheckoutsParse() {
        FixtureWorld world = FixtureWorld.parse(FLAT.replace("\n", "\r\n"));

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(new Pos(0, 65, 0), world.start());
    }

    @Test
    void everyLegendCharacterParses() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,0,0\n"
                + "--- y=0\n"
                + ".#_^>cpf?~!\n");

        assertEquals(BlockShape.AIR, world.at(0, 0, 0).shape());
        assertEquals(BlockShape.FULL, world.at(1, 0, 0).shape());
        assertEquals(BlockShape.SLAB_BOTTOM, world.at(2, 0, 0).shape());
        assertEquals(BlockShape.SLAB_TOP, world.at(3, 0, 0).shape());
        assertEquals(BlockShape.STAIR, world.at(4, 0, 0).shape());
        assertEquals(BlockShape.THIN_LAYER, world.at(5, 0, 0).shape());
        assertEquals(BlockShape.PARTIAL, world.at(6, 0, 0).shape());
        assertEquals(BlockShape.FENCE, world.at(7, 0, 0).shape());
        assertEquals(BlockShape.UNKNOWN, world.at(8, 0, 0).shape());
        assertEquals(BlockShape.AIR, world.at(9, 0, 0).shape());
        assertEquals(BlockShape.AIR, world.at(10, 0, 0).shape());
    }

    @Test
    void anUnknownCharacterIsRejectedRatherThanGuessed() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\nZ\n"));

        assertEquals(true, thrown.getMessage().contains("Z"),
            "the message must name the offending character, got: " + thrown.getMessage());
    }

    @Test
    void extraCharactersCanBeRegisteredForOneFixture() {
        Map<Character, BlockData> extra =
            Collections.singletonMap(Character.valueOf('Z'), BlockLegend.STONE);

        FixtureWorld world = FixtureWorld.parse("origin: 0,0,0\n--- y=0\nZ\n", extra);

        assertEquals(BlockShape.FULL, world.at(0, 0, 0).shape());
    }

    @Test
    void commentLinesAreIgnoredSoRendererOutputReparses() {
        FixtureWorld world = FixtureWorld.parse(
            "origin: 0,64,0\n"
                + "--- y=64\n"
                + "###\n"
                + "// FOUND, 3 steps, 5 expanded, cost 12.0\n");

        assertEquals(BlockShape.FULL, world.at(0, 64, 0).shape());
        assertEquals(65, world.maxY(), "the comment is not a row");
    }

    @Test
    void raggedRowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\n###\n##\n"));
    }

    @Test
    void nonContiguousSlicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FixtureWorld.parse("origin: 0,0,0\n--- y=0\n#\n--- y=2\n#\n"));
    }
}
