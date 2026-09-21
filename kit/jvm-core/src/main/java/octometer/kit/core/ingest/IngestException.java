package octometer.kit.core.ingest;

/**
 * A rejected ingest request. The {@link Reason} names the broken rule of
 * the contract (`contract/README.md`, issue #8).
 */
public final class IngestException extends RuntimeException {

    /** The rule that a request body or a field value breaks. */
    public enum Reason {
        /** The body is above the 16 KB limit (rule C18). */
        BODY_TOO_LARGE,
        /** The body is not valid JSON for the one ingest shape. */
        INVALID_JSON,
        /** The body holds a duplicate key in one JSON object (rule C36). */
        DUPLICATE_FIELD,
        /** The top-level JSON value is not an object. */
        WRONG_TOP_LEVEL_TYPE,
        /** A required field is absent (rule C13). */
        MISSING_FIELD,
        /** A field holds the wrong JSON type. */
        WRONG_FIELD_TYPE,
        /** The `clicks` array holds more than 50 entries (rule C17). */
        TOO_MANY_CLICKS,
        /** A number field is too large to hold as a Java `long`. */
        NUMBER_TOO_LARGE,
        /** The `element` value is not 1 to 100 characters (rule C4). */
        ELEMENT_LENGTH,
        /** The `element` value does not match the pattern of rule C4. */
        ELEMENT_PATTERN,
        /** The `sessionId` value is not a UUID (rule C5). */
        SESSION_ID_NOT_UUID,
        /** The `ageMs` value is negative (rule C15). */
        NEGATIVE_AGE_MS,
        /** The `userId` value is not 1 to 254 characters (rule C6). */
        USER_ID_LENGTH,
        /** The `userId` value holds a control character or the delete character. */
        USER_ID_CHARACTER
    }

    private final Reason reason;

    public IngestException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
