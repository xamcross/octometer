// The address rule of issue #291: lower case and trimmed, one "@", no
// space, a maximum of 254 characters.

const MAX_ADDRESS_LENGTH = 254;
const ADDRESS_PATTERN = /^[^\s@]+@[^\s@]+$/;

export function normalizeAddress(rawAddress: string): string {
  return rawAddress.trim().toLowerCase();
}

export function isValidAddress(address: string): boolean {
  if (address.length === 0 || address.length > MAX_ADDRESS_LENGTH) {
    return false;
  }
  const atCount = address.split('@').length - 1;
  if (atCount !== 1) {
    return false;
  }
  return ADDRESS_PATTERN.test(address);
}
