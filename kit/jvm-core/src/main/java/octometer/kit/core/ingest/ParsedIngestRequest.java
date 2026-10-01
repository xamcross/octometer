package octometer.kit.core.ingest;

import java.util.List;

/**
 * The parsed shape of an ingest body (design section 4.2), before the
 * field validation of rules C4, C5, and C15.
 *
 * <p>{@code userId} holds the top-level {@code userId} field of issue
 * #300 (design decision D54), as the body gave it, with no shape check
 * at this level. Rule C16 and rule C32 keep this field with no effect on
 * the kit ingest route; {@link IngestSettings#acceptBodyUserId()} gives
 * the collector route its own exception.
 */
record ParsedIngestRequest(String sessionId, List<ParsedClick> clicks, String userId) {

    ParsedIngestRequest {
        clicks = List.copyOf(clicks);
    }

    /**
     * Returns a text with no personal data (design decision D15). The
     * session id and the user id stay out of this text, and each click
     * prints its own safe text (see {@link ParsedClick#toString()}).
     */
    @Override
    public String toString() {
        return "ParsedIngestRequest[clicks=" + clicks + "]";
    }
}
