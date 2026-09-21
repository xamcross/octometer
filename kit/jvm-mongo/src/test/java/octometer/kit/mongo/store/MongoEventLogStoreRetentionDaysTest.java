package octometer.kit.mongo.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link MongoEventLogStore#retentionDaysFromValue}. The design
 * source is decision D22 and contract rule C8: {@code
 * OCTOMETER_RETENTION_DAYS} sets the TTL value, and the default is 30 days.
 */
class MongoEventLogStoreRetentionDaysTest {

    @Test
    void aNullValueGivesTheDefaultOfThirtyDays() {
        assertEquals(30, MongoEventLogStore.retentionDaysFromValue(null));
    }

    @Test
    void aPositiveWholeNumberGivesItsOwnValue() {
        assertEquals(10, MongoEventLogStore.retentionDaysFromValue("10"));
        assertEquals(1, MongoEventLogStore.retentionDaysFromValue("1"));
    }

    @Test
    void theMethodTrimsALeadingOrATrailingSpace() {
        assertEquals(15, MongoEventLogStore.retentionDaysFromValue(" 15 "));
    }

    @Test
    void aZeroValueGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        int days = MongoEventLogStore.retentionDaysFromValue("0");

        assertEquals(30, days);
        assertOneWarningNaming("OCTOMETER_RETENTION_DAYS");
    }

    @Test
    void aNegativeValueGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        int days = MongoEventLogStore.retentionDaysFromValue("-5");

        assertEquals(30, days);
        assertOneWarningNaming("OCTOMETER_RETENTION_DAYS");
    }

    @Test
    void aTextThatIsNotAWholeNumberGivesTheDefaultAndAWarning() {
        CapturingLoggerFinder.clear();

        int days = MongoEventLogStore.retentionDaysFromValue("abc");

        assertEquals(30, days);
        assertOneWarningNaming("OCTOMETER_RETENTION_DAYS");
    }

    @Test
    void aNullValueGivesNoWarning() {
        CapturingLoggerFinder.clear();

        MongoEventLogStore.retentionDaysFromValue(null);

        assertTrue(CapturingLoggerFinder.records().isEmpty());
    }

    private static void assertOneWarningNaming(String text) {
        assertEquals(1, CapturingLoggerFinder.records().size());
        CapturingLoggerFinder.Record record = CapturingLoggerFinder.records().peek();
        assertEquals(java.lang.System.Logger.Level.WARNING, record.level());
        assertTrue(record.message().contains(text));
    }
}
