import { Router } from 'express';
import { z } from 'zod';
import type { AppContext } from '../../context.js';
import { currentDevice, requireDevice } from '../../middleware/auth.js';
import { ipRateLimit } from '../../middleware/rateLimit.js';
import { base64urlBytes, parse, uuid } from '../../middleware/validate.js';
import {
  cancelPairing,
  confirmPairing,
  createPairing,
  getPairing,
  redeemPairing,
} from './pairing.service.js';

const idParams = z.object({ id: uuid });
const redeemBody = z
  .object({
    secret: z
      .string()
      .min(16)
      .max(64)
      .regex(/^[A-Za-z0-9_-]+$/),
    proof: base64urlBytes(32),
  })
  .strict();

export function pairingRouter(ctx: AppContext): Router {
  const router = Router();
  const auth = requireDevice(ctx);
  const limit = ipRateLimit(ctx.config.RATE_LIMIT_WINDOW_SECONDS, ctx.config.RATE_LIMIT_PAIRING_MAX);

  router.post('/pairings', limit, auth, async (_req, res) => {
    res.status(201).json(await createPairing(ctx, currentDevice(res)));
  });

  router.post('/pairings/:id/redeem', limit, auth, async (req, res) => {
    const { id } = parse(idParams, req.params);
    const { secret, proof } = parse(redeemBody, req.body);
    res.json(await redeemPairing(ctx, currentDevice(res), id, secret, proof));
  });

  router.get('/pairings/:id', auth, async (req, res) => {
    const { id } = parse(idParams, req.params);
    res.json(await getPairing(ctx, currentDevice(res), id));
  });

  router.post('/pairings/:id/confirm', limit, auth, async (req, res) => {
    const { id } = parse(idParams, req.params);
    res.json(await confirmPairing(ctx, currentDevice(res), id));
  });

  router.post('/pairings/:id/cancel', limit, auth, async (req, res) => {
    const { id } = parse(idParams, req.params);
    res.json(await cancelPairing(ctx, currentDevice(res), id));
  });

  return router;
}
