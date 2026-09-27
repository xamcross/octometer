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

/** The compose project name of the e2e run. Never `docker compose down` without it. */
export const COMPOSE_PROJECT = 'octometer-e2e';
