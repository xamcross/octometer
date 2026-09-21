package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests of the full ingest flow: parse, validate, then compute `ts`, with
 * a fixed {@link Clock} (acceptance criterion of issue #10).
 */
class IngestPipelineTest {

    @Test
    void computesEachEventWithTheFixedClock() {
        Instant fixedInstant = Instant.parse("2026-09-21T10:15:30.000Z");
        Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        String body = ExampleFiles.read("ingest-valid-C13.json");

        List<IngestEvent> events = IngestPipeline.process(body, clock);

        assertEquals(2, events.size());
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", events.get(0).sessionId());
        assertEquals("checkout.save", events.get(0).element());
        assertEquals(fixedInstant.minusMillis(1200L), events.get(0).ts());
        assertEquals("nav.menu.open", events.get(1).element());
        assertEquals(fixedInstant.minusMillis(400L), events.get(1).ts());
    }
}
