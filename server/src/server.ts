import { createServer } from 'node:http';
import { createApp, WS_PATH } from './app.js';
import { ConfigError, loadConfig } from './config/env.js';
import type { AppContext } from './context.js';
import { migrate } from './database/migrate.js';
import { createPool } from './database/pool.js';
import { startExpiryJob } from './jobs/expiry.js';
import { authenticateUpgrade } from './middleware/auth.js';
import { clientAddress, createAttemptLimiter } from './middleware/upgradeLimit.js';
import { countPendingFor } from './modules/messages/messages.repo.js';
import { Hub } from './realtime/hub.js';
import { createLogger } from './security/logger.js';

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    // Deliberately plain: names of bad variables only, never values.
    process.stderr.write(`${err instanceof ConfigError ? err.message : 'Invalid configuration'}\n`);
    process.exit(78);
  }
  const logger = createLogger(config.LOG_LEVEL);
  const db = createPool(config.DATABASE_URL, {
    schema: config.DATABASE_SCHEMA,
    max: config.DB_POOL_MAX,
    caCert: config.DATABASE_CA_CERT,
  });
  db.on('error', (err) => logger.error({ err: { name: err.name } }, 'database pool error'));

  const applied = await migrate(db, config.DATABASE_SCHEMA);
  if (applied.length) logger.info({ applied }, 'migrations applied');

  const hub = new Hub();
  const ctx: AppContext = { db, config, logger, hub, now: () => new Date() };
  const metrics = { startedAt: new Date(), expiredMessages: 0, expiredPairings: 0 };
  const server = createServer(createApp(ctx, metrics));
  server.requestTimeout = 30_000;
  server.headersTimeout = 15_000;
  server.keepAliveTimeout = 65_000;

  // WebSocket upgrades bypass Express, so they get their own per-address limit.
  const upgradeAllowed = createAttemptLimiter(config.RATE_LIMIT_WINDOW_SECONDS, config.RATE_LIMIT_MAX);
  hub.attach(
    server,
    WS_PATH,
    (req) => authenticateUpgrade(ctx, req),
    (device) => {
      // A device that connects with mail waiting is told straight away.
      countPendingFor(db, device.id, ctx.now())
        .then((n) => {
          if (n > 0) hub.notify(device.id, { type: 'messages_available' });
        })
        .catch(() => undefined);
    },
    logger,
    (req) => upgradeAllowed(clientAddress(req, config.TRUST_PROXY)),
  );
  const stopExpiry = startExpiryJob(ctx, metrics);

  server.listen(config.PORT, config.HOST, () =>
    logger.info({ host: config.HOST, port: config.PORT }, 'relay listening'),
  );

  let shuttingDown = false;
  const shutdown = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info({ signal }, 'shutting down');
    stopExpiry();
    hub.close();
    const force = setTimeout(() => process.exit(1), 10_000);
    force.unref();
    server.close(() => {
      db.end()
        .catch(() => undefined)
        .finally(() => process.exit(0));
    });
    server.closeIdleConnections();
  };
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

main().catch((err: unknown) => {
  process.stderr.write(`fatal: ${(err as Error).name}: ${(err as Error).message}\n`);
  process.exit(1);
});
