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
(issue #33). A signed-in user gets 30 requests each minute. Without
`OCTOMETER_RECORD_ANONYMOUS=true`, a request with no user id gets 120
requests each minute, by the client address. Each of the two limits
uses its own map, with its own LRU eviction, so an anonymous flood
never blocks a signed-in user.

With `OCTOMETER_RECORD_ANONYMOUS=true`, the three per-minute limits of
"The anonymous per-minute limits" below replace the 120-request limit,
for a request with no user id.

## The client address header and the trusted proxy count

`OCTOMETER_CLIENT_IP_HEADER` names a header that holds the client
address, for example `X-Forwarded-For`. `OCTOMETER_TRUSTED_PROXY_COUNT`
(default 1) counts the element to trust from the right of that header
line (design decision D20, issue #116). **Set the header only behind a
proxy that appends the real address.**

For example, take a chain of two trusted proxies: the app's own edge
proxy, behind a CDN. Each proxy of the chain appends its own observed
peer address as the last element of the header. A client at
`203.0.113.9` gives the CDN a header with one element,
`203.0.113.9`. The CDN appends its own address, `198.51.100.1`, before
it forwards the request to the edge proxy: `203.0.113.9, 198.51.100.1`.
The edge proxy appends its own address too, `198.51.100.9`, before the
app reads the header: `203.0.113.9, 198.51.100.1, 198.51.100.9`. The
address that the edge proxy itself observed, `198.51.100.1`, is the
second element from the right; `OCTOMETER_TRUSTED_PROXY_COUNT=2` reads
it.

The route reads the element at that position, at most 64 characters.
It reads that element only when it has the text form of an IPv4
address or an IPv6 address. Four things fall back to the remote
address of the connection instead:

- a missing header;
- a value above 64 characters;
- a value with no address form;
- a proxy count above the length of the header list.

Leave `OCTOMETER_CLIENT_IP_HEADER` unset when no such proxy sits in
front of the app. A wrong header name lets a client choose its own
rate-limit key, and the limit then protects nobody.

`OCTOMETER_TRUSTED_PROXY_COUNT` must be a positive whole number. A text
value, a zero, and a negative value each stop the app start with a
clear error. The error never repeats the raw value.

**A known gap.** Without `OCTOMETER_RECORD_ANONYMOUS=true`, the rate
limiter of design decision D20 still uses the full text of the client
address as its key. One host with a routed `/64` prefix can thus use a
new key for every request. The 120-request limit then has no effect
against that host. Issue #116 closes this gap only for
`OCTOMETER_RECORD_ANONYMOUS=true`. The three per-minute limits of
design decision D43 then apply instead, keyed by the first 64 bits of
an IPv6 address (see `kit/jvm-core/README.md`). Issue #118 owns the
remaining fix of the D20 rate limiter itself.

## OCTOMETER_PATH_PATTERNS

A run of whitespace separates each route pattern of `OCTOMETER_PATH_PATTERNS` (a space, a tab, or a line break).

## The anonymous per-minute limits

`octometerIngestRoute` applies three per-minute limits of design
decision D43 (issue #116). They replace the 120-request limit of
design decision D20. They apply only for a request with no user id,
when the app records an anonymous click
(`OCTOMETER_RECORD_ANONYMOUS=true`).

`OCTOMETER_ANON_REQ_PER_MIN` (default 300) limits the request count.
`OCTOMETER_ANON_EVENTS_PER_MIN` (default 900) limits the click entry
count. `OCTOMETER_ANON_SESSIONS_PER_MIN` (default 120) limits the
`octo:session-start` entry count. Each limit uses the key of design
decision D43: one IPv4 address, or the first 64 bits of an IPv6
address. `kit/jvm-core/README.md` holds the full state of
`AnonymousMinuteLimiter`. Each of the three counters is independent:
an exhausted click-entry counter never blocks a session-start check
of the same key.

Above one counter, the route answers 429 (contract rule C19), not
204. The request counter runs beside the rate limit of design
decision D20, before the bot filter and the body read, because it
needs no parsed entry. The click-entry counter and the session-start
counter run only after the parse of the body. They need the parsed
batch to tell the two kinds of entry apart. See "The order of the
checks" below.

## The daily anonymous caps and the bot filter

`octometerIngestRoute` applies two more limits of design decision D43
(issue #117), with the per-minute limits above.

`OCTOMETER_MAX_ANON_EVENTS_PER_DAY` (default 20000) and
`OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY` (default 2000) cap the
anonymous events of one day, one global counter and one counter for
each key (`kit/jvm-core` README.md holds the full state). Only a
request with no user id, when the app records an anonymous click,
pays this check (`OCTOMETER_RECORD_ANONYMOUS=true`). A batch above
either cap drops in full and answers 204.

The route also drops a batch when the first 512 characters of the
`User-Agent` header value match a fixed pattern (design decision
D43). The match ignores the letter case. The pattern is
`bot|crawl|spider|slurp|headless|preview|monitor|Go-http-client|python-requests|curl`.
This check applies to each request, signed in or not. It answers 204,
and it never stores the header value. An absent header passes. A drop
writes a maximum of one DEBUG log line for each elapsed hour, with the
drop count of that hour.

## The order of the checks

`octometerIngestRoute` runs each check of one request in this order,
and it stops at the first one that answers (design decision D43, issue
#117; issue #116). See the KDoc of `octometerIngestRoute` for the full
detail.

1. the `Content-Type` header (415);
2. the rate limit of design decision D20 (429) — a client already at
   its limit never reaches step 3 or any step below;
3. the anonymous per-minute request counter of design decision D43
   (429); it needs no parsed entry, so it sits beside step 2;
4. the bot filter (204), on the first 512 characters of the
   `User-Agent` value;
5. the body size (400), the declared `Content-Length` header only,
   with no body read;
6. the real body read (400, for a body above the limit that step 5
   could not catch from its declared length alone) and the parse of
   the body (400);
7. the anonymous per-minute click-entry counter and session-start
   counter of design decision D43 (429). They need the parsed batch;
8. the design decision D19 drop: a request with no user id stores
   nothing, when the app records no anonymous click;
9. the daily anonymous caps (204), for a request with no user id,
   when the app records an anonymous click;
10. the store, with the event cap of design decision D21 inside it.

The route corrected this order twice on 2026-09-22. The rate limiter
now runs before the bot filter and any real body read, the original
rule of issue #33.

A success answers 204 with an empty body. Each of the three drops
above (the bot filter, a daily cap, and the event cap) also answers
204 with an empty body. Each per-minute limiter rejection above
answers 429 with an empty body. A client thus learns nothing about
the reason beyond that one status code (contract rule C19).

**The daily cap key and the minute-limiter key.** The daily anonymous
cap, and the anonymous per-minute limiter, each read the same
normalised client address as the rate limiter above. Each one uses
the same `clientIpHeaderName` and `trustedProxyCount` rule. A header
value above 64 characters, or a value with no IPv4 or IPv6 address
form, falls back to the remote address. Neither map ever holds a raw
header value as a key.

## The store dispatcher

`octometerIngestRoute` runs the store call inside a dispatcher (design
decision D23). The default, `defaultStoreDispatcher()`, is a view of
`Dispatchers.IO` limited to 8 tasks at the same time
(`Dispatchers.IO.limitedParallelism(8)`), so the route never fills the
whole shared `Dispatchers.IO` pool of the app. The app can give
`storeDispatcher` a different value.
