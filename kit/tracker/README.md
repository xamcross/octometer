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

## Options

| Option | Default | Purpose |
| --- | --- | --- |
| `endpoint` | none, mandatory | The ingest URL of the app. `createTracker()` throws a `TypeError` for an empty or a missing value. |
| `credentials` | `same-origin` | The `fetch` credentials mode. |
| `headers` | none | A function that returns extra request headers. |
| `flushIntervalMs` | `5000` | The delay before the tracker sends a filled queue. |
| `routes` | none | The ordered route pattern list of the app. With this option, each click entry holds `path`. |

## The path of a click

Give the option `routes` to add `path` to each click entry: an ordered
list of route patterns, for example `['/', '/articles', '/articles/*',
'/history/:id']` (contract rule C42). Without this option, no click entry
holds `path`.

The tracker reads `location.pathname` at the time of the click, removes a
trailing slash (not for the root path), and finds the first pattern with
the same count of segments that matches:

- A literal segment matches without the ASCII letter case. The stored
  text is the text of the pattern, never the text of the input.
- A `:name` segment matches one segment, and the stored text is the
  literal `:name`.
- A `*` segment matches one segment, and it keeps the real segment only
  when the segment holds a plain token: `^[A-Za-z0-9](?:[A-Za-z0-9._~-]|
  %[0-9A-Fa-f]{2}){0,79}$`. Never use `*` for a segment that can hold an
  identifier, a token, or a search term; use `:name` for that segment
  instead.

The tracker sends `/other` for a path with no matching pattern, for a bad
`*` segment, for an empty segment, a `.` segment, or a `..` segment, and
for a result above 150 bytes in UTF-8. It never decodes a `%` escape.

## What leaves the browser

Each request body holds only `sessionId` and `clicks`. Each click holds
only `element`, `ageMs`, and, with the `routes` option, `path`. The
tracker never sends a user id: the app takes the user id from its own
authentication context (owner decision O5).

The session id is a UUID. The tracker keeps it under the `sessionStorage` key
`octo_session_id`, for the life of one browser tab.

## Out of scope

This package holds the tracker core only. Issue #36 owns the page lifecycle
flush (`pagehide`, `visibilitychange`), the `keepalive` request option, and
the retry rule after a failed request.
