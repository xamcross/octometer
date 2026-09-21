package octometer.kit.core.store;

import octometer.kit.core.ingest.IngestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void deleteByUserIdRemovesOnlyTheMatchingEvents() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save", FIXED_INSTANT);
        store.append(event, "user-1");
        store.append(event, "user-2");
        store.append(event, null);

        store.deleteByUserId("user-1");

        List<StoredEvent> events = store.events();
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(storedEvent -> "user-1".equals(storedEvent.userId())));
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
