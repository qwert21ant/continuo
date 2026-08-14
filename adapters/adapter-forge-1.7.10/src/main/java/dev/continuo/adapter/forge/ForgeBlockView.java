package dev.continuo.adapter.forge;

import dev.continuo.platform.BlockDescription;
import dev.continuo.platform.IBlockView;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Translation only.
 *
 * <p>Reports what 1.7.10 says about a block and nothing more. Notably it does <em>not</em>
 * normalise {@code flowing_water} to water — 1.7.10 registers still and flowing water as two
 * distinct blocks, and collapsing them is classification, which the core's per-version table
 * does instead.
 */
final class ForgeBlockView implements IBlockView {

    /** 1.7.10's world is fixed at 0..256. */
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 256;

    private final Minecraft minecraft;

    ForgeBlockView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public int stateId(int x, int y, int z) {
        World world = minecraft.theWorld;
        if (world == null) {
            return -1;
        }
        if (y < MIN_Y || y >= MAX_Y) {
            return -1;
        }
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return -1;
        }
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        // The format's own composition: 12 bits of block id, 4 of metadata.
        return (Block.getIdFromBlock(block) << 4) | (meta & 0xF);
    }

    @Override
    public BlockDescription describe(int x, int y, int z) {
        World world = minecraft.theWorld;
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);

        String id = String.valueOf(Block.blockRegistry.getNameForObject(block));

        return new BlockDescription(
            id,
            id + "#" + meta,
            collisionBoxes(world, block, x, y, z),
            fluidId(block, id),
            block.isLadder(world, x, y, z, null),
            block instanceof BlockFalling);
    }

    /**
     * The block's collision boxes, in block-relative coordinates.
     *
     * <p>The mask reaches two blocks above the target because fences and walls emit boxes 1.5
     * tall, and {@code addCollisionBoxesToList} only adds a box that intersects the mask.
     */
    private static double[] collisionBoxes(World world, Block block, int x, int y, int z) {
        // Base Block.addCollisionBoxesToList reads the bounds fields, and vanilla does not set
        // them first. Setting them here is strictly more correct, and harmless for the blocks
        // that override the method, since those set their own bounds internally.
        block.setBlockBoundsBasedOnState(world, x, y, z);

        AxisAlignedBB mask = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 2.0D, z + 1.0D);
        List<AxisAlignedBB> collected = new ArrayList<AxisAlignedBB>();
        block.addCollisionBoxesToList(world, x, y, z, mask, collected, null);

        double[] boxes = new double[collected.size() * 6];
        for (int i = 0; i < collected.size(); i++) {
            // Copied out immediately: these instances come from a pool that is cleared and
            // reused, so retaining one and reading it later yields another block's geometry.
            AxisAlignedBB b = collected.get(i);
            boxes[i * 6] = b.minX - x;
            boxes[i * 6 + 1] = b.minY - y;
            boxes[i * 6 + 2] = b.minZ - z;
            boxes[i * 6 + 3] = b.maxX - x;
            boxes[i * 6 + 4] = b.maxY - y;
            boxes[i * 6 + 5] = b.maxZ - z;
        }
        return boxes;
    }

    /**
     * The block's own registry name when it is a fluid block, reported verbatim.
     *
     * <p>1.7.10 has no separate fluid concept — water <em>is</em> a block — so the fluid id is
     * the block id, and the per-version table maps {@code minecraft:flowing_water} onto water.
     */
    private static String fluidId(Block block, String id) {
        Material material = block.getMaterial();
        if (material == Material.water || material == Material.lava) {
            return id;
        }
        return null;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        World world = minecraft.theWorld;
        return world != null && world.getChunkProvider().chunkExists(chunkX, chunkZ);
    }

    @Override
    public int minY() {
        return MIN_Y;
    }

    @Override
    public int maxY() {
        return MAX_Y;
    }
}
