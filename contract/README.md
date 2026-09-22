# Event log contract v1.2

This document states the event log contract of Octometer. The contract has four parts: the
event document, the ingest request, the reader rule, and the database user. The source is
section 4 of `docs/superpowers/specs/2026-09-21-octometer-design.md`, plus section 1 and the
last paragraph of section 6 for the definitions below. Some rules also cite an owner decision
or a design decision from that document: O4, O5, D4, D5, D20, D21, D42, D43, and D44.

Version 1.1. It adds the rules C38 to C43: the session-start marker and its transport form, the
`path` field and its stored form, the `referrerHost` field and its closed set, and the erasure
rule for an anonymous session. It also adds one sentence to rule C32. Version 1.1 adds no field
for a campaign parameter, because no advertisement campaign runs. Version 1.1 is a minor change
under rule C9. The collection name stays `octometer_events`. Version 1.1 reserves one value of
the field `element`. A reader of version 1.0 counts an event `octo:session-start` as a click.

Version 1.2. It corrects the text of the Purpose paragraph, and of rules C19, C38, C40, and
C42. Each one now agrees word for word with design version 1.1. It adds no field and no rule,
thus it is not a major change under rule C10. A reader of version 1.1 already matches each
corrected rule, because the correction states no new behavior of the tracker or of the
server.

Each rule has an ID, `C1` to `C43`. The table at the end maps each rule ID to its example file.

## Purpose

Octometer is a monitor for the click metrics of the owner's web apps (design section 1). Each
app writes its clicks to a flat event log. The monitor reads this log and shows five table
views: an app view, a user view, and an element view. Design decision D44 adds two more
views, "First pages" and "Anonymous sessions".

## Definitions

- **A session.** A session is the life of one browser tab. The client creates one
  `sessionId`, a UUID, for each session (rule C5).
- **Sessions at level 1.** The level 1 column "unique sessions" counts the distinct
  `sessionId` values across the whole app.
- **Sessions at level 2.** The level 2 column "sessions" counts the distinct `sessionId`
  values for one user. A user can sign in during the life of one tab, so one session can
  belong to two users. For this reason, the level 2 session counts do not sum to the level 1
  unique-session count (design section 6, last paragraph).
- **Sessions at level 3.** The level 3 column "sessions" counts the distinct `sessionId`
  values for one app, one user id, and one element. The source is design section 1. Design
  section 6 says level 3 filters with `user_id IS ?` and groups by `element`.
- **Unique elements.** The level 2 column "unique elements" counts the distinct `element`
  values that one user has clicked.

## Extended JSON

A BSON value has no direct JSON form. Each example event document in `examples/` uses
[MongoDB Extended JSON](https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/),
in its relaxed form. An ObjectId is `{"$oid": "..."}`. In the relaxed form, a Date is
`{"$date": "..."}`. In the canonical form, a Date is `{"$date": {"$numberLong": "..."}}`.

## 1. The event document (design section 4.1)

Collection `octometer_events`, in the database of the app. An event document can also hold two
optional fields: `path` and `referrerHost`. Section 2 states their exact form: rule C39 for
`path`, and rule C40 for `referrerHost`.

- **C1.** The event collection is `octometer_events`, in the database of the app.
- **C2.** The `_id` field is an ObjectId. The kit creates it inside the insert call. The time
  bytes of the `_id` hold the insert time, with a resolution of 1 second. In full seconds,
  this time is at or after `ts` (design section 2.4).
- **C3.** The `ts` field is a Date. It holds the click time. The app server sets it.
- **C4.** The `element` field is a string of 1 to 100 characters. It matches the pattern
  `[A-Za-z0-9_.:-]+`.
- **C5.** The `sessionId` field is a string. It is a UUID.
- **C6.** The `userId` field is a string or `null`. A string has 1 to 254 characters. `null`
  means "not signed in". The value is the internal user id of the app (design decision O5).
  It must not be a username, an email address, or an IP address. An event holds no IP
  address.
