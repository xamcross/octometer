# octometer-tracker

The click tracker of Octometer. It records a click on a `data-octo` element
and sends it to the ingest route of the app.

## The consent gate

The tracker stays off until the app calls `start()`. Call `start()` only
after the consent signal of the app. Call `stop()` to end tracking. `stop()`
empties the queue, stops the pending timer, and removes the click listener.
It also removes the session id from `sessionStorage`, but only when
`start()` ran at least one time before.

## Use

```ts
import { createTracker } from 'octometer-tracker';

const tracker = createTracker({
  endpoint: '/api/octometer/v1/clicks',
});

// After the consent signal:
tracker.start();

// After a consent withdrawal, or on sign-out:
tracker.stop();
```

Mark an element with `data-octo="<name>"`. The name is 1 to 100 characters,
with the pattern `[A-Za-z0-9_.:-]+` (contract rule C4).

The prefix `octo:` is reserved for the contract, in each letter case (for
example `OCTO:foo`). A click on such a value records no click, and the
tracker writes one console warning for the first dropped value (contract
rule C38).

## Options

| Option | Default | Purpose |
| --- | --- | --- |
| `endpoint` | none, mandatory | The ingest URL of the app. `createTracker()` throws a `TypeError` for an empty or a missing value. |
| `credentials` | `same-origin` | The `fetch` credentials mode. |
| `headers` | none | A function that returns extra request headers. |
| `flushIntervalMs` | `5000` | The delay before the tracker sends a filled queue. |
| `routes` | none | The ordered route pattern list of the app. With this option, each click entry holds `path`. |

## The page lifecycle flush

The tracker sends the queue at once in two cases, so a click reaches the
app before the browser hides or unloads the page:

- The event `pagehide`.
- The event `visibilitychange`, only when `document.visibilityState`
  becomes `hidden`.

Each of these two requests uses `fetch` with `keepalive: true`. A browser
limits the **sum** of the bodies of each `keepalive` request in flight
for one page, not each single body. A full queue of 200 entries, at the
maximum sizes of the contract, gives about 58 KB in total. This stays
below the 64 KB quota, with a small margin.

The 64 KB quota belongs to the page, not to the tracker. An app that
sends its own `keepalive` request, or a `sendBeacon` call, at the page
end shares the same budget. Remember this before you add another
`keepalive` request at that point.

Only one flush sends a batch a second time. See "The retry rule" below.

## The retry rule

The tracker sends a batch again one time after a network error, a 5xx
response, or a 429 response. It drops the batch after each other 4xx
response, and it never sends a batch a third time.

The tracker waits before it sends a batch again:

- After a network error or a 5xx response: 500 ms, plus a random value
  from 0 up to 500 ms.
- After a 429 response: 2000 ms, plus a random value from 0 up to
  2000 ms. A 429 response states a rate limit of a fixed 60-second
  window. An immediate retry would fall in the same window.

A batch from the `pagehide` listener, or from the `visibilitychange`
listener, goes out one time only, with no retry. A browser can freeze
the page after the hidden state and drop the connection. A retry there
gives the largest risk of a double count (see below). Only the timer
flush, which runs while the page stays visible, sends a batch a second
time.

**What a retry can cause.** The MongoDB store of the app writes a batch
with one ordered `insertMany` call, without a transaction. When a
document in the middle of a batch fails, the first part of the batch
stays in the collection, and the app answers 500. A retry of the same
batch then writes the first part again, with new `_id` values. The
monitor cannot drop those copies: contract rule C24 drops a copy only
for the same `_id`. Such a failure is rare, but a retry after a 5xx
response can count some clicks two times. Issue #36 adds no client-side
id to the event. A fix for this needs a contract change.

A failed request throws no error into the page, and it writes no console
line. No console line holds a path, a pattern, a `data-octo` value, or a
session id.

## The path of a click

