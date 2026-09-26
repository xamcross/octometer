package com.octometer.smoke;

import octometer.kit.spring.IngestController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * The Spring Security chain of this app, with a cookie CSRF repository
 * (issue #69, the maintainer's decision 3). It exempts the ingest path
 * from CSRF, the exemption of {@code kit/jvm-spring/README.md}. It
 * exempts no other path: {@code OtherController} proves the contrast.
 *
 * <p>{@code authorizeHttpRequests} permits every request. This app checks
 * only the CSRF rule, never an authentication rule of its own.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers(IngestController.DEFAULT_INGEST_PATH));
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
