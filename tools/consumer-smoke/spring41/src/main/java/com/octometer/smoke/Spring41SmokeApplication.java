package com.octometer.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The consumer smoke app for Spring Boot 4.1 with Gradle (issue #68).
 * The app proves that the Octometer kit resolves and starts, on the
 * newest Spring Boot line.
 */
@SpringBootApplication
public class Spring41SmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(Spring41SmokeApplication.class, args);
    }
}
