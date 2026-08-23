package dev.continuo.movement.parkour;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.Capability;
import dev.continuo.movement.MoveSink;
import dev.continuo.movement.MovementContract;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourMoveTest {

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    /** Offers recorded as "x,y,z@cost", so order and content are both assertable. */
    private static List<String> offers(BlockSource world, int x, int y, int z) {
        final List<String> recorded = new ArrayList<String>();
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        ctx.moveTo(x, y, z);
        new ParkourMove().expand(ctx, new MoveSink() {
            @Override
            public void offer(int nx, int ny, int nz, double cost) {
                recorded.add(nx + "," + ny + "," + nz + "@" + cost);
            }
        });
        return recorded;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static BlockSource world(final Map<String, BlockData> blocks) {
        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(key(x, y, z));
                return found == null ? AIR : found;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int maxY() {
                return 256;
            }
        };
    }

    /** Floor at y=63 on both sides of a one-block gap at x=1, standing at (0,64,0). */
    private static Map<String, BlockData> gapWorld() {
        Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);
        blocks.put(key(2, 63, 0), STONE);
        return blocks;
    }

    @Test
    void itJumpsAOneBlockGapToTheLandingBeyond() {
        assertEquals(
            java.util.Collections.singletonList("2,64,0@" + (2 * MovementCosts.TRAVERSE
                + MovementCosts.JUMP_SURCHARGE)),
            offers(world(gapWorld()), 0, 64, 0));
    }

    @Test
    void itDoesNotOfferAJumpWhereTheGapIsWalkable() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 63, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "traverse already reaches the far side in two steps; a parkour edge here would be a "
                + "duplicate at a worse cost, which would make the search prefer jumping over "
                + "walking for no reason");
    }

    @Test
    void itDoesNotOfferAJumpThroughAWall() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 64, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "the player's feet pass through the gap column");
    }

    @Test
    void itDoesNotOfferAJumpWhenTheGapColumnIsBlockedAtHeadHeight() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(1, 65, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "the player's head passes through the gap column too");
    }

    @Test
    void itDoesNotOfferAJumpWithoutHeadroomToJumpInto() {
        Map<String, BlockData> blocks = gapWorld();
        blocks.put(key(0, 66, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0),
            "a ceiling at y+2 stops the jump before it starts, exactly as it does for ascend");
    }

    @Test
    void itDoesNotOfferAJumpWithNothingToLandOn() {
        Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);

        assertEquals(java.util.Collections.<String>emptyList(),
            offers(world(blocks), 0, 64, 0));
    }

    @Test
    void itDeclaresItsCostPerAxisStepHonestly() {
        assertEquals(java.util.Collections.<String>emptyList(),
            MovementContract.violations(new ParkourMove()));
    }

    @Test
    void itSpansTwoAxisStepsSoItDeclaresHalfItsCost() {
        double cost = 2 * MovementCosts.TRAVERSE + MovementCosts.JUMP_SURCHARGE;

        assertEquals(cost / 2.0, new ParkourMove().minCostPerAxisStep(), 1.0e-9);
        assertTrue(new ParkourMove().minCostPerAxisStep() > MovementCosts.TRAVERSE,
            "parkour must not become the cheapest axis step, or it would loosen the heuristic "
                + "for every search including ones that cannot use it");
    }

    @Test
    void itRequiresItsCapability() {
        assertEquals(EnumSet.of(Capability.PARKOUR), new ParkourMove().requires());
    }
}
