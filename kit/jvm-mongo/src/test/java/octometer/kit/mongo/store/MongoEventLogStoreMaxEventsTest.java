package octometer.kit.mongo.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link MongoEventLogStore#maxEventsFromValue}. The design
 * source is decision D21 and contract rule C19, issue #34:
 * {@code OCTOMETER_MAX_EVENTS} sets the event cap, and the default is
 * 200000.
 */
class MongoEventLogStoreMaxEventsTest {

    @Test
    void aNullValueGivesTheDefaultOf200000() {
        assertEquals(200_000, MongoEventLogStore.maxEventsFromValue(null));
    }

    @Test
    void aPositiveWholeNumberGivesItsOwnValue() {
        assertEquals(10, MongoEventLogStore.maxEventsFromValue("10"));
        assertEquals(1, MongoEventLogStore.maxEventsFromValue("1"));
    }

    @Test
    void theMethodTrimsALeadingOrATrailingSpace() {
        assertEquals(500, MongoEventLogStore.maxEventsFromValue(" 500 "));
    }

    @Test
    void aZeroValueStopsTheAppStartWithAClearError() {
        // Correction round 1 of pull request #165: a wrong cap must never
        // guess a default and let the ingest fill the whole database.
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MongoEventLogStore.maxEventsFromValue("0"));
        assertTrue(exception.getMessage().contains("OCTOMETER_MAX_EVENTS"));
    }

    @Test
    void aNegativeValueStopsTheAppStartWithAClearError() {
        assertThrows(IllegalStateException.class, () -> MongoEventLogStore.maxEventsFromValue("-5"));
    }

    @Test
    void aTextThatIsNotAWholeNumberStopsTheAppStartWithAClearError() {
        assertThrows(IllegalStateException.class, () -> MongoEventLogStore.maxEventsFromValue("abc"));
    }

    @Test
    void aValueAboveTheClampGivesTheClampAndAWarning() {
        // Security review of pull request #165, MINOR 1: a huge cap must
        // not let the collection grow past the storage of a small
        // MongoDB cluster.
        CapturingLoggerFinder.clear();

        long maxEvents = MongoEventLogStore.maxEventsFromValue("100000000000");

        assertEquals(1_000_000, maxEvents);
        assertOneWarningNaming("OCTOMETER_MAX_EVENTS");
    }

    @Test
    void aValueAtTheClampGivesItsOwnValueWithNoWarning() {
        CapturingLoggerFinder.clear();

        long maxEvents = MongoEventLogStore.maxEventsFromValue("1000000");

        assertEquals(1_000_000, maxEvents);
        assertTrue(CapturingLoggerFinder.records().isEmpty());
    }

    @Test
    void aNullValueGivesNoWarning() {
        CapturingLoggerFinder.clear();

        MongoEventLogStore.maxEventsFromValue(null);

        assertTrue(CapturingLoggerFinder.records().isEmpty());
    }

    private static void assertOneWarningNaming(String text) {
        assertEquals(1, CapturingLoggerFinder.records().size());
        CapturingLoggerFinder.Record record = CapturingLoggerFinder.records().peek();
        assertEquals(java.lang.System.Logger.Level.WARNING, record.level());
        assertTrue(record.message().contains(text));
    }
}
