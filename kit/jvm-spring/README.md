# kit/jvm-spring

The Spring MVC adapter of the kit (design decision D17, issue #69).
`IngestController` mounts the ingest route of design section 4.2 in a
Spring MVC app. `spring-web` and the Jakarta Servlet API are
`compileOnly` dependencies: the app gives Spring MVC, and the kit adds
no Spring dependency of its own.

## The order of the checks

`IngestController` calls `IngestRequestProcessor` of `kit/jvm-core` for
the whole order of the checks (the content type, the rate limiter, the
anonymous per-minute counters, the bot filter, the declared body size,
the parse, the design decision D19 drop, the daily anonymous caps, and
the store call). Read the Javadoc of `IngestRequestProcessor` for the
full order. `kit/jvm-ktor` calls the very same class, so the Ktor route
and this controller run one order, not two.

## The two compile versions

`org.springframework:spring-web` (6.2.1) and
`jakarta.servlet:jakarta.servlet-api` (6.0.0) are the versions of the
Spring Boot 3.4.1 dependency list. Confirmed on 2026-09-26 from the
`v3.4.1` tag of `spring-projects/spring-boot` on GitHub:

- `gradle.properties` of that tag holds `springFrameworkVersion=6.2.1`.
- `spring-boot-project/spring-boot-dependencies/build.gradle` of that
  tag holds the Jakarta Servlet entry `6.0.0`.

`gradle/libs.versions.toml` holds each version, under the keys
`spring-framework` and `jakarta-servlet-api`.

## How an app mounts the controller

The app supplies an `EventLogStore` bean and a `SpringUserIdResolver`
bean, then builds one `IngestController` bean with them:

```java
@Configuration
public class OctometerConfiguration {

    @Bean
    public IngestController ingestController(EventLogStore store) {
        return new IngestController(store, request -> resolveUserId(request));
    }
}
```

Spring finds the `@PostMapping` method of any bean of this type, so a
plain `@Bean` method registers the route with no component scan of the
kit's own package.

The path of the route reads the Spring property
`octometer.ingest-path`, with `/api/octometer/v1/clicks` (contract rule
C12) as its default. Set `octometer.ingest-path` in
`application.properties`, or an environment variable of the same name
in upper case with underscores (`OCTOMETER_INGEST_PATH`), to change it.

The controller writes no CORS header, and it adds no authentication
check of its own. The app decides whether its security chain lets an
anonymous request reach the ingest path.

## The CSRF exemption

A Spring Security app with a CSRF filter must exempt the ingest path
from that filter. The client of this route is a tracker script, not a
form; it holds no CSRF token of the app's own session. Add this
exemption to the app's `SecurityFilterChain` bean:

```java
http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/octometer/v1/clicks"));
```

Change the path of this line to match a custom
`octometer.ingest-path` value. Without this exemption, each ingest
request without a valid CSRF token gets 403, never the answer of
`IngestRequestProcessor`.

## The JitPack coordinates

Issue #71 publishes this module to JitPack, at:

```
com.github.xamcross.octometer:octometer-kit-spring:0.1.0
```

Add the JitPack repository (`https://jitpack.io`) and this dependency
line to the app's build. `jitpack.yml` names this module's
`publishToMavenLocal` task, the same list as `kit/jvm-core`,
`kit/jvm-ktor`, and `kit/jvm-mongo`.
