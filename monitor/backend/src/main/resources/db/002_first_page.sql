-- Migration 002: the first-page columns and the partial indexes.
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, the
-- contract version 1.1 of #101, and the rules M1 and M2.
-- The three column statements stand before the four index statements,
-- because a partial index needs the column kind.

ALTER TABLE event ADD COLUMN path TEXT;
ALTER TABLE event ADD COLUMN referrer_host TEXT;
ALTER TABLE event ADD COLUMN kind INTEGER NOT NULL DEFAULT 0;

DROP INDEX event_agg;
CREATE INDEX event_agg ON event(app_id, user_id, element, session_id, ts) WHERE kind = 0;

DROP INDEX event_session;
CREATE INDEX event_session ON event(app_id, session_id, element, ts, user_id, kind);

CREATE INDEX event_first_page ON event(app_id, path, ts, session_id, user_id) WHERE kind = 1;

CREATE INDEX event_start ON event(app_id, user_id, ts, session_id) WHERE kind = 1;
