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

/**
 * The character set of a route pattern segment (contract rule C39). A
 * literal segment, a `:name` segment, and the wildcard segment `*` each
 * pass this set on their own text. Rule C39 also allows a well-formed
 * escape `%[0-9A-Fa-f]{2}` inside a segment.
 */
const SEGMENT_CHAR_PATTERN = /^(?:[A-Za-z0-9._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})+$/;

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
 *
 * This function assumes a well-formed `routes` list. Call `prepareRoutes`
 * first for a list that an app builds from its own configuration, and
 * call `matchPreparedPath` with its result.
 */
export function matchPath(routes: readonly string[], pathname: string): string {
  const pathSegments = splitPathSegments(pathname);
  if (pathSegments === null) {
    return OTHER;
  }
  for (const route of routes) {
    const result = matchOneRoute(splitRouteSegments(route), pathSegments);
    if (result !== null) {
      return withinByteLimit(result) ? result : OTHER;
    }
  }
  return OTHER;
}

/** One route pattern of `routes`, checked and split into segments one time. */
export interface PreparedRoute {
  readonly pattern: string;
  readonly segments: readonly string[];
}

/** The result of `prepareRoutes`: the good entries, and the index of each invalid entry. */
export interface PreparedRoutes {
  readonly routes: readonly PreparedRoute[];
  readonly invalidIndexes: readonly number[];
}

/**
 * Checks and splits each entry of `routes` one time, so a later click
 * reuses the split form instead of splitting the whole list again.
 *
 * This function marks an entry as invalid in three cases. The entry is
 * not a string, or it is an empty string. The entry does not start with
 * `/`. A segment of the entry holds a character outside the set of rule
 * C39. The result holds the index of each invalid entry, in list order.
 * This function writes no console warning. The caller builds the
 * warning text and applies the fail-closed rule.
 */
export function prepareRoutes(routes: readonly unknown[]): PreparedRoutes {
  const prepared: PreparedRoute[] = [];
  const invalidIndexes: number[] = [];
  routes.forEach((route, index) => {
    if (isValidRoutePattern(route)) {
      prepared.push({ pattern: route, segments: splitRouteSegments(route) });
    } else {
      invalidIndexes.push(index);
    }
  });
  return { routes: prepared, invalidIndexes };
}

/**
 * Matches `pathname` against a `routes` list that `prepareRoutes` already
 * checked and split. This is the fast form: one click splits `pathname`
 * only, not the whole route list again.
 */
export function matchPreparedPath(routes: readonly PreparedRoute[], pathname: string): string {
  const pathSegments = splitPathSegments(pathname);
  if (pathSegments === null) {
    return OTHER;
  }
  for (const route of routes) {
    const result = matchOneRoute(route.segments, pathSegments);
    if (result !== null) {
      return withinByteLimit(result) ? result : OTHER;
    }
  }
  return OTHER;
}

/**
 * Checks one route pattern: a non-empty string, starting with `/`, with
 * each segment inside the character set of rule C39. The segment `*` and
 * a `:name` segment pass this set on their own text too.
 */
function isValidRoutePattern(route: unknown): route is string {
  if (typeof route !== 'string' || route.length === 0 || !route.startsWith('/')) {
    return false;
  }
  const segments = splitRouteSegments(route);
  return segments.every((segment) => segment.length > 0 && SEGMENT_CHAR_PATTERN.test(segment));
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
function matchOneRoute(routeSegments: readonly string[], pathSegments: readonly string[]): string | null {
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
