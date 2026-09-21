package octometer.kit.mongo.store;

import java.lang.System.Logger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ResourceBundle;

/**
 * A test-only {@link System.LoggerFinder}. The service file of
 * {@code META-INF/services} names this class, so the JVM gives its
 * {@link Logger} for each call of {@link System#getLogger(String)}. A test
 * reads {@link #records()} with no new dependency: {@code System.Logger}
 * is a part of {@code java.base}.
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    /** One captured log call: its level and its message. */
    public record Record(Logger.Level level, String message) {
    }

    private static final Deque<Record> RECORDS = new ArrayDeque<>();

    /** Returns each record since the last call of {@link #clear()}, oldest first. */
    static Deque<Record> records() {
        return RECORDS;
    }

    /** Empties the recorded records, before one test runs. */
    static void clear() {
        RECORDS.clear();
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
                RECORDS.add(new Record(level, msg));
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                RECORDS.add(new Record(level, format));
            }
        };
    }
}
