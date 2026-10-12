import express, { type Express } from 'express';
import helmet from 'helmet';
import type { AppContext } from './context.js';
import { errorHandler, notFoundHandler } from './middleware/errors.js';
import { ipRateLimit } from './middleware/rateLimit.js';
import { devicesRouter } from './modules/devices/devices.routes.js';
import { healthRouter, type Metrics } from './modules/health/health.routes.js';
import { messagesRouter } from './modules/messages/messages.routes.js';
import { cronRouter, infoRouter } from './modules/system/system.routes.js';
import { pairingRouter } from './modules/pairing/pairing.routes.js';

export const API_PREFIX = '/v1';
export const WS_PATH = '/v1/ws';

export function createApp(ctx: AppContext, metrics: Metrics): Express {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', ctx.config.TRUST_PROXY);
  app.use(helmet());
  // Responses carry credentials (registration, token rotation, pairing codes) and ciphertext.
  // Nothing the relay returns may be kept by a cache.
  app.use((_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });

  // Content-free access log: method, route pattern, status, duration and device id only.
  app.use((req, res, next) => {
    const started = process.hrtime.bigint();
    res.on('finish', () => {
      ctx.logger.debug(
        {
          method: req.method,
          route: req.route ? `${req.baseUrl}${String(req.route.path)}` : 'unmatched',
          status: res.statusCode,
          ms: Number((process.hrtime.bigint() - started) / 1_000_000n),
          deviceId: (res.locals.device as { id?: string } | undefined)?.id,
        },
        'request',
      );
    });
    next();
  });

  app.use(healthRouter(ctx, metrics));
  app.use(cronRouter(ctx, metrics));
  app.use(ipRateLimit(ctx.config.RATE_LIMIT_WINDOW_SECONDS, ctx.config.RATE_LIMIT_MAX));
  // base64url inflates the ciphertext by 4/3; leave room for the JSON envelope.
  app.use(express.json({ limit: Math.ceil(ctx.config.MAX_CIPHERTEXT_BYTES * 1.4) + 2048, strict: true }));

  app.use(API_PREFIX, infoRouter(ctx));
  app.use(API_PREFIX, devicesRouter(ctx));
  app.use(API_PREFIX, pairingRouter(ctx));
  app.use(API_PREFIX, messagesRouter(ctx));

  app.use(notFoundHandler);
  app.use(errorHandler(ctx.logger));
  return app;
}
