# kit/jvm-ktor

The Ktor adapter of the kit (design decision D17). It mounts the ingest
route of design section 4.2 with one function, `octometerIngestRoute`.

## Minimum versions

- **Java 21.** `kit/jvm-ktor/build.gradle.kts` sets `jvmToolchain(21)`. The
  compiled class files hold major version 65, the Java 21 class file
  format (design decision D17). An app needs a JDK 21 runtime or newer.
- **Kotlin 2.0.** This module compiles with `apiVersion` and
  `languageVersion` pinned to Kotlin 2.0
  (`kit/jvm-ktor/build.gradle.kts`). Kotlin 2.0 is the lowest version that
  the module's own compiler (2.4.20) accepts without an error, confirmed
  on 2026-09-21: version 1.6 gives the compiler error "Language version
  1.6 is no longer supported", and version 2.0 compiles with a
  deprecation warning only ("Update the version to 2.2"). An app on
  Kotlin 2.0, 2.1, or 2.2 can read the class files of this module.
  `kit/jvm-ktor/build.gradle.kts` also sets `coreLibrariesVersion =
  "2.0.0"`, so the published POM names `kotlin-stdlib:2.0.0`, and not the
  compiler's own 2.4.20. Without this setting, an app on Kotlin 2.0, 2.1,
  or 2.2 would fail to compile with a metadata version error (PR #155,
  the JitPack review of issue #37).
- **Ktor 3.2.0.** Ktor is a `compileOnly` dependency (design decision
  D17), thus the app brings its own Ktor 3 version. This module reads the
  request body with `ByteReadChannel.readRemaining(Long)`, because
  `ByteReadChannel.readBuffer(Long)` (the Ktor 3.6.0 replacement) does not
  exist in Ktor 3.2.0. Confirmed on 2026-09-21 with `javap` against
  `ktor-io-jvm-3.2.0.jar` and `ktor-io-jvm-3.6.0.jar`:
  `readRemaining(Long)` exists in each jar; `readBuffer(Long)` exists only
  in the 3.6.0 jar. The module was also compiled and tested against Ktor
  3.2.0 on 2026-09-22, as a temporary local check (not a committed
  dependency change); `./gradlew :kit:jvm-ktor:test` gave `BUILD
  SUCCESSFUL`.

## The ingest rate limit

`octometerIngestRoute` limits the request rate of design decision D20
(issue #33). A signed-in user gets 30 requests each minute. A request
with no user id gets 120 requests each minute, by the client address.
Each of the two limits uses its own map, with its own LRU eviction, so an
anonymous flood never blocks a signed-in user.

`OCTOMETER_CLIENT_IP_HEADER` names a header that holds the client
address, for example `X-Forwarded-For`. **Set this option only behind a
proxy that appends the real client address as the last element of the
last header line.** The route reads that last element, at most 64
characters, and only when it has the text form of an IPv4 address or an
IPv6 address. A missing header, a value above 64 characters, and a value
with no address form, each fall back to the remote address of the
connection.

Leave the option unset when no such proxy sits in front of the app. A
wrong header name lets a client choose its own rate-limit key, and the
limit then protects nobody.

**A known gap.** The client address key uses the full text of an IPv6
address. One host with a routed `/64` prefix can thus use a new key for
every request, and the limit has no effect against that host. Issue
#116 and issue #118 own the fix (the first 64 bits of an IPv6 address as
the key, design decision D43).

## OCTOMETER_PATH_PATTERNS

A run of whitespace separates each route pattern of `OCTOMETER_PATH_PATTERNS` (a space, a tab, or a line break).

## The daily anonymous caps and the bot filter

`octometerIngestRoute` applies two more limits of design decision D43
(issue #117), on top of the per-minute rate limit above.

`OCTOMETER_MAX_ANON_EVENTS_PER_DAY` (default 20000) and
`OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY` (default 2000) cap the
anonymous events of one day, one global counter and one counter for
each key (`kit/jvm-core` README.md holds the full state). Only a
request with no user id, with `OCTOMETER_RECORD_ANONYMOUS=true`, pays
this check. A batch above either cap drops in full and answers 204.

The route also drops a batch when the `User-Agent` header value
matches `bot|crawl|spider|slurp|headless|preview|monitor|
Go-http-client|python-requests|curl`, in any letter case (design
decision D43). This check applies to each request, signed in or not.
It answers 204 with one DEBUG log line, and it never stores the header
value. An absent header passes.

## The order of the checks

`octometerIngestRoute` runs each check of one request in this order,
and it stops at the first one that answers (design decision D43, issue
#117; corrected 2026-09-22, so the rate limiter again runs before any
real body read, the original rule of issue #33; see the KDoc of
`octometerIngestRoute` for the full detail):

1. the `Content-Type` header (415);
2. the body size (400), the declared `Content-Length` header only,
   with no body read;
3. the bot filter (204);
4. the rate limit of design decision D20 (429) — a client already at
   its limit pays for no real body read and no parse below;
5. the real body read (400, for a body above the limit that step 2
   could not catch from its declared length alone) and the parse of
   the body (400);
6. the design decision D19 drop (a request with no user id, with
   anonymous recording off, stores nothing);
7. the daily anonymous caps (204), for a request with no user id and
   with anonymous recording on;
8. the store, with the event cap of design decision D21 inside it.

A success and each of the three drops above (the bot filter, a daily
cap, and the event cap) all answer 204 with an empty body, so a client
learns nothing about the reason (contract rule C19).

**The daily cap key.** The daily anonymous cap reads the same
normalised client address as the rate limiter above (the same
`clientIpHeaderName` rule): a header value above 64 characters, or
with no IPv4 or IPv6 address form, falls back to the remote address.
The map of `AnonymousDailyCap` never holds a raw header value as a
key.

## The store dispatcher

`octometerIngestRoute` runs the store call inside a dispatcher (design
decision D23). The default, `defaultStoreDispatcher()`, is a view of
`Dispatchers.IO` limited to 8 tasks at the same time
(`Dispatchers.IO.limitedParallelism(8)`), so the route never fills the
whole shared `Dispatchers.IO` pool of the app. The app can give
`storeDispatcher` a different value.
