package com.octometer.smoke;

import com.mongodb.client.MongoDatabase;
import octometer.kit.mongo.store.MongoEventLogStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * This class links the app against {@code octometer-kit-mongo} and the
 * MongoDB driver of the Spring Boot BOM (issue #68, decision 1).
 *
 * <p>The bean stays lazy. Its constructor sends a {@code createIndex}
 * command to the server. The smoke test starts the app with no MongoDB
 * server, so no code here may create the store during context start.
 */
@Configuration
public class KitConfiguration {

    @Bean
    @Lazy
    public MongoEventLogStore mongoEventLogStore(MongoDatabase database) {
        return new MongoEventLogStore(database);
    }
}
