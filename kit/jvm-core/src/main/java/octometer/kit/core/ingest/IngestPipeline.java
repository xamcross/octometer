package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
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
 *   <li>the server checks `path` against rule C39, and, with a route
 *       pattern matcher, maps an invalid value to {@code /other} (rule
 *       C41, amended by design decision D49); with no matcher, the
 *       server drops the value while it keeps the entry, the same as
 *       before;</li>
 *   <li>the server checks `referrerHost` against rule C40 on the
 *       `octo:session-start` entry only, and drops an invalid value or a
 *       value of another entry while it keeps the entry (rules C40,
 *       C41).</li>
 * </ul>
 * Each drop rule writes a maximum of one warning for the whole batch, and
 * no warning holds a raw path or a raw host (rule C41).
 *
 * <p>Issue #104 adds the route pattern match of rule C42. A shape-valid
 * `path` value must pass rule C39 first, and {@link IngestSettings} must
 * carry a {@link PathPatternMatcher}. Only then does {@link
 * IngestEvent#path()} hold the match result of that matcher, never the
 * raw client value. Without a matcher, {@link IngestEvent#path()} is
 * {@code null}, also for a shape-valid client value.
 *
 * <p>Issue #278 (design decision D49) amends rule C41 for `path`: the
 * tracker sends the raw page path with no route list of its own, so a
 * value that breaks rule C39 becomes {@code /other} on the server, never
 * a dropped field, when a route pattern matcher exists. The tracker keeps
 * tracking a route after a deploy that changes the route list, because it
 * sends no list of its own.
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
        return process(rawBody, clock, settings, null);
    }

    /**
     * Calls {@link #process(String, Clock, IngestSettings)} with the
     * default route pattern rule of design decision D40 and design
     * decision D50 (issue #297, {@code DefaultRoutePattern.apply}) as a
     * fallback for a shape-valid {@code path} value.
     *
     * <p>{@code defaultPathRule} runs only for a {@code path} value that
     * passes the shape check of rule C39. A {@code null}
     * {@code defaultPathRule} gives the exact behavior of
     * {@link #process(String, Clock, IngestSettings)}: with no route
     * pattern matcher, a shape-valid value gives a {@code null} path
     * (rule C42, no change for an existing caller). With
     * {@code defaultPathRule} present:
     * <ul>
     *   <li>with no route pattern matcher, the path becomes
     *       {@code defaultPathRule.apply(click.path())};</li>
     *   <li>with a route pattern matcher, the path becomes the matcher's
     *       own result, unless that result is
     *       {@link PathPatternMatcher#OTHER}, in which case the path
     *       becomes {@code defaultPathRule.apply(click.path())}
     *       instead (the hosted collector's own rule: the typed list
     *       first, then the default rule).</li>
     * </ul>
     * A shape-invalid {@code path} value with {@code defaultPathRule}
     * present, and with no route pattern matcher, becomes
     * {@link PathPatternMatcher#OTHER}; the kit never gives the raw
     * client value to {@code defaultPathRule}.
     */
    public static List<IngestEvent> process(String rawBody, Clock clock, IngestSettings settings,
            UnaryOperator<String> defaultPathRule) {
        return processBatch(rawBody, clock, settings, defaultPathRule).events();
    }

    /**
     * One processed batch: {@link #events} in the order of the body's
     * {@code clicks} array, and {@link #userId}, the body's top-level
     * {@code userId} field, kept only when {@link
     * IngestSettings#acceptBodyUserId()} is {@code true} and the value
     * passes {@link #acceptableBodyUserId} (issue #300, design decision
     * D54). {@code userId} is {@code null} on every other call, the same
     * as before this issue.
     *
     * <p>{@link #userIdDropped} is {@code true} when the body carried a
     * top-level {@code userId} value, {@link
     * IngestSettings#acceptBodyUserId()} was {@code true}, and {@link
     * #acceptableBodyUserId} dropped that value (MAJOR 1 of the review
     * of pull request #333). It stays {@code false} for an absent value
     * and for a kept value, so a caller can count only a real drop, and
     * never an ordinary anonymous batch.
     */
    public record IngestBatch(List<IngestEvent> events, String userId, boolean userIdDropped) {
    }

    /**
     * Runs {@link #process(String, Clock, IngestSettings, UnaryOperator)}
     * and also gives the body's own user id, for the collector route of
     * issue #297 (design decision D54, issue #300). {@link
     * IngestSettings#acceptBodyUserId()} stays {@code false} for the kit
     * ingest route, so the body's {@code userId} field keeps no effect
     * there (contract rules C16, C32).
     *
     * <p>With {@link IngestSettings#acceptBodyUserId()} {@code true}, the
     * collector's own server-side rule of the brief review of
     * 2026-10-01, point 25 applies: a body user id with an {@code @}
     * character, a space, or more than 254 characters, counts as absent.
     * The browser's own filter protects nothing, so the server repeats
     * it. This method throws no exception for such a value; the batch
     * stays, with no user id.
     */
    public static IngestBatch processBatch(String rawBody, Clock clock, IngestSettings settings,
            UnaryOperator<String> defaultPathRule) {
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
                        String matched = pathPatternMatcher.match(click.path());
                        // Issue #297 (design decision D40, design
                        // decision D50): the hosted collector gives a
                        // defaultPathRule. The typed list wins first; the
                        // default rule only replaces an /other result.
                        path = (defaultPathRule != null && PathPatternMatcher.OTHER.equals(matched))
                                ? defaultPathRule.apply(click.path())
                                : matched;
                    } else if (defaultPathRule != null) {
                        path = defaultPathRule.apply(click.path());
                    }
                } else if (pathPatternMatcher != null || defaultPathRule != null) {
                    // Contract rule C41 (amended by design decision D49): a
                    // path value outside the grammar of rule C39 becomes
                    // /other, never a dropped field or a dropped batch.
                    path = "/other";
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
        String rawBodyUserId = parsed.userId();
        String bodyUserId = settings.acceptBodyUserId() ? acceptableBodyUserId(rawBodyUserId) : null;
        boolean userIdDropped = settings.acceptBodyUserId() && rawBodyUserId != null && bodyUserId == null;
        return new IngestBatch(List.copyOf(events), bodyUserId, userIdDropped);
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
     * The collector's own server-side rule for a body user id (issue
     * #300, design decision D54, the brief review of 2026-10-01, point
     * 25): {@code null} for an absent value, an empty value, a value
     * above 254 characters, or a value with an {@code @} character or a
     * space. Each other value stays as it is; this method applies no
     * other shape check, so a value with a slash still passes (issue
     * #300, the owner's example {@code team/42}).
     */
    private static String acceptableBodyUserId(String rawValue) {
        if (rawValue == null || rawValue.isEmpty() || rawValue.length() > 254) {
            return null;
        }
        if (rawValue.indexOf('@') >= 0 || rawValue.indexOf(' ') >= 0) {
            return null;
        }
        return rawValue;
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
     *
     * <p>This method stays the public entry point of the kit for an app
     * with no Ktor route. Such an app must apply the bot filter and the
     * daily caps of design decision D43 itself, the way
     * `octometerIngestRoute` of `kit/jvm-ktor` shows (issue #117).
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
