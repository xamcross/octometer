package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The full ingest flow of design section 4.2: parse the raw body, check
 * the `sessionId` and `element` field rules, then compute `ts` for each
 * click. An adapter of a later issue calls this class with the raw body
 * and a {@link Clock}, then attaches the `userId` value and stores each
 * {@link IngestEvent}.
 */
public final class IngestPipeline {

    private IngestPipeline() {
    }

    /**
     * Parses and validates one ingest body, then returns one
     * {@link IngestEvent} for each click, in the order of the `clicks`
     * array. It throws {@link IngestException} for a broken rule of the
     * contract (`contract/README.md`, rules C4, C5, C13, C15, C17, C18).
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
        return events;
    }
}
