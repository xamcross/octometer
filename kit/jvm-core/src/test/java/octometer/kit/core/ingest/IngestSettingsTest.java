package octometer.kit.core.ingest;

import java.util.List;
import octometer.kit.core.path.PathPatternMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestSettings} (design decision D19, issue #26; design
 * decision D40, contract rule C42, issue #104). A test sets each field
 * with the constructor. It does not change the process environment.
 */
class IngestSettingsTest {

    @Test
    void recordAnonymousClicksHoldsTheConstructorValue() {
        assertTrue(new IngestSettings(true).recordAnonymousClicks());
        assertFalse(new IngestSettings(false).recordAnonymousClicks());
    }

    @Test
    void theOneArgumentConstructorGivesNoPathPatternMatcher() {
        assertNull(new IngestSettings(true).pathPatternMatcher());
    }

    @Test
    void theTwoArgumentConstructorHoldsThePathPatternMatcher() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/articles"));

        IngestSettings settings = new IngestSettings(false, matcher);

        assertEquals(matcher, settings.pathPatternMatcher());
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

    @Test
    void pathPatternMatcherFromValueGivesNoMatcherForANullValueAndWarnsOnce() {
        CapturingLoggerFinder.clear();

        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue(null);

        assertNull(matcher);
        assertEquals(1, CapturingLoggerFinder.messages().size());
        assertTrue(CapturingLoggerFinder.messages().peek().contains("OCTOMETER_PATH_PATTERNS"));
    }

    @Test
    void pathPatternMatcherFromValueGivesNoMatcherForAnEmptyValueAndWarnsOnce() {
        CapturingLoggerFinder.clear();

        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue("");

        assertNull(matcher);
        assertEquals(1, CapturingLoggerFinder.messages().size());
    }

    @Test
    void pathPatternMatcherFromValueBuildsAMatcherForAWellFormedList() {
        PathPatternMatcher matcher =
                IngestSettings.pathPatternMatcherFromValue("/ /articles /articles/* /history/:id /ovdp/rates");

        assertEquals("/history/:id", matcher.match("/history/42"));
    }

    @Test
    void pathPatternMatcherFromValueGivesNoWarningForAWellFormedList() {
        CapturingLoggerFinder.clear();

        IngestSettings.pathPatternMatcherFromValue("/articles /history/:id");

        assertTrue(CapturingLoggerFinder.messages().isEmpty());
    }

    @Test
    void pathPatternMatcherFromValueGivesNoMatcherForOneInvalidEntryAndWarnsWithItsIndex() {
        CapturingLoggerFinder.clear();

        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue("/articles no-slash /history/:id");

        assertNull(matcher);
        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        assertTrue(message.contains("OCTOMETER_PATH_PATTERNS"));
        assertTrue(message.contains("index 1"));
        assertFalse(message.contains("no-slash"));
    }

    @Test
    void pathPatternMatcherFromValueNamesEachInvalidIndexWithOneWarning() {
        CapturingLoggerFinder.clear();

        PathPatternMatcher matcher =
                IngestSettings.pathPatternMatcherFromValue("no-slash /articles also-bad");

        assertNull(matcher);
        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        assertTrue(message.contains("index 0, 2"));
        assertFalse(message.contains("also-bad"));
    }

    @Test
    void pathPatternMatcherFromValueSplitsOnARunOfWhitespaceAndKeepsAnEntryWithSpacesAroundIt() {
        PathPatternMatcher matcher =
                IngestSettings.pathPatternMatcherFromValue("   /articles     /history/:id   ");

        assertEquals("/history/:id", matcher.match("/history/42"));
        assertEquals("/articles", matcher.match("/articles"));
    }

    @Test
    void pathPatternMatcherFromValueTreatsAnEntryWithACommaAsOnePattern() {
        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue("/a,b /articles");

        assertEquals("/a,b", matcher.match("/a,b"));
    }

    @Test
    void pathPatternMatcherFromValueSplitsOnALineBreakBetweenTwoEntries() {
        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue("/articles\n/history/:id");

        assertEquals("/history/:id", matcher.match("/history/42"));
    }

    @Test
    void pathPatternMatcherFromValueGivesNoMatcherForAWhitespaceOnlyValueAndWarnsOnce() {
        CapturingLoggerFinder.clear();

        PathPatternMatcher matcher = IngestSettings.pathPatternMatcherFromValue("   \t  ");

        assertNull(matcher);
        assertEquals(1, CapturingLoggerFinder.messages().size());
    }

    @Test
    void fromEnvironmentReadsBothVariablesWithNoFailure() {
        // The process environment of the test run holds no
        // OCTOMETER_PATH_PATTERNS entry. This test proves only that
        // fromEnvironment reads it through System.getenv with no
        // failure. IngestRouteTest of kit/jvm-ktor covers the route
        // seam.
        IngestSettings settings = IngestSettings.fromEnvironment();

        assertNull(settings.pathPatternMatcher());
    }

    // The tests below cover the two daily anonymous caps of design
    // decision D43 and issue #117.

    @Test
    void theOneArgumentConstructorGivesTheDefaultDailyCaps() {
        IngestSettings settings = new IngestSettings(true);

        assertEquals(20_000, settings.anonMaxEventsPerDay());
        assertEquals(2_000, settings.anonEventsPerKeyPerDay());
    }

    @Test
    void theTwoArgumentConstructorGivesTheDefaultDailyCaps() {
        IngestSettings settings = new IngestSettings(true, null);

        assertEquals(20_000, settings.anonMaxEventsPerDay());
        assertEquals(2_000, settings.anonEventsPerKeyPerDay());
    }

    @Test
    void theFourArgumentConstructorHoldsEachDailyCap() {
        IngestSettings settings = new IngestSettings(true, null, 500, 50);

        assertEquals(500, settings.anonMaxEventsPerDay());
        assertEquals(50, settings.anonEventsPerKeyPerDay());
    }

    @Test
    void positiveWholeNumberFromValueGivesTheDefaultForANullValue() {
        assertEquals(20_000,
                IngestSettings.positiveWholeNumberFromValue(null, "OCTOMETER_MAX_ANON_EVENTS_PER_DAY", 20_000));
    }

    @Test
    void positiveWholeNumberFromValueGivesItsOwnValueForAPositiveWholeNumber() {
        assertEquals(500,
                IngestSettings.positiveWholeNumberFromValue("500", "OCTOMETER_MAX_ANON_EVENTS_PER_DAY", 20_000));
    }

    @Test
    void positiveWholeNumberFromValueTrimsALeadingOrATrailingSpace() {
        assertEquals(500,
                IngestSettings.positiveWholeNumberFromValue(" 500 ", "OCTOMETER_MAX_ANON_EVENTS_PER_DAY", 20_000));
    }

    @Test
    void positiveWholeNumberFromValueStopsTheAppStartForAZeroValueWithNoRepeatOfTheValue() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> IngestSettings.positiveWholeNumberFromValue("0", "OCTOMETER_MAX_ANON_EVENTS_PER_DAY", 20_000));

        assertTrue(exception.getMessage().contains("OCTOMETER_MAX_ANON_EVENTS_PER_DAY"));
        assertFalse(exception.getMessage().contains("\"0\""));
    }

    @Test
    void positiveWholeNumberFromValueStopsTheAppStartForANegativeValue() {
        assertThrows(IllegalStateException.class,
                () -> IngestSettings.positiveWholeNumberFromValue("-5", "OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY",
                        2_000));
    }

    @Test
    void positiveWholeNumberFromValueStopsTheAppStartForATextValue() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> IngestSettings.positiveWholeNumberFromValue("abc", "OCTOMETER_MAX_ANON_EVENTS_PER_DAY",
                        20_000));

        assertFalse(exception.getMessage().contains("abc"));
    }

    @Test
    void fromEnvironmentGivesTheDefaultDailyCapsWithNoEnvironmentVariable() {
        // The process environment of the test run holds no
        // OCTOMETER_MAX_ANON_EVENTS_PER_DAY entry and no
        // OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY entry.
        IngestSettings settings = IngestSettings.fromEnvironment();

        assertEquals(20_000, settings.anonMaxEventsPerDay());
        assertEquals(2_000, settings.anonEventsPerKeyPerDay());
    }
}
