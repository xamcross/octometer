package octometer.kit.core.ingest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A strict parser for the one ingest body shape of design section 4.2
 * (`contract/README.md`, rules C13, C17, C18, C32). It is a hand-written
 * parser, not a general JSON library.
 *
 * <p>The parser stays safe with a hostile body: a body above 16 KB fails
 * at once, a deeply nested value fails at a fixed depth, and a number
 * field never grows past a `long`. Each check runs in linear time, thus
 * a large body never causes a long run time or a {@link StackOverflowError}.
 */
public final class IngestParser {

    /** The body size limit of rule C18. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    /** The batch limit of rule C17. */
    static final int MAX_CLICKS = 50;

    /**
     * The nesting limit of an object or an array. The one valid shape
     * nests three levels deep (the body, the `clicks` array, and one
     * click object), thus this limit leaves room for an unknown field
     * with its own small structure, and it still stops a deep-nesting
     * attack well before the JVM call stack would overflow.
     */
    static final int MAX_DEPTH = 32;

    private final String body;
    private final int length;
    private int pos;
    private int depth;

    private IngestParser(String body) {
        this.body = body;
        this.length = body.length();
    }

    /**
     * Parses a raw ingest body. It throws {@link IngestException} for a
     * body above the size limit, for invalid JSON, for the wrong
     * top-level type, for a missing field, for the wrong field type, and
     * for a batch above the click limit. It ignores an unknown field
     * (rule C32).
     */
    public static ParsedIngestRequest parse(String rawBody) {
        checkBodySize(rawBody);
        IngestParser parser = new IngestParser(rawBody);
        ParsedIngestRequest result = parser.parseTopLevel();
        parser.skipWhitespace();
        if (parser.pos != parser.length) {
            throw parser.invalidJson();
        }
        return result;
    }

