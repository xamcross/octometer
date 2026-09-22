package octometer.kit.core.path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for one test of the shared case file
 * `contract/examples/C42-path-match-cases.json` (issue #104). It reads a
 * JSON string, a JSON array, and a JSON object; the case file holds no
 * other value shape. It is not a general JSON library, and this test
 * module uses no such library.
 */
final class MiniJson {

    private final String text;
    private final int length;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
        this.length = text.length();
    }

    /** Parses {@code text} as a top-level JSON array, and gives its entries. */
    static List<Object> parseArray(String text) {
        MiniJson reader = new MiniJson(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != reader.length) {
            throw new IllegalStateException("The case file holds text after the top-level value.");
        }
        return castList(value);
    }

    private Object readValue() {
        skipWhitespace();
        char c = text.charAt(pos);
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        throw new IllegalStateException("The case file holds an unsupported JSON value at position " + pos + ".");
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // consume '{'
        skipWhitespace();
        if (text.charAt(pos) == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            result.put(key, value);
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                break;
            }
            throw new IllegalStateException("The case file holds a malformed object.");
        }
        return result;
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++; // consume '['
        skipWhitespace();
        if (text.charAt(pos) == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                break;
            }
            throw new IllegalStateException("The case file holds a malformed array.");
        }
        return result;
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return result.toString();
            }
            if (c == '\\') {
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    case 'r' -> result.append('\r');
                    case 'u' -> {
                        int code = Integer.parseInt(text.substring(pos, pos + 4), 16);
                        result.append((char) code);
                        pos += 4;
                    }
                    default -> throw new IllegalStateException("The case file holds an unsupported escape.");
                }
            } else {
                result.append(c);
            }
        }
    }

    private void expect(char expected) {
        if (pos >= length || text.charAt(pos) != expected) {
            throw new IllegalStateException("The case file is missing the character '" + expected + "'.");
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < length && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
