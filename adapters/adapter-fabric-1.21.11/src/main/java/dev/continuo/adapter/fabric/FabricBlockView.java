package dev.continuo.adapter.fabric;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Translation only.
 *
 * <p>Reports what 1.21.11 says about a block and nothing more. Every judgement — what a set of
 * boxes means, whether a fluid id counts as water, which blocks to avoid — belongs to the
 * core's shared classifier, so that this adapter and the 1.7.10 one cannot reach different
 * conclusions about the same block.
 */
final class FabricBlockView implements IBlockView {

    private final Minecraft minecraft;

    FabricBlockView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public int stateId(int x, int y, int z) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return -1;
        }
        if (y < minY() || y >= maxY()) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        return Block.getId(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        ClientLevel level = minecraft.level;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);

        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        // toAabbs() returns block-relative boxes, which is what BlockDescription wants.
        List<AABB> aabbs = state.getCollisionShape(level, pos).toAabbs();
        double[] boxes = new double[aabbs.size() * 6];
        for (int i = 0; i < aabbs.size(); i++) {
            AABB b = aabbs.get(i);
            boxes[i * 6] = b.minX;
            boxes[i * 6 + 1] = b.minY;
            boxes[i * 6 + 2] = b.minZ;
            boxes[i * 6 + 3] = b.maxX;
            boxes[i * 6 + 4] = b.maxY;
            boxes[i * 6 + 5] = b.maxZ;
        }

        FluidState fluid = state.getFluidState();
        String fluidId = fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();

        return new BlockDescription(
            id,
            stateKey(id, state),
            boxes,
            fluidId,
            state.is(BlockTags.CLIMBABLE),
            state.getBlock() instanceof FallingBlock);
    }

    /**
     * {@code minecraft:oak_slab[type=bottom,waterlogged=false]}.
     *
     * <p>Built by hand rather than from {@code BlockState.toString()}, which wraps the block in
     * {@code Block{...}} and would not match the key format the 1.7.10 tables use.
     */
    private static String stateKey(String id, BlockState state) {
        Map<Property<?>, Comparable<?>> values = state.getValues();
        if (values.isEmpty()) {
            return id;
        }
        StringBuilder out = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(entry.getKey().getName()).append('=').append(entry.getValue());
        }
        return out.append(']').toString();
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ClientLevel level = minecraft.level;
        // Must go through getChunkSource().hasChunk(...), not level.hasChunk(...) directly:
        // ClientLevel.hasChunk(int, int) (ClientLevel.java:405-408) is a hardcoded
        // "return true;" stub. That shorter spelling reads as the obvious choice but silently
        // reports every chunk as loaded on this version only.
        return level != null && level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    @Override
    public int minY() {
        ClientLevel level = minecraft.level;
        return level == null ? 0 : level.getMinY();
    }

    @Override
    public int maxY() {
        ClientLevel level = minecraft.level;
        // Level.getMaxY() is INCLUSIVE (confirmed in LevelHeightAccessor.java: getMinY() +
        // getHeight() - 1, i.e. 319 in the overworld), but IBlockView.maxY() is contractually
        // EXCLUSIVE and documents 320 for the overworld. The +1 converts between the two; get
        // it wrong and the top world layer becomes silently unreadable on this version only.
        return level == null ? 0 : level.getMaxY() + 1;
    }
}
