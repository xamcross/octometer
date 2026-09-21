package octometer.kit.core.ingest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A strict parser for the one ingest body shape of design section 4.2
 * (`contract/README.md`, rules C13, C17, C18, C32, C36, C37). It also
 * reads the optional fields `path` and `referrerHost` of a click entry
 * (rules C39, C40), with no shape check at this level (issue #103).
 * It is a hand-written parser. It is not a general JSON library.
 * {@link IngestPipeline} is the public entry point of this module; a
 * caller must not reach this class from outside the package.
 *
 * <p>The parser stays safe with a hostile body.
 * <ul>
 *   <li>A body above 16 KB fails at once.</li>
 *   <li>A deeply nested value fails at a fixed depth.</li>
 *   <li>A number field never grows past a {@code long}.</li>
 * </ul>
 * <p>Each check runs in linear time. A large body never causes a long
 * run time or a {@link StackOverflowError}.
 */
final class IngestParser {

    /** The body size limit of rule C18. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    /** The batch limit of rule C17. */
    static final int MAX_CLICKS = 50;

    /**
     * The nesting limit of an object or an array.
     * The one valid shape nests three levels deep: the body, the `clicks`
     * array, and one click object. This limit leaves room for a small
     * unknown field. It also stops a deep body before the JVM call stack
     * overflows.
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
     * Parses a raw ingest body.
     * It ignores an unknown field (rule C32). It throws
     * {@link IngestException} for each of these reasons:
     * <ul>
     *   <li>the body is above the size limit;</li>
     *   <li>the body holds invalid JSON;</li>
     *   <li>the body holds a duplicate key in one JSON object;</li>
     *   <li>the top-level value has the wrong type;</li>
     *   <li>a required field is absent;</li>
     *   <li>a field has the wrong JSON type;</li>
     *   <li>the batch is above the click limit.</li>
     * </ul>
     */
    static ParsedIngestRequest parse(String rawBody) {
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
        // UTF-16 chars. This check rejects a huge hostile body at once.
        // It never needs to encode the body first.
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
        if (pos < length && body.charAt(pos) == '﻿') {
            throw new IngestException(IngestException.Reason.WRONG_TOP_LEVEL_TYPE,
                    "The body starts with a byte order mark.");
        }
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
        Set<String> seenKeys = new HashSet<>();

        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                String key = parseRawString();
                if (!seenKeys.add(key)) {
                    throw duplicateField();
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if (key.equals("sessionId")) {
                    sessionId = parseStringValue();
                    sessionIdSeen = true;
                } else if (key.equals("clicks")) {
                    clicks = parseClicksArray();
                    clicksSeen = true;
                } else {
                    skipValue();
                }

                skipWhitespace();
                char c = next();
                if (c == ',') {
                    rejectTrailingComma('}');
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
                if (result.size() == MAX_CLICKS) {
                    throw new IngestException(IngestException.Reason.TOO_MANY_CLICKS,
                            "The clicks array must not have more than 50 entries.");
                }
                result.add(parseClickObject());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    rejectTrailingComma(']');
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
        String path = null;
        String referrerHost = null;
        Set<String> seenKeys = new HashSet<>();

        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                String key = parseRawString();
                if (!seenKeys.add(key)) {
                    throw duplicateField();
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if (key.equals("element")) {
                    element = parseStringValue();
                    elementSeen = true;
                } else if (key.equals("ageMs")) {
                    ageMs = parseAgeMsValue();
                    ageMsSeen = true;
                } else if (key.equals("path")) {
                    path = parseOptionalStringValue();
                } else if (key.equals("referrerHost")) {
                    referrerHost = parseOptionalStringValue();
                } else {
                    skipValue();
                }

                skipWhitespace();
                char c = next();
                if (c == ',') {
                    rejectTrailingComma('}');
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
        return new ParsedClick(element, ageMs, path, referrerHost);
    }

    /**
     * Reads `path` or `referrerHost` (rules C39, C40). Each field is a
     * plain JSON string, with no shape check at this level. A value of a
     * JSON type other than a string is not an error at this level; this
     * method skips it and returns {@code null}, the same as an absent
     * field. {@link IngestPipeline#process} holds the shape check, and
     * rule C41 makes an invalid value non-fatal, so this parser stays
     * lenient about the JSON type too.
     */
    private String parseOptionalStringValue() {
        skipWhitespace();
        if (pos < length && body.charAt(pos) == '"') {
            return parseRawString();
        }
        skipValue();
        return null;
    }

    // -- Generic value skip, for an unknown field (rule C32) --------------

    private void skipValue() {
        skipWhitespace();
        if (pos >= length) {
            throw invalidJson();
        }
        char c = body.charAt(pos);
        switch (c) {
            case '"' -> skipRawStringValue();
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
        Set<String> seenKeys = new HashSet<>();
        skipWhitespace();
        if (pos < length && body.charAt(pos) == '}') {
            pos++;
        } else {
            while (true) {
                skipWhitespace();
                String key = parseRawString(); // kept only for the duplicate check
                if (!seenKeys.add(key)) {
                    throw duplicateField();
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                skipValue();
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    rejectTrailingComma('}');
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
                    rejectTrailingComma(']');
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
     * Parses `ageMs` as a plain JSON integer.
     * A fraction or an exponent is invalid (rule C37).
     * A negative value is invalid (rule C15), at any magnitude.
     * A value that does not fit a {@code long} is invalid, with the
     * reason {@code NUMBER_TOO_LARGE}. Each digit still advances the
     * cursor at a linear cost.
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
        if (negative) {
            // Rule C15 makes a negative ageMs invalid. The sign check
            // wins over the overflow check, thus Long.MIN_VALUE also
            // gets the reason NEGATIVE_AGE_MS.
            throw new IngestException(IngestException.Reason.NEGATIVE_AGE_MS,
                    "The ageMs value is negative.");
        }
        if (overflow) {
            throw new IngestException(IngestException.Reason.NUMBER_TOO_LARGE,
                    "The ageMs value is too large.");
        }
        return value;
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
                        int codeUnit = 0;
                        for (int i = 0; i < 4; i++) {
                            codeUnit = codeUnit * 16 + hexDigit(body.charAt(pos + i));
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

    /**
     * Skips a JSON string value with no allocation.
     * A caller uses this method only for a value that it then discards.
     */
    private void skipRawStringValue() {
        if (pos >= length || body.charAt(pos) != '"') {
            throw invalidJson();
        }
        pos++; // consume the opening quote
        while (true) {
            if (pos >= length) {
                throw invalidJson();
            }
            char c = body.charAt(pos++);
            if (c == '"') {
                return;
            }
            if (c == '\\') {
                if (pos >= length) {
                    throw invalidJson();
                }
                char escape = body.charAt(pos++);
                switch (escape) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                        // A valid short escape. Nothing to build.
                    }
                    case 'u' -> {
                        if (pos + 4 > length) {
                            throw invalidJson();
                        }
                        for (int i = 0; i < 4; i++) {
                            hexDigit(body.charAt(pos + i));
                        }
                        pos += 4;
                    }
                    default -> throw invalidJson();
                }
            } else if (c < 0x20) {
                throw invalidJson();
            }
        }
    }

    // -- Low-level helpers --------------------------------------------------

    /**
     * Reads one hex digit of a JSON unicode escape: `0` to `9`, `a` to
     * `f`, or `A` to `F`. It throws {@link IngestException} for any
     * other character. A sign and a non-ASCII digit are therefore
     * invalid.
     */
    private int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw invalidJson();
    }

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

    /**
     * Rejects a trailing comma. JSON allows no comma right before a
     * closing bracket or a closing brace.
     */
    private void rejectTrailingComma(char closer) {
        skipWhitespace();
        if (pos < length && body.charAt(pos) == closer) {
            throw invalidJson();
        }
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

    private IngestException duplicateField() {
        return new IngestException(IngestException.Reason.DUPLICATE_FIELD,
                "The body holds a duplicate field name.");
    }
}
