package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link EventFieldValidator#matchReferrerHost} and {@link
 * EventFieldValidator#referrerHostSourceList} against rule C40 (issue
 * #103).
 */
class EventFieldValidatorReferrerHostTest {

    @Test
    void matchesTheLiteralOther() {
        assertEquals("other", EventFieldValidator.matchReferrerHost("other"));
    }

    @Test
    void matchesGoogleComExactly() {
        assertEquals("google.com", EventFieldValidator.matchReferrerHost("google.com"));
    }

    @Test
    void matchesASubdomainOfGoogleCom() {
        assertEquals("google.com", EventFieldValidator.matchReferrerHost("www.google.com"));
    }

    @Test
    void matchesAGoogleCountryHost() {
        assertEquals("google.com", EventFieldValidator.matchReferrerHost("www.google.de"));
        assertEquals("google.com", EventFieldValidator.matchReferrerHost("www.google.co.uk"));
    }

    @Test
    void givesOtherForAHostThatOnlyLooksLikeGoogle() {
        assertEquals("other", EventFieldValidator.matchReferrerHost("attacker.google.top"));
        assertEquals("other", EventFieldValidator.matchReferrerHost("google.zip"));
    }

    @Test
    void matchesBingComExactly() {
        assertEquals("bing.com", EventFieldValidator.matchReferrerHost("bing.com"));
    }

    @Test
    void matchesASubdomainOfBingCom() {
        assertEquals("bing.com", EventFieldValidator.matchReferrerHost("www.bing.com"));
    }

    @Test
    void givesOtherForAHostNameOutsideTheSourceList() {
        assertEquals("other", EventFieldValidator.matchReferrerHost("example.org"));
    }

    @Test
    void givesNullForAHostWithAnUpperCaseLetter() {
        assertNull(EventFieldValidator.matchReferrerHost("Example.org"));
    }

    @Test
    void givesNullForAHostWithNoDot() {
        assertNull(EventFieldValidator.matchReferrerHost("localhost"));
    }

    @Test
    void givesNullForAnEmptyValue() {
        assertNull(EventFieldValidator.matchReferrerHost(""));
    }

    @Test
    void acceptsAHostNameOfExactly253Bytes() {
        String host = "a".repeat(249) + ".com";
        assertEquals(253, host.length());
        assertEquals("other", EventFieldValidator.matchReferrerHost(host));
    }

    @Test
    void givesNullForAHostNameOf254Bytes() {
        String host = "a".repeat(250) + ".com";
        assertEquals(254, host.length());
        assertNull(EventFieldValidator.matchReferrerHost(host));
    }

    @Test
    void referrerHostSourceListHoldsExactlyTwoEntries() {
        assertEquals(Set.of("google.com", "bing.com"), EventFieldValidator.referrerHostSourceList());
    }

    @Test
    void theSourceListMatchesTheContractSourceList() {
        String readme = ContractReadme.read();
        // The raw markdown wraps this sentence across two source lines, so
        // the pattern allows a run of whitespace (a space, or a line
        // break plus the indent) between each word.
        Pattern pattern = Pattern.compile(
                "The\\s+source\\s+list\\s+has\\s+two\\s+entries:\\s*"
                        + "`([a-z0-9.-]+)`\\s+and\\s+`([a-z0-9.-]+)`\\.");
        Matcher matcher = pattern.matcher(readme);
        assertTrue(matcher.find(), "the contract text must state the source list with two entries");
        Set<String> contractList = new HashSet<>();
        contractList.add(matcher.group(1));
        contractList.add(matcher.group(2));

        assertEquals(contractList, EventFieldValidator.referrerHostSourceList());
    }
}
