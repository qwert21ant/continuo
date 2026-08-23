package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import dev.continuo.movement.ActiveMovements;
import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementRegistry;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Witnesses the two things nothing else in the suite observes: that
 * {@link AStarPathfinder#AStarPathfinder(int, dev.continuo.movement.IMovementRegistry)} actually
 * searches over the <em>injected</em> registry rather than {@link AStarPathfinder#defaultRegistry()},
 * and that the four-argument {@code findPath} overload actually defaults to
 * {@link CapabilitySet#none()} rather than some other capability set.
 *
 * <p>Every other test either constructs an {@code AStarPathfinder} with no registry argument at
 * all, or never calls the six-argument {@code findPath}. A registry silently ignored, or a caps
 * default other than {@code none()}, would leave every other test in the suite green. This class
 * proves both through the path a search actually returns — not by inspecting the registry
 * directly, which a mutation swapping in the wrong registry would still satisfy if the assertion
 * only checked shape.
 *
 * <p>The scenario: a registry with two movements along one axis. {@code walk.plain} needs no
 * capability and offers one block at a time. {@code gate.leap} needs {@link Capability#PARKOUR}
 * and offers the whole five-block gap in one step, more cheaply overall than five plain steps.
 * With no capability granted only the plain steps are available, so the search must take five of
 * them; granting {@code PARKOUR} makes the leap active and cheaper, so the search must take it
 * instead. Which one comes back is therefore direct evidence of which registry and which
 * capability set the search actually used.
 */
class RegistryInjectionTest {

    /** Neither movement here ever reads the world, so its contents are irrelevant. */
    private static final BlockSource DUMMY_WORLD = new BlockSource() {
        @Override
        public BlockData at(int x, int y, int z) {
            return BlockData.UNKNOWN;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    private static MovementRegistry gatedRegistry() {
        MovementRegistry registry = new MovementRegistry();
        registry.register(new FakeMovement("walk.plain", 3.5636, 1, 3.5636));
        registry.register(new FakeMovement("gate.leap", 1.0, 5, 5.0, Capability.PARKOUR));
        return registry;
    }

    /**
     * An {@link IMovementRegistry} that, unlike {@link MovementRegistry}, does not null-check
     * {@code caps} itself. A real third-party plugin registry is under no obligation to — the
     * interface says nothing about it — so this is what {@code AStarPathfinder}'s own {@code caps}
     * guard exists to protect against: without it, a null {@code caps} passed through a registry
     * like this one would surface as a bare {@link NullPointerException} from inside a plugin
     * rather than the symmetric {@link IllegalArgumentException} every other bad argument gets.
     */
    private static final class NonValidatingRegistry implements IMovementRegistry {
        private final MovementRegistry delegate = new MovementRegistry();

        NonValidatingRegistry() {
            delegate.register(new FakeMovement("walk.plain", 3.5636, 1, 3.5636));
        }

        @Override
        public void register(IMovementType type) {
            delegate.register(type);
        }

        @Override
        public ActiveMovements activeFor(CapabilitySet caps) {
            caps.capabilities();
            return delegate.activeFor(caps);
        }
    }

    @Test
    void theNoCapabilityOverloadUsesTheInjectedRegistryAndExcludesTheGatedMovement() {
        AStarPathfinder pathfinder = new AStarPathfinder(1000, gatedRegistry());

        PathResult result = pathfinder.findPath(DUMMY_WORLD, 0, 0, 0, new GoalBlock(5, 0, 0));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(6, result.path().size(),
            "with no capability granted, only the ungated single-block movement is active, so "
                + "reaching x=5 takes five steps plus the start");
        assertEquals(5 * 3.5636, result.cost(), 1.0e-9,
            "if the registry passed to the constructor were ignored — for instance by falling "
                + "back to defaultRegistry(), which holds none of these fake movements at all — "
                + "or if the four-arg overload defaulted to anything other than "
                + "CapabilitySet.none(), the cheaper gated leap would be reachable and this cost "
                + "would not match five plain steps");
    }

    @Test
    void theSixArgOverloadGrantingTheCapabilityAdmitsTheGatedMovement() {
        AStarPathfinder pathfinder = new AStarPathfinder(1000, gatedRegistry());

        PathResult result = pathfinder.findPath(DUMMY_WORLD, 0, 0, 0, new GoalBlock(5, 0, 0),
            CapabilitySet.of(Capability.PARKOUR));

        assertEquals(PathOutcome.FOUND, result.outcome());
        assertEquals(2, result.path().size(),
            "with PARKOUR granted, the direct five-block leap is active and strictly cheaper "
                + "than five plain steps, so the whole path must be the start plus one leap");
        assertEquals(5.0, result.cost(), 1.0e-9,
            "the injected registry's declared cost for the leap; this is only reachable if "
                + "findPath actually used the CapabilitySet passed to the six-arg overload");
    }

    @Test
    void aNullRegistryIsRejectedRatherThanDeferredToTheFirstSearch() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new AStarPathfinder(1000, null);
            }
        });
    }

    @Test
    void aNullCapabilitySetIsRejectedEvenBehindARegistryThatDoesNotCheckItItself() {
        final AStarPathfinder pathfinder = new AStarPathfinder(1000, new NonValidatingRegistry());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                pathfinder.findPath(DUMMY_WORLD, 0, 0, 0, new GoalBlock(5, 0, 0), null);
            }
        }, "caps must be null-checked by AStarPathfinder itself, not merely delegated to "
            + "whatever IMovementRegistry happens to be behind the seam — a registry that skips "
            + "its own check, like this one, would otherwise let a null caps surface as a bare "
            + "NullPointerException from inside a plugin");
    }
}
