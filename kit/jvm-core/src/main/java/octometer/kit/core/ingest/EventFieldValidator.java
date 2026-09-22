package octometer.kit.core.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The field rules of design section 4.1 (`contract/README.md`, rules C4,
 * C5, C6). The ingest route uses this class at the time of a request
 * (rule C33). A later issue can reuse it for the stored event document.
 *
 * <p>Version 1.1 of the contract adds the shape check of `path` (rule
 * C39) and the match rule of `referrerHost` (rule C40, issue #103).
 * Unlike {@link #validateElement}, {@link #validateSessionId}, and
 * {@link #validateUserId}, the two new methods return a value instead of
 * throwing: rule C41 makes an invalid `path` or `referrerHost` value
 * non-fatal, and it does not stop the whole request.
 */
public final class EventFieldValidator {

    private static final Pattern ELEMENT_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** The extra characters of the `path` set of rule C39, besides a letter and a digit. */
    private static final String PATH_EXTRA_CHARACTERS = "._~!$&'()*+,;=:@/-";

    /** The maximum byte count of a `path` value (rule C39). */
    private static final int MAX_PATH_BYTES = 150;

    /** The shape of a host name of rule C40: only a lower-case letter, a digit, a dot, or a hyphen. */
    private static final Pattern HOST_NAME_SHAPE = Pattern.compile("[a-z0-9.-]+");

    /** The maximum byte count of a `referrerHost` value with the host-name shape (rule C40). */
    private static final int MAX_HOST_BYTES = 253;

    /** The literal value of `referrerHost` for a visit with no known source (rule C40). */
    private static final String REFERRER_HOST_OTHER = "other";

    /** The Google host pattern of rule C40, from the second data review of issue #101. */
    private static final Pattern GOOGLE_HOST_PATTERN =
            Pattern.compile("^([a-z0-9-]+\\.)*google\\.((com|co)\\.[a-z]{2}|com|[a-z]{2})$");

    /** The source list of rule C40, besides the literal `other`. The order fixes the match order. */
    private static final List<String> REFERRER_HOST_SOURCE_LIST = List.of("google.com", "bing.com");

    /** The same list, as a set, for {@link #referrerHostSourceList()}. */
    private static final Set<String> REFERRER_HOST_SOURCE_SET = Set.copyOf(REFERRER_HOST_SOURCE_LIST);

    private EventFieldValidator() {
    }

    /**
     * Checks the `element` value against rule C4: 1 to 100 characters,
     * the pattern {@code [A-Za-z0-9_.:-]+}.
     */
    public static void validateElement(String element) {
        int length = element.length();
        if (length < 1 || length > 100) {
            throw new IngestException(IngestException.Reason.ELEMENT_LENGTH,
                    "The element value must have 1 to 100 characters.");
        }
        if (!ELEMENT_PATTERN.matcher(element).matches()) {
            throw new IngestException(IngestException.Reason.ELEMENT_PATTERN,
                    "The element value must match the pattern of rule C4.");
        }
    }

    /**
     * Checks the `sessionId` value against rule C5: a UUID.
     *
     * <p>The pattern below is stricter than {@link java.util.UUID#fromString}.
     * That method accepts a short form such as `1-1-1-1-1`. It also
     * accepts a 35-character value. This module keeps the canonical
     * 36-character form only.
     */
    public static void validateSessionId(String sessionId) {
        if (sessionId.length() != 36) {
            throw new IngestException(IngestException.Reason.SESSION_ID_NOT_UUID,
                    "The sessionId value must be a UUID.");
        }
        if (!UUID_PATTERN.matcher(sessionId).matches()) {
            throw new IngestException(IngestException.Reason.SESSION_ID_NOT_UUID,
                    "The sessionId value must be a UUID.");
        }
    }

    /**
     * Checks the `userId` value against rule C6: {@code null}, or a
     * string of 1 to 254 characters, with no control character and no
     * delete character (U+007F). The character rule is a defence for a
     * later log line and a later export; the contract states only the
     * length rule.
     */
    public static void validateUserId(String userId) {
        if (userId == null) {
            return;
        }
        int length = userId.length();
        if (length < 1 || length > 254) {
            throw new IngestException(IngestException.Reason.USER_ID_LENGTH,
                    "The userId value must have 1 to 254 characters.");
        }
        for (int i = 0; i < length; i++) {
            char c = userId.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IngestException(IngestException.Reason.USER_ID_CHARACTER,
                        "The userId value must not hold a control character.");
            }
        }
    }

    /**
     * Checks a `path` value against rule C39: 1 to 150 bytes in UTF-8, a
     * leading `/`, a second character that is not `/`, and each other
     * character in the set {@code [A-Za-z0-9._~!$&'()*+,;=:@/-]} or in a
     * well-formed escape {@code %[0-9A-Fa-f]{2}}. It returns a boolean.
     * It does not throw, because rule C41 makes an invalid value
     * non-fatal. The value of {@code path} must not be {@code null}.
     */
    public static boolean isValidPath(String path) {
        // A UTF-8 encoding never needs fewer bytes than the string has
        // UTF-16 chars. This check rejects a huge value with no array
        // allocation.
        if (path.isEmpty() || path.length() > MAX_PATH_BYTES) {
            return false;
        }
        int byteLength = path.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_PATH_BYTES) {
            return false;
        }
        int length = path.length();
        if (path.charAt(0) != '/') {
            return false;
        }
        if (length >= 2 && path.charAt(1) == '/') {
            return false;
        }
        int i = 1;
        while (i < length) {
            char c = path.charAt(i);
            if (c == '%') {
                if (i + 3 > length || !isHexDigit(path.charAt(i + 1)) || !isHexDigit(path.charAt(i + 2))) {
                    return false;
                }
                i += 3;
                continue;
            }
            if (!isPathCharacter(c)) {
                return false;
            }
            i++;
        }
        return true;
    }

    private static boolean isPathCharacter(char c) {
        if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
            return true;
        }
        return PATH_EXTRA_CHARACTERS.indexOf(c) >= 0;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Returns the source list of rule C40, besides the literal `other`:
     * {@code google.com} and {@code bing.com}. A test compares this list
     * against the source list of `contract/README.md`, so the two lists
     * never drift apart. {@link #matchReferrerHost} reads the same list,
     * in the same order, so the two lists never drift apart either.
     */
    public static Set<String> referrerHostSourceList() {
        return REFERRER_HOST_SOURCE_SET;
    }

    /**
     * Matches a `referrerHost` value against rule C40. It returns
     * {@code google.com}, {@code bing.com}, or the literal {@code other}
     * for a value with the shape of a host name. It returns {@code null}
     * for a value with a different shape; rule C41 then makes the
     * caller drop the field, and it does not stop the whole request.
     * The value of {@code rawValue} must not be {@code null}.
     *
     * <p>The value {@code other} matches at once, with no shape check:
     * rule C40 states it as a literal, not as a host name. A value with
     * the shape of a host name that matches no entry of the source list
     * also gives {@code other} (rule C40). The method reads {@link
     * #REFERRER_HOST_SOURCE_LIST} in order, so a test over each entry of
     * {@link #referrerHostSourceList()} also proves the match rule, and
     * not only the closed set.
     */
    public static String matchReferrerHost(String rawValue) {
        if (REFERRER_HOST_OTHER.equals(rawValue)) {
            return REFERRER_HOST_OTHER;
        }
        if (!isValidHostNameShape(rawValue)) {
            return null;
        }
        for (String entry : REFERRER_HOST_SOURCE_LIST) {
            if (matchesSourceEntry(rawValue, entry)) {
                return entry;
            }
        }
        // The literal text "google." must be present before the pattern
        // runs, so a long host with no chance of a match costs one
        // cheap search and not one regex match (issue #103, second
        // review).
        if (rawValue.indexOf("google.") >= 0 && GOOGLE_HOST_PATTERN.matcher(rawValue).matches()) {
            return "google.com";
        }
        return REFERRER_HOST_OTHER;
    }

    /**
     * Checks the host-name shape of rule C40: 1 to 253 bytes, the
     * pattern {@code [a-z0-9.-]+}, and at least one dot. The pattern is
     * ASCII-only, so the character count of a matching value equals its
     * UTF-8 byte count.
     */
    private static boolean isValidHostNameShape(String value) {
        int length = value.length();
        if (length < 1 || length > MAX_HOST_BYTES) {
            return false;
        }
        if (!HOST_NAME_SHAPE.matcher(value).matches()) {
            return false;
        }
        return value.indexOf('.') >= 0;
    }

    /**
     * A host matches an entry when it equals the entry, or when it ends
     * with a dot plus the entry (rule C40).
     */
    private static boolean matchesSourceEntry(String host, String entry) {
        return host.equals(entry) || host.endsWith("." + entry);
    }
}
