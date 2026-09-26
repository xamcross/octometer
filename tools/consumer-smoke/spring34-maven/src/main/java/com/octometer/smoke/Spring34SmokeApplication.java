package com.octometer.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The consumer smoke app for Spring Boot 3.4.1 with Maven (issue #68).
 * The app proves that the Octometer kit resolves and starts, on the
 * build of the pilot app.
 */
@SpringBootApplication
public class Spring34SmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(Spring34SmokeApplication.class, args);
    }
}
