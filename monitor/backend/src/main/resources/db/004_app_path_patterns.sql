-- Migration 004: the route pattern list of one app.
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, the D5
-- rule of 2026-09-27 (issue #226).
-- path_patterns holds the value of OCTOMETER_PATH_PATTERNS in the same
-- form: one pattern per line, no blank line. NULL means the app gives no
-- list, so the reader keeps the cap of issue #205.

ALTER TABLE app ADD COLUMN path_patterns TEXT;
