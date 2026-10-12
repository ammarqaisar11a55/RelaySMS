import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

/** 256-bit random secret, URL-safe. */
export function generateSecret(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

/**
 * Secrets issued by the relay are high-entropy random values, so a single SHA-256 is a sound
 * way to store them: there is nothing to brute-force, and a database leak reveals no usable token.
 */
export function hashSecret(secret: string): Buffer {
  return createHash('sha256').update(secret, 'utf8').digest();
}

export function secretMatches(secret: string, expectedHash: Buffer): boolean {
  const actual = hashSecret(secret);
  return actual.length === expectedHash.length && timingSafeEqual(actual, expectedHash);
}

export function constantTimeEquals(a: string, b: string): boolean {
  return timingSafeEqual(hashSecret(a), hashSecret(b));
}

/** Bearer credential format: "<deviceId>.<secret>". */
export function formatDeviceToken(deviceId: string, secret: string): string {
  return `${deviceId}.${secret}`;
}

export function parseDeviceToken(token: string): { deviceId: string; secret: string } | null {
  const dot = token.indexOf('.');
  if (dot <= 0) return null;
  const deviceId = token.slice(0, dot);
  const secret = token.slice(dot + 1);
  if (!UUID_RE.test(deviceId) || secret.length < 32 || secret.length > 128) return null;
  return { deviceId, secret };
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
