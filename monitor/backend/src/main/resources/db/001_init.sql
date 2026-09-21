-- Migration 001: the first schema of the monitor store.
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, section 6.
-- The DDL is exact. A change of one column or one index needs a new file.

CREATE TABLE app (
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, database_name TEXT NOT NULL,
  collection_name TEXT NOT NULL, created_at INTEGER NOT NULL,
  cursor TEXT, next_poll_at INTEGER, last_poll_at INTEGER, last_success_at INTEGER,
  status TEXT, last_error TEXT, consecutive_failures INTEGER NOT NULL DEFAULT 0,
  privileges_checked_at INTEGER
) STRICT;

CREATE TABLE event (
  app_id INTEGER NOT NULL REFERENCES app(id), event_id TEXT NOT NULL,
  ts INTEGER NOT NULL,                       -- epoch milliseconds, UTC
  element TEXT NOT NULL, session_id TEXT NOT NULL,
  user_id TEXT CHECK (user_id IS NULL OR user_id <> ''),
  PRIMARY KEY (app_id, event_id)
) STRICT;

CREATE INDEX event_agg     ON event(app_id, user_id, element, session_id, ts);
CREATE INDEX event_session ON event(app_id, session_id);

CREATE TABLE skipped_event (app_id INTEGER NOT NULL, event_id TEXT NOT NULL, reason TEXT NOT NULL,
  PRIMARY KEY (app_id, event_id)) STRICT;
CREATE TABLE gap (app_id INTEGER NOT NULL, from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL) STRICT;
