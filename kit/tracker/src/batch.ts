/**
 * The request batch splitter of the tracker (issue #36, step 6).
 *
 * The tracker measures the encoded body of a batch as UTF-8 bytes, not as
 * a string length. It splits a batch whose body passes the byte limit,
 * and it also keeps each batch below the entry-count limit of contract
 * rule C17. Each request body stays below the 16 KB limit of contract
 * rule C18.
 *
 * The `element` limit of rule C4 and the `path` limit of rule C39 stop
 * one click from passing the byte limit on its own. The splitter still
 * guards for this case: see `splitIntoRequestBatches`.
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
 * body of each group stays below `MAX_BODY_BYTES`. The function measures
 * each click one time, so it runs in linear time (MINOR 4 of the
 * reliability review).
 *
 * The contract limits `element` and `path`, so one click cannot pass
 * `MAX_BODY_BYTES` on its own. This case still needs a guard. The
 * function drops such a click instead of an oversized request. It writes
 * one console warning for the call, with no click text in it.
 */
export function splitIntoRequestBatches(
  sessionId: string,
  clicks: readonly ClickPayload[],
): ClickPayload[][] {
  const emptyBodyBytes = encodedBodyBytes(sessionId, []);
  const batches: ClickPayload[][] = [];
  let current: ClickPayload[] = [];
  let currentBytes = emptyBodyBytes;
  let warnedAboutOneClick = false;
  for (const click of clicks) {
    const clickBytes = textEncoder.encode(JSON.stringify(click)).length;
    if (emptyBodyBytes + clickBytes > MAX_BODY_BYTES) {
      if (!warnedAboutOneClick) {
        warnedAboutOneClick = true;
        console.warn(
          'octometer: the tracker drops one click. Its own request body passes the byte limit.',
        );
      }
      continue;
    }
    // A comma joins this click to an earlier click of the same group.
    const separatorBytes = current.length > 0 ? 1 : 0;
    const candidateBytes = currentBytes + separatorBytes + clickBytes;
    const tooManyEntries = current.length + 1 > MAX_BATCH_ENTRIES;
    const tooManyBytes = candidateBytes > MAX_BODY_BYTES;
    if (current.length > 0 && (tooManyEntries || tooManyBytes)) {
      batches.push(current);
      current = [click];
      currentBytes = emptyBodyBytes + clickBytes;
    } else {
      current.push(click);
      currentBytes = candidateBytes;
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
