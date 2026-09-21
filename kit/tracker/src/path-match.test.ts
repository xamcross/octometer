import { describe, expect, it } from 'vitest';
import { matchPath, matchPreparedPath, prepareRoutes } from './path-match.js';
import cases from '../../../contract/examples/C42-path-match-cases.json' with { type: 'json' };

/**
 * The shared test table for the path matcher (contract rule C42), read
 * from `contract/examples/C42-path-match-cases.json`. Issue #104 builds
 * the same matcher in Java for the server, and its test reads the same
 * file, so one new case reaches both matchers at once.
 */
interface MatchCase {
  name: string;
  rule: string;
  routes: readonly string[];
  path: string;
  result: string;
}

const CASES = cases as MatchCase[];

describe('matchPath', () => {
  it.each(CASES)('$name: $path -> $result', ({ routes, path, result }) => {
    expect(matchPath(routes, path)).toBe(result);
  });
});

describe('prepareRoutes and matchPreparedPath', () => {
  it('gives the same result as matchPath for each shared case', () => {
    for (const { routes, path, result } of CASES) {
      const { routes: prepared } = prepareRoutes(routes);
      expect(matchPreparedPath(prepared, path)).toBe(result);
    }
  });

  it('drops an entry that is not a string, with one warning that names its index', () => {
    const { routes, warnings } = prepareRoutes(['/articles', 42, '/history/:id']);
    expect(routes.map((route) => route.pattern)).toEqual(['/articles', '/history/:id']);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain('entry 1');
  });

  it('drops an entry with no leading slash, with one warning', () => {
    const { routes, warnings } = prepareRoutes(['articles']);
    expect(routes).toHaveLength(0);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain('entry 0');
  });

  it('drops an entry whose literal segment holds a double quote', () => {
    const { routes, warnings } = prepareRoutes(['/articles/"']);
    expect(routes).toHaveLength(0);
    expect(warnings).toHaveLength(1);
  });

  it('drops an entry whose literal segment holds a question mark or a hash', () => {
    const { routes, warnings } = prepareRoutes(['/a?b', '/a#b']);
    expect(routes).toHaveLength(0);
    expect(warnings).toHaveLength(2);
  });

  it('keeps an entry whose literal segment holds an apostrophe, a valid C39 character', () => {
    const { routes, warnings } = prepareRoutes(["/it's-fine"]);
    expect(routes).toHaveLength(1);
    expect(warnings).toHaveLength(0);
  });

  it('drops a :name segment with a character outside the set of rule C39', () => {
    const { routes, warnings } = prepareRoutes(['/history/:<id>']);
    expect(routes).toHaveLength(0);
    expect(warnings).toHaveLength(1);
  });

  it('gives no warning when every entry is valid', () => {
    const { routes, warnings } = prepareRoutes(['/', '/articles', '/articles/*', '/history/:id']);
    expect(routes).toHaveLength(4);
    expect(warnings).toHaveLength(0);
  });

  it('keeps an entry whose literal segment holds a well-formed percent escape (rule C39)', () => {
    const { routes, warnings } = prepareRoutes(['/caf%C3%A9']);
    expect(routes.map((route) => route.pattern)).toEqual(['/caf%C3%A9']);
    expect(warnings).toHaveLength(0);
  });

  it('drops an entry with a bad percent escape', () => {
    const { routes, warnings } = prepareRoutes(['/a%zz']);
    expect(routes).toHaveLength(0);
    expect(warnings).toHaveLength(1);
  });
});
