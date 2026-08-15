package dev.continuo.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A node of the tiny JSON subset the block tables are written in.
 *
 * <p>The subset is objects, strings, and arrays of strings — nothing else. Numbers, booleans,
 * {@code null} and nested containers are parse errors rather than values, because the tables
 * have no use for them and a reader that silently accepts a typo is worse than one that stops.
 *
 * @see JsonReader
 */
public final class JsonValue {

    private final Map<String, JsonValue> object;
    private final String string;
    private final List<String> array;

    private JsonValue(Map<String, JsonValue> object, String string, List<String> array) {
        this.object = object;
        this.string = string;
        this.array = array;
    }

    static JsonValue ofObject(Map<String, JsonValue> value) {
        return new JsonValue(Collections.unmodifiableMap(value), null, null);
    }

    static JsonValue ofString(String value) {
        return new JsonValue(null, value, null);
    }

    static JsonValue ofArray(List<String> value) {
        return new JsonValue(null, null, Collections.unmodifiableList(value));
    }

    /**
     * Parses a document. The root must be an object.
     *
     * @param text the document
     * @return the root value
     * @throws IllegalArgumentException if the text is not valid within this subset
     */
    public static JsonValue parse(String text) {
        return new JsonReader(text).parseDocument();
    }

    /** @return whether this is an object */
    public boolean isObject() {
        return object != null;
    }

    /** @return whether this is a string */
    public boolean isString() {
        return string != null;
    }

    /** @return whether this is an array */
    public boolean isArray() {
        return array != null;
    }

    /**
     * @return this value's members, unmodifiable
     * @throws IllegalStateException if this is not an object
     */
    public Map<String, JsonValue> asObject() {
        if (object == null) {
            throw new IllegalStateException("not an object: " + this);
        }
        return object;
    }

    /**
     * @return this value's text
     * @throws IllegalStateException if this is not a string
     */
    public String asString() {
        if (string == null) {
            throw new IllegalStateException("not a string: " + this);
        }
        return string;
    }

    /**
     * @return this value's elements, unmodifiable
     * @throws IllegalStateException if this is not an array
     */
    public List<String> asStringArray() {
        if (array == null) {
            throw new IllegalStateException("not an array: " + this);
        }
        return array;
    }

    @Override
    public String toString() {
        if (object != null) {
            return "object" + object.keySet();
        }
        if (array != null) {
            return "array" + array;
        }
        return "string \"" + string + "\"";
    }
}
