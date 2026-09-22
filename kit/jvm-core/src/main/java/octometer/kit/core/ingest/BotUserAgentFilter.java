package octometer.kit.core.ingest;

import java.util.regex.Pattern;

/**
 * The user-agent filter of design decision D43 and issue #117. A batch
 * of a request whose `User-Agent` header value matches this pattern is a
 * batch of a known robot.
 *
 * <p>The match ignores the letter case, and it matches any part of the
 * header value, not only the start. This class stores no header value
 * of its own; {@link #isBot(String)} only returns a result.
 */
public final class BotUserAgentFilter {

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "bot|crawl|spider|slurp|headless|preview|monitor|Go-http-client|python-requests|curl",
            Pattern.CASE_INSENSITIVE);

    private BotUserAgentFilter() {
    }

    /**
     * True when {@code userAgent} matches the bot pattern of design
     * decision D43. A {@code null} value gives {@code false}, so an
     * absent `User-Agent` header passes the filter (issue #117).
     */
    public static boolean isBot(String userAgent) {
        return userAgent != null && BOT_PATTERN.matcher(userAgent).find();
    }
}
