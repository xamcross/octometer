# The Spring integration guide

This guide connects a Spring Boot app to Octometer. It holds the differences from
`docs/integration-ktor.md` only. Read that guide first. Follow this guide alone, with no
read of the design document. This guide names no real app and no real organisation.
Where this guide says "your app", put the name of your own app.

**A note for an app behind a path-limited proxy.** Read the note near the top of
`docs/integration-ktor.md`, before section 1. It applies here too: put the ingest path of
section 4 below the proxied prefix, so the tracker of section 5 posts to the same origin
as the page.

## 1. The dependency

The Spring MVC adapter is the artifact `octometer-kit-spring`, group
`com.github.xamcross.octometer`. Release `0.1.0` holds no `kit/jvm-spring` module
(`CHANGELOG.md`; `jitpack.yml` names the next tag `0.2.0`). Release `0.2.0` is the first
release with the Spring module. Use tag `0.2.0` for each kit module of your app (design
decision D26): one tag builds each module together, so a mixed pair of tags never
happens.

### The Gradle form

Read section 1 of `docs/integration-ktor.md` for the JitPack rule (design decision D25)
and the `exclusiveContent` block. Add this dependency beside the ones of that section:

```kotlin
dependencies {
    implementation("com.github.xamcross.octometer:octometer-kit-spring:0.2.0")
    implementation("com.github.xamcross.octometer:octometer-kit-mongo:0.2.0")
}
```

### The Maven form

Maven has no `exclusiveContent` element. A Maven build asks each declared repository in
turn for a missing artifact, in the declared order. This guide proved the order with a
copy of `tools/consumer-smoke/spring34-maven/pom.xml`, one added `<repository>` entry for
JitPack after Central, and one run of `mvn -X dependency:resolve` with a fresh local
repository. The log held these lines for the kit artifact:

```
Downloading from central: .../octometer-kit-spring/f866f5b/octometer-kit-spring-f866f5b.pom
Downloading from jitpack.io: .../octometer-kit-spring/f866f5b/octometer-kit-spring-f866f5b.pom
Downloaded from jitpack.io: .../octometer-kit-spring/f866f5b/octometer-kit-spring-f866f5b.pom
```

Central gave no artifact. JitPack served the artifact. The log held no such line for
`spring-web`: Central served that artifact alone. This proves the repository order.

Add the plain repository entry as the minimum form. Give it releases only, with no
snapshot:

```xml
<repositories>
  <repository>
    <id>central</id>
    <url>https://repo.maven.apache.org/maven2</url>
  </repository>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
    <releases>
      <enabled>true</enabled>
    </releases>
    <snapshots>
      <enabled>false</enabled>
    </snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.xamcross.octometer</groupId>
    <artifactId>octometer-kit-spring</artifactId>
    <version>0.2.0</version>
  </dependency>
  <dependency>
    <groupId>com.github.xamcross.octometer</groupId>
    <artifactId>octometer-kit-mongo</artifactId>
    <version>0.2.0</version>
  </dependency>
</dependencies>
```

This plain entry gives no group filter. It still lets Maven ask JitPack for an artifact
of a different group, when a later dependency names one. A `<mirrorOf>` rule of
`settings.xml` matches a repository id. It never matches a group id, so a mirror alone
gives no group filter either.

The full form needs a repository manager, for example Nexus or Artifactory. Give the
manager a remote repository rule that serves the group `com.github.xamcross.octometer`
from JitPack alone. The rule rejects each other group. Point `settings.xml` at the
manager with a `<mirrorOf>*</mirrorOf>` rule. This rule sends each request of the build
to the manager, and the manager applies the group filter:

```xml
<mirrors>
  <mirror>
    <id>company-manager</id>
    <url>https://maven.your-company.example/repository/maven-public/</url>
    <mirrorOf>*</mirrorOf>
  </mirror>
</mirrors>
```

Set up the manager rule with the manager's own documentation. This guide states only the
Maven shape of the protection; the JitPack rule of `docs/integration-ktor.md` section 1
(design decision D25) states the reason for it.

**Upgrade the monitor before an app.** Read section 1 of `docs/integration-ktor.md` for
this rule (design decision D26).

## 2. The store

Read section 2 of `docs/integration-ktor.md` for the driver rule (design decision D22):
the same `mongodb-driver-sync` version as your app, and a client pool of
`maxPoolSize=5`.

Give the database to `MongoEventLogStore` one of two ways.

