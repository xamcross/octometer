-- Migration 008: the collector's own route pattern list, and the daily
-- event count of one app (issue #297, D40, D50).
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D40, D50.
--
-- The brief review of 2026-10-01 (m3): route_patterns is a JSON list, the
-- pattern form of D40. NULL means the app gives no list; the collector
-- then applies the default rule of octometer.kit.core.path. #299 adds the
-- API that writes this column; this migration only adds the column.
--
-- daily_event_count holds the daily cap counter of D50: one row for each
-- app and each UTC day with one collected event or more. The primary key
-- on (app_id, day) lets the collector upsert the count with one
-- statement.

ALTER TABLE app ADD COLUMN route_patterns TEXT;

CREATE TABLE daily_event_count (
    app_id INTEGER NOT NULL,
    day TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (app_id, day)
);
