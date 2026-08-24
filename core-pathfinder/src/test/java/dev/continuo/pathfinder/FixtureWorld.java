package dev.continuo.pathfinder;

import dev.continuo.core.BlockData;
import dev.continuo.core.BlockSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A world written as text art, implementing {@link BlockSource} directly.
 *
 * <p>This is the payoff B1 set up: a headless pathfinding test needs no {@code IBlockView}, no
 * classifier and no per-version table. It constructs {@link BlockData} values and hands them
 * over.
 *
 * <p><b>Format.</b> A header line declares the origin of the lowest slice's first cell, then one
 * slice per Y level, lowest first, contiguous. Within a slice, columns run +X and rows run +Z.
 *
 * <pre>
 * origin: 0,64,0
 * --- y=64
 * #####
 * #####
 * --- y=65
 * S...G
 * ..#..
 * </pre>
 *
 * <p>{@code S} and {@code G} mark the start and goal and read as air. Reads outside the declared
 * extent yield {@link BlockData#UNKNOWN}, never air — treating unmapped space as air is how a
 * pathfinder walks confidently into terrain it was never told about.
 *
 * <p>The renderer emits this same format, and its overlay characters read back as air, so a
 * failure dump pastes straight in as a new fixture. Lines starting with {@code //} are ignored,
 * which is how the renderer's summary line survives that round trip; no legend character is
 * {@code /}, so no terrain row can begin with one.
 */
final class FixtureWorld implements BlockSource {

    static final char START = 'S';
    static final char GOAL = 'G';
    static final char PATH = '*';
    static final char EXPANDED = '+';

    private final Map<Long, BlockData> blocks;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final Pos start;
    private final Pos goal;

    private FixtureWorld(Map<Long, BlockData> blocks, int minX, int maxX, int minY, int maxY,
                         int minZ, int maxZ, Pos start, Pos goal) {
        this.blocks = blocks;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.start = start;
        this.goal = goal;
    }

    /**
     * @param text the text art
     * @return the parsed world
     */
    static FixtureWorld parse(String text) {
        return parse(text, Collections.<Character, BlockData>emptyMap());
    }

    /**
     * @param text the text art
     * @param extra additional legend characters for this fixture only; may override the defaults
     * @return the parsed world
     */
    static FixtureWorld parse(String text, Map<Character, BlockData> extra) {
        Map<Character, BlockData> legend = new HashMap<Character, BlockData>(BlockLegend.legend());
        legend.putAll(extra);

        String[] lines = text.split("\r?\n");
        if (lines.length == 0 || !lines[0].startsWith("origin:")) {
            throw new IllegalArgumentException("first line must be 'origin: x,y,z', got: "
                + (lines.length == 0 ? "<empty>" : lines[0]));
        }

        String[] originParts = lines[0].substring("origin:".length()).trim().split(",");
        if (originParts.length != 3) {
            throw new IllegalArgumentException("origin must have three parts, got: " + lines[0]);
        }
        int originX = Integer.parseInt(originParts[0].trim());
        int originY = Integer.parseInt(originParts[1].trim());
        int originZ = Integer.parseInt(originParts[2].trim());

        Map<Long, BlockData> blocks = new HashMap<Long, BlockData>();
        Pos start = null;
        Pos goal = null;
        int width = -1;
        int depth = 0;
        int sliceCount = 0;
        int currentY = 0;
        int rowInSlice = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("--- y=")) {
                int declared = Integer.parseInt(line.substring("--- y=".length()).trim());
                int expected = originY + sliceCount;
                if (declared != expected) {
                    throw new IllegalArgumentException("slices must be contiguous and ascending;"
                        + " expected y=" + expected + " but found y=" + declared);
                }
                if (sliceCount == 1) {
                    depth = rowInSlice;
                } else if (sliceCount > 1 && rowInSlice != depth) {
                    throw new IllegalArgumentException("slice y=" + currentY + " has " + rowInSlice
                        + " rows but an earlier slice has " + depth);
                }
                currentY = declared;
                sliceCount++;
                rowInSlice = 0;
                continue;
            }
            if (sliceCount == 0) {
                throw new IllegalArgumentException("terrain before the first '--- y=' line: " + line);
            }
            if (width == -1) {
                width = line.length();
            } else if (line.length() != width) {
                throw new IllegalArgumentException("ragged row: expected " + width
                    + " characters but found " + line.length() + " in: " + line);
            }

            int y = currentY;
            int z = originZ + rowInSlice;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                int x = originX + c;
                if (ch == START || ch == GOAL || ch == PATH || ch == EXPANDED) {
                    if (ch == START) {
                        start = new Pos(x, y, z);
                    } else if (ch == GOAL) {
                        goal = new Pos(x, y, z);
                    }
                    blocks.put(Long.valueOf(Pos.pack(x, y, z)), BlockLegend.AIR);
                    continue;
                }
                BlockData data = legend.get(Character.valueOf(ch));
                if (data == null) {
                    throw new IllegalArgumentException("unknown legend character '" + ch
                        + "' at x=" + x + " y=" + y + " z=" + z);
                }
                blocks.put(Long.valueOf(Pos.pack(x, y, z)), data);
            }
            rowInSlice++;
        }

        if (sliceCount == 0) {
            throw new IllegalArgumentException("no slices; expected at least one '--- y=' line");
        }
        if (sliceCount == 1) {
            depth = rowInSlice;
        } else if (rowInSlice != depth) {
            throw new IllegalArgumentException("slice y=" + currentY + " has " + rowInSlice
                + " rows but an earlier slice has " + depth);
        }

        return new FixtureWorld(blocks, originX, originX + width - 1, originY,
            originY + sliceCount, originZ, originZ + depth - 1, start, goal);
    }

    @Override
    public BlockData at(int x, int y, int z) {
        BlockData found = blocks.get(Long.valueOf(Pos.pack(x, y, z)));
        return found == null ? BlockData.UNKNOWN : found;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    /** @return the lowest X in the extent, inclusive */
    int minX() {
        return minX;
    }

    /** @return the highest X in the extent, inclusive */
    int maxX() {
        return maxX;
    }

    /** @return the lowest Z in the extent, inclusive */
    int minZ() {
        return minZ;
    }

    /** @return the highest Z in the extent, inclusive */
    int maxZ() {
        return maxZ;
    }

    /** @return the position marked {@code S}, or {@code null} if the art marks none */
    Pos start() {
        return start;
    }

    /** @return the position marked {@code G}, or {@code null} if the art marks none */
    Pos goal() {
        return goal;
    }
}