With Spring Data MongoDB, use the `MongoDatabaseFactory` bean that Spring Boot builds
from your connection string:

```java
@Bean
public MongoEventLogStore octometerStore(MongoDatabaseFactory factory) {
    return new MongoEventLogStore(factory.getMongoDatabase());
}
```

With your own `MongoClient`, build the database the same way as the Ktor guide:

```java
MongoClientSettings settings = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(mongoUri))
        .applyToConnectionPoolSettings(builder -> builder.maxSize(5))
        .applyToSocketSettings(builder -> builder.readTimeout(5, TimeUnit.SECONDS))
        .writeConcern(WriteConcern.ACKNOWLEDGED.withWTimeout(5, TimeUnit.SECONDS))
        .build();
MongoClient mongoClient = MongoClients.create(settings);
MongoEventLogStore store = new MongoEventLogStore(mongoClient.getDatabase(yourDatabaseName));
```

**Set a timeout.** The store sets no timeout of its own. Without a timeout, a blocked
primary node holds a thread of the ingest route. On driver 5.0, set a socket read
timeout, and also set a write concern `wtimeout`, as the example above shows. On driver
5.2 or newer, set `timeoutMS` instead.

**Set the database role.** The app database user needs a custom role on
`octometer_events` with four actions: `insert`, `createIndex`, `collMod`, and `find`. The
built-in role `readWrite` has no `collMod`, so the store can build no TTL index and can
run no `collMod` repair of that index. Without `find`, the event cap of design decision
D21 never stops the ingest, and the store writes one warning each hour, with the MongoDB
error code.

Read sections 3 and 4 of `docs/integration-ktor.md` for the read-only database user of
the monitor and the three Atlas alerts. That user is not the app database user of this
section.

Read section 6 of `docs/integration-ktor.md` for `deleteByUserId`, the 3 passes, and the
erasure order (contract rule C43, design decision D15).

## 3. The user id

`IngestController` takes a `SpringUserIdResolver` bean (`kit/jvm-spring/README.md`,
"How an app mounts the controller"). It is a functional interface,
`String resolve(HttpServletRequest request)`. Write the body yourself, over the security
context of your app:

```java
@Bean
public SpringUserIdResolver octometerUserIdResolver() {
    return request -> {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        return principal.getInternalUserId();
    };
}
```

`AppUserDetails` is your own `UserDetails` implementation, with one field for the
internal user id of the app. Read that field, never `getName()`. `getName()` gives a
username, and contract rule C6 forbids a username. A JWT resource server may read the
`sub` claim in its place, only when that claim already holds an opaque internal id, and
never an email address.

Return `null` for an anonymous request or an unauthenticated request. Spring Security
gives an anonymous request an `AnonymousAuthenticationToken`, not a `null` value, so the
guard above checks for that class too. `resolve` runs on the thread of the request,
before the store call (the Javadoc of `SpringUserIdResolver`), so the thread-local
security context is valid inside it.

The returned value must follow contract rule C6: 1 to 254 characters, never a username,
an email address, or an IP address. A bad value gives status 500, not 400. The resolver
never logs the id.

## 4. The route

The ingest path is `/api/octometer/v1/clicks` by default (contract rule C12), the same
path as the Ktor guide. Put this path below the proxied prefix of the note at the top of
this guide, and of `docs/integration-ktor.md`.

Mount `IngestController` as a Spring bean, with your store and your resolver
(`kit/jvm-spring/README.md`):

```java
@Configuration
public class OctometerConfiguration {

    @Bean
    public IngestController ingestController(MongoEventLogStore store,
            SpringUserIdResolver userIdResolver) {
        return new IngestController(store, userIdResolver);
    }
}
```

**Permit the path, and exempt it from CSRF.** The ingest path needs no authorization
check of its own; the kit decides with `OCTOMETER_RECORD_ANONYMOUS` whether it stores an
anonymous request. Add both rules to the app's `SecurityFilterChain` bean:

```java
http.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(IngestController.DEFAULT_INGEST_PATH).permitAll()
        .anyRequest().authenticated());
http.csrf(csrf -> csrf.ignoringRequestMatchers(IngestController.DEFAULT_INGEST_PATH));
```

`ignoringRequestMatchers` takes the path only; it needs no `HttpMethod` argument
(`kit/jvm-spring/README.md`; `tools/consumer-smoke/spring34-maven/.../SecurityConfiguration.java`).
Change the path of both lines to match a custom `octometer.ingest-path` value.

