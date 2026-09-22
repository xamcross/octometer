import { afterEach, describe, expect, it, vi } from 'vitest';
import { encodedBodyBytes, MAX_BATCH_ENTRIES, MAX_BODY_BYTES, splitIntoRequestBatches, type ClickPayload } from './batch.js';

const SESSION_ID = '0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11';

function clickOfSize(element: string, pathBytes: number): ClickPayload {
  return { element, ageMs: 1000, path: `/${'a'.repeat(pathBytes - 1)}` };
}

describe('splitIntoRequestBatches', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });


  it('gives one batch with every click for a small queue', () => {
    const clicks: ClickPayload[] = [
      { element: 'save', ageMs: 100 },
      { element: 'open', ageMs: 200 },
    ];

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches).toEqual([clicks]);
  });

  it('gives an empty array for an empty click list', () => {
    expect(splitIntoRequestBatches(SESSION_ID, [])).toEqual([]);
  });

  it('splits at the 50-entry limit of contract rule C17, also with a small body', () => {
    const clicks: ClickPayload[] = Array.from({ length: 120 }, (_, index) => ({
      element: `e${index}`,
      ageMs: 100,
    }));

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches).toHaveLength(3);
    expect(batches[0]).toHaveLength(MAX_BATCH_ENTRIES);
    expect(batches[1]).toHaveLength(MAX_BATCH_ENTRIES);
    expect(batches[2]).toHaveLength(20);
  });

  it('splits a batch whose encoded body passes 15 000 bytes, each request below that limit', () => {
    // Each click below the 50-entry cap, but its own bytes are large, so
    // the byte limit binds before the entry-count limit does.
    const clicks: ClickPayload[] = Array.from({ length: 20 }, (_, index) =>
      clickOfSize(`e${index}`, 1000),
    );
    // 20 clicks of about 1000 bytes each give a body well above 15 000 bytes.
    expect(encodedBodyBytes(SESSION_ID, clicks)).toBeGreaterThan(MAX_BODY_BYTES);

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches.length).toBeGreaterThan(1);
    const allClicks = batches.flat();
    expect(allClicks).toEqual(clicks);
    for (const batch of batches) {
      expect(encodedBodyBytes(SESSION_ID, batch)).toBeLessThan(MAX_BODY_BYTES);
    }
  });

  it('keeps 50 entries with a 150-byte path each in one request, below the 16 KB limit of rule C18', () => {
    const clicks: ClickPayload[] = Array.from({ length: 50 }, (_, index) =>
      clickOfSize(`e${index}`, 150),
    );

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches).toHaveLength(1);
    expect(encodedBodyBytes(SESSION_ID, batches[0] ?? [])).toBeLessThan(16 * 1024);
  });

  it('drops one click that alone passes the byte limit, with one console warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const hugeClick = clickOfSize('e0', MAX_BODY_BYTES + 500);
    const clicks: ClickPayload[] = [hugeClick, { element: 'e1', ageMs: 50 }];

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches.flat()).toEqual([{ element: 'e1', ageMs: 50 }]);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.mock.calls[0]?.[0])).not.toContain('e0');
  });

  it('writes only one warning for one call, also with two oversized clicks', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const clicks: ClickPayload[] = [
      clickOfSize('e0', MAX_BODY_BYTES + 500),
      clickOfSize('e1', MAX_BODY_BYTES + 500),
      { element: 'e2', ageMs: 50 },
    ];

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches.flat()).toEqual([{ element: 'e2', ageMs: 50 }]);
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('keeps the order of the clicks across the batches', () => {
    const clicks: ClickPayload[] = Array.from({ length: 75 }, (_, index) => ({
      element: `e${index}`,
      ageMs: index,
    }));

    const batches = splitIntoRequestBatches(SESSION_ID, clicks);

    expect(batches.flat()).toEqual(clicks);
  });
});

describe('encodedBodyBytes', () => {
  it('measures the UTF-8 byte length of the request body, not the string length', () => {
    // "café" has 4 UTF-16 code units but 5 UTF-8 bytes (the é takes 2 bytes).
    const clicks: ClickPayload[] = [{ element: 'save', ageMs: 100, path: '/café' }];
    const stringLength = JSON.stringify({ sessionId: SESSION_ID, clicks }).length;

    expect(encodedBodyBytes(SESSION_ID, clicks)).toBe(stringLength + 1);
  });
});
