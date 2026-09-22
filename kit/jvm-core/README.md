# kit/jvm-core

The ingest core of the kit (design decision D18). It parses one raw
request body, checks each field rule of the contract, and gives the
parsed events to the store of the app. This module needs no dependency
beyond Java 21.

## The daily anonymous caps

`OCTOMETER_MAX_ANON_EVENTS_PER_DAY` (default 20000) and
`OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY` (default 2000) limit the
anonymous events of one day (design decision D43, issue #117). Each
value must be a positive whole number. A text value, a zero, and a
negative value each stop the app start with a clear error.

`AnonymousDailyCap` holds the state: one global counter, and one
`LinkedHashMap` of a maximum of 20000 keys, with an LRU eviction. Each
counter uses a fixed window of 24 hours from its own first counted
event, not a window aligned to the clock. A batch above either cap
drops in full, and it changes no counter.

**The LRU limitation.** A key above its cap can leave the map through
the eviction and start a fresh window. The global cap of 20000 events
each day bounds how much such an evicted key can add back in one day.

`AnonymousKey` derives the key of design decision D43: one IPv4
address stays as it is, and one IPv6 address becomes its first 64
bits. An embedded IPv4 tail expands to two hex groups before that
cut. An IPv4-mapped address (`::ffff:a.b.c.d`) gives the plain IPv4
text, so it shares one key with the plain IPv4 form. Issue #116
reuses this class for its per-minute counters. This key form is
separate from the client address key of the rate limiter of design
decision D20.

## The user-agent filter

`BotUserAgentFilter` marks a request as a robot when its `User-Agent`
value matches a fixed pattern (design decision D43, issue #117). The
match ignores the letter case. It matches at any position of the
value. The pattern is
`bot|crawl|spider|slurp|headless|preview|monitor|Go-http-client|python-requests|curl`.
An absent header passes the filter. The kit stores no header value: no
log line, no exception message, and no stored event holds a
`User-Agent` value.

## The ingest rate limit

See `kit/jvm-ktor/README.md` for the per-minute rate limit of design
decision D20, and for the full order of the checks of the ingest
route.
