package dev.continuo.movement;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MutableExpansionContextTest {

    private static final BlockSource WORLD = new BlockSource() {
        @Override
        public BlockData at(int x, int y, int z) {
            return BlockData.UNKNOWN;
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

    @Test
    void itReportsThePositionItWasMovedTo() {
        MutableExpansionContext ctx = new MutableExpansionContext(WORLD);
        ctx.moveTo(3, 64, -7);

        assertSame(WORLD, ctx.world());
        assertEquals(3, ctx.x());
        assertEquals(64, ctx.y());
        assertEquals(-7, ctx.z());
    }

    @Test
    void oneInstanceIsReusedAcrossPositions() {
        MutableExpansionContext ctx = new MutableExpansionContext(WORLD);
        ctx.moveTo(0, 0, 0);
        ctx.moveTo(1, 2, 3);

        assertEquals(1, ctx.x());
        assertEquals(2, ctx.y());
        assertEquals(3, ctx.z());
    }

    @Test
    void aNullWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new MutableExpansionContext(null);
            }
        });
    }
}
