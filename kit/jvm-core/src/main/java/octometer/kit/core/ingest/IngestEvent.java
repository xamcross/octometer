package octometer.kit.core.ingest;

import java.time.Instant;

/**
 * One validated click, ready for a store to hold.
 * {@link IngestPipeline#ingest ingest} passes the user id to the store as
 * a separate parameter, from the app's
 * {@link octometer.kit.core.user.UserIdResolver} (design decision D18).
 * This record stays as it was in issue #10.
 *
 * <p>Version 1.1 of the contract adds two optional fields (issue #103).
 * {@code path} holds the client value that
 * {@link EventFieldValidator#isValidPath} checked for shape only (rule
 * C39); it is {@code null} when the body held no valid `path`. Rule C42
 * states that the server stores no `path` field without a route pattern
 * list, and this module has no such list yet. Issue #104 adds the list
 * and the match, and it can then replace this field's value with the
 * match result before a store writes it. {@code referrerHost} holds the
 * matched source of rule C40; it is {@code null} on an entry other than
 * `octo:session-start`, and on a session-start entry with no valid
 * `referrerHost`.
 */
public record IngestEvent(String sessionId, String element, Instant ts, String path, String referrerHost) {

    /**
     * Builds an event with no `path` and no `referrerHost`, the shape of
     * this record before version 1.1 of the contract. A caller from
     * before issue #103 keeps working with this constructor.
     */
    public IngestEvent(String sessionId, String element, Instant ts) {
        this(sessionId, element, ts, null, null);
    }

    /**
     * Returns a text with no personal data (design decision D15). The
     * session id, the path, and the referrer host stay out of this text,
     * because a log line must never hold them.
     */
    @Override
    public String toString() {
        return "IngestEvent[element=" + element + ", ts=" + ts + "]";
    }
}
