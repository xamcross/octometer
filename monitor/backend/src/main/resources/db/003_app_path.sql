-- Migration 003: the distinct path values of one app, with the first-seen
-- time of each value.
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, the D5
-- rule of 2026-09-27 (issue #205).
-- The unit of first_seen_at is the unit of created_at: epoch milliseconds,
-- UTC. PRAGMA foreign_keys=ON runs on each connection (SqlitePragmas.kt),
-- so ON DELETE CASCADE removes each row of this table when the app row
-- goes; deleteApp needs no new statement for this table.

CREATE TABLE app_path (
  app_id INTEGER NOT NULL REFERENCES app(id) ON DELETE CASCADE,
  path TEXT NOT NULL,
  first_seen_at INTEGER NOT NULL,
  PRIMARY KEY (app_id, path)
) STRICT;
