package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
     * decisions D18 and D19, then gives each valid event to
     * {@code store}. It resolves the user id with
     * {@code userIdResolver}. The client never sets the user id; a
     * `userId` field of the request body has no effect (contract rules
     * C6, C16, C32).
     *
     * <p>When the user id is {@code null} and {@code settings} does not
     * record an anonymous click, this method appends nothing and
     * returns.
     *
     * <p>It throws {@link IngestException} for a broken rule of the
     * contract, the rules of {@link #process} and rule C6 for the
     * resolved user id.
     */
    public static void ingest(String rawBody, Clock clock, UserIdResolver userIdResolver,
            EventLogStore store, IngestSettings settings) {
        List<IngestEvent> events = process(rawBody, clock);
        String userId = userIdResolver.resolve();
        EventFieldValidator.validateUserId(userId);
        if (userId == null && !settings.recordAnonymousClicks()) {
            return;
        }
        for (IngestEvent event : events) {
            store.append(event, userId);
        }
    }
}
