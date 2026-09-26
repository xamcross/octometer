package com.octometer.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This test starts the Spring context with no MongoDB server (issue
 * #68, decision 1). The bean of {@link KitConfiguration} stays lazy, so
 * its constructor never runs and the context needs no connection.
 */
@SpringBootTest
class Spring41SmokeApplicationTests {

    @Test
    void contextLoads() {
    }
}
