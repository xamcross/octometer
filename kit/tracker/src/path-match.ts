/**
 * The route pattern matcher of the tracker (contract rule C42).
 *
 * `matchPath` is a pure function: an ordered route pattern list and a path
 * go in, and the stored path form comes out. Issue #104 builds the same
 * rule in Java for the server. The two matchers must give the same result
 * for the same list and the same path.
 */

/**
 * The set of a `*` segment. A segment outside this set makes the whole
 * path `/other` (contract rule C42).
 */
const STAR_SEGMENT_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2}){0,79}$/;

/** The stored path has a maximum of 150 bytes in UTF-8 (contract rule C42). */
const MAX_PATH_BYTES = 150;

const OTHER = '/other';

const textEncoder = new TextEncoder();

/**
 * Matches `pathname` against the ordered `routes` list, and gives the
 * stored path form.
 *
 * The function gives `/other` for a path with no matching pattern, for a
 * path with an empty segment, a `.` segment, or a `..` segment, for a bad
 * `*` segment of a matching pattern, and for a result above 150 bytes. It
 * never decodes a `%` escape.
 */
export function matchPath(routes: readonly string[], pathname: string): string {
  const pathSegments = splitPathSegments(pathname);
  if (pathSegments === null) {
    return OTHER;
  }
  for (const route of routes) {
    const routeSegments = splitRouteSegments(route);
    const result = matchOneRoute(routeSegments, pathSegments);
    if (result !== null) {
      return withinByteLimit(result) ? result : OTHER;
    }
  }
  return OTHER;
}

/**
 * Splits a normalized path into its segments. Gives `null` for an empty
 * segment, a `.` segment, or a `..` segment.
 */
function splitPathSegments(pathname: string): string[] | null {
  const normalized = removeTrailingSlash(pathname);
  if (normalized === '/') {
    return [];
  }
  const segments = normalized.slice(1).split('/');
  for (const segment of segments) {
    if (segment === '' || segment === '.' || segment === '..') {
      return null;
    }
  }
  return segments;
}

/** Removes one trailing slash from a path, but never from the root path. */
function removeTrailingSlash(pathname: string): string {
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1);
  }
  return pathname;
}

function splitRouteSegments(route: string): string[] {
  return route === '/' ? [] : route.slice(1).split('/');
}

/**
 * Tries one route pattern against the path segments.
 *
 * Gives `null` when the route does not match: a different segment count,
 * or a literal segment that does not equal the path segment. Gives the
 * stored path, or `/other` for a bad `*` segment, when the route matches.
 */
function matchOneRoute(routeSegments: string[], pathSegments: string[]): string | null {
  if (routeSegments.length !== pathSegments.length) {
    return null;
  }
  const resultSegments: string[] = [];
  for (let index = 0; index < routeSegments.length; index += 1) {
    const routeSegment = routeSegments[index] as string;
    const pathSegment = pathSegments[index] as string;
    if (routeSegment === '*') {
      if (!STAR_SEGMENT_PATTERN.test(pathSegment)) {
        return OTHER;
      }
      resultSegments.push(pathSegment);
    } else if (routeSegment.length > 1 && routeSegment.startsWith(':')) {
      resultSegments.push(routeSegment);
    } else if (equalsIgnoreAsciiCase(routeSegment, pathSegment)) {
      resultSegments.push(routeSegment);
    } else {
      return null;
    }
  }
  return resultSegments.length === 0 ? '/' : `/${resultSegments.join('/')}`;
}

/** Compares two strings, ignoring the case of an ASCII letter only. */
function equalsIgnoreAsciiCase(first: string, second: string): boolean {
  if (first.length !== second.length) {
    return false;
  }
  for (let index = 0; index < first.length; index += 1) {
    if (toAsciiLowerCode(first.charCodeAt(index)) !== toAsciiLowerCode(second.charCodeAt(index))) {
      return false;
    }
  }
  return true;
}

function toAsciiLowerCode(code: number): number {
  return code >= 65 && code <= 90 ? code + 32 : code;
}

function withinByteLimit(path: string): boolean {
  return textEncoder.encode(path).length <= MAX_PATH_BYTES;
}
