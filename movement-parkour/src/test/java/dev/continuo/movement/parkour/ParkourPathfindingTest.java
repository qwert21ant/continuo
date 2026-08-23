package dev.continuo.movement.parkour;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementCosts;
import dev.continuo.movement.MovementRegistry;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathResult;
import dev.continuo.pathfinder.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourPathfindingTest {

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    /**
     * A causeway one block wide from x=0 to x=4 at y=64, with the floor missing under x=2 and
     * nothing but void either side — so the only route to the far end is a parkour jump.
     */
    private static BlockSource island() {
        final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        blocks.put(key(0, 63, 0), STONE);
        blocks.put(key(1, 63, 0), STONE);
        blocks.put(key(3, 63, 0), STONE);
        blocks.put(key(4, 63, 0), STONE);

        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                BlockData found = blocks.get(key(x, y, z));
                return found == null ? AIR : found;
            }

            @Override
            public int minY() {
                return 60;
            }

            @Override
            public int maxY() {
                return 70;
            }
        };
    }

    @Test
    void aDiscoveredMovementIsFoundWithoutAnybodyNamingIt() {
        MovementRegistry registry = new MovementRegistry();
        registry.discover();

        List<String> ids = new ArrayList<String>();
        ActiveMovements active = registry.activeFor(CapabilitySet.of(Capability.PARKOUR));
        for (IMovementType type : active.movements()) {
            ids.add(type.id());
        }

        assertTrue(ids.contains("walk.parkour"),
            "ServiceLoader must find ParkourMove through META-INF/services; found " + ids);
    }

    @Test
    void aSearchCrossesTheGapWhenParkourIsGranted() {
        PathResult result = new AStarPathfinder().findPath(island(), 0, 64, 0,
            new GoalBlock(4, 64, 0), CapabilitySet.of(Capability.PARKOUR));

        assertEquals(PathOutcome.FOUND, result.outcome());

        // The causeway admits exactly one route — 0, 1, jump to 3, 4 — so both the cost and the
        // route itself are pinned rather than bounded. `>= ParkourMove.COST` was the earlier
        // assertion and it cannot tell a right route from a wrong one: any route paying for a
        // jump satisfies it, including one that jumped somewhere pointless first.
        List<Integer> xs = new ArrayList<Integer>();
        for (Pos step : result.path()) {
            xs.add(Integer.valueOf(step.x()));
        }
        assertEquals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1), Integer.valueOf(3),
            Integer.valueOf(4)), xs, "the route must walk, jump the hole, then walk");
        assertEquals(2 * MovementCosts.TRAVERSE + ParkourMove.COST, result.cost(), 1.0e-9,
            "two traverses and exactly one jump");
    }

    @Test
    void theSameSearchFindsNothingWhenParkourIsNotGranted() {
        PathResult result =
            new AStarPathfinder().findPath(island(), 0, 64, 0, new GoalBlock(4, 64, 0));

        assertEquals(PathOutcome.NO_PATH, result.outcome(),
            "without the capability the gap is impassable, and this is what proves the gate is "
                + "load-bearing rather than decorative");
    }

    @Test
    void grantingParkourDoesNotChangeTheMultiplierForTheBuiltIns() {
        MovementRegistry registry = AStarPathfinder.defaultRegistry();

        assertEquals(
            registry.activeFor(CapabilitySet.none()).cheapestAxisStep(),
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).cheapestAxisStep(),
            1.0e-9,
            "parkour costs more per axis step than traverse, so turning it on must not loosen "
                + "the heuristic — if this ever changes, every search gets slower");
    }
}
