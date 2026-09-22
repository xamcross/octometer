package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import octometer.kit.core.path.PathPatternMatcher;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.user.UserIdResolver;

/**
 * The full ingest flow of design section 4.2.
 * It parses the raw body, checks the `sessionId` and `element` field
 * rules, then computes `ts` for each click. This class is the only
 * public entry point of the module. An adapter calls {@link #ingest}
 * with the raw body, a {@link Clock}, a {@link UserIdResolver}, and an
 * {@link EventLogStore}.
 *
 * <p>Version 1.1 of the contract adds three behaviors (issue #103):
 * <ul>
 *   <li>the server drops an entry whose `element` starts with the
 *       reserved prefix `octo:` in each letter case, except the exact
 *       text `octo:session-start` (rule C38);</li>
 *   <li>the server checks `path` against rule C39, and drops an invalid
 *       value while it keeps the entry (rule C41);</li>
 *   <li>the server checks `referrerHost` against rule C40 on the
 *       `octo:session-start` entry only, and drops an invalid value or a
 *       value of another entry while it keeps the entry (rules C40,
 *       C41).</li>
 * </ul>
 * Each drop rule writes a maximum of one warning for the whole batch, and
 * no warning holds a raw path or a raw host (rule C41).
 *
 * <p>Issue #104 adds the route pattern match of rule C42. When a shape-
 * valid `path` value passes rule C39, and {@link IngestSettings} carries
 * a {@link PathPatternMatcher}, {@link IngestEvent#path()} holds the
 * match result of that matcher, never the raw client value. Without a
 * matcher, {@link IngestEvent#path()} is {@code null}, also for a
 * shape-valid client value.
 */
public final class IngestPipeline {

    /** The exact text that marks the first event of a session (rule C38). */
    static final String SESSION_START_ELEMENT = "octo:session-start";

    /** The reserved element prefix of rule C38. The letter case does not matter (issue #101, second data review). */
    private static final String RESERVED_ELEMENT_PREFIX = "octo:";

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    private IngestPipeline() {
    }

    /**
     * Calls {@link #process(String, Clock, IngestSettings)} with a
     * setting that carries no route pattern list. Each event of the
     * result then holds a {@code null} path (rule C42). A caller from
     * before issue #104 still compiles with this method.
     */
    public static List<IngestEvent> process(String rawBody, Clock clock) {
        return process(rawBody, clock, new IngestSettings(false));
    }

    /**
     * Parses and validates one ingest body, then returns one
     * {@link IngestEvent} for each kept click, in the order of the
     * `clicks` array. An entry whose `element` breaks rule C38 is not
     * kept; see the class comment.
     *
     * <p>It throws {@link IngestException} for a broken rule of the
     * contract (`contract/README.md`, rules C4, C5, C13, C15, C17, C18,
     * C36, C37).
     *
     * <p>This method checks the size of the decoded {@code String}. The
     * adapter must reject a request above 16 384 bytes. It checks the
     * `Content-Length` header and the byte count, before it decodes the
     * body.
     *
     * <p>{@code settings} carries the route pattern matcher of rule C42
     * (issue #104). This method never reads {@code
     * OCTOMETER_PATH_PATTERNS} on its own; {@link
     * IngestSettings#fromEnvironment()} owns that read.
     */
    public static List<IngestEvent> process(String rawBody, Clock clock, IngestSettings settings) {
        ParsedIngestRequest parsed = IngestParser.parse(rawBody);
        EventFieldValidator.validateSessionId(parsed.sessionId());

        Instant receivedAt = clock.instant();
        PathPatternMatcher pathPatternMatcher = settings.pathPatternMatcher();
        List<IngestEvent> events = new ArrayList<>(parsed.clicks().size());
        boolean warnedReservedElement = false;
        boolean warnedInvalidField = false;
        for (ParsedClick click : parsed.clicks()) {
            // Rule C33 runs before rule C38. An element that breaks rule
            // C4 makes the whole body invalid (400), also when it starts
            // with the reserved prefix. Rule C38 then drops only an
            // entry whose element is legal under rule C4.
            EventFieldValidator.validateElement(click.element());
            boolean isSessionStart = SESSION_START_ELEMENT.equals(click.element());
            if (!isSessionStart && hasReservedElementPrefix(click.element())) {
                if (!warnedReservedElement) {
                    LOGGER.log(Level.WARNING, "The batch holds an entry whose element starts with "
                            + "the reserved prefix octo: and is not octo:session-start. The server "
                            + "drops the entry and keeps the rest of the batch (contract rule C38).");
                    warnedReservedElement = true;
                }
                continue;
            }
            Instant ts = TsCalculator.computeTs(receivedAt, click.ageMs());

            // The shape check of rule C39 runs on the raw client value.
            // A shape-valid value then goes to the matcher of rule C42,
            // when the app gave one; the match result is the only form
            // that can reach the event record. The raw value stays in
            // click (a ParsedClick) and goes no further.
            String path = null;
            if (click.path() != null) {
                if (EventFieldValidator.isValidPath(click.path())) {
                    if (pathPatternMatcher != null) {
                        path = pathPatternMatcher.match(click.path());
                    }
                } else if (!warnedInvalidField) {
                    LOGGER.log(Level.WARNING, "The batch holds an entry with an invalid path or "
                            + "referrerHost value. The server drops the field and keeps the entry "
                            + "(contract rule C41).");
                    warnedInvalidField = true;
                }
            }

            String referrerHost = null;
            if (isSessionStart && click.referrerHost() != null) {
                String matchedReferrerHost = EventFieldValidator.matchReferrerHost(click.referrerHost());
                if (matchedReferrerHost != null) {
                    referrerHost = matchedReferrerHost;
                } else if (!warnedInvalidField) {
                    LOGGER.log(Level.WARNING, "The batch holds an entry with an invalid path or "
                            + "referrerHost value. The server drops the field and keeps the entry "
                            + "(contract rule C41).");
                    warnedInvalidField = true;
                }
            }

            events.add(new IngestEvent(parsed.sessionId(), click.element(), ts, path, referrerHost));
        }
        return List.copyOf(events);
    }

