-- Migration 007: the app key, the secret key hash, and the origin list
-- of one app (issue #296, D51, D52, D57).
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D51, D57.
--
-- The brief review of 2026-10-01 (B1): this file adds three columns
-- only. SQLite takes no UNIQUE clause inside ADD COLUMN, so the unique
-- rule of app_key is a separate index. An existing app row keeps a
-- NULL app_key and a NULL secret_key_hash; origins defaults to the
-- empty JSON list.

ALTER TABLE app ADD COLUMN app_key TEXT;
ALTER TABLE app ADD COLUMN secret_key_hash TEXT;
ALTER TABLE app ADD COLUMN origins TEXT NOT NULL DEFAULT '[]';

CREATE UNIQUE INDEX app_app_key ON app(app_key);
