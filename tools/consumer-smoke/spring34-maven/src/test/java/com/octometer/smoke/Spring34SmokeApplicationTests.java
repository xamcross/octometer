package com.octometer.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import octometer.kit.core.ingest.IngestEvent;
import octometer.kit.core.ingest.IngestPipeline;
import octometer.kit.core.ingest.IngestSettings;
import octometer.kit.mongo.store.MongoEventLogStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * This test starts the Spring context with no MongoDB server (issue
 * #68, decision 1). The bean of {@link KitConfiguration} stays lazy, so
 * its constructor never runs while the context starts.
 */
@SpringBootTest
class Spring34SmokeApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    /**
     * This test asks for the {@link MongoEventLogStore} bean, then it
     * calls {@link IngestPipeline#ingest} with a test store (release
     * review of pull request #200, MAJOR 2). The call to {@code
     * context.getBean} builds the kit bean and proves that Spring
     * resolves each parameter of {@link KitConfiguration}. It needs no
     * MongoDB server: the driver of the bean gets a fast refusal from
     * the test config, and {@link MongoEventLogStore} logs a warning
     * and keeps no exception.
     */
    @Test
    void theKitBeanResolvesAndTheIngestPipelineKeepsOneEvent() {
        MongoEventLogStore mongoEventLogStore = context.getBean(MongoEventLogStore.class);
        assertNotNull(mongoEventLogStore);

        RecordingEventLogStore testStore = new RecordingEventLogStore();
        String rawBody = "{\"sessionId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"clicks\":[{\"element\":\"button\",\"ageMs\":0}]}";
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

        IngestPipeline.ingest(rawBody, fixedClock, () -> null, testStore, new IngestSettings(true));

        assertEquals(1, testStore.keptEvents().size());
        IngestEvent keptEvent = testStore.keptEvents().get(0);
        assertEquals("button", keptEvent.element());
    }
}