- **C7.** The app only inserts an event. It never updates an event.
- **C8.** The collection has two indexes: one on `_id`, and a TTL index on `ts` with
  `expireAfterSeconds`. The default value is 2592000 (30 days). The environment variable
  `OCTOMETER_RETENTION_DAYS` sets a different value.
- **C9.** A reader ignores an unknown field in an event document. The addition of a field is a
  minor change to the contract.
- **C10.** A major change to the contract uses a new collection name, for example
  `octometer_events_v2`.
- **C11. The `_class` rule.** An app that writes its own event store must not add a `_class`
  field. Spring Data adds this field on its own. A Spring Data app needs a step that removes
  the field before the write. A store that skips the field is also correct.
- **C43. The erasure rule.** The erasure of a user id also removes each event with `userId:
  null` whose `sessionId` matches an event of that user id. An event of a second user id in
  the same session stays. A visitor can sign in during the life of one tab, thus an anonymous
  event of that session describes the same person. An app with its own event store (rule C11)
  must follow rule C43 too.

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
- **C19.** The server returns status 204 for one of three cases:

  - a success;
  - a batch that the event cap drops;
  - a batch that a daily anonymous cap drops, or that the bot filter of design decision D43
    drops.

  It returns 400 for an invalid body, 415 for a content type other than JSON, and 429 for the
  rate limit (design decision D20). The event cap is `OCTOMETER_MAX_EVENTS`, default 200000
  (design decision D21).
- **C32.** The server ignores an unknown field in the ingest request body. A `userId` field
  in the body is such a field. The server ignores it. The stored user id comes only from the
  authentication context (rule C16). The server also ignores an unknown field inside an entry
  of `clicks`.
- **C33.** The ingest route checks the `element` value with the rule of C4, and the
  `sessionId` value with the rule of C5. A value that breaks one of these rules makes the
  body invalid; the server returns 400.
- **C36.** A duplicate key in one JSON object makes the body invalid.
- **C37.** `ageMs` is a JSON integer. A value with a fraction or an exponent, for example
  `1.0` or `1e3`, makes the body invalid.
- **C38.** The element prefix `octo:` is reserved for the contract. An app must not use it as
  a `data-octo` value. The server checks rule C33 before it checks this rule. An `element`
  value that breaks rule C33 makes the whole body invalid, also when the value starts with
  the prefix `octo:`. The server then returns 400 for the whole batch, and this rule drops no
  entry. The tracker drops each `data-octo` value with the prefix `octo:` in
  each letter case (for example `OCTO:foo`), also the exact text `octo:session-start`, and it
  writes one console warning for the first dropped value. Only the own call of the tracker
  makes a session start. A `data-octo` attribute of a page never makes a session start. The
  element `octo:session-start` marks the first event of a
  session. It is not a click. A reader of version 1.0 counts it as a click. The tracker sends
  the session start as one entry of `clicks`, with `element: "octo:session-start"` and
  `ageMs: 0` (rule C13). This is not a new request form. The server accepts the element
  `octo:session-start`. It drops each other entry whose `element` starts with `octo:` in each
  letter case (for example `OCTO:foo`), except the exact text `octo:session-start`. It keeps
  the rest of the batch, and it writes a maximum of one warning for each batch. A session
  start is a statement of the client. It is not a proof of a visit. The rate limits of the
  kit control a flood.
- **C39.** The `path` field is optional on an event and on an entry of `clicks`. It has 1 to
  150 bytes in UTF-8. It starts with `/`, and its second character is not `/`. Each other
  character is in the set `[A-Za-z0-9._~!$&'()*+,;=:@/-]`, or it is a part of a well-formed
  escape `%[0-9A-Fa-f]{2}`. It holds no query string and no fragment. The server matches the
  client value against the route pattern list of the app (rule C42). The server stores only
  the result of that match. The server never stores the raw client value. Rule C16 states
  the same rule for `userId`.
