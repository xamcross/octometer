package octometer.kit.mongo.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aZeroValueGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        long maxEvents = MongoEventLogStore.maxEventsFromValue("0");

        assertEquals(200_000, maxEvents);
        assertOneWarningNaming("OCTOMETER_MAX_EVENTS");
    }

    @Test
    void aNegativeValueGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        long maxEvents = MongoEventLogStore.maxEventsFromValue("-5");

        assertEquals(200_000, maxEvents);
        assertOneWarningNaming("OCTOMETER_MAX_EVENTS");
    }

    @Test
    void aTextThatIsNotAWholeNumberGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        long maxEvents = MongoEventLogStore.maxEventsFromValue("abc");

        assertEquals(200_000, maxEvents);
        assertOneWarningNaming("OCTOMETER_MAX_EVENTS");
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
