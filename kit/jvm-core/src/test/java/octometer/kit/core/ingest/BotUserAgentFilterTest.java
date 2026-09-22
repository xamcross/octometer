package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link BotUserAgentFilter} against design decision D43 and
 * issue #117.
 */
class BotUserAgentFilterTest {

    @Test
    void theGooglebotUserAgentIsABot() {
        assertTrue(BotUserAgentFilter.isBot("Mozilla/5.0 (compatible; Googlebot/2.1)"));
    }

    @Test
    void theMatchIgnoresTheLetterCase() {
        assertTrue(BotUserAgentFilter.isBot("PYTHON-REQUESTS/2.31"));
    }

    @Test
    void aCommonBrowserUserAgentPassesTheFilter() {
        String chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
        assertFalse(BotUserAgentFilter.isBot(chrome));
    }

    @Test
    void aNullUserAgentPassesTheFilter() {
        assertFalse(BotUserAgentFilter.isBot(null));
    }

    @Test
    void eachTokenOfTheFixedPatternMatches() {
        assertTrue(BotUserAgentFilter.isBot("a bot here"));
        assertTrue(BotUserAgentFilter.isBot("a crawl here"));
        assertTrue(BotUserAgentFilter.isBot("a spider here"));
        assertTrue(BotUserAgentFilter.isBot("a slurp here"));
        assertTrue(BotUserAgentFilter.isBot("a headless here"));
        assertTrue(BotUserAgentFilter.isBot("a preview here"));
        assertTrue(BotUserAgentFilter.isBot("a monitor here"));
        assertTrue(BotUserAgentFilter.isBot("Go-http-client/1.1"));
        assertTrue(BotUserAgentFilter.isBot("curl/8.4.0"));
    }
}
