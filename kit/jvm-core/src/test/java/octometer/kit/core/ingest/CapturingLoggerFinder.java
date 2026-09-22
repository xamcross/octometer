package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ResourceBundle;

/**
 * A test-only {@link System.LoggerFinder}. The service file of
 * `META-INF/services` names this class, so the JVM gives its
 * {@link Logger} for each call of {@link System#getLogger(String)}. A
 * test then reads {@link #messages()} with no new dependency: {@code
 * System.Logger} is a part of `java.base`. {@link #messages()} and
 * {@link #clear()} are public, so a test of another package of this
 * module reads them too, for example {@code octometer.kit.core.store}.
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    private static final Deque<String> MESSAGES = new ArrayDeque<>();

    /**
     * Returns each message that a test recorded since the last call of
     * {@link #clear()}, oldest first.
     */
    public static Deque<String> messages() {
        return MESSAGES;
    }

    /** Empties the recorded messages, before one test runs. */
    public static void clear() {
        MESSAGES.clear();
    }

    @Override
    public Logger getLogger(String name, Module module) {
        return new Logger() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                MESSAGES.add(msg);
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                MESSAGES.add(format);
            }
        };
    }
}
