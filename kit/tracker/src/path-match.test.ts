import { describe, expect, it } from 'vitest';
import { matchPath } from './path-match.js';

/**
 * The route list of the acceptance criteria of issue #106.
 */
const ROUTES = ['/', '/articles', '/articles/*', '/history/:id', '/ovdp/rates'];

/**
 * The shared test table for the path matcher (contract rule C42). Issue
 * #104 builds the same matcher in Java for the server. Issue #104 must use
 * the same case list, because the contract has no dedicated JSON file for
 * this table (the issue that adds it names no such file).
 */
const CASES: Array<{ name: string; routes: readonly string[]; path: string; result: string }> = [
  { name: 'the root path', routes: ROUTES, path: '/', result: '/' },
  {
    name: 'a named segment masks the real value',
    routes: ROUTES,
    path: '/history/42',
    result: '/history/:id',
  },
  {
    name: 'a star segment keeps a valid real value',
    routes: ROUTES,
    path: '/articles/ovdp-2026',
    result: '/articles/ovdp-2026',
  },
  {
    name: 'a trailing slash goes before the match, and the match ignores the letter case',
    routes: ROUTES,
    path: '/ARTICLES/',
    result: '/articles',
  },
  {
    name: 'a path with no matching pattern',
    routes: ROUTES,
    path: '/tokens/abc',
    result: '/other',
  },
  {
    name: 'a path with a different segment count than each pattern',
    routes: ROUTES,
    path: '/articles/a/b',
    result: '/other',
  },
  {
    name: 'an empty segment',
    routes: ROUTES,
    path: '/articles//x',
    result: '/other',
  },
  {
    name: 'a dot segment',
    routes: ROUTES,
    path: '/articles/.',
    result: '/other',
  },
  {
    name: 'a dot-dot segment',
    routes: ROUTES,
    path: '/articles/..',
    result: '/other',
  },
  {
    name: 'a star segment of 80 characters stays valid',
    routes: ROUTES,
    path: `/articles/${'a'.repeat(80)}`,
    result: `/articles/${'a'.repeat(80)}`,
  },
  {
    name: 'a star segment of 81 characters',
    routes: ROUTES,
    path: `/articles/${'a'.repeat(81)}`,
    result: '/other',
  },
  {
    name: 'a star segment with a raw space',
    routes: ROUTES,
    path: '/articles/a b',
    result: '/other',
  },
  {
    name: 'a star segment that keeps a well-formed escape, undecoded',
    routes: ROUTES,
    path: '/articles/a%20b',
    result: '/articles/a%20b',
  },
  {
    name: 'a literal segment match ignores the letter case, without a trailing slash',
    routes: ROUTES,
    path: '/OvDp/RaTeS',
    result: '/ovdp/rates',
  },
];

describe('matchPath', () => {
  it.each(CASES)('$name: $path -> $result', ({ routes, path, result }) => {
    expect(matchPath(routes, path)).toBe(result);
  });

  it('gives /other for an empty route list', () => {
    expect(matchPath([], '/')).toBe('/other');
  });

  it('gives /other for a percent-encoded path above 150 bytes, at a full route match', () => {
    // Each `%D0%B0` is one well-formed escape (3 bytes of a 2-byte glyph in
    // the source text, 6 ASCII bytes on the wire). 30 of them plus the
    // first character give a star segment of 181 bytes: above the 150-byte
    // cap of rule C42, so the whole path becomes /other.
    const longSegment = 'a' + '%D0%B0'.repeat(30);
    const path = `/articles/${longSegment}`;
    expect(matchPath(ROUTES, path)).toBe('/other');
  });

  it('keeps a result of exactly 150 bytes', () => {
    // Route "/a/*/*" gives a result "/a/" + star1 + "/" + star2: 4 bytes
    // plus the two star segments. 80 + 66 = 146, so the full result is
    // exactly 150 bytes.
    const star1 = 'a'.repeat(80);
    const star2 = 'b'.repeat(66);
    const result = matchPath(['/a/*/*'], `/a/${star1}/${star2}`);
    expect(result).toBe(`/a/${star1}/${star2}`);
    expect(new TextEncoder().encode(result).length).toBe(150);
  });

  it('gives /other for a result of 151 bytes, one byte above the cap', () => {
    const star1 = 'a'.repeat(80);
    const star2 = 'b'.repeat(67);
    expect(matchPath(['/a/*/*'], `/a/${star1}/${star2}`)).toBe('/other');
  });

  it('never decodes a percent escape before the match', () => {
    // The literal text of a route pattern never holds a percent escape, so
    // this case only proves that the matcher passes the raw segment
    // through to a star result without a decode step.
    const result = matchPath(['/articles/*'], '/articles/e%CC%81t%C3%A9');
    expect(result).toBe('/articles/e%CC%81t%C3%A9');
  });

  it('uses the text of the pattern for a literal segment, never the text of the input', () => {
    const result = matchPath(['/Articles'], '/articles');
    expect(result).toBe('/Articles');
  });
});