`permitAll` removes the authorization check only. The session filter, or the bearer
token filter, of your chain stays in place. Keep that filter in the chain: without it,
`SpringUserIdResolver` gives `null` for a signed-in user too, and the app stores no user
id for any click.

Give the ingest path this narrow rule. Do not add a wide `csrf().disable()` for the whole
app: that rule removes the CSRF check of every other path too, not the ingest path
alone.

A chain with `oauth2ResourceServer` is not necessary for this route. The resolver of
section 3 reads the authentication that your app already holds in the request; the
ingest route needs no bearer token of its own.

## 5. The tracker

Read section 7 of `docs/integration-ktor.md` for the install command, the `start()`
call, and the `data-octo` attribute rules.

The ingest path is exempt from the CSRF check of section 4. The tracker sends no
`X-XSRF-TOKEN` header for it. The tracker has two options for a cross-origin request,
`credentials` (default `same-origin`) and `headers` (a function that returns extra
request headers; `kit/tracker/README.md`). Set neither option:

```ts
import { createTracker } from 'octometer-tracker';

const tracker = createTracker({
  endpoint: '/api/octometer/v1/clicks',
});

// After the consent signal:
tracker.start();
```

The app sets no `headers` function, because the ingest path needs no CSRF token. The
app keeps the default `credentials: 'same-origin'`. Another origin does not work with
this tracker: the kit writes no CORS header (design decision D23), and the note at the
top of this guide sends the ingest path to the app through the same origin as the page.

**A change from an earlier step of this guide's issue.** An earlier step of issue #70
read the `XSRF-TOKEN` cookie into an `X-XSRF-TOKEN` header, and used
`credentials: 'include'` for another origin. Pull request #215 exempted the ingest path
from the CSRF check, so the tracker needs neither the header nor `credentials: 'include'`
now. This guide gives the current, corrected form above.

## 6. The check

Sign in to your app in a browser first. Copy the value of your session cookie from the
browser developer tools.

Send this request through the public origin of your app, the same origin the tracker
uses. Send your session cookie with the request:

```
curl -i -X POST "https://your-app.example/api/octometer/v1/clicks" \
  -A "Mozilla/5.0 (integration check)" \
  -H "Content-Type: application/json" \
  -H "Cookie: <your session cookie name>=<your session cookie value>" \
  --data-binary '{"sessionId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","clicks":[{"element":"checkout.save","ageMs":1200}]}'
```

The answer has status `204`, with an empty body (contract rule C19). Read the event back
through the monitor. Read the app row of the monitor API, or open the level 1 view of
the monitor UI. The new click raises the click count of your app within the poll
interval of the monitor mode.

## 7. Left for a later guide change

Issue #119 adds the pilot route list, `OCTOMETER_RECORD_ANONYMOUS=true`, and the
`start()` rule for an app with server-side rendering, to this same file.

<!--
Sources:
kit/jvm-spring/README.md
kit/jvm-mongo/README.md
kit/jvm-spring/src/main/java/octometer/kit/spring/IngestController.java
kit/jvm-spring/src/main/java/octometer/kit/spring/SpringUserIdResolver.java
kit/tracker/README.md
tools/consumer-smoke/spring34-maven/pom.xml
tools/consumer-smoke/spring34-maven/src/main/java/com/octometer/smoke/SecurityConfiguration.java
tools/consumer-smoke/spring34-maven/src/test/java/com/octometer/smoke/IngestControllerCsrfTest.java
tools/consumer-smoke/spring41/build.gradle.kts
docs/integration-ktor.md, the note at the top, and sections 1, 2, 3, 4, 6, 7, 8
CHANGELOG.md, the 0.1.0 entry
jitpack.yml
docs/superpowers/specs/2026-09-21-octometer-design.md, section 2.3, D22, D23, D24, D25,
  D43
The Maven repository order proof of section 1: one run of "mvn -X dependency:resolve"
  on 2026-09-27, with a copy of tools/consumer-smoke/spring34-maven/pom.xml, a
  "-Dmaven.repo.local" folder outside the repository, one added JitPack repository
  entry, and the coordinate octometer-kit-spring:f866f5b (a JitPack commit build). The
  temporary folder was deleted after the proof.
Section 5, the change from the earlier tracker step of issue #70: pull request #215 and
  the edit of issue #69 of 2026-09-26.
-->
