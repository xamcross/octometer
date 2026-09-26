package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import octometer.kit.core.store.EventLogStore;

/**
 * The framework-free order of the ingest checks of design section 4.2
 * (contract rules C12 to C19, C32, C33, C36, C37; design decisions D19,
 * D20, D43). Issue #69 extracts this class from the Ktor route of
 * `kit/jvm-ktor` (issue #33, issue #116, issue #117), so a second adapter
 * of a different framework (`kit/jvm-spring`) runs the very same order,
 * with no repeat of its checks.
 *
 * <p>This class has no framework dependency and no Kotlin coroutine
 * dependency (design decision D17). A suspending adapter (Ktor) and a
 * synchronous adapter (Spring MVC) each read the request body in their own
 * way, so this class splits the order into two calls. {@link #beforeBody}
 * runs each check that needs no parsed body: the content type, the rate
 * limiter, the anonymous per-minute request counter, the bot filter, and
 * the declared body size. {@link #afterBody} runs each check that needs
 * the parsed batch: the real body size and the parse, the anonymous
 * per-minute entry counters, the design decision D19 drop, the daily
 * anonymous caps, and the store call with the event cap of design decision
 * D21 inside it. The adapter reads the request body between the two
 * calls, then passes it to {@link #afterBody}.
 *
 * <p>{@link RequestView} gives this class the small set of request
 * attributes that a check needs. An adapter builds one implementation of
 * {@link RequestView} for each request; the interface holds no method that
 * reads the request body, because the two calls of this class never read
 * one through it.
 *
 * <p><strong>The user id.</strong> {@link RequestView#resolveUserId()} is
 * the app's resolver of contract rule C6. {@link #beforeBody} calls it
 * once, right after the content type check, the same position as
 * `resolveUserId(call)` of `octometerIngestRoute` before this issue. A
 * throw of that call passes to the caller of {@link #beforeBody}
 * unhandled; the adapter must map it to status 500, never to status 400,
 * the same rule as a throw of the store (see {@link #afterBody}).
 *
 * <p><strong>The client address.</strong> {@link #beforeBody} reads it
 * only when {@link RequestView#resolveUserId()} gives {@code null}
 * (design decision D20, issue #33). It reads the header that the
 * constructor's {@code clientIpHeaderName} names, at the position that
 * {@code trustedProxyCount} counts from the right of that header's last
 * line, or falls back to {@link RequestView#remoteAddress()} for a
 * {@code null} header name, a request with no such header, a value above
 * 64 characters, a value with no IPv4 or IPv6 address form, or a
 * {@code trustedProxyCount} above the length of the header's
 * comma-separated list. This class makes no network call and resolves no
 * name.
 *
 * <p>One instance of this class holds each stateful check (the rate
 * limiter, the two anonymous limiters, the daily cap, the bot-drop log
 * throttle) for the life of an adapter's route or controller, the form of
 * `octometerIngestRoute` before this issue. One instance serves many
 * requests at the same time; it is thread-safe, because each check that it
 * calls is thread-safe on its own, and the bot-drop log throttle guards its
 * own state with one lock.
 */
public final class IngestRequestProcessor {

    /** The maximum size of one request body, in bytes (contract rule C18). */
    private static final long MAX_BODY_BYTES = 16 * 1024;

    /**
     * The maximum length of one client address value, in characters
     * (issue #33). A value above this length falls back to the remote
     * address; see {@link #clientAddress}. The text of an IPv6 address
     * needs at most 45 characters, so this cap stays well above every
     * normal address text.
     */
    private static final int MAX_CLIENT_ADDRESS_LENGTH = 64;

    /**
     * The maximum length of the {@code User-Agent} text that the bot
     * filter reads (design decision D43, issue #117). A client can send a
     * header value near the 8 KB header limit of a server engine. This cap
     * keeps one call of {@link BotUserAgentFilter#isBot} cheap, also for a
     * client already at its rate limit.
     */
    private static final int MAX_USER_AGENT_LENGTH = 512;

    /** One hour, in milliseconds (the throttle window of {@link BotDropLogThrottle}). */
    private static final long BOT_DROP_LOG_THROTTLE_MILLIS = Duration.ofHours(1).toMillis();

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String USER_AGENT_HEADER = "User-Agent";

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    /**
     * A text form of one IPv4 address (four dot-separated numbers, each 0
     * to 255).
     */
    private static final Pattern IPV4_ADDRESS_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                    + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$");

