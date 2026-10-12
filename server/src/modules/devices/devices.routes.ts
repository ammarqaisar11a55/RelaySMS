import { Router } from 'express';
import { z } from 'zod';
import type { AppContext } from '../../context.js';
import { currentDevice, requireDevice } from '../../middleware/auth.js';
import { ipRateLimit } from '../../middleware/rateLimit.js';
import { base64urlBytes, parse } from '../../middleware/validate.js';
import { deleteDevice, describeDevice, registerDevice, revokeLink, rotateToken } from './devices.service.js';

const registerBody = z
  .object({
    role: z.enum(['source', 'companion']),
    hpkePublicKey: base64urlBytes(32),
    signingPublicKey: base64urlBytes(32),
    registrationCode: z.string().max(256).optional(),
  })
  .strict();

export function devicesRouter(ctx: AppContext): Router {
  const router = Router();
  const auth = requireDevice(ctx);
  const registrationLimit = ipRateLimit(3600, ctx.config.RATE_LIMIT_REGISTRATION_MAX);

  router.post('/devices', registrationLimit, async (req, res) => {
    const body = parse(registerBody, req.body);
    res.status(201).json(await registerDevice(ctx, body));
  });

  router.get('/devices/me', auth, async (_req, res) => {
    res.json(await describeDevice(ctx, currentDevice(res)));
  });

  router.post('/devices/me/token', auth, async (_req, res) => {
    res.json(await rotateToken(ctx, currentDevice(res)));
  });

  router.delete('/devices/me', auth, async (_req, res) => {
    await deleteDevice(ctx, currentDevice(res));
    res.status(204).end();
  });

  router.delete('/link', auth, async (_req, res) => {
    res.json(await revokeLink(ctx, currentDevice(res)));
  });

  return router;
}
