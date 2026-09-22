package octometer.kit.core.store;

/**
 * A cheap count of stored events, for the event cap of design decision D21
 * (issue #34). A store gives its own estimate. MongoDB gives one with
 * {@code estimatedDocumentCount()}, from collection metadata, with no full
 * scan of the collection.
 *
 * <p>The estimate can be stale by a small amount. {@link EventCapGuard}
 * reads it at most one time in each refresh interval, so a store never
 * pays the cost of a fresh count on each request.
 */
public interface EventCountEstimator {

    /**
     * Returns the current estimate. A call can be cheap or costly,
     * depending on the store; {@link EventCapGuard} is the caller that
     * limits how often this method runs.
     */
    long estimatedEventCount();
}
