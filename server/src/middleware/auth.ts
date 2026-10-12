import type { IncomingMessage } from 'node:http';
import type { RequestHandler, Response } from 'express';
import type { AppContext } from '../context.js';
import { findDevice, touchDevice, type Device } from '../modules/devices/devices.repo.js';
import { parseDeviceToken, secretMatches } from '../security/tokens.js';
import { errors } from './errors.js';

const TOUCH_INTERVAL_MS = 60_000;

/**
 * Resolves a bearer token to a non-revoked device, or null. The device id in the token only
 * selects which stored hash to compare against; it grants nothing by itself.
 */
export async function authenticate(ctx: AppContext, header: string | undefined): Promise<Device | null> {
  if (!header || !header.startsWith('Bearer ')) return null;
  const parsed = parseDeviceToken(header.slice('Bearer '.length).trim());
  if (!parsed) return null;
  const device = await findDevice(ctx.db, parsed.deviceId);
  if (!device || device.revokedAt) return null;
  if (!secretMatches(parsed.secret, device.tokenHash)) return null;
  const now = ctx.now();
  if (now.getTime() - device.lastSeenAt.getTime() > TOUCH_INTERVAL_MS) {
    await touchDevice(ctx.db, device.id, now);
    device.lastSeenAt = now;
  }
  return device;
}

export function requireDevice(ctx: AppContext): RequestHandler {
  return async (req, res, next) => {
    const device = await authenticate(ctx, req.headers.authorization);
    if (!device) return next(errors.unauthorized());
    res.locals.device = device;
    next();
  };
}

/** The authenticated device for this request. Only valid behind [requireDevice]. */
export function currentDevice(res: Response): Device {
  return res.locals.device as Device;
}

export const authenticateUpgrade = (ctx: AppContext, req: IncomingMessage) =>
  authenticate(ctx, req.headers.authorization);