    private static void checkBodySize(String rawBody) {
        // A UTF-8 encoding never needs fewer bytes than the string has
        // UTF-16 chars, thus this check rejects a huge hostile body at
        // once, with no need to encode it first.
        if (rawBody.length() > MAX_BODY_BYTES) {
            throw new IngestException(IngestException.Reason.BODY_TOO_LARGE,
                    "The body is above the 16 KB limit.");
        }
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IngestException(IngestException.Reason.BODY_TOO_LARGE,
                    "The body is above the 16 KB limit.");
        }
    }

    private ParsedIngestRequest parseTopLevel() {
        skipWhitespace();
        if (pos >= length || body.charAt(pos) != '{') {
            throw new IngestException(IngestException.Reason.WRONG_TOP_LEVEL_TYPE,
                    "The body must be a JSON object.");
        }
        return parseIngestObject();
    }

    private ParsedIngestRequest parseIngestObject() {
        pos++; // consume '{'
        enterContainer();

        String sessionId = null;
        boolean sessionIdSeen = false;
        List<ParsedClick> clicks = null;
        boolean clicksSeen = false;

        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                String key = parseRawString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if (key.equals("sessionId") && !sessionIdSeen) {
                    sessionId = parseStringValue();
                    sessionIdSeen = true;
                } else if (key.equals("clicks") && !clicksSeen) {
                    clicks = parseClicksArray();
                    clicksSeen = true;
                } else {
                    skipValue();
                }

                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw invalidJson();
            }
        }
        exitContainer();

        if (!sessionIdSeen) {
            throw new IngestException(IngestException.Reason.MISSING_FIELD,
                    "The body is missing the sessionId field.");
        }
        if (!clicksSeen) {
            throw new IngestException(IngestException.Reason.MISSING_FIELD,
                    "The body is missing the clicks field.");
        }
        return new ParsedIngestRequest(sessionId, clicks);
    }

    private List<ParsedClick> parseClicksArray() {
        if (pos >= length || body.charAt(pos) != '[') {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "The clicks field must be a JSON array.");
        }
        pos++; // consume '['
        enterContainer();

        List<ParsedClick> result = new ArrayList<>();
        skipWhitespace();
        if (pos < length && body.charAt(pos) == ']') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                result.add(parseClickObject());
                if (result.size() > MAX_CLICKS) {
                    throw new IngestException(IngestException.Reason.TOO_MANY_CLICKS,
                            "The clicks array must not have more than 50 entries.");
                }
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw invalidJson();
            }
        }
        exitContainer();
        return result;
    }

    private ParsedClick parseClickObject() {
        if (pos >= length || body.charAt(pos) != '{') {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "Each click entry must be a JSON object.");
        }
        pos++; // consume '{'
        enterContainer();

        String element = null;
        boolean elementSeen = false;
        long ageMs = 0;
        boolean ageMsSeen = false;

        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                String key = parseRawString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if (key.equals("element") && !elementSeen) {
                    element = parseStringValue();
                    elementSeen = true;
                } else if (key.equals("ageMs") && !ageMsSeen) {
                    ageMs = parseAgeMsValue();
                    ageMsSeen = true;
                } else {
                    skipValue();
                }

                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw invalidJson();
            }
        }
        exitContainer();

        if (!elementSeen) {
            throw new IngestException(IngestException.Reason.MISSING_FIELD,
                    "A click entry is missing the element field.");
        }
        if (!ageMsSeen) {
            throw new IngestException(IngestException.Reason.MISSING_FIELD,
                    "A click entry is missing the ageMs field.");
        }
        return new ParsedClick(element, ageMs);
    }

    // -- Generic value skip, for an unknown field (rule C32) --------------

    private void skipValue() {
        skipWhitespace();
        if (pos >= length) {
            throw invalidJson();
        }
        char c = body.charAt(pos);
        switch (c) {
            case '"' -> parseRawString();
            case '{' -> skipObject();
            case '[' -> skipArray();
            case 't' -> expectLiteral("true");
            case 'f' -> expectLiteral("false");
            case 'n' -> expectLiteral("null");
            default -> skipNumber();
        }
    }

    private void skipObject() {
        pos++; // consume '{'
        enterContainer();
        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                parseRawString(); // a key
                skipWhitespace();
                expect(':');
                skipWhitespace();
                skipValue();
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw invalidJson();
            }
        }
        exitContainer();
    }

    private void skipArray() {
        pos++; // consume '['
        enterContainer();
        skipWhitespace();
        if (pos < length && body.charAt(pos) == ']') {
            pos++;
        } else {
            while (true) {
                skipValue();
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw invalidJson();
            }
        }
        exitContainer();
    }

    private void skipNumber() {
        int start = pos;
        if (pos < length && body.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= length || !isDigit(body.charAt(pos))) {
            throw invalidJson();
        }
        if (body.charAt(pos) == '0') {
            pos++;
        } else {
            while (pos < length && isDigit(body.charAt(pos))) {
                pos++;
            }
        }
        if (pos < length && body.charAt(pos) == '.') {
            pos++;
            if (pos >= length || !isDigit(body.charAt(pos))) {
                throw invalidJson();
            }
            while (pos < length && isDigit(body.charAt(pos))) {
                pos++;
            }
        }
        if (pos < length && (body.charAt(pos) == 'e' || body.charAt(pos) == 'E')) {
            pos++;
            if (pos < length && (body.charAt(pos) == '+' || body.charAt(pos) == '-')) {
                pos++;
            }
            if (pos >= length || !isDigit(body.charAt(pos))) {
                throw invalidJson();
            }
            while (pos < length && isDigit(body.charAt(pos))) {
                pos++;
            }
        }
        if (pos == start) {
            throw invalidJson();
        }
    }

    private void expectLiteral(String literal) {
        if (pos + literal.length() > length || !body.regionMatches(pos, literal, 0, literal.length())) {
            throw invalidJson();
        }
        pos += literal.length();
    }

    // -- A required field value --------------------------------------------

    private String parseStringValue() {
        if (pos >= length || body.charAt(pos) != '"') {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "The value must be a JSON string.");
        }
        return parseRawString();
    }

    /**
     * Parses `ageMs` as a plain JSON integer (no fraction, no exponent).
     * It never grows a number past a {@code long}: a longer digit run
     * still advances the cursor at a linear cost, and it then throws
     * {@link IngestException} with the reason {@code NUMBER_TOO_LARGE}.
     */
    private long parseAgeMsValue() {
        if (pos >= length) {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "The ageMs value must be an integer.");
        }
        boolean negative = false;
        if (body.charAt(pos) == '-') {
            negative = true;
            pos++;
        }
        if (pos >= length || !isDigit(body.charAt(pos))) {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "The ageMs value must be an integer.");
        }

        long value = 0;
        boolean overflow = false;
        if (body.charAt(pos) == '0') {
            pos++;
        } else {
            while (pos < length && isDigit(body.charAt(pos))) {
                int digit = body.charAt(pos) - '0';
                pos++;
                if (!overflow) {
                    if (value > (Long.MAX_VALUE - digit) / 10) {
                        overflow = true;
                    } else {
                        value = value * 10 + digit;
                    }
                }
            }
        }

        if (pos < length && (body.charAt(pos) == '.' || body.charAt(pos) == 'e' || body.charAt(pos) == 'E')) {
            throw new IngestException(IngestException.Reason.WRONG_FIELD_TYPE,
                    "The ageMs value must be a plain integer.");
        }
        if (overflow) {
            throw new IngestException(IngestException.Reason.NUMBER_TOO_LARGE,
                    "The ageMs value is too large.");
        }
        return negative ? -value : value;
    }

    // -- A JSON string, used for a key and for a string value -------------

    private String parseRawString() {
        if (pos >= length || body.charAt(pos) != '"') {
            throw invalidJson();
        }
        pos++; // consume the opening quote
        StringBuilder result = new StringBuilder();
        while (true) {
            if (pos >= length) {
                throw invalidJson();
            }
            char c = body.charAt(pos++);
            if (c == '"') {
                return result.toString();
            }
            if (c == '\\') {
                if (pos >= length) {
                    throw invalidJson();
                }
                char escape = body.charAt(pos++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > length) {
                            throw invalidJson();
                        }
                        String hex = body.substring(pos, pos + 4);
                        int codeUnit;
                        try {
                            codeUnit = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            throw invalidJson();
                        }
                        result.append((char) codeUnit);
                        pos += 4;
                    }
                    default -> throw invalidJson();
                }
            } else if (c < 0x20) {
                throw invalidJson();
            } else {
                result.append(c);
            }
        }
    }

    // -- Low-level helpers --------------------------------------------------

    private void enterContainer() {
        depth++;
        if (depth > MAX_DEPTH) {
            throw new IngestException(IngestException.Reason.INVALID_JSON,
                    "The body nests a JSON value too deeply.");
        }
    }

    private void exitContainer() {
        depth--;
    }

    private char next() {
        if (pos >= length) {
            throw invalidJson();
        }
        return body.charAt(pos++);
    }

    private void expect(char expected) {
        if (pos >= length || body.charAt(pos) != expected) {
            throw invalidJson();
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < length) {
            char c = body.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private IngestException invalidJson() {
        return new IngestException(IngestException.Reason.INVALID_JSON,
                "The body is not valid JSON for the one ingest shape.");
    }
}
