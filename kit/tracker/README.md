# octometer-tracker

The click tracker of Octometer. It records a click on a `data-octo` element
and sends it to the ingest route of the app.

## The consent gate

The tracker stays off until the app calls `start()`. Call `start()` only
after the consent signal of the app. Call `stop()` to end tracking. `stop()`
empties the queue, stops the pending timer, removes the click listener, and
removes the session id from `sessionStorage`.

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
| `endpoint` | none, mandatory | The ingest URL of the app. |
| `credentials` | `same-origin` | The `fetch` credentials mode. |
| `headers` | none | A function that returns extra request headers. |
| `flushIntervalMs` | `5000` | The delay before the tracker sends a filled queue. |

## What leaves the browser

Each request body holds only `sessionId` and `clicks`. Each click holds only
`element` and `ageMs`. The tracker never sends a user id: the app takes the
user id from its own authentication context (owner decision O5).

The session id is a UUID. The tracker keeps it under the `sessionStorage` key
`octo_session_id`, for the life of one browser tab.

## Out of scope

This package holds the tracker core only. Issue #36 owns the page lifecycle
flush (`pagehide`, `visibilitychange`), the `keepalive` request option, and
the retry rule after a failed request.
