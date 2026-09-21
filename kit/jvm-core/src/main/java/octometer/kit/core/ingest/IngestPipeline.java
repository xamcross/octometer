package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The full ingest flow of design section 4.2.
 * It parses the raw body, checks the `sessionId` and `element` field
 * rules, then computes `ts` for each click. This class is the only
 * public entry point of the module. An adapter of a later issue calls
 * it with the raw body and a {@link Clock}, then attaches the `userId`
 * value and stores each {@link IngestEvent}.
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
}
