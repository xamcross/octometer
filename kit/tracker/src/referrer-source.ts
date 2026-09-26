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
 * The country pattern of contract rule C40. The rule states this
 * pattern for the entry `google.com` only. It gives no such pattern
 * for `bing.com`. The plain equals-or-suffix rule below covers each
 * other entry. Thus a host such as `bing.co.uk` gives `other` (the
 * correction of MAJOR 1, from both reviews of pull request #192). The
 * server class `EventFieldValidator` holds one Google pattern, with no
 * `bing` pattern.
 */
const GOOGLE_HOST_PATTERN = /^([a-z0-9-]+\.)*google\.((com|co)\.[a-z]{2}|com|[a-z]{2})$/;

/**
 * Checks the plain match rule of contract rule C40. A host matches an
 * entry when it equals the entry. It also matches when it ends with
 * `.` plus the entry.
 */
function matchesSourceEntry(host: string, entry: string): boolean {
  return host === entry || host.endsWith(`.${entry}`);
}

/**
 * Matches a lower-case host against the source list. Gives the matched
 * entry, or the literal `other` for each other host.
 *
 * The order follows contract rule C40. The plain equals-or-suffix rule
 * runs first, for each entry of the list. The country pattern of
 * `google.com` runs second, as an extra rule.
 */
export function matchSourceHost(host: string): string {
  for (const entry of REFERRER_SOURCE_LIST) {
    if (matchesSourceEntry(host, entry)) {
      return entry;
    }
  }
  if (GOOGLE_HOST_PATTERN.test(host)) {
    return 'google.com';
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
