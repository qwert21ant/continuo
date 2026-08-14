package dev.continuo.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReaderTest {

    private static JsonValue parse(String text) {
        return JsonValue.parse(text);
    }

    @Test
    void parsesAnEmptyObject() {
        assertTrue(parse("{}").isObject());
        assertTrue(parse("{}").asObject().isEmpty());
    }

    @Test
    void parsesAFlatStringObject() {
        Map<String, JsonValue> o = parse("{\"a\": \"b\"}").asObject();
        assertEquals(1, o.size());
        assertEquals("b", o.get("a").asString());
    }

    @Test
    void parsesNestedObjects() {
        Map<String, JsonValue> o = parse("{\"outer\": {\"inner\": \"v\"}}").asObject();
        assertEquals("v", o.get("outer").asObject().get("inner").asString());
    }

    @Test
    void parsesAStringArray() {
        JsonValue v = parse("{\"tags\": [\"SLOW\", \"AVOID\"]}").asObject().get("tags");
        assertTrue(v.isArray());
        assertEquals(Arrays.asList("SLOW", "AVOID"), v.asStringArray());
    }

    @Test
    void parsesAnEmptyArray() {
        assertEquals(0, parse("{\"tags\": []}").asObject().get("tags").asStringArray().size());
    }

    @Test
    void ignoresWhitespaceAndNewlines() {
        Map<String, JsonValue> o = parse("{\n  \"a\" :\t\"b\" ,\r\n \"c\" : \"d\"\n}").asObject();
        assertEquals("b", o.get("a").asString());
        assertEquals("d", o.get("c").asString());
    }

    @Test
    void handlesEscapesInStrings() {
        assertEquals("a\"b\\c\nd", parse("{\"k\": \"a\\\"b\\\\c\\nd\"}").asObject().get("k").asString());
    }

    @Test
    void rejectsNumbers() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> parse("{\"a\": 1}"));
        assertTrue(e.getMessage().contains("offset"), "message must locate the failure: " + e.getMessage());
    }

    @Test
    void rejectsBooleans() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": true}"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": null}"));
    }

    @Test
    void rejectsNestedArrays() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": [[\"b\"]]}"));
    }

    @Test
    void rejectsAnObjectInsideAnArray() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": [{}]}"));
    }

    @Test
    void rejectsATrailingComma() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\",}"));
    }

    @Test
    void rejectsAnUnterminatedObject() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\""));
    }

    @Test
    void rejectsAnUnterminatedString() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b}"));
    }

    @Test
    void rejectsTrailingContentAfterTheRootValue() {
        assertThrows(IllegalArgumentException.class, () -> parse("{} junk"));
    }

    @Test
    void rejectsANonObjectRoot() {
        assertThrows(IllegalArgumentException.class, () -> parse("\"just a string\""));
    }

    @Test
    void rejectsADuplicateKey() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"a\": \"b\", \"a\": \"c\"}"));
    }

    @Test
    void asStringOnAnObjectIsAnError() {
        assertThrows(IllegalStateException.class, () -> parse("{\"a\": {}}").asObject().get("a").asString());
    }
}
