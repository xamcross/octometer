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
 * System.Logger} is a part of `java.base`.
 *
 * <p>{@link #messages()} and {@link #clear()} are {@code public} (issue
 * #34), so a test of a different package of this module, for example
 * {@code octometer.kit.core.store}, can read the same log capture. The
 * module registers one {@link System.LoggerFinder} only. {@link
 * #messages()} returns a copy of the recorded messages, so a caller
 * cannot change the live capture (MongoDB review of pull request #165,
 * MINOR 5).
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    private static final Deque<String> MESSAGES = new ArrayDeque<>();

    /**
     * Returns a copy of each message that a test recorded since the
     * last call of {@link #clear()}, oldest first. The copy protects
     * the live capture from a change by the caller.
     */
    public static Deque<String> messages() {
        return new ArrayDeque<>(MESSAGES);
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
