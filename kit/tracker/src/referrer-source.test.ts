import { describe, expect, it } from 'vitest';
import {
  computeSessionStartReferrerHost,
  matchSourceHost,
  REFERRER_HOST_OTHER,
  REFERRER_SOURCE_LIST,
} from './referrer-source.js';

/**
 * Finds `contract/README.md`, from the current working folder upward. A
 * test run of `kit/tracker` can start with a different working folder,
 * so this function walks up, the same way the Java test helper
 * `ContractReadme` does for the server side.
 */
async function readContractReadme(): Promise<string> {
  const { readFileSync, existsSync } = await import('node:fs');
  const { resolve, dirname } = await import('node:path');
  const { cwd } = await import('node:process');
  let dir = cwd();
  for (let depth = 0; depth < 10; depth += 1) {
    const candidate = resolve(dir, 'contract', 'README.md');
    if (existsSync(candidate)) {
      return readFileSync(candidate, 'utf8');
    }
    const parent = dirname(dir);
    if (parent === dir) {
      break;
    }
    dir = parent;
  }
  throw new Error('The file contract/README.md is absent above the working folder.');
}

const APP_ORIGIN = 'https://app.example';

describe('matchSourceHost', () => {
  it('matches google.com exactly', () => {
    expect(matchSourceHost('google.com')).toBe('google.com');
  });

  it('matches bing.com exactly', () => {
    expect(matchSourceHost('bing.com')).toBe('bing.com');
  });

  it('matches a subdomain of bing.com', () => {
    expect(matchSourceHost('www.bing.com')).toBe('bing.com');
  });

  it('matches a google country host, the same way as a bing country host', () => {
    expect(matchSourceHost('www.google.de')).toBe('google.com');
    expect(matchSourceHost('www.google.co.uk')).toBe('google.com');
    expect(matchSourceHost('www.bing.de')).toBe('bing.com');
    expect(matchSourceHost('www.bing.co.uk')).toBe('bing.com');
  });

  it('gives the literal other for a host that only looks like google', () => {
    expect(matchSourceHost('attacker.google.top')).toBe(REFERRER_HOST_OTHER);
    expect(matchSourceHost('google.zip')).toBe(REFERRER_HOST_OTHER);
    expect(matchSourceHost('google.com.evil.example')).toBe(REFERRER_HOST_OTHER);
  });

  it('gives the literal other for a host outside the source list', () => {
    expect(matchSourceHost('example.org')).toBe(REFERRER_HOST_OTHER);
  });
});

describe('computeSessionStartReferrerHost', () => {
  it('gives google.com for a referrer https://www.google.co.uk/search?q=x', () => {
    expect(
      computeSessionStartReferrerHost('https://www.google.co.uk/search?q=x', APP_ORIGIN),
    ).toBe('google.com');
  });

  it('gives bing.com for a referrer https://www.bing.com/', () => {
    expect(computeSessionStartReferrerHost('https://www.bing.com/', APP_ORIGIN)).toBe('bing.com');
  });

  it('gives the literal other for a referrer https://example.org/page', () => {
    expect(computeSessionStartReferrerHost('https://example.org/page', APP_ORIGIN)).toBe(
      REFERRER_HOST_OTHER,
    );
  });

  it('matches an upper-case host, in lower case', () => {
    expect(computeSessionStartReferrerHost('https://WWW.GOOGLE.COM/x', APP_ORIGIN)).toBe(
      'google.com',
    );
  });

  it('sends no part of the query string of the address', () => {
    const result = computeSessionStartReferrerHost(
      'https://www.google.com/search?q=secret&user=name',
      APP_ORIGIN,
    );
    expect(result).toBe('google.com');
  });

  it('gives undefined for an empty referrer', () => {
    expect(computeSessionStartReferrerHost('', APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for a referrer with a scheme other than http or https', () => {
    expect(computeSessionStartReferrerHost('ftp://files.example/report', APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for the origin of the app', () => {
    expect(computeSessionStartReferrerHost(`${APP_ORIGIN}/dashboard`, APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for an IPv4 literal', () => {
    expect(computeSessionStartReferrerHost('http://192.168.1.1/', APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for an IPv6 literal', () => {
    expect(computeSessionStartReferrerHost('http://[::1]/', APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for a host without a dot', () => {
    expect(computeSessionStartReferrerHost('https://intranet/page', APP_ORIGIN)).toBeUndefined();
  });

  it('gives undefined for a referrer that fails to parse as a URL', () => {
    expect(computeSessionStartReferrerHost('not a url', APP_ORIGIN)).toBeUndefined();
  });
});

describe('the source list', () => {
  it('holds exactly two entries', () => {
    expect(REFERRER_SOURCE_LIST).toEqual(['google.com', 'bing.com']);
  });

  it('matches the source list of contract/README.md', async () => {
    const readme = await readContractReadme();
    // The raw markdown wraps this sentence across two source lines. The
    // pattern allows a line break plus the indent in place of one space.
    const pattern =
      /The\s+source\s+list\s+has\s+two\s+entries:\s*`([a-z0-9.-]+)`\s+and\s+`([a-z0-9.-]+)`\./;
    const match = pattern.exec(readme);
    expect(match, 'the contract text must state the source list with two entries').not.toBeNull();
    const contractList = [match?.[1], match?.[2]].sort();
    expect(contractList).toEqual([...REFERRER_SOURCE_LIST].sort());
  });
});
