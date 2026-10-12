import { rateLimit, type RateLimitRequestHandler } from 'express-rate-limit';
import type { Response } from 'express';
import type { Device } from '../modules/devices/devices.repo.js';

const body = { error: { code: 'rate_limited', message: 'Too many requests. Try again later.' } };

/** Per client IP. Applied to every request before authentication. */
export function ipRateLimit(windowSeconds: number, max: number): RateLimitRequestHandler {
  return rateLimit({
    windowMs: windowSeconds * 1000,
    limit: max,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: body,
  });
}

/** Per authenticated device. Must be mounted after authentication. */
export function deviceRateLimit(windowSeconds: number, max: number): RateLimitRequestHandler {
  return rateLimit({
    windowMs: windowSeconds * 1000,
    limit: max,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: body,
    keyGenerator: (_req, res: Response) => (res.locals.device as Device).id,
  });
}
