import type { IncomingMessage, ServerResponse } from 'node:http';
import { createApp } from './app.js';
import { loadConfig } from './config/env.js';
import type { AppContext } from './context.js';
import { createPool } from './database/pool.js';
import { Hub } from './realtime/hub.js';
import { createLogger } from './security/logger.js';

/**
 * Entry point for serverless platforms (Vercel). One instance handles many requests, so the app
 * and its small connection pool are created once per instance and reused.
 *
 * Differences from the long-running server (src/server.ts):
 * - no WebSocket: devices poll (REALTIME defaults to "poll" here);
 * - no timer: expired data is removed by a scheduled request to /internal/cron/expire;
 * - migrations run in the build step (`npm run migrate`), not at start.
 */
type Handler = (req: IncomingMessage, res: ServerResponse) => void;

let handler: Handler | undefined;

function build(): Handler {
  const config = loadConfig({
    REALTIME: 'poll',
    TRUST_PROXY: '1',
    DB_POOL_MAX: '2',
    ...process.env,
  });
  const logger = createLogger(config.LOG_LEVEL);
  const db = createPool(config.DATABASE_URL, {
    schema: config.DATABASE_SCHEMA,
    max: config.DB_POOL_MAX,
    caCert: config.DATABASE_CA_CERT,
  });
  db.on('error', (err) => logger.error({ err: { name: err.name } }, 'database pool error'));
  // The hub is never attached to a server here: notifications are no-ops and presence comes
  // from each device's last request.
  const ctx: AppContext = { db, config, logger, hub: new Hub(), now: () => new Date() };
  return createApp(ctx, {
    startedAt: new Date(),
    expiredMessages: 0,
    expiredPairings: 0,
  }) as unknown as Handler;
}

export default function relay(req: IncomingMessage, res: ServerResponse): void {
  try {
    handler ??= build();
  } catch {
    // Configuration problems must not leak values; the build log names the variable.
    res.statusCode = 500;
    res.setHeader('Content-Type', 'application/json');
    res.end('{"error":{"code":"misconfigured","message":"The relay is not configured."}}');
    return;
  }
  handler(req, res);
}
