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

**The /56 risk (design decision D43, issue #116).** Ten distinct keys
already fill the global cap of 20000 events. One home IPv6 `/56`
prefix holds 256 `/64` prefixes, so one connection can reach the
global cap in about two minutes. The monitor then shows no new
anonymous number for the rest of the day. The per-minute limits of
`AnonymousMinuteLimiter` slow this attack. They do not stop a `/56`
owner with many `/64` keys. The mitigation sits on the operations
side. Set an alert on the WARN line that names
`OCTOMETER_MAX_ANON_EVENTS_PER_DAY`. The alert lets an operator learn
of the attack while it runs.

`AnonymousKey` derives the key of design decision D43: one IPv4
address stays as it is, and one IPv6 address becomes its first 64
bits. An embedded IPv4 tail expands to two hex groups before that
cut. An IPv4-mapped address (`::ffff:a.b.c.d`, also with one extra
zero group such as `::ffff:0:a.b.c.d`) gives the plain IPv4 text, so
it shares one key with the plain IPv4 form. Issue #116 reuses this
class for its per-minute counters. This key form is separate from the
client address key of the rate limiter of design decision D20.

## The anonymous per-minute limits

`OCTOMETER_ANON_REQ_PER_MIN` (default 300), `OCTOMETER_ANON_EVENTS_PER_MIN`
(default 900), and `OCTOMETER_ANON_SESSIONS_PER_MIN` (default 120)
limit one anonymous key in one 60-second window. Design decision D43
and issue #116 set this rule. Each value must be a positive whole
number, the form of the two daily caps above.

`AnonymousMinuteLimiter` holds the state: one `LinkedHashMap` of a
maximum of 20000 keys, with an LRU eviction, under one lock. The
three counters of one key share one window, from the first counted
request or entry of that key. Each counter gates a check only when
that check itself adds to it, so the three counters stay independent
of each other. See `kit/jvm-ktor/README.md` for the order of this
check inside the ingest route.

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
