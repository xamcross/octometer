-- Migration 006: the accounts, the invite list, the sign-in tokens, and
-- the sessions (D52, issue #301).
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D52, D51.
--
-- signin_token holds the email address, not an account id: the account
-- row is absent before the first sign-in of that address (the pull
-- request text of issue #301 states this reason).
--
-- app.name was unique over the whole table (001_init.sql). It is now
-- unique per account (issue #301, brief review M3). SQLite gives no
-- ALTER of a table constraint on a STRICT table, so this file rebuilds
-- the app table: a new table, a copy of the rows, a drop, and a rename.
-- An existing app row keeps a NULL account_id; the run guide states that
-- a NULL-account app is visible to no account.

CREATE TABLE account (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE invite (
  email TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE signin_token (
  token_hash TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE session (
  session_hash TEXT PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES account(id),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
) STRICT;

CREATE INDEX session_account ON session(account_id);

CREATE TABLE app_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, database_name TEXT NOT NULL,
  collection_name TEXT NOT NULL, created_at INTEGER NOT NULL,
  cursor TEXT, next_poll_at INTEGER, last_poll_at INTEGER, last_success_at INTEGER,
  status TEXT, last_error TEXT, consecutive_failures INTEGER NOT NULL DEFAULT 0,
  privileges_checked_at INTEGER,
  path_patterns TEXT, path_patterns_published_at INTEGER, kit_version TEXT,
  account_id INTEGER REFERENCES account(id),
  UNIQUE(account_id, name)
) STRICT;

INSERT INTO app_new (
  id, name, database_name, collection_name, created_at,
  cursor, next_poll_at, last_poll_at, last_success_at,
  status, last_error, consecutive_failures, privileges_checked_at,
  path_patterns, path_patterns_published_at, kit_version, account_id
)
SELECT
  id, name, database_name, collection_name, created_at,
  cursor, next_poll_at, last_poll_at, last_success_at,
  status, last_error, consecutive_failures, privileges_checked_at,
  path_patterns, path_patterns_published_at, kit_version, NULL
FROM app;

DROP TABLE app;
ALTER TABLE app_new RENAME TO app;
