import type { AppContext } from '../context.js';
import type { Metrics } from '../modules/health/health.routes.js';
import { deleteExpiredMessages } from '../modules/messages/messages.repo.js';
import { deleteOldPairings, expirePairings } from '../modules/pairing/pairing.repo.js';

const DAY_MS = 24 * 3_600_000;

/** Removes expired ciphertext and stale pairing sessions. Safe to run at any time, repeatedly. */
export async function runExpiry(
  ctx: AppContext,
  metrics: Metrics,
): Promise<{ messages: number; pairings: number }> {
  const now = ctx.now();
  const messages = await deleteExpiredMessages(ctx.db, now);
  const pairings = await expirePairings(ctx.db, now);
  await deleteOldPairings(ctx.db, new Date(now.getTime() - DAY_MS));
  metrics.expiredMessages += messages;
  metrics.expiredPairings += pairings;
  return { messages, pairings };
}

export function startExpiryJob(ctx: AppContext, metrics: Metrics, intervalMs = 60_000): () => void {
  const timer = setInterval(() => {
    runExpiry(ctx, metrics)
      .then((r) => {
        if (r.messages || r.pairings) ctx.logger.info(r, 'expired data removed');
      })
      .catch((err: unknown) => ctx.logger.error({ err: { name: (err as Error).name } }, 'expiry job failed'));
  }, intervalMs);
  timer.unref();
  return () => clearInterval(timer);
}
