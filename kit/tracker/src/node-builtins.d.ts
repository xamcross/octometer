/**
 * Local ambient types for the few Node built-ins that one test file needs
 * (issue #108): the contract-list test of `referrer-source.test.ts` reads
 * `contract/README.md` at test time. The tracker package needs no new
 * dependency (`@types/node`) for this one need, because a test file runs
 * under real Node through Vitest either way.
 */
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
  export function existsSync(path: string): boolean;
}

declare module 'node:path' {
  export function resolve(...parts: string[]): string;
  export function dirname(path: string): string;
}

declare module 'node:process' {
  export function cwd(): string;
}