- **C40.** The `referrerHost` field is optional on `octo:session-start`. It is the literal
  `other`, or it has 1 to 253 bytes, the pattern `[a-z0-9.-]+`, and at least one dot. The
  server converts no letter case. A value with an upper-case letter breaks this rule. The
  source list has two entries: `google.com` and `bing.com`. The tracker takes the host of
  `document.referrer` in lower case. A host matches an entry when it equals the entry, or
  when it ends with `.` plus the entry; the stored value is the entry. A host that matches
  `^([a-z0-9-]+\.)*google\.((com|co)\.[a-z]{2}|com|[a-z]{2})$` gives `google.com`. Each other
  host gives the literal `other`. The stored `referrerHost` value is exactly one of three
  literals: `google.com`, `bing.com`, or `other`. The server repeats the same match on the
  client value, and it stores the entry or the literal `other`. The server stores a value of
  that set as it is. For a client value with the form of a host name that matches no entry,
  the server stores `other`. The server drops a client value with a different form, under
  rule C41. The server drops a `referrerHost`
  field on an entry with an element other than `octo:session-start`. A free host name can
  name an employer, a tenant, or an internal host, thus the source list is fixed. The tracker
  sends no `referrerHost` field for one of five cases (design decision D42):

  - an empty referrer;
  - a referrer with a scheme other than `http` or `https`;
  - the origin of the app;
  - an IP literal;
  - a host without a dot.

  The server never adds a `referrerHost` field on its own. An entry `octo:session-start`
  without `referrerHost` is a direct visit, or a visit from a source that sends no referrer.
- **C41.** An invalid `path` or `referrerHost` value does not make the body invalid. The
  server drops that field, it keeps the entry, and it writes a maximum of one warning for
  each batch. No log line holds a raw path or a raw host. This rule holds only for a value
  that breaks the shape rule of C39 or of C40. A `path` that matches no route pattern is not
  an invalid value; rule C42 states its stored result.
- **C42. The stored `path`.** The app gives an ordered list of route patterns to the tracker
  and to the server (`OCTOMETER_PATH_PATTERNS`). Each literal segment of a pattern is ASCII.
  A pattern holds a literal segment, the segment `*`, or the segment `:name`. A pattern
  matches only when it has the same count of segments as the path. The segment `*` and the
  segment `:name` each match exactly one segment. No pattern matches a prefix. The first
  pattern that matches gives the result. The segment `*` keeps the real segment, and only
  when that segment matches `^[A-Za-z0-9](?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2}){0,79}$`. Each
  other value for that segment makes the whole path `/other`. The segment `:name` writes the
  literal `:name` in place of the real segment. For a literal segment and for a `:name`
  segment, the stored value uses the text of the pattern, never the text of the input. A
  result above 150 bytes becomes `/other`. The match of a literal segment ignores the ASCII
  letter case. The server removes a trailing slash before the match, but not for the root
  path. A path with an empty segment, a `.`
  segment, or a `..` segment gives `/other`. The server decodes no `%` escape before the
  match. A path that matches no pattern gives `/other`. The server stores `/other` for that
  path. It does not drop the `path` field. Without a route pattern list, the server stores no
  `path` field, and it writes one warning at startup. **An invalid route pattern.** An entry
  of the pattern list is invalid in one of five cases:

  - the entry is not a string;
  - the entry is an empty string;
  - the entry has no leading `/`;
  - a segment of the entry is empty, for example in `/a/` or in `/a//b`;
  - a segment of the entry holds a character outside the set of rule C39; a well-formed
    escape `%XX` is valid there too.

  The root entry `/` stays valid, because it has no segment. A pattern list with one invalid
  pattern counts as no list: the tracker sends no `path`, and the server stores no `path` and
  writes one warning at the start. Neither side drops one pattern and keeps the rest, because
  a dropped pattern would change the match order. **Warning for an app team.** A path can hold
  an identifier, a token, or a search term. Mark each such segment with `:name` in the route
  list. Never use `*` for a segment that holds a token, an email address, or a user id.

