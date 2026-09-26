package com.octometer.smoke;

import octometer.kit.mongo.store.MongoEventLogStore;
import octometer.kit.spring.IngestController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.MongoDatabaseFactory;

/**
 * This class links the app against {@code octometer-kit-mongo}, the
 * MongoDB driver of the Spring Boot BOM (issue #68, decision 1), and
 * {@code octometer-kit-spring} (issue #69, the maintainer's decision 3).
 *
 * <p>The parameter type is {@link MongoDatabaseFactory}. Spring Boot
 * auto-configures a bean of this type in both 3.4.1 and 4.1.1 (release
 * review of pull request #200, MAJOR 1): the class
 * {@code MongoDatabaseFactoryConfiguration}, in the package
 * {@code org.springframework.boot.autoconfigure.data.mongo} of Spring
 * Boot 3.4.1, and in the package
 * {@code org.springframework.boot.data.mongodb.autoconfigure} of Spring
 * Boot 4.1.1. Design decision D22 says the app gives the {@code
 * MongoDatabase}; {@link MongoDatabaseFactory#getMongoDatabase()} is the
 * form that each Spring Boot version gives here.
 *
 * <p>The bean stays lazy. Its constructor sends a {@code createIndex}
 * command to the server. While the test starts the context, no code
 * here may create the store, so the test needs no MongoDB server.
 *
 * <p>{@link #ingestController} is also lazy, so a test that never sends
 * a request keeps {@link #mongoEventLogStore} uninstantiated too (issue
 * #69). This app has no Spring Security starter, thus it adds no
 * exemption of its own; the CSRF exemption test runs in
 * `tools/consumer-smoke/spring34-maven` alone.
 */
@Configuration
public class KitConfiguration {

    @Bean
    @Lazy
    public MongoEventLogStore mongoEventLogStore(MongoDatabaseFactory factory) {
        return new MongoEventLogStore(factory.getMongoDatabase());
    }

    @Bean
    @Lazy
    public IngestController ingestController(MongoEventLogStore mongoEventLogStore) {
        return new IngestController(mongoEventLogStore, request -> null);
    }
}
