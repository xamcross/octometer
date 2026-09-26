/**
 * The visitor source of the session start (contract rule C40, design
 * decision D42, issue #108).
 *
 * The `octo:session-start` entry can hold `referrerHost`: the host of
 * `document.referrer`, in lower case, matched against a closed set of
 * three values: `google.com`, `bing.com`, or the literal `other`. A
 * click entry never holds this field.
 */

/** The literal value of `referrerHost` for a host outside the source list. */
export const REFERRER_HOST_OTHER = 'other';

/**
 * The source list of rule C40, in match order. Each entry is a constant
 * of this one list (the maintainer's decision on issue #108); no other
 * part of the module repeats a host name as a literal.
 */
export const REFERRER_SOURCE_LIST = ['google.com', 'bing.com'] as const;

/** The shape of an IPv4 literal host, for example `192.168.1.1`. */
const IPV4_HOST_PATTERN = /^\d{1,3}(\.\d{1,3}){3}$/;

/**
 * Builds the match pattern of one source entry, from its plain host
 * name. The `google` pattern of contract rule C40 is
 * `^([a-z0-9-]+\.)*google\.((com|co)\.[a-z]{2}|com|[a-z]{2})$`. The
 * `bing` pattern has the same form, with `bing` in place of `google`
 * (the maintainer's decision on issue #108). The README of this package
 * states the exact regex of each entry.
 */
function buildSourcePattern(name: string): RegExp {
  return new RegExp(`^([a-z0-9-]+\\.)*${name}\\.((com|co)\\.[a-z]{2}|com|[a-z]{2})$`);
}

interface SourceEntry {
  readonly host: string;
  readonly pattern: RegExp;
}

const SOURCE_ENTRIES: readonly SourceEntry[] = REFERRER_SOURCE_LIST.map((host) => {
  const name = host.split('.')[0] as string;
  return { host, pattern: buildSourcePattern(name) };
});

/**
 * Matches a lower-case host against the source list. Gives the matched
 * entry, or the literal `other` for each other host.
 */
export function matchSourceHost(host: string): string {
  for (const entry of SOURCE_ENTRIES) {
    if (entry.pattern.test(host)) {
      return entry.host;
    }
  }
  return REFERRER_HOST_OTHER;
}

/**
 * Computes the `referrerHost` value of the session start, from the raw
 * `document.referrer` value and the origin of the app page.
 *
 * It gives `undefined` for one of five cases (contract rule C40, design
 * decision D42):
 *
 * - an empty referrer;
 * - a referrer with a scheme other than `http` or `https`;
 * - the origin of the app;
 * - an IP literal;
 * - a host without a dot.
 *
 * It gives the matched entry, or the literal `other`, for each other
 * referrer. It reads no part of the referrer other than its host, thus
 * it sends no part of a query string or a path.
 */
export function computeSessionStartReferrerHost(
  referrer: string,
  appOrigin: string,
): string | undefined {
  if (referrer === '') {
    return undefined;
  }
  let url: URL;
  try {
    url = new URL(referrer);
  } catch {
    return undefined;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return undefined;
  }
  if (url.origin === appOrigin) {
    return undefined;
  }
  const host = url.hostname.toLowerCase();
  if (host.startsWith('[') || IPV4_HOST_PATTERN.test(host)) {
    return undefined;
  }
  if (!host.includes('.')) {
    return undefined;
  }
  return matchSourceHost(host);
}
