package dev.continuo.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDescriptionTest {

    private static final double[] FULL_CUBE = {0, 0, 0, 1, 1, 1};

    private static BlockDescription stone() {
        return new BlockDescription(
            "minecraft:stone", "minecraft:stone", FULL_CUBE.clone(), null, false, false);
    }

    @Test
    void exposesWhatItWasGiven() {
        BlockDescription d = stone();
        assertEquals("minecraft:stone", d.id());
        assertEquals("minecraft:stone", d.stateKey());
        assertArrayEquals(FULL_CUBE, d.collisionBoxes());
        assertNull(d.fluidId());
        assertFalse(d.climbable());
        assertFalse(d.gravity());
    }

    @Test
    void copiesTheBoxArrayIn() {
        double[] caller = FULL_CUBE.clone();
        BlockDescription d = new BlockDescription("a:b", "a:b", caller, null, false, false);
        caller[4] = 99.0;
        assertEquals(1.0, d.collisionBoxes()[4], 0.0, "mutating the caller's array must not change the description");
    }

    @Test
    void copiesTheBoxArrayOut() {
        BlockDescription d = stone();
        double[] first = d.collisionBoxes();
        first[4] = 99.0;
        assertEquals(1.0, d.collisionBoxes()[4], 0.0, "mutating a returned array must not change the description");
        assertNotSame(first, d.collisionBoxes());
    }

    @Test
    void acceptsAnEmptyBoxArrayMeaningNoCollision() {
        BlockDescription d = new BlockDescription("a:air", "a:air", new double[0], null, false, false);
        assertEquals(0, d.collisionBoxes().length);
    }

    @Test
    void acceptsAFluidId() {
        BlockDescription d = new BlockDescription(
            "minecraft:water", "minecraft:water", new double[0], "minecraft:water", false, false);
        assertEquals("minecraft:water", d.fluidId());
    }

    @Test
    void carriesTheClimbableAndGravityFlags() {
        BlockDescription d = new BlockDescription("a:b", "a:b", new double[0], null, true, true);
        assertTrue(d.climbable());
        assertTrue(d.gravity());
    }

    @Test
    void rejectsANullId() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription(null, "a:b", new double[0], null, false, false));
    }

    @Test
    void rejectsANullStateKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", null, new double[0], null, false, false));
    }

    @Test
    void rejectsANullBoxArray() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", "a:b", null, null, false, false));
    }

    @Test
    void rejectsABoxArrayThatIsNotAWholeNumberOfSixTuples() {
        assertThrows(IllegalArgumentException.class, () ->
            new BlockDescription("a:b", "a:b", new double[]{0, 0, 0, 1, 1}, null, false, false));
    }
}
