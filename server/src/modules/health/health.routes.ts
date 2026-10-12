import { Router } from 'express';
import type { AppContext } from '../../context.js';
import { ipRateLimit } from '../../middleware/rateLimit.js';

/** Process-level counters. Contain no identifiers and no message data. */
export interface Metrics {
  startedAt: Date;
  expiredMessages: number;
  expiredPairings: number;
}

export function healthRouter(ctx: AppContext, metrics: Metrics): Router {
  const router = Router();

  /** Liveness: the process is up. */
  router.get('/healthz', (_req, res) => {
    res.json({ status: 'ok' });
  });

  /** Readiness: the database answers. */
  router.get('/readyz', async (_req, res) => {
    try {
      await ctx.db.query('SELECT 1');
      res.json({ status: 'ready' });
    } catch {
      res.status(503).json({ status: 'unavailable' });
    }
  });

  /**
   * Aggregate operational numbers only. Unlike the two probes above it queries the tables, so it
   * is rate limited per client address.
   */
  const metricsLimit = ipRateLimit(ctx.config.RATE_LIMIT_WINDOW_SECONDS, ctx.config.RATE_LIMIT_MAX);
  router.get('/metrics', metricsLimit, async (_req, res) => {
    const q = await ctx.db.query<{ devices: string; links: string; pending: string }>(
      `SELECT (SELECT COUNT(*) FROM relay_devices WHERE revoked_at IS NULL) AS devices,
              (SELECT COUNT(*) FROM relay_links) AS links,
              (SELECT COUNT(*) FROM relay_messages WHERE acked_at IS NULL) AS pending`,
    );
    const row = q.rows[0];
    res.json({
      uptimeSeconds: Math.round((ctx.now().getTime() - metrics.startedAt.getTime()) / 1000),
      activeDevices: Number(row?.devices ?? 0),
      links: Number(row?.links ?? 0),
      pendingMessages: Number(row?.pending ?? 0),
      openConnections: ctx.hub.connectionCount(),
      expiredMessages: metrics.expiredMessages,
      expiredPairings: metrics.expiredPairings,
    });
  });

  return router;
}
