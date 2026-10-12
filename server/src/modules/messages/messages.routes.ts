import { Router } from 'express';
import { z } from 'zod';
import type { AppContext } from '../../context.js';
import { currentDevice, requireDevice } from '../../middleware/auth.js';
import { deviceRateLimit } from '../../middleware/rateLimit.js';
import { base64urlBytes, base64urlUpTo, parse, uuid } from '../../middleware/validate.js';
import { acknowledgeMessages, pendingMessages, submitMessage } from './messages.service.js';

export function messagesRouter(ctx: AppContext): Router {
  const router = Router();
  const auth = requireDevice(ctx);
  const perDevice = deviceRateLimit(ctx.config.RATE_LIMIT_WINDOW_SECONDS, ctx.config.RATE_LIMIT_MAX);

  const submitBody = z
    .object({
      messageId: uuid,
      ciphertext: base64urlUpTo(ctx.config.MAX_CIPHERTEXT_BYTES),
      signature: base64urlBytes(64),
    })
    .strict();
  const pendingQuery = z.object({ limit: z.coerce.number().int().min(1).max(100).default(50) }).strict();
  const ackBody = z.object({ messageIds: z.array(uuid).min(1).max(100) }).strict();

  router.post('/messages', auth, perDevice, async (req, res) => {
    const body = parse(submitBody, req.body);
    const status = await submitMessage(ctx, currentDevice(res), body);
    res.status(status === 'accepted' ? 201 : 200).json({ status });
  });

  router.get('/messages/pending', auth, perDevice, async (req, res) => {
    const { limit } = parse(pendingQuery, req.query);
    res.json(await pendingMessages(ctx, currentDevice(res), limit));
  });

  router.post('/messages/ack', auth, perDevice, async (req, res) => {
    const { messageIds } = parse(ackBody, req.body);
    res.json(await acknowledgeMessages(ctx, currentDevice(res), messageIds));
  });

  return router;
}
