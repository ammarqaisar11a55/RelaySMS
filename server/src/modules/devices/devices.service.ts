import { randomUUID } from 'node:crypto';
import type { AppContext } from '../../context.js';
import { withTransaction } from '../../database/pool.js';
import { errors } from '../../middleware/errors.js';
import { constantTimeEquals, formatDeviceToken, generateSecret, hashSecret } from '../../security/tokens.js';
import { countPendingFrom } from '../messages/messages.repo.js';
import {
  deleteLinkFor,
  findDevice,
  findLinkFor,
  insertDevice,
  peerOf,
  revokeDevice,
  rotateTokenHash,
  type Device,
  type DeviceRole,
} from './devices.repo.js';

export interface PublicDevice {
  deviceId: string;
  role: DeviceRole;
  hpkePublicKey: string;
  signingPublicKey: string;
}

export const toPublicDevice = (d: Device): PublicDevice => ({
  deviceId: d.id,
  role: d.role,
  hpkePublicKey: d.hpkePublicKey.toString('base64url'),
  signingPublicKey: d.signingPublicKey.toString('base64url'),
});

export async function registerDevice(
  ctx: AppContext,
  input: {
    role: DeviceRole;
    hpkePublicKey: Buffer;
    signingPublicKey: Buffer;
    registrationCode?: string | undefined;
  },
): Promise<{ deviceId: string; token: string }> {
  const required = ctx.config.REGISTRATION_CODE;
  if (required !== '' && !constantTimeEquals(input.registrationCode ?? '', required)) {
    throw errors.forbidden('registration_code_required', 'This relay requires a valid registration code.');
  }
  const id = randomUUID();
  const secret = generateSecret();
  await insertDevice(ctx.db, {
    id,
    role: input.role,
    hpkePublicKey: input.hpkePublicKey,
    signingPublicKey: input.signingPublicKey,
    tokenHash: hashSecret(secret),
    now: ctx.now(),
  });
  ctx.logger.info({ deviceId: id, role: input.role }, 'device registered');
  return { deviceId: id, token: formatDeviceToken(id, secret) };
}

export async function describeDevice(ctx: AppContext, device: Device) {
  const link = await findLinkFor(ctx.db, device.id);
  const peer = link ? await findDevice(ctx.db, peerOf(link, device.id)) : null;
  return {
    deviceId: device.id,
    role: device.role,
    createdAt: device.createdAt.toISOString(),
    serverTime: ctx.now().toISOString(),
    peer:
      link && peer
        ? {
            ...toPublicDevice(peer),
            linkedAt: link.createdAt.toISOString(),
            lastSeenAt: peer.lastSeenAt.toISOString(),
            // With a held connection presence is exact. When devices poll instead, a device
            // counts as online while its last request is recent.
            online:
              ctx.hub.isOnline(peer.id) ||
              (ctx.config.REALTIME === 'poll' &&
                ctx.now().getTime() - peer.lastSeenAt.getTime() < PRESENCE_WINDOW_MS),
            /** Messages this device sent that the peer has not acknowledged yet. */
            awaitingPeer: await countPendingFrom(ctx.db, device.id, peer.id, ctx.now()),
          }
        : null,
  };
}

/** In poll mode a device is shown as online this long after its last request. */
const PRESENCE_WINDOW_MS = 150_000;

/** How long the previous credential keeps working if the new one is never used. */
export const TOKEN_ROTATION_GRACE_MS = 24 * 3_600_000;

/**
 * Issues a new credential. The old one stops working as soon as the new one is used, and after
 * one day at the latest; until then it still works, so a device that never received the answer
 * is not locked out. Open connections are closed either way.
 */
export async function rotateToken(ctx: AppContext, device: Device): Promise<{ token: string }> {
  const secret = generateSecret();
  const graceUntil = new Date(ctx.now().getTime() + TOKEN_ROTATION_GRACE_MS);
  await rotateTokenHash(ctx.db, device.id, hashSecret(secret), graceUntil);
  ctx.hub.disconnect(device.id);
  ctx.logger.info({ deviceId: device.id }, 'device token rotated');
  return { token: formatDeviceToken(device.id, secret) };
}

/** Removes the link between this device and its peer. Either side may do this. */
export async function revokeLink(ctx: AppContext, device: Device): Promise<{ revoked: boolean }> {
  const link = await withTransaction(ctx.db, (tx) => deleteLinkFor(tx, device.id));
  if (!link) return { revoked: false };
  const peerId = peerOf(link, device.id);
  ctx.hub.notify(peerId, { type: 'link_changed' });
  ctx.hub.notify(device.id, { type: 'link_changed' });
  ctx.logger.info({ deviceId: device.id, peerId }, 'link revoked');
  return { revoked: true };
}

/** Permanently disables this device's credential and deletes its link and queued data. */
export async function deleteDevice(ctx: AppContext, device: Device): Promise<void> {
  const link = await findLinkFor(ctx.db, device.id);
  await withTransaction(ctx.db, (tx) => revokeDevice(tx, device.id, ctx.now()));
  if (link) ctx.hub.notify(peerOf(link, device.id), { type: 'link_changed' });
  ctx.hub.disconnect(device.id);
  ctx.logger.info({ deviceId: device.id }, 'device revoked');
}
