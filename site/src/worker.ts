// The waitlist handler of issue #291: the "fetch" handler of the public
// site Worker. "site/wrangler.jsonc" names this file as "main", and
// routes only "/api/*", "/confirm", and "/remove" here through
// "run_worker_first"; every other path goes straight to the static
// assets.
import type { Env, ExecutionContext } from './worker-lib/types';
import { normalizeAddress, isValidAddress } from './worker-lib/address';
import { createToken, hashAddress } from './worker-lib/token';
import { sendConfirmationMail } from './worker-lib/mail';
import { isSameHost, isSameOrigin } from './worker-lib/http';
import {
  renderConfirmForm,
  renderRemoveForm,
  renderMessagePage,
  renderInvalidLinkPage,
  HTML_HEADERS,
} from './worker-lib/pages';
import {
  cleanupOldRows,
  countAndRecordAttempt,
  isOverAttemptLimit,
  findWaitlistRow,
  insertWaitlistRow,
  reopenWaitlistRow,
  confirmByToken,
  removeByToken,
} from './worker-lib/store';
import {
  WAITLIST_SUCCESS_MESSAGE,
  WAITLIST_ADDRESS_ERROR_MESSAGE,
  WAITLIST_SERVER_ERROR_MESSAGE,
} from './shared/waitlist-messages';

type Outcome = 'joined' | 'address' | 'server';

// Builds the answer of "POST /api/waitlist": a JSON body for a fetch
// request, or a "303" redirect back to the page for a plain form post
// (the maintainer's notes). Both forms carry the same outcome.
function answer(
  isJson: boolean,
  jsonStatus: number,
  message: string,
  outcome: Outcome,
  env: Env,
): Response {
  if (isJson) {
    return new Response(JSON.stringify({ message }), {
      status: jsonStatus,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  const redirectUrl = new URL(env.SITE_URL);
  redirectUrl.pathname = '/';
  redirectUrl.search = outcome === 'joined' ? 'joined=1' : `error=${outcome}`;
  redirectUrl.hash = 'waitlist';
  return new Response(null, { status: 303, headers: { Location: redirectUrl.toString() } });
}

async function readSubmission(
  request: Request,
  isJson: boolean,
): Promise<{ email: string; website: string }> {
  if (isJson) {
    const body = (await request.json()) as Record<string, unknown>;
    return {
      email: typeof body.email === 'string' ? body.email : '',
      website: typeof body.website === 'string' ? body.website : '',
    };
  }
  const form = await request.formData();
  return {
    email: String(form.get('email') ?? ''),
    website: String(form.get('website') ?? ''),
  };
}

async function handlePostWaitlist(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  url: URL,
): Promise<Response> {
  if (!isSameHost(url, env.SITE_URL)) {
    return new Response('Not found', { status: 404 });
  }
  if (!isSameOrigin(request, env.SITE_URL)) {
    return new Response(null, { status: 403 });
  }

  const contentType = request.headers.get('Content-Type') ?? '';
  const isJson = contentType.includes('application/json');

  let email = '';
  let website = '';
  try {
    const submission = await readSubmission(request, isJson);
    email = submission.email;
    website = submission.website;
  } catch {
    return answer(isJson, 400, WAITLIST_ADDRESS_ERROR_MESSAGE, 'address', env);
  }

  // The honeypot (the maintainer's notes): a filled field answers the
  // success text, and writes no row.
  if (website.trim().length > 0) {
    return answer(isJson, 202, WAITLIST_SUCCESS_MESSAGE, 'joined', env);
  }

  const address = normalizeAddress(email);
  if (!isValidAddress(address)) {
    return answer(isJson, 400, WAITLIST_ADDRESS_ERROR_MESSAGE, 'address', env);
  }

  try {
    const now = Date.now();
    await cleanupOldRows(env, now);

    const emailHash = await hashAddress(address);
    const attemptCount = await countAndRecordAttempt(env, emailHash, now);

    if (isOverAttemptLimit(attemptCount)) {
      // Above the limit: the same success text, and no mail (the
      // enumeration rule of the maintainer's notes).
      return answer(isJson, 202, WAITLIST_SUCCESS_MESSAGE, 'joined', env);
    }

    const existing = await findWaitlistRow(env, address);

    if (!existing) {
      const token = createToken();
      await insertWaitlistRow(env, address, token, now);
      ctx.waitUntil(sendConfirmationMail(env, address, token));
    } else if (existing.confirmed_at === null || existing.unsubscribed_at !== null) {
      // An unconfirmed address, or an unsubscribed address that joins
      // again, reuses its one stable token (the brief review correction
      // M2) and gets the mail again.
      await reopenWaitlistRow(env, address, now);
      ctx.waitUntil(sendConfirmationMail(env, address, existing.token));
    }
    // A confirmed address that stays on the list: no mail, the same
    // success text.

    return answer(isJson, 202, WAITLIST_SUCCESS_MESSAGE, 'joined', env);
  } catch {
    return answer(isJson, 500, WAITLIST_SERVER_ERROR_MESSAGE, 'server', env);
  }
}

function handleGetConfirm(url: URL): Response {
  const token = url.searchParams.get('token') ?? '';
  return new Response(renderConfirmForm(token), { status: 200, headers: HTML_HEADERS });
}

function handleGetRemove(url: URL): Response {
  const token = url.searchParams.get('token') ?? '';
  return new Response(renderRemoveForm(token), { status: 200, headers: HTML_HEADERS });
}

async function handlePostConfirm(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isSameHost(url, env.SITE_URL)) {
    return new Response('Not found', { status: 404 });
  }
  try {
    const form = await request.formData();
    const token = String(form.get('token') ?? '');
    const now = Date.now();
    const found = await confirmByToken(env, token, now);
    if (!found) {
      return new Response(renderInvalidLinkPage(), { status: 400, headers: HTML_HEADERS });
    }
    return new Response(renderMessagePage('You are on the list.'), {
      status: 200,
      headers: HTML_HEADERS,
    });
  } catch {
    return new Response(renderMessagePage(WAITLIST_SERVER_ERROR_MESSAGE), {
      status: 500,
      headers: HTML_HEADERS,
    });
  }
}

async function handlePostRemove(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isSameHost(url, env.SITE_URL)) {
    return new Response('Not found', { status: 404 });
  }
  try {
    const form = await request.formData();
    const token = String(form.get('token') ?? '');
    const found = await removeByToken(env, token);
    if (!found) {
      return new Response(renderInvalidLinkPage(), { status: 400, headers: HTML_HEADERS });
    }
    return new Response(renderMessagePage('You are removed.'), {
      status: 200,
      headers: HTML_HEADERS,
    });
  } catch {
    return new Response(renderMessagePage(WAITLIST_SERVER_ERROR_MESSAGE), {
      status: 500,
      headers: HTML_HEADERS,
    });
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/api/waitlist' && request.method === 'POST') {
      return handlePostWaitlist(request, env, ctx, url);
    }
    if (url.pathname === '/confirm' && request.method === 'GET') {
      return handleGetConfirm(url);
    }
    if (url.pathname === '/confirm' && request.method === 'POST') {
      return handlePostConfirm(request, env, url);
    }
    if (url.pathname === '/remove' && request.method === 'GET') {
      return handleGetRemove(url);
    }
    if (url.pathname === '/remove' && request.method === 'POST') {
      return handlePostRemove(request, env, url);
    }

    return new Response('Not found', { status: 404 });
  },
};
