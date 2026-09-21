# Event log contract v1

This document states the event log contract of Octometer. The contract has four parts: the
event document, the ingest request, the reader rule, and the database user. The source is
section 4 of `docs/superpowers/specs/2026-09-21-octometer-design.md`, plus section 1 and the
last paragraph of section 6 for the definitions below.

Each rule has an ID, `C1` to `C33`. The table at the end maps each rule ID to its example file.

## Purpose

Octometer is a monitor for the click metrics of the owner's web apps (design section 1). Each
app writes its clicks to a flat event log. The monitor reads this log and shows three table
views: an app view, a user view, and an element view.

## Definitions

- **A session.** A session is the life of one browser tab. The client creates one
  `sessionId`, a UUID, for each session (rule C5).
- **Sessions at level 1.** The level 1 column "unique sessions" counts the distinct
  `sessionId` values across the whole app.
- **Sessions at level 2.** The level 2 column "sessions" counts the distinct `sessionId`
  values for one user. A user can sign in during the life of one tab, so one session can
  belong to two users. For this reason, the level 2 session counts do not sum to the level 1
  unique-session count (design section 6, last paragraph).
- **Unique elements.** The level 2 column "unique elements" counts the distinct `element`
  values that one user has clicked.

## Extended JSON

A BSON value has no direct JSON form. Each example event document in `examples/` uses
[MongoDB Extended JSON](https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/):
an ObjectId is `{"$oid": "..."}`, and a Date is `{"$date": "..."}`.

## 1. The event document (design section 4.1)

Collection `octometer_events`, in the database of the app.

- **C1.** The event collection is `octometer_events`, in the database of the app.
- **C2.** The `_id` field is an ObjectId. The kit creates it inside the insert call.
- **C3.** The `ts` field is a Date. It holds the click time. The app server sets it.
- **C4.** The `element` field is a string of 1 to 100 characters. It matches the pattern
  `[A-Za-z0-9_.:-]+`.
- **C5.** The `sessionId` field is a string. It is a UUID.
- **C6.** The `userId` field is a string or `null`. A string has 1 to 254 characters. `null`
  means "not signed in".
- **C7.** The app only inserts an event. It never updates an event.
- **C8.** The collection has two indexes: one on `_id`, and a TTL index on `ts`. The default
  retention is 30 days. The environment variable `OCTOMETER_RETENTION_DAYS` sets a different
  value.
- **C9.** A reader ignores an unknown field in an event document. The addition of a field is a
  minor change to the contract.
- **C10.** A major change to the contract uses a new collection name, for example
  `octometer_events_v2`.
- **C11. The `_class` rule.** An app that writes its own event store must not add a `_class`
  field. Spring Data adds this field on its own, so a Spring Data app needs a step that
  removes it before the write, or a store that skips it.

## 2. The ingest request (design section 4.2)

`POST <ingestPath>`, default `/api/octometer/v1/clicks`, header `Content-Type:
application/json`.

- **C12.** The tracker sends `POST <ingestPath>`. The default path is
  `/api/octometer/v1/clicks`. The request header `Content-Type` is `application/json`.
- **C13.** The request body has a `sessionId` field and a `clicks` array. Each entry of
  `clicks` has an `element` field and an `ageMs` field.
- **C14.** The server calculates `ts` as `receivedAt` minus `ageMs`. The server clamps an
  `ageMs` value above 600000.
- **C15.** A negative `ageMs` value is invalid.
- **C16.** The server takes the `userId` value from the authentication context. The client
  never sends a `userId` field.
- **C17.** One batch holds a maximum of 50 clicks. A batch above this limit is invalid; the
  server returns 400.
- **C18.** One request body has a maximum size of 16 KB. A body above this limit is invalid;
  the server returns 400.
- **C19.** The server returns status 204 for a success and for a batch that the event cap
  drops. It returns 400 for an invalid body, 415 for a content type other than JSON, and 429
  for the rate limit.
- **C32.** The server ignores an unknown field in the ingest request body. A `userId` field
  in the body is such a field: the server ignores it, and the stored user id comes only from
  the authentication context (rule C16).
- **C33.** The ingest route checks the `element` value with the rule of C4, and the
  `sessionId` value with the rule of C5. A value that breaks one of these rules makes the
  body invalid; the server returns 400.

## 3. The reader rule (design section 4.3)

- **C20.** The reader computes a bound with `ObjectId.getSmallestWithDate(serverTime - lag)`.
  It reads events with the filter `{_id: {$gt: cursor, $lt: bound}}`, sorted by `_id`
  ascending, with a limit of 1000 and a batch size of 1000.
