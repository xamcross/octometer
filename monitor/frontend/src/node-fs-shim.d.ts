// Gives a spec file a small, local type for the one Node function that
// reads a CSS file as text. The project adds no full `@types/node`
// package; this file names only the function a spec uses.
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
}
