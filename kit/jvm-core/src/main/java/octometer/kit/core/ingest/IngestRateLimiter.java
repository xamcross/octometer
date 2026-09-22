package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory ingest rate limiter of design decision D20 and issue #33.
 * One key holds one counter in a fixed 60-second window. The window is
 * fixed, not a sliding window: each key starts a new count of zero at the
 * start of each 60-second slot of the clock, and it never looks back at
 * an older request.
 *
 * <p>A user id gives a key of the user map, with a limit of
 * {@value #USER_LIMIT_PER_WINDOW} requests. A {@code null} user id gives
 * a key of the client address map, with a limit of
 * {@value #CLIENT_ADDRESS_LIMIT_PER_WINDOW} requests. The two maps are
 * separate objects, so a user key and a client address key of the same
 * text never share one counter.
 *
 * <p><strong>The two maps of this class.</strong> Design decision D20
 * holds two maps, each with its own cap and its own LRU eviction: a
 * maximum of {@value #MAX_USER_KEYS} user keys, and a maximum of
 * {@value #MAX_CLIENT_ADDRESS_KEYS} client address keys. Both maps are
 * empty at the start of each window. When a map is already at its cap, a
 * new key evicts the oldest key of that map first; a new key thus always
 * gets a fresh counter, and it never waits for the window to end. This
 * class chooses a {@link LinkedHashMap} in access order, under one lock
 * for each map, over a lock-free map such as
 * {@link java.util.concurrent.ConcurrentHashMap}. The access order of an
 * eviction needs a write on each read, and a lock-free map cannot give an
 * exact eviction order under contention; the acceptance criterion of an
 * exact count under contention (1 000 requests, 8 threads, 20 runs) needs
 * that exact order. The lock adds a small, bounded cost to one call of
 * {@link #check}.
 *
 * <p><strong>The key text.</strong> A user id or a client address above
 * {@value #MAX_KEY_LENGTH} characters is replaced by its SHA-256 hex text
 * before it becomes a map key (issue #33). A long header value or a long
 * user id can then never fill the memory of one map with one huge key,
 * and it can never make two different long values collide by truncation.
 *
 * <p><strong>Cost of a rejection.</strong> A rejected request allocates
 * no object beyond one short key text; {@link RateLimitResult#LIMITED} is
 * a fixed enum constant, never a new exception. A rejection writes no log
 * line of its own. This class writes a maximum of one warning for the
 * whole window, and that one line never holds a key, a user id, or a
 * client address (design decision D15).
 *
 * <p>This class is thread-safe. Many requests can call {@link #check} at
 * the same time.
 */
public final class IngestRateLimiter {

    /** The limit of one user id key in one window (design decision D20). */
    static final int USER_LIMIT_PER_WINDOW = 30;

    /** The limit of one client address key in one window (design decision D20). */
    static final int CLIENT_ADDRESS_LIMIT_PER_WINDOW = 120;

    /** The cap of the user key map, with an LRU eviction (design decision D20). */
    static final int MAX_USER_KEYS = 5_000;

    /** The cap of the client address key map, with an LRU eviction (design decision D20). */
    static final int MAX_CLIENT_ADDRESS_KEYS = 20_000;

    /**
     * The maximum length of one key text before this class replaces it
     * with its SHA-256 hex text (issue #33). The text form of an IPv6
     * address needs at most 45 characters, so this cap stays well above
     * every normal address text.
     */
    static final int MAX_KEY_LENGTH = 64;

    private static final long WINDOW_MILLIS = Duration.ofSeconds(60).toMillis();

    private static final String SHA_256 = "SHA-256";

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    private final Clock clock;

    /** Guards a change of {@link #window} from one window to the next. */
    private final Object windowLock = new Object();

    private volatile Window window = new Window(Long.MIN_VALUE, new KeyMap(MAX_USER_KEYS), new KeyMap(MAX_CLIENT_ADDRESS_KEYS));
    private final AtomicLong lastWarnedWindowStart = new AtomicLong(Long.MIN_VALUE);

    public IngestRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Checks one request against the window limit of design decision D20.
     *
     * @param userId the user id of the request, or {@code null} for a
     *   request with no signed-in user (contract rule C6).
     * @param clientAddress the client address of the request (issue #33,
     *   step 3). This method reads it only when {@code userId} is
     *   {@code null}, but the parameter itself must never be
     *   {@code null}.
     * @throws NullPointerException when {@code userId} is {@code null}
     *   and {@code clientAddress} is also {@code null}.
     */
    public RateLimitResult check(String userId, String clientAddress) {
        boolean anonymous = userId == null;
        String rawKey;
        int limit;
        if (anonymous) {
            Objects.requireNonNull(clientAddress, "clientAddress must not be null when userId is null");
            rawKey = clientAddress;
            limit = CLIENT_ADDRESS_LIMIT_PER_WINDOW;
        } else {
            rawKey = userId;
            limit = USER_LIMIT_PER_WINDOW;
        }
        String key = shortenKey(rawKey);

        long now = clock.millis();
        long requestedWindowStart = now - Math.floorMod(now, WINDOW_MILLIS);
        Window current = windowFor(requestedWindowStart);
        KeyMap map = anonymous ? current.addressCounters() : current.userCounters();

        int count = map.incrementAndGet(key);
        if (count > limit) {
            // current.start() is the window that this request actually
            // counted in. A late caller can compute an older
            // requestedWindowStart than current.start() (see windowFor);
            // the warning must still name the real window, so that two
            // late callers of the same real window write one warning
            // together, not two.
            warnOncePerWindow(current.start());
            return RateLimitResult.LIMITED;
        }
        return RateLimitResult.ALLOWED;
    }

    /**
     * Returns the window of {@code requestedWindowStart}, moving
     * {@link #window} forward once, the first time a caller reaches a
     * new window. This method never moves the window backwards: a caller
     * whose {@code requestedWindowStart} is older than the live window
     * (a stalled thread, or a clock that steps backwards) still gets the
     * live window, not a fresh, empty one. Concurrency review BLOCKER 1
     * of pull request #158 named the earlier, two-field version of this
     * method, where a stalled thread could move the window backwards and
     * reset every count of the live window.
     */
    private Window windowFor(long requestedWindowStart) {
        Window current = window;
        if (current.start() == requestedWindowStart) {
            return current;
        }
        synchronized (windowLock) {
            current = window;
            if (requestedWindowStart > current.start()) {
                current = new Window(requestedWindowStart, new KeyMap(MAX_USER_KEYS), new KeyMap(MAX_CLIENT_ADDRESS_KEYS));
                window = current;
            }
            return current;
        }
    }

    /**
     * Writes one warning for {@code windowStart}, at most one time. The
     * message names no key, no user id, and no client address (design
     * decision D15).
     */
    private void warnOncePerWindow(long windowStart) {
        long previouslyWarnedWindowStart = lastWarnedWindowStart.get();
        if (previouslyWarnedWindowStart == windowStart) {
            return;
        }
        if (lastWarnedWindowStart.compareAndSet(previouslyWarnedWindowStart, windowStart)) {
            LOGGER.log(Level.WARNING, "The ingest rate limiter rejects one or more requests in "
                    + "this 60-second window (design decision D20). The log holds no key, no "
                    + "user id, and no client address.");
        }
    }

    /**
     * Returns {@code rawKey} when it holds {@value #MAX_KEY_LENGTH}
     * characters or fewer. Above that length, this method returns the
     * SHA-256 hex text of {@code rawKey} instead (issue #33), so a key
     * map key always stays short, and two different long values almost
     * never collide.
     */
    private static String shortenKey(String rawKey) {
        if (rawKey.length() <= MAX_KEY_LENGTH) {
            return rawKey;
        }
        return sha256Hex(rawKey);
    }

    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException cause) {
            // Every JDK 21 runtime provides SHA-256 (the Java Cryptography
            // Architecture standard algorithm list). This branch never
            // runs in practice.
            throw new IllegalStateException(SHA_256 + " must be available in a JDK 21 runtime.", cause);
        }
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * One fixed window: its start instant, and its own user map and
     * client address map. This class holds the whole triple in one
     * {@code volatile} field ({@link #window}), so a reader of that field
     * always sees one matching start and one matching pair of maps
     * together, never a start of one window paired with the maps of a
     * different window.
     */
    private record Window(long start, KeyMap userCounters, KeyMap addressCounters) {
    }

    /**
     * One key map of one window, with a fixed cap and an LRU eviction
     * (design decision D20, issue #33). See the class comment of
     * {@link IngestRateLimiter} for the reason this class uses a
     * {@link LinkedHashMap} under one lock, and not a lock-free map.
     */
    private static final class KeyMap {

        private final Object lock = new Object();
        private final LinkedHashMap<String, Integer> counters;

        KeyMap(int maxKeys) {
            this.counters = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > maxKeys;
                }
            };
        }

        /**
         * Increments the counter of {@code key} and returns the new
         * count. A key that this map has not tracked yet in this window
         * starts at one. When this map already holds its cap of keys,
         * the insert of a new key evicts the oldest key of this map
         * first (the {@code accessOrder} constructor argument makes each
         * read move a key to the newest end); the new key thus always
         * gets a fresh counter, never a rejection for the reason that
         * this map is full.
         */
        int incrementAndGet(String key) {
            synchronized (lock) {
                Integer count = counters.get(key);
                int newCount = (count == null ? 0 : count) + 1;
                counters.put(key, newCount);
                return newCount;
            }
        }
    }
}
