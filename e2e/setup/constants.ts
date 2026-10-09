import { createHash } from 'node:crypto';
import * as path from 'node:path';

/**
 * Shared constants of the setup and the fixtures.
 *
 * The correction of the brief review of 2026-09-27 states two rules
 * together: the kit drops a batch whose `User-Agent` header matches
 * `headless|bot|crawl|monitor|preview` (`kit/jvm-core/.../ingest/BotUserAgentFilter.java`
 * line 17), and headless Chromium sends a `User-Agent` value with the
 * text `HeadlessChrome`. This plain desktop Chrome string holds none of
 * the matched words, so the kit stores each click of this suite.
 */
export const DESKTOP_CHROME_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

/**
 * Builds the compose project name of one clone (issue #406). Each clone
 * sits at its own absolute path, so a hash of that path gives each
 * clone its own project, and two clones never share one set of
 * containers. The environment variable `OCTOMETER_E2E_PROJECT` is the
 * override: the CI job sets it to the one fixed name `octometer-e2e`,
 * so a CI run keeps the name that an older log or dashboard expects.
 */
function deriveComposeProject(): string {
  const override = process.env.OCTOMETER_E2E_PROJECT;
  if (override) {
    return override;
  }
  const repoRoot = path.resolve(__dirname, '..', '..');
  const hash = createHash('sha256').update(repoRoot).digest('hex').slice(0, 8);
  return `octometer-e2e-${hash}`;
}

/** The compose project name of the e2e run. Never `docker compose down` without it. */
export const COMPOSE_PROJECT = deriveComposeProject();
