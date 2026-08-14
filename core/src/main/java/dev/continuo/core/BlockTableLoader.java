package dev.continuo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a {@link BlockTable} from the JSON shipped as a core resource.
 *
 * <p>Strict on purpose. An unrecognised shape, fluid or tag name, an unknown key, or a missing
 * section fails loudly rather than being skipped, because a silently-ignored typo in a data
 * table produces a bot that paths wrongly for reasons nobody can find.
 *
 * <p>One thing is deliberately <em>not</em> an error: an unrecognised game version yields an
 * empty table rather than throwing. Geometry is the designed default classification path, so a
 * version with no table still works — it simply has no overrides.
 */
public final class BlockTableLoader {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final Set<String> TOP_LEVEL_KEYS =
        new HashSet<String>(Arrays.asList("version", "blocks", "states"));

    private static final Set<String> ROW_KEYS =
        new HashSet<String>(Arrays.asList("shape", "fluid", "tags"));

    private BlockTableLoader() {
    }

    /**
     * Loads the table shipped for a game version.
     *
     * @param version the value of {@code IPlatformInfo.gameVersion()}, such as {@code "1.7.10"}
     * @return that version's table, or {@link BlockTable#EMPTY} if none is shipped
     * @throws IllegalArgumentException if a shipped table exists but is malformed
     */
    public static BlockTable forVersion(String version) {
        if (version == null) {
            return BlockTable.EMPTY;
        }
        String path = "/blocks/" + version + ".json";
        InputStream in = BlockTableLoader.class.getResourceAsStream(path);
        if (in == null) {
            return BlockTable.EMPTY;
        }
        try {
            return parse(readAll(in));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Block table " + path + " is invalid: " + e.getMessage(), e);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Parses a table document.
     *
     * @param json the document
     * @return the parsed table
     * @throws IllegalArgumentException if the document is malformed or names anything unknown
     */
    public static BlockTable parse(String json) {
        Map<String, JsonValue> root = JsonValue.parse(json).asObject();
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "unknown top-level key \"" + key + "\"; expected one of " + TOP_LEVEL_KEYS);
            }
        }
        require(root, "version");
        require(root, "blocks");
        require(root, "states");
        root.get("version").asString();
        return new BlockTable(
            parseSection(root.get("blocks").asObject()),
            parseSection(root.get("states").asObject()));
    }

    private static void require(Map<String, JsonValue> root, String key) {
        if (!root.containsKey(key)) {
            throw new IllegalArgumentException("missing required key \"" + key + "\"");
        }
    }

    private static Map<String, BlockTable.Row> parseSection(Map<String, JsonValue> section) {
        Map<String, BlockTable.Row> rows = new LinkedHashMap<String, BlockTable.Row>();
        for (Map.Entry<String, JsonValue> entry : section.entrySet()) {
            rows.put(entry.getKey(), parseRow(entry.getKey(), entry.getValue().asObject()));
        }
        return rows;
    }

    private static BlockTable.Row parseRow(String key, Map<String, JsonValue> row) {
        for (String k : row.keySet()) {
            if (!ROW_KEYS.contains(k)) {
                throw new IllegalArgumentException(
                    "unknown key \"" + k + "\" in row \"" + key + "\"; expected one of " + ROW_KEYS);
            }
        }
        BlockShape shape = null;
        if (row.containsKey("shape")) {
            shape = constant(BlockShape.class, row.get("shape").asString(), key);
        }
        Fluid fluid = null;
        if (row.containsKey("fluid")) {
            fluid = constant(Fluid.class, row.get("fluid").asString(), key);
        }
        EnumSet<BlockTag> tags = EnumSet.noneOf(BlockTag.class);
        if (row.containsKey("tags")) {
            List<String> names = row.get("tags").asStringArray();
            for (String name : names) {
                tags.add(constant(BlockTag.class, name, key));
            }
        }
        return new BlockTable.Row(shape, fluid, tags);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String key) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "\"" + name + "\" in row \"" + key + "\" is not a " + type.getSimpleName()
                    + "; expected one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read the block table: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the table has already been read or has already failed.
        }
    }
}
