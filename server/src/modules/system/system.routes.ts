import { timingSafeEqual } from 'node:crypto';
import { Router } from 'express';
import type { AppContext } from '../../context.js';
import { runExpiry } from '../../jobs/expiry.js';
import { errors } from '../../middleware/errors.js';
import type { Metrics } from '../health/health.routes.js';

/** What a client needs to know about this relay before it signs in. Contains nothing secret. */
export function infoRouter(ctx: AppContext): Router {
  const router = Router();
  router.get('/info', (_req, res) => {
    res.json({
      apiVersion: 1,
      /** "websocket": connect to /v1/ws for hints. "poll": ask for pending messages instead. */
      realtime: ctx.config.REALTIME,
      pollIntervalSeconds: ctx.config.POLL_INTERVAL_SECONDS,
    });
  });
  return router;
}

/**
 * Scheduled clean-up for platforms without a long-running process. Removes expired ciphertext
 * and stale pairing sessions; returns counts only. Exists only when CRON_SECRET is configured and
 * answers 404 to anyone without it, so its presence is not revealed.
 */
export function cronRouter(ctx: AppContext, metrics: Metrics): Router {
  const router = Router();
  const secret = ctx.config.CRON_SECRET;
  if (secret === '') return router;
  const expected = Buffer.from(`Bearer ${secret}`);
  router.get('/internal/cron/expire', async (req, res, next) => {
    const given = Buffer.from(req.headers.authorization ?? '');
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) return next(errors.notFound());
    res.json(await runExpiry(ctx, metrics));
  });
  return router;
}
