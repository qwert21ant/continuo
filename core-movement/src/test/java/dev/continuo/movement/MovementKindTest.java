package dev.continuo.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MovementKindTest {

    @Test
    void everyDeltaTheFourBuiltInsCanEmitIsNamed() {
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(1, 0, 0));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(-1, 0, 0));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(0, 0, 1));
        assertEquals(MovementKind.TRAVERSE, MovementKind.forDelta(0, 0, -1));

        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(1, 0, 1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(-1, 0, 1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(1, 0, -1));
        assertEquals(MovementKind.DIAGONAL, MovementKind.forDelta(-1, 0, -1));

        assertEquals(MovementKind.ASCEND, MovementKind.forDelta(1, 1, 0));
        assertEquals(MovementKind.ASCEND, MovementKind.forDelta(0, 1, -1));
    }

    @Test
    void descendNamesEveryDropDepthNotJustOne() {
        // DescendMove offers the landing rather than each level passed through, so a single step
        // can drop by up to MAX_SAFE_FALL. A table that only handled dy == -1 would return null
        // for a real four-block drop and stop the executor on legal terrain.
        for (int drop = 1; drop <= MovementCosts.MAX_SAFE_FALL; drop++) {
            assertEquals(MovementKind.DESCEND, MovementKind.forDelta(1, -drop, 0),
                "a " + drop + "-block drop is a descend");
            assertEquals(MovementKind.DESCEND, MovementKind.forDelta(0, -drop, 1),
                "a " + drop + "-block drop is a descend");
        }
    }

    @Test
    void parkourIsTwoAlongOneAxisOnly() {
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(2, 0, 0));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(-2, 0, 0));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(0, 0, 2));
        assertEquals(MovementKind.PARKOUR, MovementKind.forDelta(0, 0, -2));
    }

    @Test
    void nothingElseIsNamed() {
        // Each of these is a movement the registry cannot currently produce. Returning a kind for
        // one would mean the executor silently drove something the search never planned.
        assertNull(MovementKind.forDelta(0, 0, 0), "a repeated position is not a move");
        assertNull(MovementKind.forDelta(1, 1, 1), "no diagonal ascend exists");
        assertNull(MovementKind.forDelta(1, -1, 1), "no diagonal descend exists");
        assertNull(MovementKind.forDelta(3, 0, 0), "no three-block parkour exists");
        assertNull(MovementKind.forDelta(2, 0, 2), "no diagonal parkour exists");
        assertNull(MovementKind.forDelta(0, 2, 1), "nothing climbs two blocks in one step");
        assertNull(MovementKind.forDelta(2, 1, 0), "no parkour ascend exists");
        assertNull(MovementKind.forDelta(1, -(MovementCosts.MAX_SAFE_FALL + 1), 0),
            "no movement can emit a drop deeper than MAX_SAFE_FALL, so nothing names one");
    }

    @Test
    void everyConstantNamesAMovementId() {
        // The association section 5.3's guard checks the derivation against. A constant with a
        // typo'd id would make the guard compare the derivation to nothing.
        assertEquals("walk.traverse", MovementKind.TRAVERSE.movementId());
        assertEquals("walk.diagonal", MovementKind.DIAGONAL.movementId());
        assertEquals("walk.ascend", MovementKind.ASCEND.movementId());
        assertEquals("walk.descend", MovementKind.DESCEND.movementId());
        assertEquals("walk.parkour", MovementKind.PARKOUR.movementId());
    }
}
