import type { IncomingMessage } from 'node:http';

/**
 * The client address of a raw HTTP request, using the same rule as Express's numeric
 * "trust proxy" setting: with n trusted proxies, the client is the n-th address counted back from
 * the socket through X-Forwarded-For. With n = 0 the header is ignored, so it cannot be spoofed.
 */
export function clientAddress(req: IncomingMessage, trustedProxies: number): string {
  const socketAddress = req.socket.remoteAddress ?? 'unknown';
  if (trustedProxies <= 0) return socketAddress;
  const header = req.headers['x-forwarded-for'];
  const forwarded = (Array.isArray(header) ? header.join(',') : (header ?? ''))
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
  const chain = [socketAddress, ...forwarded.reverse()];
  return chain[Math.min(trustedProxies, chain.length - 1)] ?? socketAddress;
}

const MAX_TRACKED_KEYS = 50_000;

/**
 * Fixed-window counter for requests that never reach Express, i.e. WebSocket upgrades.
 *
 * @returns a function that records one attempt for [key] and says whether it is allowed.
 */
export function createAttemptLimiter(
  windowSeconds: number,
  max: number,
  now: () => number = Date.now,
): (key: string) => boolean {
  const windowMs = windowSeconds * 1000;
  const counters = new Map<string, { start: number; count: number }>();
  return (key) => {
    const t = now();
    if (counters.size >= MAX_TRACKED_KEYS) {
      for (const [k, v] of counters) if (t - v.start >= windowMs) counters.delete(k);
      // Still full of live windows: refuse new keys rather than grow without bound.
      if (counters.size >= MAX_TRACKED_KEYS && !counters.has(key)) return false;
    }
    const entry = counters.get(key);
    if (!entry || t - entry.start >= windowMs) {
      counters.set(key, { start: t, count: 1 });
      return true;
    }
    entry.count += 1;
    return entry.count <= max;
  };
}
