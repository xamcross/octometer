package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestSettings} (design decision D19, issue #26). A test
 * sets the flag with the constructor. It does not change the process
 * environment.
 */
class IngestSettingsTest {

    @Test
    void recordAnonymousClicksHoldsTheConstructorValue() {
        assertTrue(new IngestSettings(true).recordAnonymousClicks());
        assertFalse(new IngestSettings(false).recordAnonymousClicks());
    }
}
