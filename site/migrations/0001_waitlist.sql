-- The waitlist store (issue #291). No IP column, and no IP in a log line
-- (the maintainer's decision of the brief review of #290, 2026-10-01).
CREATE TABLE waitlist (
  email TEXT PRIMARY KEY,
  token TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  confirmed_at INTEGER,
  unsubscribed_at INTEGER
);

-- The per-address rate limit (5 requests per hour). The column holds the
-- SHA-256 hash of the address, never the address (the brief review
-- BLOCKER M1, 2026-10-01). The handler deletes a row older than one hour
-- at the start of each request.
CREATE TABLE attempts (
  email_hash TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX idx_attempts_email_hash_at ON attempts (email_hash, at);
