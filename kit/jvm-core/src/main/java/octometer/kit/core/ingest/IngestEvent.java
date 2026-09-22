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
 *
 * <p><strong>{@code path} holds only the match result of rules C39 and
 * C42, never the raw client value.</strong> Rule C39 says the server
 * never stores the raw client value, and rule C42 says the server
 * stores no `path` field without a route pattern list. {@code
 * IngestPipeline#process} fills this component with the route pattern
 * match of {@code IngestSettings#pathPatternMatcher()}, for a click
 * whose `path` value passed the shape check of rule C39 (issue #104).
 * Without a matcher, this component is {@code null}, also for a
 * shape-valid client value. The raw client value, after the shape check
 * of rule C39, stays in {@link ParsedClick} only; a store must never
 * receive it. A caller must not read this component as the raw client
 * `path`.
 *
 * <p>{@code referrerHost} holds the matched source of rule C40; it is
 * {@code null} on an entry other than `octo:session-start`, and on a
 * session-start entry with no valid `referrerHost`.
 */
public record IngestEvent(String sessionId, String element, Instant ts, String path, String referrerHost) {

    /**
     * Builds an event with no `path` and no `referrerHost`, the shape of
     * this record before version 1.1 of the contract. A caller from
     * before issue #103 still compiles with this constructor.
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
