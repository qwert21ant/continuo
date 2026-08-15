package dev.continuo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict recursive-descent reader for the block tables' JSON subset.
 *
 * <p>Package-private; callers use {@link JsonValue#parse(String)}. Every failure carries the
 * character offset, because the whole point of parsing strictly is that a human can find the
 * typo.
 */
final class JsonReader {

    private final String text;
    private int pos;

    JsonReader(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        this.text = text;
    }

    JsonValue parseDocument() {
        skipWhitespace();
        if (peek() != '{') {
            throw error("the root of a block table must be an object");
        }
        JsonValue root = parseObject();
        skipWhitespace();
        if (pos != text.length()) {
            throw error("trailing content after the root object");
        }
        return root;
    }

    private JsonValue parseValue() {
        skipWhitespace();
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseStringArray();
        }
        if (c == '"') {
            return JsonValue.ofString(parseString());
        }
        throw error("expected an object, an array or a string, but found '" + c + "'");
    }

    private JsonValue parseObject() {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<String, JsonValue>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return JsonValue.ofObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a quoted member name");
            }
            String key = parseString();
            if (members.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            skipWhitespace();
            expect(':');
            members.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return JsonValue.ofObject(members);
            }
            throw error("expected ',' or '}' but found '" + c + "'");
        }
    }

    private JsonValue parseStringArray() {
        expect('[');
        List<String> elements = new ArrayList<String>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return JsonValue.ofArray(elements);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("arrays in a block table may only contain strings");
            }
            elements.add(parseString());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return JsonValue.ofArray(elements);
            }
            throw error("expected ',' or ']' but found '" + c + "'");
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"':  out.append('"');  break;
                case '\\': out.append('\\'); break;
                case '/':  out.append('/');  break;
                case 'n':  out.append('\n'); break;
                case 't':  out.append('\t'); break;
                case 'r':  out.append('\r'); break;
                default:
                    throw error("unsupported escape '\\" + esc + "'");
            }
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("expected '" + expected + "' but found '" + peek() + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Block table JSON is invalid at offset " + pos + ": " + message);
    }
}