## 3. The reader rule (design section 4.3)

- **C20.** The reader computes a bound with `ObjectId.getSmallestWithDate(serverTime - lag)`.
  It reads events with the filter `{_id: {$gt: cursor, $lt: bound}}`. It sorts by `_id`
  ascending. The limit is 1000, and the batch size is 1000.
- **C21.** `serverTime` is the `localTime` value from the `hello` command of the primary node.
- **C22.** `lag` is 60 seconds in prod mode and 2 seconds in dev mode (a config value).
- **C23.** The read preference is `primary`, set in code. The monitor rejects a
  `readPreference` value in a connection URI.
- **C24.** The delivery of an event is "at least once". The primary key `(app_id, event_id)`
  in the monitor store drops a duplicate.
- **C25. The accepted loss.** The accepted loss is an insert that commits more than `lag`
  seconds after the creation of its `_id`. The monitor does not read this insert.
- **C26.** A `$gt` filter on a deleted `_id` is a plain index seek. A TTL delete does not
  break the reader's cursor.
- **C34.** Without a cursor, the reader starts at the oldest event (design section 4.3). The
  cursor moves to the `_id` of the last document of a committed page. The cursor also moves
  after a skipped document (design decisions D4 and D5).

## 4. The database user (design section 4.4)

The owner runs these commands. A password must not pass through an agent (rule C30).

```
atlas customDbRoles create octometerEventReader --privilege FIND@<database>.octometer_events --projectId <id>
atlas dbusers create --username octometer-reader --role octometerEventReader --projectId <id>
atlas dbusers describe octometer-reader --projectId <id> -o json
```

The Atlas CLI prompts for the password. No command takes the password as an argument, so the
shell history does not keep it.

- **C27.** The owner creates the database role and the database user with the three commands
  above.
- **C28.** The Atlas CLI prompts for the password. The password stays out of the shell
  history.
- **C29.** The owner creates the password with `openssl rand -hex 24`. The owner uses one
  password for each Atlas project.
- **C30.** The owner runs these commands. A password must not pass through an agent.
- **C31.** The owner rotates the password after a laptop loss, after a suspected leak, and
  each 12 months. A rotation deletes the user, then creates the user again.
- **C35.** The database role holds only the `FIND` privilege. The role limits the privilege
  to the collection `octometer_events` (design decision O4).

## Examples

Each example file holds only fake data: a fake user id, a fake UUID, no real name, no email
address, no password, and no real host name, except a public search host of the source list
of rule C40. Each file in `examples/` parses as JSON. A file that shows an invalid ingest body
is also valid JSON. Its value breaks one rule of the contract.

