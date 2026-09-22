package octometer.kit.core.store;

import octometer.kit.core.ingest.IngestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link InMemoryEventLogStore} (design decision D18, issue #26).
 */
class InMemoryEventLogStoreTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-21T10:15:30.000Z");

    @Test
    void appendAddsOneStoredEventWithTheGivenUserId() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);

        store.append(event, "user-1");

        List<StoredEvent> events = store.events();
        assertEquals(1, events.size());
        assertEquals("user-1", events.get(0).userId());
        assertEquals("checkout.save", events.get(0).element());
    }

    @Test
    void appendAcceptsANullUserId() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);

        store.append(event, null);

        assertNull(store.events().get(0).userId());
    }

    /**
     * Covers each acceptance criterion of issue #35 for one store: the
     * user's own events are gone, the anonymous event of the same
     * session is gone (contract rule C43), a second user of that session
     * stays, and an anonymous event of another session stays.
     */
    @Test
    void deleteByUserIdRemovesTheUsersEventsAndTheAnonymousEventsOfTheSameSession() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent sessionAEvent = new IngestEvent("session-a", "checkout.save", FIXED_INSTANT);
        IngestEvent sessionBEvent = new IngestEvent("session-b", "checkout.save", FIXED_INSTANT);
        IngestEvent sessionCEvent = new IngestEvent("session-c", "checkout.save", FIXED_INSTANT);
        store.append(sessionAEvent, "user-1");
        store.append(sessionAEvent, "user-2");
        store.append(sessionAEvent, null);
        store.append(sessionBEvent, "user-1");
        store.append(sessionCEvent, null);

        DeletionResult result = store.deleteByUserId("user-1");

        assertEquals(2, result.userEventCount());
        assertEquals(1, result.anonymousEventCount());
        assertEquals(3, result.totalCount());
        assertTrue(result.complete(), "The call found no new session id on its last pass.");
        List<StoredEvent> events = store.events();
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(
                storedEvent -> "session-a".equals(storedEvent.sessionId()) && "user-2".equals(storedEvent.userId())),
                "The event of the second user in session-a must stay.");
        assertTrue(events.stream().anyMatch(
                storedEvent -> "session-c".equals(storedEvent.sessionId()) && storedEvent.userId() == null),
                "The anonymous event of session-c must stay.");
    }

    /**
     * Proves the fix of the BLOCKER of pull request #157: a session that
     * starts between step (a) and step (c) of {@link
     * InMemoryEventLogStore#deleteByUserId} waits for the next pass, and
     * the same call still removes its anonymous event.
     */
    @Test
    void aNewSessionBetweenTheSessionReadAndTheDeletesIsRemovedOnTheSecondPass() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent sessionAEvent = new IngestEvent("session-a", "checkout.save", FIXED_INSTANT);
        store.append(sessionAEvent, "user-1");
        IngestEvent newSessionUserEvent = new IngestEvent("session-new", "checkout.save", FIXED_INSTANT);
        IngestEvent newSessionAnonEvent = new IngestEvent("session-new", "nav.open", FIXED_INSTANT);
        store.setAfterFirstSessionReadHookForTest(() -> {
            store.append(newSessionAnonEvent, null);
            store.append(newSessionUserEvent, "user-1");
        });

        DeletionResult result = store.deleteByUserId("user-1");

        assertEquals(2, result.userEventCount(), "The events of session-a and of session-new.");
        assertEquals(1, result.anonymousEventCount(), "The one anonymous event of session-new.");
        assertTrue(result.complete());
        assertTrue(store.events().isEmpty());
    }

    /**
     * Proves the fix of the BLOCKER of pull request #157 from the other
     * side: a throw before step (c) never removes the anonymous events
     * that step (b) already removed, and a second call finishes the
     * user delete that the throw stopped.
     */
    @Test
    void aThrowBeforeTheUserDeleteLeavesTheAnonymousDeleteInPlaceForASecondCall() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent userEvent = new IngestEvent("session-a", "checkout.save", FIXED_INSTANT);
        IngestEvent anonEvent = new IngestEvent("session-a", "nav.open", FIXED_INSTANT);
        store.append(userEvent, "user-1");
        store.append(anonEvent, null);
        store.setBeforeUserEventsRemovedHookForTest(pass -> {
            throw new RuntimeException("A test failure before the user delete of pass " + pass + ".");
        });

        assertThrows(RuntimeException.class, () -> store.deleteByUserId("user-1"));

        List<StoredEvent> afterFirstCall = store.events();
        assertEquals(1, afterFirstCall.size(), "The anonymous event is gone; the user event stays.");
        assertEquals("user-1", afterFirstCall.get(0).userId());

        store.setBeforeUserEventsRemovedHookForTest(pass -> { });
        DeletionResult secondResult = store.deleteByUserId("user-1");

        assertEquals(1, secondResult.userEventCount());
        assertEquals(0, secondResult.anonymousEventCount());
        assertTrue(secondResult.complete());
        assertTrue(store.events().isEmpty());
    }

    /**
     * Proves the pass bound of the maintainer decision: a session that
     * appears on every pass stops the call after 3 passes, and the
     * answer holds {@code complete: false}.
     */
    @Test
    void thePassBoundStopsAtThreePassesAndTheCompleteFlagIsFalse() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        store.append(new IngestEvent("session-0", "checkout.save", FIXED_INSTANT), "user-1");
        AtomicInteger nextSession = new AtomicInteger();
        store.setBeforeUserEventsRemovedHookForTest(pass -> store.append(
                new IngestEvent("session-" + nextSession.incrementAndGet(), "checkout.save", FIXED_INSTANT),
                "user-1"));

        DeletionResult result = store.deleteByUserId("user-1");

        assertFalse(result.complete(), "The bound of 3 passes stopped the call.");
        assertEquals(3, result.userEventCount());
        assertEquals(0, result.anonymousEventCount());
        assertEquals(1, store.events().size(), "The last session, added by the third pass, stays.");
    }

    /**
     * Confirms MINOR 4 of the MongoDB review of pull request #157: the
     * lock around {@link InMemoryEventLogStore#deleteByUserId} gives an
     * exact count under two parallel calls for the same user id.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoParallelErasuresOfTheSameUserGiveAnExactTotalCount() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            store.append(new IngestEvent("session-" + i, "checkout.save", FIXED_INSTANT), "user-1");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<DeletionResult>> tasks = List.of(
                    () -> store.deleteByUserId("user-1"),
                    () -> store.deleteByUserId("user-1"));
            List<Future<DeletionResult>> results = pool.invokeAll(tasks);
            long sum = 0;
            for (Future<DeletionResult> result : results) {
                sum += result.get().userEventCount();
            }
            assertEquals(total, sum, "The lock must give the real removed count, with no double count.");
        } finally {
            pool.shutdown();
        }
        assertTrue(store.events().isEmpty());
    }

    /**
     * Confirms MAJOR 1 of the privacy review of pull request #157
     * (design decision D15): {@link InMemoryEventLogStore#deleteByUserId}
     * puts no user id and no session id into a log line.
     */
    @Test
    void deleteByUserIdLogsNoUserIdAndNoSessionId() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        String sentinelUserId = "sentinel-user-98f3c1";
        String sentinelSessionId = "sentinel-session-71ae2b";
        store.append(new IngestEvent(sentinelSessionId, "checkout.save", FIXED_INSTANT), sentinelUserId);

        Logger rootLogger = Logger.getLogger("");
        List<String> capturedMessages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedMessages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        Level originalLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.ALL);
        rootLogger.addHandler(handler);
        try {
            store.deleteByUserId(sentinelUserId);
        } finally {
            rootLogger.removeHandler(handler);
            rootLogger.setLevel(originalLevel);
        }

        for (String message : capturedMessages) {
            assertFalse(message.contains(sentinelUserId), "A log line must not hold the user id.");
            assertFalse(message.contains(sentinelSessionId), "A log line must not hold the session id.");
        }
    }

    @Test
    void deleteByUserIdRejectsANullValue() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        store.append(event, null);
        store.append(event, "user-1");

        assertThrows(NullPointerException.class, () -> store.deleteByUserId(null));

        // A rejected call must not remove an anonymous event.
        assertEquals(2, store.events().size());
    }

    @Test
    void deleteByUserIdRejectsAnEmptyText() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();

        assertThrows(IllegalArgumentException.class, () -> store.deleteByUserId(""));
    }

    @Test
    void aRejectedBatchLeavesNoPartOfItselfInTheStore() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent first = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        IngestEvent second = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "nav.menu.open", FIXED_INSTANT);
        List<IngestEvent> batchWithANullElement = new java.util.ArrayList<>();
        batchWithANullElement.add(first);
        batchWithANullElement.add(second);
        batchWithANullElement.add(null);

        assertThrows(NullPointerException.class, () -> store.append(batchWithANullElement, "user-1"));

        assertTrue(store.events().isEmpty());
    }

    @Test
    void appendTakesTheWholeBatchInOneCall() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent first = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        IngestEvent second = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "nav.menu.open", FIXED_INSTANT);

        store.append(List.of(first, second), "user-1");

        List<StoredEvent> events = store.events();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(storedEvent -> "user-1".equals(storedEvent.userId())));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void staysCorrectUnderConcurrentAppend() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        int threadCount = 8;
        int iterationsPerThread = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                String userId = "user-" + t;
                tasks.add(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        store.append(event, userId);
                    }
                    return null;
                });
            }
            List<Future<Void>> results = pool.invokeAll(tasks);
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(threadCount * iterationsPerThread, store.events().size());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void staysCorrectWithConcurrentAppendAndDelete() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        for (int i = 0; i < 500; i++) {
            store.append(event, "target-user");
        }

        int otherThreadCount = 4;
        int iterationsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(otherThreadCount + 1);
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < otherThreadCount; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        store.append(event, "other-user");
                    }
                    return null;
                });
            }
            tasks.add(() -> {
                store.deleteByUserId("target-user");
                return null;
            });
            List<Future<Void>> results = pool.invokeAll(tasks);
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }

        // A concurrent removeIf on a ConcurrentLinkedQueue is weakly
        // consistent: a concurrent append can survive a concurrent
        // delete. This second call gives a deterministic end state.
        store.deleteByUserId("target-user");

        List<StoredEvent> events = store.events();
        assertEquals(otherThreadCount * iterationsPerThread, events.size());
        assertFalse(events.stream().anyMatch(storedEvent -> "target-user".equals(storedEvent.userId())));
    }
}
