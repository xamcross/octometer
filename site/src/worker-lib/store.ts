// The D1 queries of "POST /api/waitlist" (issue #291, the brief review
// corrections M1 and M2). Each function runs one statement, so the
// handler stays easy to read.
import type { Env } from './types';

const HOUR_MS = 60 * 60 * 1000;
const SEVEN_DAYS_MS = 7 * 24 * HOUR_MS;
const ATTEMPT_LIMIT = 5;

export interface WaitlistRow {
  token: string;
  confirmed_at: number | null;
  unsubscribed_at: number | null;
}

// The lazy cleanup (the maintainer's notes): one statement removes an
// old attempt row, one statement removes an unconfirmed row that stays
// older than 7 days. Both run at the start of each request.
export async function cleanupOldRows(env: Env, now: number): Promise<void> {
  await env.WAITLIST.prepare('DELETE FROM attempts WHERE at < ?')
    .bind(now - HOUR_MS)
    .run();
  await env.WAITLIST.prepare('DELETE FROM waitlist WHERE confirmed_at IS NULL AND created_at < ?')
    .bind(now - SEVEN_DAYS_MS)
    .run();
}

// Counts the attempts of one address hash in the last hour, then records
// this attempt. The count that comes back excludes the attempt that this
// call records, so a count of 5 or more means the limit already closed
// before this request (the enumeration rule of the maintainer's notes).
export async function countAndRecordAttempt(env: Env, emailHash: string, now: number): Promise<number> {
  const row = await env.WAITLIST.prepare(
    'SELECT COUNT(*) AS n FROM attempts WHERE email_hash = ? AND at > ?',
  )
    .bind(emailHash, now - HOUR_MS)
    .first<{ n: number }>();
  const count = row?.n ?? 0;
  await env.WAITLIST.prepare('INSERT INTO attempts (email_hash, at) VALUES (?, ?)')
    .bind(emailHash, now)
    .run();
  return count;
}

export function isOverAttemptLimit(count: number): boolean {
  return count >= ATTEMPT_LIMIT;
}

export async function findWaitlistRow(env: Env, address: string): Promise<WaitlistRow | null> {
  return env.WAITLIST.prepare(
    'SELECT token, confirmed_at, unsubscribed_at FROM waitlist WHERE email = ?',
  )
    .bind(address)
    .first<WaitlistRow>();
}

export async function insertWaitlistRow(
  env: Env,
  address: string,
  token: string,
  now: number,
): Promise<void> {
  await env.WAITLIST.prepare(
    'INSERT INTO waitlist (email, token, created_at, confirmed_at, unsubscribed_at) VALUES (?, ?, ?, NULL, NULL)',
  )
    .bind(address, token, now)
    .run();
}

// One stable token per address (the brief review correction M2): a later
// submit of an unconfirmed address reuses the token of the first submit,
// and clears "unsubscribed_at" so the address can join again. The
// correction round of pull request #326, MAJOR 1: "created_at" moves to
// now too, so the lazy cleanup never deletes a row that just re-joined.
export async function reopenWaitlistRow(env: Env, address: string, now: number): Promise<void> {
  await env.WAITLIST.prepare(
    'UPDATE waitlist SET confirmed_at = NULL, unsubscribed_at = NULL, created_at = ? WHERE email = ?',
  )
    .bind(now, address)
    .run();
}

export async function confirmByToken(env: Env, token: string, now: number): Promise<boolean> {
  const row = await env.WAITLIST.prepare('SELECT email FROM waitlist WHERE token = ?')
    .bind(token)
    .first<{ email: string }>();
  if (!row) {
    return false;
  }
  await env.WAITLIST.prepare(
    'UPDATE waitlist SET confirmed_at = ? WHERE token = ? AND confirmed_at IS NULL',
  )
    .bind(now, token)
    .run();
  return true;
}

// The correction round of pull request #326, MAJOR 2: the row deletes in
// place of a "state" update, so the store keeps the promise of the
// notice ("we keep it until you remove yourself"), and a later re-join
// writes a fresh row (which also closes MAJOR 1 for this path).
export async function removeByToken(env: Env, token: string): Promise<boolean> {
  const row = await env.WAITLIST.prepare('SELECT email FROM waitlist WHERE token = ?')
    .bind(token)
    .first<{ email: string }>();
  if (!row) {
    return false;
  }
  await env.WAITLIST.prepare('DELETE FROM waitlist WHERE token = ?').bind(token).run();
  return true;
}