- **C21.** `serverTime` is the `localTime` value from the `hello` command of the primary node.
- **C22.** `lag` is 60 seconds in prod mode and 2 seconds in dev mode.
- **C23.** The read preference is `primary`, set in code. The monitor rejects a
  `readPreference` value in a connection URI.
- **C24.** The delivery of an event is "at least once". The primary key `(app_id, event_id)`
  in the monitor store drops a duplicate.
- **C25. The accepted loss.** The accepted loss is an insert that commits more than `lag`
  seconds after the creation of its `_id`. The monitor does not read this insert.
- **C26.** A `$gt` filter on a deleted `_id` is a plain index seek. A TTL delete does not
  break the reader's cursor.

## 4. The database user (design section 4.4)

The owner runs these commands. A password must not pass through an agent (rule C30).

```
atlas customDbRoles create octometerEventReader --privilege FIND@<database>.octometer_events --projectId <id>
atlas dbusers create --username octometer-reader --role octometerEventReader --projectId <id>
atlas dbusers describe octometer-reader --projectId <id> -o json
```

The Atlas CLI prompts for the password. The prompt keeps the password, shown here as
`<password>`, out of the shell history.

- **C27.** The owner creates the database role and the database user with the three commands
  above.
- **C28.** The Atlas CLI prompts for the password. The password stays out of the shell
  history.
- **C29.** The owner creates the password with `openssl rand -hex 24`. The owner uses one
  password for each Atlas project.
- **C30.** The owner runs these commands. A password must not pass through an agent.
- **C31.** The owner rotates the password after a laptop loss, after a suspected leak, and
  each 12 months. A rotation deletes the user, then creates the user again.

## Examples

Each example file holds only fake data: a fake user id, a fake UUID, no real name, no email
address, and no password. Each file in `examples/` parses as JSON, including a file that
shows an invalid ingest body — the file is valid JSON, but the value it holds breaks one rule.

| Rule ID | Rule | Example file |
|---|---|---|
| C1 | Collection name | `examples/event-valid-C1-C5.json` |
| C2 | `_id` field | `examples/event-valid-C1-C5.json` |
| C3 | `ts` field | `examples/event-valid-C1-C5.json` |
| C4 | `element` field | `examples/event-valid-C1-C5.json` (valid), `examples/ingest-invalid-C4-element-pattern.json` (invalid) |
| C5 | `sessionId` field | `examples/event-valid-C1-C5.json` (valid), `examples/ingest-invalid-C5-session-id.json` (invalid) |
| C6 | `userId` field, `null` case | `examples/event-userid-null-C6.json` |
| C7 | Insert-only | No example file. This rule states an app action, not a document shape. |
| C8 | Indexes | No example file. This rule states a database setting, not a document shape. |
| C9 | Unknown-field tolerance | No example file. This rule states reader behavior. |
| C10 | Major-change collection name | No example file. This rule names a future collection. |
| C11 | The `_class` rule | No example file. This rule states a write-side step, not a document shape. |
| C12 | Ingest endpoint and header | No example file. This rule states the URL and the header, not the body content. |
| C13 | Ingest body shape | `examples/ingest-valid-C13.json` |
| C14 | `ageMs` clamp | No example file. The design does not ask for an example of the clamp. |
| C15 | Negative `ageMs` | `examples/ingest-invalid-C15-negative-age.json` |
| C16 | `userId` from the authentication context | No example file. See rule C32. |
| C17 | Batch limit (50 clicks), invalid above the limit | `examples/ingest-invalid-C17-batch-limit.json` |
| C18 | Body size limit (16 KB), invalid above the limit | `examples/ingest-invalid-C18-body-size.json` — the file's padding field is an ignored unknown field (rule C32); the file breaks only the size rule. |
| C19 | Response codes | No example file. An HTTP response is not a JSON document in this contract. |
| C20–C24, C26 | The read window and the delivery model | No example file. These rules state a server-side read algorithm. |
| C25 | The accepted loss | No example file. This rule states a timing bound, not a document shape. |
| C27–C31 | The database user | No example file. These rules state shell commands. |
| C32 | Unknown-field tolerance in the ingest body, `userId` included | No dedicated example file. The padding field of `examples/ingest-invalid-C18-body-size.json` is such an ignored field. |
| C33 | Ingest-time check of `element` (C4) and `sessionId` (C5) | `examples/ingest-invalid-C4-element-pattern.json`, `examples/ingest-invalid-C5-session-id.json` |
