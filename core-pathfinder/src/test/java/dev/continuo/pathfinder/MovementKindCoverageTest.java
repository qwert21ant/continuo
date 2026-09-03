package dev.continuo.pathfinder;

import dev.continuo.movement.Capability;
import dev.continuo.movement.CapabilitySet;
import dev.continuo.movement.IMovementType;
import dev.continuo.movement.MovementKind;
import dev.continuo.movement.MovementRegistry;
import dev.continuo.movement.MutableExpansionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that makes deriving a step's movement from its delta legitimate.
 *
 * <p>D2 chose to derive movement identity rather than thread it through {@code MoveSink.offer},
 * on the evidence that the registered movements emit pairwise disjoint delta sets that jointly
 * cover everything the registry can produce. That is a property of today's registry, not a law.
 * This test is what converts it from an assumption into something a build can enforce: adding a
 * diagonal ascend, a three-block parkour, a ladder or a water move fails here, at the point of
 * addition, rather than mis-executing silently in a client.
 */
class MovementKindCoverageTest {

    /** Every offer one movement made, as a delta. */
    private static final class Offer {

        final int dx;
        final int dy;
        final int dz;
        final String movementId;

        Offer(int dx, int dy, int dz, String movementId) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.movementId = movementId;
        }

        String key() {
            return dx + "," + dy + "," + dz;
        }

        @Override
        public String toString() {
            return movementId + " offers (" + key() + ")";
        }
    }

    @Test
    void everyOfferEveryRegisteredMovementCanMakeIsNamedByExactlyOneKind() {
        List<Offer> offers = collectOffers();

        // The assertion that stops a broken fixture from making this test vacuous. Without it, a
        // world where nothing can move passes every check below while proving nothing.
        Set<MovementKind> observed = EnumSet.noneOf(MovementKind.class);
        for (Offer offer : offers) {
            MovementKind kind = MovementKind.forDelta(offer.dx, offer.dy, offer.dz);
            assertNotNull(kind, "no MovementKind names " + offer
                + "; extend MovementKind.forDelta rather than deleting this assertion");
            observed.add(kind);
        }
        assertEquals(EnumSet.allOf(MovementKind.class), observed,
            "the fixture must exercise every kind, or this test proves nothing about the ones it "
                + "misses; fix the fixture, not this assertion");

        // Disjointness. Two movements offering the same delta would make the derivation ambiguous
        // and the executor's choice arbitrary.
        Map<String, String> owner = new HashMap<String, String>();
        for (Offer offer : offers) {
            String previous = owner.put(offer.key(), offer.movementId);
            assertTrue(previous == null || previous.equals(offer.movementId),
                "delta (" + offer.key() + ") is offered by both " + previous + " and "
                    + offer.movementId + ", so it cannot be derived unambiguously");
        }

        // Agreement. A kind that names the wrong movement would drive the wrong inputs.
        for (Offer offer : offers) {
            MovementKind kind = MovementKind.forDelta(offer.dx, offer.dy, offer.dz);
            assertEquals(offer.movementId, kind.movementId(),
                offer + " derived as " + kind + ", which names " + kind.movementId());
        }
    }

    /**
     * Expands every registered movement from every position of a fixture built to give all five
     * of them something to offer, and returns the deltas.
     */
    private static List<Offer> collectOffers() {
        MovementRegistry registry = AStarPathfinder.defaultRegistry();
        List<IMovementType> types =
            registry.activeFor(CapabilitySet.of(Capability.PARKOUR)).movements();

        FixtureWorld world = FixtureWorld.parse(COVERAGE_FIXTURE);
        MutableExpansionContext ctx = new MutableExpansionContext(world);
        List<Offer> offers = new ArrayList<Offer>();

        for (IMovementType type : types) {
            for (int y = FIXTURE_MIN_Y; y <= FIXTURE_MAX_Y; y++) {
                for (int x = FIXTURE_MIN_X; x <= FIXTURE_MAX_X; x++) {
                    for (int z = FIXTURE_MIN_Z; z <= FIXTURE_MAX_Z; z++) {
                        ctx.moveTo(x, y, z);
                        offers.addAll(offersOf(type, ctx, x, y, z));
                    }
                }
            }
        }
        return offers;
    }

    private static List<Offer> offersOf(final IMovementType type, MutableExpansionContext ctx,
                                        final int x, final int y, final int z) {
        final List<Offer> found = new ArrayList<Offer>();
        RecordingSink sink = new RecordingSink();
        type.expand(ctx, sink);
        for (Pos offered : sink.positions()) {
            found.add(new Offer(offered.x() - x, offered.y() - y, offered.z() - z, type.id()));
        }
        return found;
    }

    private static final int FIXTURE_MIN_X = 0;
    private static final int FIXTURE_MAX_X = 8;
    private static final int FIXTURE_MIN_Y = 64;
    private static final int FIXTURE_MAX_Y = 68;
    private static final int FIXTURE_MIN_Z = 0;
    private static final int FIXTURE_MAX_Z = 4;

    /**
     * A fixture giving all five movements something to offer.
     *
     * <p>Reading west to east along z=0 (with z=1,2 used only for the diagonal corner): a floored
     * cell at x=1 for traverse; a floored cell at x=1,z=2 with both corners clear for diagonal; a
     * step up at x=3 for ascend; a ledge at x=5 that drops one block onto a floor for descend; and
     * a floorless gap at x=7 with a landing at x=8 for parkour — floorless because
     * {@code ParkourMove} refuses a gap that is standable, since traverse already reaches the far
     * side in two cheaper steps.
     *
     * <p>{@code expand} is called from every coordinate in the declared extent regardless of
     * whether that coordinate is itself standable, so each destination only needs its own local
     * geometry correct; the origin coordinates below are not meant to be reachable from one
     * another.
     *
     * <ul>
     *   <li>TRAVERSE: origin (0,65,0) &rarr; (1,65,0), floored at y=64.
     *   <li>DIAGONAL: origin (0,65,1) &rarr; (1,65,2), floored at y=64, with corners (1,65,1) and
     *       (0,65,2) clear at feet and head.
     *   <li>ASCEND: origin (2,65,0) &rarr; (3,66,0), floored at y=65, with clearance at (2,67,0).
     *   <li>DESCEND: origin (4,66,0) &rarr; (5,65,0), a one-block drop onto a floor at y=64.
     *   <li>PARKOUR: origin (6,65,0) &rarr; (8,65,0) over an open, floorless gap at (7,*,0), with
     *       clearance at (6,67,0).
     * </ul>
     */
    private static final String COVERAGE_FIXTURE =
        "origin: 0,64,0\n"
        + "--- y=64\n"
        + ".#...#..#\n"
        + ".........\n"
        + ".#.......\n"
        + ".........\n"
        + ".........\n"
        + "--- y=65\n"
        + "...#.....\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + "--- y=66\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + "--- y=67\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + "--- y=68\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n"
        + ".........\n";
}
