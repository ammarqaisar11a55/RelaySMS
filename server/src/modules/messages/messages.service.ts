import type { AppContext } from '../../context.js';
import { errors } from '../../middleware/errors.js';
import { messageSigningInput, verifyEd25519 } from '../../security/signatures.js';
import { findLinkFor, peerOf, type Device } from '../devices/devices.repo.js';
import { acknowledge, countPendingFor, insertMessage, listPending } from './messages.repo.js';

/**
 * Accepts one encrypted message for the caller's linked peer. The recipient is always derived
 * from the server-side link, never from the request, so a device cannot address anyone else.
 */
export async function submitMessage(
  ctx: AppContext,
  sender: Device,
  input: { messageId: string; ciphertext: Buffer; signature: Buffer },
): Promise<'accepted' | 'duplicate'> {
  const link = await findLinkFor(ctx.db, sender.id);
  if (!link) throw errors.conflict('not_linked', 'This device is not paired with another device.');
  const recipientId = peerOf(link, sender.id);

  const signed = messageSigningInput(sender.id, recipientId, input.messageId, input.ciphertext);
  if (!verifyEd25519(sender.signingPublicKey, signed, input.signature)) {
    throw errors.badRequest('invalid_signature', 'The message signature is not valid.');
  }

  const now = ctx.now();
  if ((await countPendingFor(ctx.db, recipientId, now)) >= ctx.config.MAX_PENDING_PER_DEVICE) {
    throw errors.tooMany('queue_full', 'The recipient has too many undelivered messages.');
  }
  const inserted = await insertMessage(ctx.db, {
    senderId: sender.id,
    messageId: input.messageId,
    recipientId,
    ciphertext: input.ciphertext,
    signature: input.signature,
    now,
    expiresAt: new Date(now.getTime() + ctx.config.MESSAGE_TTL_HOURS * 3_600_000),
  });
  if (inserted) ctx.hub.notify(recipientId, { type: 'messages_available' });
  return inserted ? 'accepted' : 'duplicate';
}

export async function pendingMessages(ctx: AppContext, recipient: Device, limit: number) {
  const rows = await listPending(ctx.db, recipient.id, ctx.now(), limit + 1);
  return {
    messages: rows.slice(0, limit).map((m) => ({
      messageId: m.messageId,
      senderId: m.senderId,
      ciphertext: m.ciphertext.toString('base64url'),
      signature: m.signature.toString('base64url'),
      createdAt: m.createdAt.toISOString(),
    })),
    more: rows.length > limit,
  };
}

export async function acknowledgeMessages(ctx: AppContext, recipient: Device, messageIds: string[]) {
  const acked = await acknowledge(ctx.db, recipient.id, messageIds, ctx.now());
  if (acked > 0) {
    const link = await findLinkFor(ctx.db, recipient.id);
    if (link) ctx.hub.notify(peerOf(link, recipient.id), { type: 'delivery_updated' });
  }
  return { acknowledged: acked };
}
