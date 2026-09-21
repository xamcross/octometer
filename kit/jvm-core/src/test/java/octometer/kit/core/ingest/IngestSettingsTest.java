package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void fromValueAcceptsOnlyTheExactTextTrue() {
        assertFalse(IngestSettings.fromValue(null).recordAnonymousClicks());
        assertTrue(IngestSettings.fromValue("true").recordAnonymousClicks());
        assertFalse(IngestSettings.fromValue("TRUE").recordAnonymousClicks());
        assertFalse(IngestSettings.fromValue("false").recordAnonymousClicks());
        assertFalse(IngestSettings.fromValue("1").recordAnonymousClicks());
        assertFalse(IngestSettings.fromValue("yes").recordAnonymousClicks());
        assertFalse(IngestSettings.fromValue(" true ").recordAnonymousClicks());
    }

    @Test
    void fromValueWarnsForAValueThatIsNeitherTrueNorFalseWithNoRepeatOfTheValue() {
        CapturingLoggerFinder.clear();

        IngestSettings.fromValue("TRUE");

        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        assertTrue(message.contains("OCTOMETER_RECORD_ANONYMOUS"));
        assertFalse(message.contains("TRUE"));
    }

    @Test
    void fromValueGivesNoWarningForTheExactTextsTrueAndFalseAndForANullValue() {
        CapturingLoggerFinder.clear();

        IngestSettings.fromValue("true");
        IngestSettings.fromValue("false");
        IngestSettings.fromValue(null);

        assertTrue(CapturingLoggerFinder.messages().isEmpty());
    }
}
