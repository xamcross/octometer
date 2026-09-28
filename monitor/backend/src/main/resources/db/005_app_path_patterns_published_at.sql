-- Migration 005: the published route list of one app.
-- Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D48 and D47.
-- path_patterns_published_at is the publishedAt of the config document
-- route-patterns, in epoch milliseconds UTC (the same unit as created_at).
-- kit_version is the kitVersion field of the same document, or NULL
-- before the reader's first read of a published list.

ALTER TABLE app ADD COLUMN path_patterns_published_at INTEGER;
ALTER TABLE app ADD COLUMN kit_version TEXT;
