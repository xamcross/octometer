// The minimal Cloudflare Worker types that "src/worker.ts" needs (issue
// #291). The project adds no "@cloudflare/workers-types" dependency; each
// shape below matches the one method or the one field that the handler
// calls, from the Cloudflare Workers documentation (the D1 and the
// Fetch Handler pages, read on 2026-10-01).

interface D1Result<T = unknown> {
  results?: T[];
  success: boolean;
  meta: Record<string, unknown>;
}

interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first<T = unknown>(column?: string): Promise<T | null>;
  run<T = unknown>(): Promise<D1Result<T>>;
  all<T = unknown>(): Promise<D1Result<T>>;
}

interface D1Database {
  prepare(query: string): D1PreparedStatement;
}

export interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

// The bindings and the variables of "site/wrangler.jsonc" (docs/site.md).
export interface Env {
  WAITLIST: D1Database;
  MAIL_API_KEY?: string;
  SITE_URL: string;
  MAIL_MODE?: string;
}
