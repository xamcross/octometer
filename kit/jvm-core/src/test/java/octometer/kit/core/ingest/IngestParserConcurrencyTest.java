package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A thread-safety test. Each parse builds its own {@link IngestParser}
 * instance, thus many threads must give the same result at the same
 * time, with no shared mutable state.
 */
class IngestParserConcurrencyTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void staysCorrectUnderConcurrentUse() throws Exception {
        String validBody = ExampleFiles.read("ingest-valid-C13.json");
        int threadCount = 8;
        int iterationsPerThread = 200;
        AtomicInteger wrongResults = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        ParsedIngestRequest request = IngestParser.parse(validBody);
                        if (request.clicks().size() != 2) {
                            wrongResults.incrementAndGet();
                        }
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

        assertEquals(0, wrongResults.get());
    }
}
