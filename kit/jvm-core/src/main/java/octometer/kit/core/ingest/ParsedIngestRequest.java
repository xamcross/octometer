package octometer.kit.core.ingest;

import java.util.List;

/**
 * The parsed shape of an ingest body (design section 4.2), before the
 * field validation of rules C4, C5, and C15.
 */
record ParsedIngestRequest(String sessionId, List<ParsedClick> clicks) {

    ParsedIngestRequest {
        clicks = List.copyOf(clicks);
    }
}
