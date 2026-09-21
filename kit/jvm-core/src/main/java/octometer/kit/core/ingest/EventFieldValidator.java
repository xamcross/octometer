package octometer.kit.core.ingest;

import java.util.regex.Pattern;

/**
 * The field rules of design section 4.1 (`contract/README.md`, rules C4,
 * C5, C6). The ingest route uses this class at the time of a request
 * (rule C33). A later issue can reuse it for the stored event document.
 */
public final class EventFieldValidator {

    private static final Pattern ELEMENT_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

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

    /** Checks the `sessionId` value against rule C5: a UUID. */
    public static void validateSessionId(String sessionId) {
        if (!UUID_PATTERN.matcher(sessionId).matches()) {
            throw new IngestException(IngestException.Reason.SESSION_ID_NOT_UUID,
                    "The sessionId value must be a UUID.");
        }
    }

    /**
     * Checks the `userId` value against rule C6: {@code null}, or a
     * string of 1 to 254 characters.
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
    }
}
