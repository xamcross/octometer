package octometer.kit.core.store;

/**
 * The result of {@link EventLogStore#deleteByUserId}. It holds two counts
 * and one flag.
 *
 * <p>{@code userEventCount} is the count of deleted events that held the
 * given user id. {@code anonymousEventCount} is the count of deleted
 * events with {@code userId: null}, of a session of that user (contract
 * rule C43).
 *
 * <p>{@code complete} is {@code true} when the call found no new session
 * id on its last pass. It is {@code false} when the pass bound stopped
 * the call first. A caller runs the method again when {@code complete}
 * is {@code false}.
 *
 * <p>This record holds no user id, no session id, and no other value of a
 * deleted event (design decision D15). A log line, an exception message,
 * or a {@code toString()} text can print this record with no privacy
 * risk.
 */
public record DeletionResult(long userEventCount, long anonymousEventCount, boolean complete) {

    /**
     * Rejects a negative count. A count of zero is valid; it means that
     * the call found no matching event.
     */
    public DeletionResult {
        if (userEventCount < 0) {
            throw new IllegalArgumentException("userEventCount must not be negative");
        }
        if (anonymousEventCount < 0) {
            throw new IllegalArgumentException("anonymousEventCount must not be negative");
        }
    }

    /**
     * The sum of the two counts: each event that the call deleted.
     */
    public long totalCount() {
        return userEventCount + anonymousEventCount;
    }
}
