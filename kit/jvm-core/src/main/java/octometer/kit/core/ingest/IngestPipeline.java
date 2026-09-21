package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.user.UserIdResolver;

/**
 * The full ingest flow of design section 4.2.
 * It parses the raw body, checks the `sessionId` and `element` field
 * rules, then computes `ts` for each click. This class is the only
 * public entry point of the module. An adapter calls {@link #ingest}
 * with the raw body, a {@link Clock}, a {@link UserIdResolver}, and an
 * {@link EventLogStore}.
 */
public final class IngestPipeline {

    private IngestPipeline() {
    }

    /**
     * Parses and validates one ingest body, then returns one
     * {@link IngestEvent} for each click, in the order of the `clicks`
     * array.
     *
     * <p>It throws {@link IngestException} for a broken rule of the
     * contract (`contract/README.md`, rules C4, C5, C13, C15, C17, C18,
     * C36, C37).
     *
     * <p>This method checks the size of the decoded {@code String}. The
     * adapter must reject a request above 16 384 bytes. It checks the
     * `Content-Length` header and the byte count, before it decodes the
     * body.
     */
    public static List<IngestEvent> process(String rawBody, Clock clock) {
        ParsedIngestRequest parsed = IngestParser.parse(rawBody);
        EventFieldValidator.validateSessionId(parsed.sessionId());

        Instant receivedAt = clock.instant();
        List<IngestEvent> events = new ArrayList<>(parsed.clicks().size());
        for (ParsedClick click : parsed.clicks()) {
            EventFieldValidator.validateElement(click.element());
            Instant ts = TsCalculator.computeTs(receivedAt, click.ageMs());
            events.add(new IngestEvent(parsed.sessionId(), click.element(), ts));
        }
        return List.copyOf(events);
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
     * <p>Each parameter must not be {@code null}.
     */
    public static void ingest(String rawBody, Clock clock, UserIdResolver userIdResolver,
            EventLogStore store, IngestSettings settings) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(userIdResolver, "userIdResolver must not be null");
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        List<IngestEvent> events = process(rawBody, clock);
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
