/**
 * The request batch splitter of the tracker (issue #36, step 6).
 *
 * The tracker measures the encoded body of a batch as UTF-8 bytes, not as
 * a string length. It splits a batch whose body passes the byte limit,
 * and it also keeps each batch below the entry-count limit of contract
 * rule C17. Each request body stays below the 16 KB limit of contract
 * rule C18, with a safety margin.
 */

/** One click entry of the ingest request body (contract rule C13). */
export interface ClickPayload {
  readonly element: string;
  readonly ageMs: number;
  readonly path?: string;
}

/** One request holds a maximum of 50 clicks (contract rule C17). */
export const MAX_BATCH_ENTRIES = 50;

/**
 * One request body stays below this many UTF-8 bytes (issue #36, step 6).
 * The value gives a safety margin under the 16 KB body limit of contract
 * rule C18.
 */
export const MAX_BODY_BYTES = 15000;

const textEncoder = new TextEncoder();

/**
 * Splits `clicks` into request-sized groups for the session `sessionId`.
 *
 * Each group holds a maximum of `MAX_BATCH_ENTRIES` clicks. The encoded
 * body of each group stays below `MAX_BODY_BYTES`, except when one single
 * click already passes the byte limit on its own; that click still forms
 * its own group, because this function never drops a click.
 */
export function splitIntoRequestBatches(
  sessionId: string,
  clicks: readonly ClickPayload[],
): ClickPayload[][] {
  const batches: ClickPayload[][] = [];
  let current: ClickPayload[] = [];
  for (const click of clicks) {
    const candidate = [...current, click];
    const tooManyEntries = candidate.length > MAX_BATCH_ENTRIES;
    const tooManyBytes = encodedBodyBytes(sessionId, candidate) > MAX_BODY_BYTES;
    if (current.length > 0 && (tooManyEntries || tooManyBytes)) {
      batches.push(current);
      current = [click];
    } else {
      current = candidate;
    }
  }
  if (current.length > 0) {
    batches.push(current);
  }
  return batches;
}

/** The UTF-8 byte size of the ingest request body of `sessionId` and `clicks`. */
export function encodedBodyBytes(sessionId: string, clicks: readonly ClickPayload[]): number {
  return textEncoder.encode(JSON.stringify({ sessionId, clicks })).length;
}