    /**
     * Checks the reserved element prefix of rule C38. The check ignores
     * the ASCII letter case, so it also matches `OCTO:foo` (issue #101,
     * second data review).
     */
    private static boolean hasReservedElementPrefix(String element) {
        return element.length() >= RESERVED_ELEMENT_PREFIX.length()
                && element.regionMatches(true, 0, RESERVED_ELEMENT_PREFIX, 0, RESERVED_ELEMENT_PREFIX.length());
    }

    /**
     * Runs the full ingest flow of design section 4.2 and design
     * decisions D18 and D19, then gives the whole batch of valid events
     * to {@code store} in one call. It resolves the user id with
     * {@code userIdResolver}. The client never sets the user id; a
     * `userId` field of the request body has no effect (contract rules
     * C6, C16, C32).
     *
     * <p>When the user id is {@code null} and {@code settings} does not
     * record an anonymous click, this method appends nothing and
     * returns.
     *
     * <p>This method throws {@link IngestException} for a broken rule of
     * the contract in the request body (the rules of {@link #process}).
     *
     * <p>A {@code userId} value from {@code userIdResolver} that breaks
     * rule C6 is a defect of the app, not of the client. This method
     * then throws {@link IllegalStateException}; the message names the
     * broken rule and never holds the user id. This method also lets an
     * exception of {@code userIdResolver} itself pass to the caller. An
     * adapter maps each of the two to status 500, never to status 400.
     *
     * <p>A store throws an unchecked exception when {@link
     * EventLogStore#append} fails. This method lets that exception pass
     * to the caller; an adapter maps it to status 500.
     *
     * <p>Rule C13 sets no minimum for the `clicks` array. When the body
     * holds zero clicks, this method appends nothing and returns; it
     * never calls {@code store.append} with an empty list.
     *
     * <p>Each parameter must not be {@code null}. This method calls
     * {@link #process(String, Clock, IngestSettings)}, so it fills each
     * event's `path` with the route pattern match of rule C42 before it
     * calls {@code store.append} (issue #104).
     */
    public static void ingest(String rawBody, Clock clock, UserIdResolver userIdResolver,
            EventLogStore store, IngestSettings settings) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(userIdResolver, "userIdResolver must not be null");
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        List<IngestEvent> events = process(rawBody, clock, settings);
        if (events.isEmpty()) {
            return;
        }
        String userId = userIdResolver.resolve();
        try {
            EventFieldValidator.validateUserId(userId);
        } catch (IngestException cause) {
            throw new IllegalStateException(
                    "The UserIdResolver of the app returned a userId that breaks contract rule C6.", cause);
        }
        if (userId == null && !settings.recordAnonymousClicks()) {
            return;
        }
        store.append(events, userId);
    }
}