| Rule ID | Rule | Example file |
|---|---|---|
| C1 | Collection name | `examples/event-valid-C1-C5.json` |
| C2 | `_id` field | `examples/event-valid-C1-C5.json` |
| C3 | `ts` field | `examples/event-valid-C1-C5.json` |
| C4 | `element` field | `examples/event-valid-C1-C5.json` (valid), `examples/ingest-invalid-C4-element-pattern.json` (bad pattern), `examples/ingest-invalid-C4-element-length.json` (101 characters) |
| C5 | `sessionId` field | `examples/event-valid-C1-C5.json` (valid), `examples/ingest-invalid-C5-session-id.json` (invalid) |
| C6 | `userId` field, `null` case | `examples/event-userid-null-C6.json` |
| C7 | Insert-only | No example file. This rule states an app action, not a document shape. |
| C8 | Indexes | No example file. This rule states a database setting, not a document shape. |
| C9 | Unknown-field tolerance | No example file. This rule states reader behavior. |
| C10 | Major-change collection name | No example file. This rule names a future collection. |
| C11 | The `_class` rule | No example file. This rule states a write-side step, not a document shape. |
| C12 | Ingest endpoint and header | No example file. This rule states the URL and the header, not the body content. |
| C13 | Ingest body shape | `examples/ingest-valid-C13.json` (valid), `examples/ingest-invalid-C13-missing-sessionid.json` (no `sessionId`), `examples/ingest-invalid-C13-missing-clicks.json` (no `clicks`) |
| C14 | `ageMs` clamp | No example file. The design does not ask for an example of the clamp. |
| C15 | Negative `ageMs` | `examples/ingest-invalid-C15-negative-age.json` |
| C16 | `userId` from the authentication context | No example file. See rule C32. |
| C17 | Batch limit (50 clicks), invalid above the limit | `examples/ingest-invalid-C17-batch-limit.json` |
| C18 | Body size limit (16 KB), invalid above the limit | `examples/ingest-invalid-C18-body-size.json` — the file's padding field is an ignored unknown field (rule C32); the file breaks only the size rule. |
| C19 | Response codes | No example file. An HTTP response is not a JSON document in this contract. |
| C20–C24, C26 | The read window and the delivery model | No example file. These rules state a server-side read algorithm. |
| C25 | The accepted loss | No example file. This rule states a timing bound, not a document shape. |
| C27–C31 | The database user | No example file. These rules state shell commands. |
| C32 | Unknown-field tolerance in the ingest body, `userId` included, and an unknown field inside a `clicks` entry | No dedicated example file. The padding field of `examples/ingest-invalid-C18-body-size.json` is such an ignored field. The `path` field of `examples/ingest-valid-C41-bad-path-dropped.json` is another one, under the present parser. |
| C33 | Ingest-time check of `element` (C4) and `sessionId` (C5) | `examples/ingest-invalid-C4-element-pattern.json`, `examples/ingest-invalid-C4-element-length.json`, `examples/ingest-invalid-C5-session-id.json` |
| C34 | Cursor start value and advance rule | No example file. This rule states a server-side algorithm. |
| C35 | Database role limit (`FIND` only, one collection) | No example file. This rule states a database administration fact. |
| C36 | Duplicate-key rejection | `examples/ingest-invalid-C36-duplicate-key.json` — the file repeats the `sessionId` key with the same value, thus the file breaks only the duplicate-key rule. |
| C37 | `ageMs` as a plain integer, invalid with a fraction or an exponent | `examples/ingest-invalid-C37-agems-fraction.json` |
| C38 | The `octo:` prefix, the `octo:session-start` marker, and its transport form | `examples/event-valid-C38-session-start.json` (the stored marker), `examples/ingest-invalid-C38-session-start-trailing-space.json` (`octo:session-start` with a trailing space breaks rule C33 before rule C38 runs, so the whole body is invalid) |
| C39 | The shape of the `path` field | `examples/event-valid-C39-path.json` (stored event), `examples/ingest-valid-C39-path.json` (ingest body) |
| C40 | The shape of `referrerHost`, its closed set, and the source list | `examples/event-valid-C40-source.json` |
| C41 | The rule for an invalid `path` or `referrerHost` value | `examples/ingest-valid-C41-bad-path-dropped.json` — the file holds a `path` value that breaks rule C39; the body stays valid, and the present parser ignores the field as an unknown field (rule C32). |
| C42 | The route pattern list and the derivation of the stored `path` | `examples/event-valid-C42-masked-path.json` (a masked `:name` segment), `examples/event-valid-C42-other-path.json` (no matching pattern), `examples/event-valid-C39-path.json` (a kept `*` segment), `examples/C42-path-match-cases.json` (the shared matcher case table: an ordered route pattern list, an input path, and the expected result, for the tracker and for the server of issue #104). The case file covers the match rule only. Each side checks each pattern before the first match. |
| C43 | The erasure of an anonymous session | No example file. This rule states an app action, not a document shape. |
