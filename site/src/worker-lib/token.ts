// The token of issue #291: 32 random bytes, base64url, with no padding.
// The same encoding also hides the address behind a hash for the
// "attempts" table (the brief review BLOCKER M1: the column holds the
// hash, never the address).

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function createToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return toBase64Url(bytes);
}

export async function hashAddress(address: string): Promise<string> {
  const data = new TextEncoder().encode(address);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return toBase64Url(new Uint8Array(digest));
}