    /**
     * A text form of one IPv6 address, with the standard compressed
     * ({@code ::}) form and an embedded IPv4 tail.
     */
    private static final Pattern IPV6_ADDRESS_PATTERN = Pattern.compile(
            """
            ^(
            ([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|
            ([0-9a-fA-F]{1,4}:){1,7}:|
            ([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|
            ([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|
            ([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|
            ([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|
            ([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|
            [0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|
            :((:[0-9a-fA-F]{1,4}){1,7}|:)|
            ::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|
            ([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])
            )$
            """,
            Pattern.COMMENTS);

    private final EventLogStore store;
    private final IngestSettings settings;
    private final Clock clock;
    private final IngestRateLimiter rateLimiter;
    private final AnonymousMinuteLimiter minuteLimiter;
    private final AnonymousDailyCap dailyCap;
    private final String clientIpHeaderName;
    private final int trustedProxyCount;
    private final BotDropLogThrottle botDropLogThrottle;

    /**
     * Builds one processor.
     *
     * @param store the event log store of the app.
     * @param settings the settings of design decision D19 and design
     *   decision D43.
     * @param clock the clock for {@code receivedAt} of design section 4.2.
     * @param rateLimiter the ingest rate limiter of design decision D20
     *   (issue #33).
     * @param minuteLimiter the anonymous per-minute limiter of design
     *   decision D43 (issue #116).
     * @param dailyCap the daily anonymous cap of design decision D43
     *   (issue #117).
     * @param clientIpHeaderName the name of the header that holds the
     *   client address (issue #33), or {@code null} to read only the
     *   remote address of the connection.
     * @param trustedProxyCount the position, counted from the right of the
     *   header list of {@code clientIpHeaderName}, of the address to trust
     *   (design decision D20, issue #116). It must be 1 or more.
     */
    public IngestRequestProcessor(EventLogStore store, IngestSettings settings, Clock clock,
            IngestRateLimiter rateLimiter, AnonymousMinuteLimiter minuteLimiter, AnonymousDailyCap dailyCap,
            String clientIpHeaderName, int trustedProxyCount) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.minuteLimiter = Objects.requireNonNull(minuteLimiter, "minuteLimiter must not be null");
        this.dailyCap = Objects.requireNonNull(dailyCap, "dailyCap must not be null");
        if (trustedProxyCount < 1) {
            throw new IllegalArgumentException("trustedProxyCount must be 1 or more");
        }
        this.clientIpHeaderName = clientIpHeaderName;
        this.trustedProxyCount = trustedProxyCount;
        this.botDropLogThrottle = new BotDropLogThrottle(clock);
    }

    /**
     * Runs each check that needs no parsed body: the content type (415),
     * the rate limiter (429), the anonymous per-minute request counter
     * (429), the bot filter (204), and the declared body size (400).
     *
     * @param view the request attributes of design section 4.2.
     * @return an outcome that either already holds the final
     *   {@link IngestResult} ({@link BeforeBodyOutcome#mustRespondNow()}
     *   is {@code true}, and the adapter must read no body), or that
     *   carries the state that {@link #afterBody} needs
     *   ({@link BeforeBodyOutcome#mustRespondNow()} is {@code false}).
     */
    public BeforeBodyOutcome beforeBody(RequestView view) {
        Objects.requireNonNull(view, "view must not be null");

        // 1. Content type (415, contract rule C12). This check reads no
        // body, so a request with a wrong content type stays cheap.
        if (!hasJsonContentType(firstHeader(view, CONTENT_TYPE_HEADER))) {
            return BeforeBodyOutcome.respond(new IngestResult(415));
        }

        // resolveUserId runs here, before the rate limit check (see the
        // class comment). The adapter still calls this method for a
        // rejected request, so keep it short.
        String userId = view.resolveUserId();

        // The client address is read only for a request with no user id
        // (issue #33): the rate limiter, the anonymous per-minute limiter,
        // and the daily cap all read the same normalised address, so this
        // class reads it once and reuses it below.
        String clientAddr = userId == null ? clientAddress(view) : null;

        // 2. The rate limiter (429, design decision D20, issue #33). A
        // client already at its limit never reaches step 3 or any step
        // below. Design decision D43 replaces the client-address limit of
        // D20 for a request with no user id, when the app records an
        // anonymous click (BLOCKER 1 of the review of pull request #197).
        RateLimitResult rateLimitResult;
        if (userId != null) {
            rateLimitResult = rateLimiter.check(userId, "");
        } else if (settings.recordAnonymousClicks()) {
            rateLimitResult = RateLimitResult.ALLOWED;
        } else {
            rateLimitResult = rateLimiter.check(null, clientAddr);
        }
        if (rateLimitResult == RateLimitResult.LIMITED) {
            return BeforeBodyOutcome.respond(new IngestResult(429));
        }

        // 3. The anonymous per-minute request counter (429, design
        // decision D43, issue #116). It needs no parsed entry, so it sits
        // beside the rate limiter, before the bot filter and the body
        // read. anonymousMinuteKey stays null otherwise, so afterBody's
        // entry counters skip their own check for the same request.
        String anonymousMinuteKey = null;
        if (userId == null && settings.recordAnonymousClicks()) {
            anonymousMinuteKey = AnonymousKey.of(clientAddr);
            if (!minuteLimiter.checkRequest(anonymousMinuteKey)) {
                return BeforeBodyOutcome.respond(new IngestResult(429));
            }
        }

        // 4. The bot filter (204, design decision D43, issue #117). This
        // check reads a maximum of MAX_USER_AGENT_LENGTH characters of the
        // header value, so a long value never makes the filter costly.
        // This class stores no User-Agent value.
        String userAgentValue = lastHeader(view, USER_AGENT_HEADER);
        String truncatedUserAgent = userAgentValue == null
                ? null
                : userAgentValue.substring(0, Math.min(userAgentValue.length(), MAX_USER_AGENT_LENGTH));
        if (BotUserAgentFilter.isBot(truncatedUserAgent)) {
            Long dropCount = botDropLogThrottle.recordDrop();
            if (dropCount != null) {
                LOGGER.log(Level.DEBUG, "The Octometer ingest route dropped {0} robot batches in "
                        + "the last hour (design decision D43).", dropCount);
            }
            return BeforeBodyOutcome.respond(new IngestResult(204));
        }

        // 5. Body size (400, contract rule C18): the declared
        // Content-Length only, with no body read. A request with no
        // declared length, or a length at or under the limit, continues
        // here. afterBody enforces the same limit again against the real
        // body (step 6), because a declared length under the limit does
        // not bound the real body.
        Long declaredLength = view.declaredContentLengthBytes();
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            return BeforeBodyOutcome.respond(new IngestResult(400));
        }

        return BeforeBodyOutcome.continueToBody(userId, anonymousMinuteKey);
    }

    /**
     * Runs each check that needs the parsed body: the real body size and
     * the parse (400), the anonymous per-minute entry counters (429), the
     * design decision D19 drop (204), the daily anonymous caps (204), and
     * the store call with the event cap of design decision D21 inside it
     * (204 on a stored or a dropped batch).
     *
     * @param outcome the outcome that {@link #beforeBody} returned for
     *   this same request. Its {@link BeforeBodyOutcome#mustRespondNow()}
     *   must be {@code false}.
     * @param rawBody the request body, as the adapter read it between the
     *   two calls. This method checks its UTF-8 byte count against the
     *   16 KB limit of contract rule C18, so an adapter that reads the
     *   whole body with no limit of its own (for example Spring MVC's
     *   ordinary {@code @RequestBody String} binding) still gets the
     *   correct 400 answer for an oversized body.
     * @return the final result of this request.
     * @throws IllegalStateException when {@code outcome} already holds a
     *   result, or when {@code view.resolveUserId()} of {@link #beforeBody}
     *   gave a value that breaks contract rule C6 (a defect of the app,
     *   never of the client; the adapter maps this to status 500).
     */
    public IngestResult afterBody(BeforeBodyOutcome outcome, String rawBody) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (outcome.mustRespondNow()) {
            throw new IllegalStateException(
                    "afterBody must not run once beforeBody already gives a result for this request.");
        }
        String userId = outcome.userId();
        String anonymousMinuteKey = outcome.anonymousMinuteKey();

        // 6. The body read (400, contract rule C18: a body above the
        // limit that the declared length of beforeBody could not catch)
        // and the parse (400, the field rules of this module).
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return new IngestResult(400);
        }
        List<IngestEvent> events;
        try {
            events = IngestPipeline.process(rawBody, clock, settings);
        } catch (IngestException cause) {
            return new IngestResult(400);
        }

        // 7. The anonymous per-minute click-entry counter and
        // session-start counter (429, design decision D43, issue #116).
        // They need the parsed batch, to tell a click entry apart from an
        // octo:session-start entry, so they run only here, after the
        // parse. anonymousMinuteKey is null for a signed-in user, and for
        // a request when the app records no anonymous click, so this
        // check then never runs for such a request.
        if (anonymousMinuteKey != null && !events.isEmpty()) {
            int clickEntryCount = 0;
            int sessionStartCount = 0;
            for (IngestEvent event : events) {
                if (IngestPipeline.SESSION_START_ELEMENT.equals(event.element())) {
                    sessionStartCount++;
                } else {
                    clickEntryCount++;
                }
            }
            if (!minuteLimiter.checkEntries(anonymousMinuteKey, clickEntryCount, sessionStartCount)) {
                return new IngestResult(429);
            }
        }

        // 8. The design decision D19 drop: a request with no user id
        // stores nothing, when the app records no anonymous click.
        if (userId == null && !settings.recordAnonymousClicks()) {
            return new IngestResult(204);
        }

        // 9. The daily anonymous caps (204, design decision D43, issue
        // #117), for a request with no user id, when the app records an
        // anonymous click. An empty batch needs no check: it already
        // stores nothing, the same as a dropped batch. Step 8 above
        // already returned for a request with no user id when the app
        // records no anonymous click, so anonymousMinuteKey is not null
        // here.
        if (userId == null && !events.isEmpty()) {
            if (!dailyCap.check(anonymousMinuteKey, events.size())) {
                return new IngestResult(204);
            }
        }

        if (events.isEmpty()) {
            // Contract rule C13 sets no minimum for the clicks array; an
            // empty batch stores nothing (design section 4.2).
            return new IngestResult(204);
        }

        // 10. The store, with the event cap of design decision D21 inside
        // it. A userId that breaks contract rule C6 is a defect of the
        // app, not of the client; it gives 500 above through the thrown
        // exception, never 400.
        try {
            EventFieldValidator.validateUserId(userId);
        } catch (IngestException cause) {
            throw new IllegalStateException(
                    "The resolveUserId function of the app returned a userId that breaks contract rule C6.", cause);
        }
        appendToStoreDefectSafe(events, userId);
        return new IngestResult(204);
    }

    /**
     * Calls {@link EventLogStore#append}, then wraps an
     * {@link IngestException} of the store into
     * {@link IllegalStateException}. Only the parser of this module may
     * raise a 400 answer; a store exception always gives 500 (the Javadoc
     * of {@link EventLogStore#append}).
     */
    private void appendToStoreDefectSafe(List<IngestEvent> events, String userId) {
        try {
            store.append(events, userId);
        } catch (IngestException cause) {
            throw new IllegalStateException("The EventLogStore of the app failed.", cause);
        }
    }

    /**
     * Reads the client address of one request (design decision D20, issue
     * #33; design decision D43, issue #116). See the class comment for the
     * full rule.
     */
    private String clientAddress(RequestView view) {
        if (clientIpHeaderName != null) {
            List<String> headerLines = view.headerLines(clientIpHeaderName);
            if (headerLines != null && !headerLines.isEmpty()) {
                String lastLine = headerLines.get(headerLines.size() - 1);
                String[] elements = lastLine.split(",");
                int index = elements.length - trustedProxyCount;
                if (index >= 0 && index < elements.length) {
                    String candidate = elements[index].trim();
                    if (!candidate.isEmpty() && candidate.length() <= MAX_CLIENT_ADDRESS_LENGTH
                            && looksLikeAnIpAddress(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return view.remoteAddress();
    }

    private static boolean looksLikeAnIpAddress(String value) {
        return IPV4_ADDRESS_PATTERN.matcher(value).matches() || IPV6_ADDRESS_PATTERN.matcher(value).matches();
    }

    /**
     * Checks a {@code Content-Type} value against contract rule C12. The
     * type {@code application/json} passes with a parameter (for example a
     * charset) and without one. Each other type, an absent header, and a
     * malformed header value, fail this check.
     */
    private static boolean hasJsonContentType(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String withoutParameters = rawValue.split(";", 2)[0].trim();
        return withoutParameters.equalsIgnoreCase("application/json");
    }

    private static String firstHeader(RequestView view, String headerName) {
        List<String> lines = view.headerLines(headerName);
        return (lines == null || lines.isEmpty()) ? null : lines.get(0);
    }

    private static String lastHeader(RequestView view, String headerName) {
        List<String> lines = view.headerLines(headerName);
        return (lines == null || lines.isEmpty()) ? null : lines.get(lines.size() - 1);
    }

    /**
     * The small set of request attributes that {@link IngestRequestProcessor}
     * needs. An adapter builds one implementation for each request. No
     * method of this interface reads the request body.
     */
    public interface RequestView {

        /**
         * Returns every line of the request header named
         * {@code headerName}, in receipt order, or an empty list when the
         * request holds no such header. Never {@code null}. The lookup is
         * case-insensitive, the header rule of HTTP.
         */
        List<String> headerLines(String headerName);

        /**
         * Returns the value that the request's {@code Content-Length}
         * declares, in bytes, or {@code null} when the request holds no
         * such value, or a value this method cannot read as a whole
         * number.
         */
        Long declaredContentLengthBytes();

        /**
         * Returns the remote address of the underlying connection: the
         * fallback client address of design decision D20.
         */
        String remoteAddress();

        /**
         * Resolves the user id of the current request, or {@code null}
         * when no user is signed in (contract rule C6).
         * {@link #beforeBody} calls this method once, right after the
         * content type check (see the class comment).
         */
        String resolveUserId();
    }

    /**
     * The outcome of {@link #beforeBody}: either a final
     * {@link IngestResult} that the adapter must answer with no body read,
     * or the state that {@link #afterBody} needs for the same request.
     */
    public static final class BeforeBodyOutcome {

        private final IngestResult result;
        private final String userId;
        private final String anonymousMinuteKey;

        private BeforeBodyOutcome(IngestResult result, String userId, String anonymousMinuteKey) {
            this.result = result;
            this.userId = userId;
            this.anonymousMinuteKey = anonymousMinuteKey;
        }

        private static BeforeBodyOutcome respond(IngestResult result) {
            return new BeforeBodyOutcome(result, null, null);
        }

        private static BeforeBodyOutcome continueToBody(String userId, String anonymousMinuteKey) {
            return new BeforeBodyOutcome(null, userId, anonymousMinuteKey);
        }

        /**
         * True when {@link #beforeBody} already rejected or dropped the
         * request. The adapter must then answer with {@link #result()},
         * and it must read no body, and it must not call {@link #afterBody}.
         */
        public boolean mustRespondNow() {
            return result != null;
        }

        /**
         * The result of {@link #beforeBody}. Not {@code null} when
         * {@link #mustRespondNow()} is {@code true}; {@code null}
         * otherwise.
         */
        public IngestResult result() {
            return result;
        }

        private String userId() {
            return userId;
        }

        private String anonymousMinuteKey() {
            return anonymousMinuteKey;
        }
    }

    /**
     * Throttles the DEBUG line of a bot-filter drop to one line for each
     * elapsed hour (design decision D43, issue #117). With no throttle, a
     * robot flood would write one line for each dropped request, ahead of
     * the rate limiter. {@link #recordDrop} counts every drop. It reports
     * the drop count only on the one call that must write a new line.
     *
     * <p>One processor builds one instance, held for the life of the
     * route or controller, the form of {@link AnonymousDailyCap} and
     * {@link IngestRateLimiter}.
     */
    private static final class BotDropLogThrottle {
        private final Clock clock;
        private final Object lock = new Object();
        private long lastLoggedAtMillis = Long.MIN_VALUE;
        private long dropCountSinceLastLog;

        BotDropLogThrottle(Clock clock) {
            this.clock = clock;
        }

        /**
         * Records one bot-filter drop. It returns the drop count since
         * the last written line, on the one call that must write a new
         * line. It returns {@code null} on every other call; the caller
         * then writes no line.
         */
        Long recordDrop() {
            long now = clock.millis();
            synchronized (lock) {
                dropCountSinceLastLog += 1;
                if (lastLoggedAtMillis != Long.MIN_VALUE && now - lastLoggedAtMillis < BOT_DROP_LOG_THROTTLE_MILLIS) {
                    return null;
                }
                long dueDropCount = dropCountSinceLastLog;
                lastLoggedAtMillis = now;
                dropCountSinceLastLog = 0;
                return dueDropCount;
            }
        }
    }
}