Give the option `routes` to add `path` to each click entry. The value is
an ordered list of route patterns, for example `['/', '/articles',
'/articles/*', '/history/:id']` (contract rule C42). Without this
option, no click entry holds `path`.

The tracker reads `location.pathname` at the time of the click. It
removes a trailing slash, but not for the root path. It finds the first
pattern with the same count of segments that matches:

- A literal segment matches without the ASCII letter case. The stored
  text is the text of the pattern, never the text of the input.
- A `:name` segment matches one segment, and the stored text is the
  literal `:name`.
- A `*` segment matches one segment, and it keeps the real segment only
  when the segment holds a plain token: `^[A-Za-z0-9](?:[A-Za-z0-9._~-]|
  %[0-9A-Fa-f]{2}){0,79}$`.

> **Warning for an app team.** A path can hold an identifier, a token, or a
> search term. Mark each such segment with `:name` in the route list. Never
> use `*` for a segment that holds a token, an email address, or a user id.
>
> A `*` segment keeps a percent escape. A browser writes an `@` sign as
> `%40`, a space as `%20`, and a `<` as `%3C`. The segment
> `user%40example.com` passes the test, thus it leaves the browser. Use
> `:name` for each such segment.
>
> A `*` segment also keeps `%00`, `%2F`, and a double escape such as
> `%252F`. A reader of the value decodes nothing.

The tracker sends `/other` in five cases:

- No pattern matches.
- A `*` segment is bad.
- A segment is empty.
- A segment is `.` or `..`.
- The result passes 150 bytes in UTF-8.

It never decodes a `%` escape.

The tracker checks each route pattern one time, at the creation of the
tracker. A pattern is a string. It starts with `/`. Each segment holds
only the character set of rule C39, or a well-formed escape
`%[0-9A-Fa-f]{2}`.

One bad entry stops the whole list, because a dropped entry would move a
later pattern into its place and change the match order. The tracker
then sends no `path` field for the whole list. It writes exactly one
console warning for the list, and the warning names the index of each
bad entry. A `routes` value that is not an array gives the same result:
no `path` field, with one console warning. An empty list also sends no
`path` field, with one console warning. No warning holds the text of a
pattern, and no throw of a bad `routes` value reaches the page.

The matcher itself, `matchPath` in `src/path-match.ts`, is an internal
module. The package `exports` list holds no entry for it, thus an app
cannot import it on its own. Use the `routes` option of `createTracker`
instead.

## What leaves the browser

Each request body holds only `sessionId` and `clicks`. Each click holds
only `element`, `ageMs`, and, with the `routes` option, `path`. The
tracker never sends a user id: the app takes the user id from its own
authentication context (owner decision O5).

The session id is a UUID. The tracker keeps it under the `sessionStorage` key
`octo_session_id`, for the life of one browser tab.

## The queue and the request size

The queue holds a maximum of 200 entries. It drops the oldest entry when
a click enters a full queue.

The tracker measures each request body as its encoded UTF-8 byte count,
not as a string length. One request holds a maximum of 50 clicks
(contract rule C17). The tracker also splits a batch whose encoded body
passes 15 000 bytes into two or more requests. Each request body then
stays under that limit, with a safety margin under the 16 KB limit of
contract rule C18.

The `element` limit of rule C4 and the `path` limit of rule C39 stop one
click from passing the 15 000-byte limit on its own. A click outside
these limits cannot reach the tracker through a normal page. As a guard,
the tracker still drops such a click, and it writes one console warning
for the call, with no click text in it.

## Out of scope

This package holds the tracker core, the page lifecycle flush, and the
retry rule. Issue #107 owns the session start call (`start()` sending the
entry `octo:session-start`) and the consent-gated wait rules of design
decision D41. Issue #108 owns the `referrerHost` field. The present
tracker reads no `document.referrer` value, thus it sends no
`referrerHost` field on any entry (design decision D42, contract rule
C40).
