package dev.continuo.movement.parkour;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockShape;
import dev.continuo.core.BlockSource;
import dev.continuo.core.BlockTag;
import dev.continuo.core.Fluid;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.pathfinder.AStarPathfinder;
import dev.continuo.pathfinder.GoalBlock;
import dev.continuo.pathfinder.PathOutcome;
import dev.continuo.pathfinder.PathResult;
import dev.continuo.pathfinder.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dijkstra oracle, run with parkour active. Spec done-criterion 5.
 *
 * <p>Every other optimality guard on this branch runs over C1's four built-in movements, all of
 * which span exactly one axis step. A span-2 movement is the single configuration C2 exists to
 * introduce, and until this test nothing anywhere could detect a walkable-but-not-cheapest path
 * while one was active: the only end-to-end parkour search assertion was a lower bound over a
 * causeway with one viable route.
 *
 * <p>It has to live here rather than in {@code :core-pathfinder}, and that is the seam working
 * rather than an inconvenience. {@code :core-pathfinder}'s tests cannot see {@link ParkourMove};
 * this module's can see {@code AStarPathfinder} because {@code :core-pathfinder} is test-scoped
 * here. The oracle itself had to be copied for the same reason — see
 * {@link ParkourDijkstraOracle}.
 */
class ParkourOptimalityTest {

    private static final int WORLDS = 400;
    private static final int SIZE = 8;

    /** Missing floor, so the position is a genuine gap with void beneath rather than a drop. */
    private static final int HOLE_PERCENT = 26;

    /** A two-tall pillar on an intact floor: not walkable, not climbable, not jumpable. */
    private static final int WALL_PERCENT = 10;

    /** A one-tall block on an intact floor, so ascend and descend keep a part to play. */
    private static final int STEP_PERCENT = 10;

    private static final BlockData AIR =
        new BlockData(BlockShape.AIR, 0.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));
    private static final BlockData STONE =
        new BlockData(BlockShape.FULL, 1.0, Fluid.NONE, EnumSet.noneOf(BlockTag.class));

    private static final int FLOOR_Y = 63;
    private static final int WALK_Y = 64;

    /**
     * A* must agree with Dijkstra over the very movement set it was given, on every world where a
     * route exists at all, and parkour must genuinely feature in enough of those routes for the
     * agreement to mean anything.
     */
    @Test
    void aStarMatchesDijkstraOverManyRandomWorldsWithParkourActive() {
        List<IMovementType> moves = AStarPathfinder.defaultRegistry()
            .activeFor(CapabilitySet.of(Capability.PARKOUR)).movements();
        assertTrue(idsOf(moves).contains("walk.parkour"),
            "the oracle has to run over the same set the search does, parkour included; got "
                + idsOf(moves));

        int solvable = 0;
        int withAJump = 0;

        for (long seed = 1; seed <= WORLDS; seed++) {
            BlockSource world = randomWorld(seed);
            Pos start = new Pos(0, WALK_Y, 0);
            GoalBlock goal = new GoalBlock(SIZE - 1, WALK_Y, SIZE - 1);

            PathResult result = new AStarPathfinder().findPath(world, start.x(), start.y(),
                start.z(), goal, CapabilitySet.of(Capability.PARKOUR));
            if (result.outcome() != PathOutcome.FOUND) {
                continue;
            }
            solvable++;

            assertEquals(ParkourDijkstraOracle.optimalCost(world, start, goal, moves),
                result.cost(), 1.0e-9,
                "A* returned a costlier path than Dijkstra over the same movements, with a "
                    + "span-2 movement active; seed " + seed);

            if (longestStep(result.path()) >= 2) {
                withAJump++;
            }
        }

        // Both figures are pinned as floors rather than as equalities: they are properties of
        // the fixture, not of the search, and a floor fails loudly if the fixture degenerates
        // while leaving room to retune it. Measured on this fixture: 374 of 400 worlds solvable,
        // 208 of those 374 optimal paths routed through at least one two-block step, and A*
        // matched Dijkstra to 1e-9 on all 374.
        //
        // The second floor is the one that matters. Without it this test could pass while
        // asserting nothing C2 is about: if parkour stopped appearing in optimal routes it would
        // silently become a slower re-run of the built-in-movement oracle that :core-pathfinder
        // already has, and the span-2 case - the one this project's history says to fear - would
        // again be uncovered with a green suite.
        assertTrue(solvable >= 300,
            "the fixture stopped producing solvable worlds, so the comparison above ran on almost "
                + "nothing; solvable " + solvable + " of " + WORLDS);
        assertTrue(withAJump >= 150,
            "parkour has stopped appearing in optimal routes, which makes this whole test a "
                + "re-run of the built-in-movement oracle: only " + withAJump + " of " + solvable
                + " optimal paths contain a two-block step");
    }

    /**
     * The Chebyshev length of the longest single step in a path.
     *
     * <p>Two is reachable only by {@link ParkourMove}: every built-in movement spans exactly one
     * axis step, so a step of two is a jump and nothing else.
     */
    private static int longestStep(List<Pos> path) {
        int longest = 0;
        for (int i = 1; i < path.size(); i++) {
            Pos from = path.get(i - 1);
            Pos to = path.get(i);
            int step = Math.max(Math.abs(to.x() - from.x()),
                Math.max(Math.abs(to.y() - from.y()), Math.abs(to.z() - from.z())));
            if (step > longest) {
                longest = step;
            }
        }
        return longest;
    }

    private static List<String> idsOf(List<IMovementType> moves) {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < moves.size(); i++) {
            ids.add(moves.get(i).id());
        }
        return ids;
    }

    /**
     * A deterministic pseudorandom world: a floor at {@link #FLOOR_Y} with holes punched through
     * it, two-tall walls and one-tall steps scattered over what remains, and void below.
     *
     * <p><b>The void is what makes the holes gaps rather than drops.</b> With a second floor
     * underneath, {@code walk.descend} would take every hole and parkour would never be the
     * cheapest way past one. Both corners are kept clear so the start and the goal are standable.
     */
    private static BlockSource randomWorld(long seed) {
        Random random = new Random(seed);
        final boolean[][] floor = new boolean[SIZE][SIZE];
        final int[][] height = new int[SIZE][SIZE];

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean corner = (x == 0 && z == 0) || (x == SIZE - 1 && z == SIZE - 1);
                if (corner) {
                    floor[x][z] = true;
                    height[x][z] = 0;
                    continue;
                }
                floor[x][z] = random.nextInt(100) >= HOLE_PERCENT;
                if (!floor[x][z]) {
                    continue;
                }
                int roll = random.nextInt(100);
                if (roll < WALL_PERCENT) {
                    height[x][z] = 2;
                } else if (roll < WALL_PERCENT + STEP_PERCENT) {
                    height[x][z] = 1;
                }
            }
        }

        return new BlockSource() {
            @Override
            public BlockData at(int x, int y, int z) {
                if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) {
                    return AIR;
                }
                if (y == FLOOR_Y) {
                    return floor[x][z] ? STONE : AIR;
                }
                if (y > FLOOR_Y && y <= FLOOR_Y + height[x][z]) {
                    return STONE;
                }
                return AIR;
            }

            @Override
            public int minY() {
                return 58;
            }

            @Override
            public int maxY() {
                return 71;
            }
        };
    }
}
