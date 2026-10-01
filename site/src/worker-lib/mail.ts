// The confirmation mail of issue #291, through Resend. Each mail holds
// the confirmation link and the removal link, from "SITE_URL", never from
// the request origin (the brief review correction M2).
import type { Env } from './types';

const RESEND_URL = 'https://api.resend.com/emails';
const FROM_ADDRESS = 'Octometer <no-reply@0ct0meter.com>';
const SUBJECT = 'Confirm your place on the Octometer waitlist';

function buildMailText(confirmLink: string, removeLink: string): string {
  return [
    'Welcome to the Octometer waitlist.',
    '',
    `Open this link to confirm your address: ${confirmLink}`,
    '',
    'You can remove your address at any time with this link:',
    removeLink,
  ].join('\n');
}

async function writeToOutbox(env: Env, to: string, text: string): Promise<void> {
  // The sender mock of the test suite (the brief review correction M1):
  // "MAIL_MODE=file" writes the mail text into D1 in place of the Resend
  // call, on the local database only. The table is created at the first
  // write, so the migration file never holds a table for a test fixture.
  await env.WAITLIST.prepare(
    'CREATE TABLE IF NOT EXISTS outbox (' +
      'id INTEGER PRIMARY KEY AUTOINCREMENT, ' +
      'to_address TEXT NOT NULL, ' +
      'body TEXT NOT NULL, ' +
      'at INTEGER NOT NULL)',
  ).run();
  await env.WAITLIST.prepare('INSERT INTO outbox (to_address, body, at) VALUES (?, ?, ?)')
    .bind(to, text, Date.now())
    .run();
}

// The correction round of pull request #326, MAJOR 5: a missing key, a
// non-2xx Resend answer, and a thrown error each write one log line, so
// a mail outage shows in the Worker log. The answer to the visitor
// stays the same (the brief review correction M3): this call runs
// inside "ctx.waitUntil()", after the handler already sent that answer.
// Each log line writes the status code or the error class only, never
// the address and never the key.
async function sendThroughResend(env: Env, to: string, text: string): Promise<void> {
  if (!env.MAIL_API_KEY) {
    console.error('resend: no MAIL_API_KEY secret is set');
    return;
  }
  try {
    const response = await fetch(RESEND_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${env.MAIL_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ from: FROM_ADDRESS, to: [to], subject: SUBJECT, text }),
    });
    if (!response.ok) {
      console.error('resend', response.status);
    }
  } catch (error) {
    console.error('resend', error instanceof Error ? error.name : 'unknown error');
  }
}

export async function sendConfirmationMail(env: Env, to: string, token: string): Promise<void> {
  const confirmLink = `${env.SITE_URL}/confirm?token=${token}`;
  const removeLink = `${env.SITE_URL}/remove?token=${token}`;
  const text = buildMailText(confirmLink, removeLink);

  if (env.MAIL_MODE === 'file') {
    await writeToOutbox(env, to, text);
    return;
  }

  await sendThroughResend(env, to, text);
}
