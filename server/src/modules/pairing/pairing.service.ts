import { randomUUID } from 'node:crypto';
import type { AppContext } from '../../context.js';
import { withTransaction } from '../../database/pool.js';
import { ApiError, errors } from '../../middleware/errors.js';
import { generateSecret, hashSecret, secretMatches } from '../../security/tokens.js';
import { deleteLinkFor, findDevice, type Device } from '../devices/devices.repo.js';
import { toPublicDevice } from '../devices/devices.service.js';
import {
  cancelOpenPairingsOf,
  findPairing,
  insertLink,
  insertPairing,
  lockPairing,
  markCompleted,
  markConfirmed,
  markRedeemed,
  recordFailedAttempt,
  setPairingStatus,
  type PairingSession,
} from './pairing.repo.js';

const expired = () => errors.gone('pairing_expired', 'This pairing code has expired. Create a new one.');
const cancelled = () => errors.gone('pairing_cancelled', 'This pairing was cancelled. Create a new code.');
const notFound = () => errors.notFound('pairing_not_found', 'Pairing session not found.');

/** Step 1 (companion): open a short-lived, single-use session. The secret is returned once. */
export async function createPairing(ctx: AppContext, companion: Device) {
  if (companion.role !== 'companion') {
    throw errors.forbidden('wrong_role', 'Only a companion device can start pairing.');
  }
  const id = randomUUID();
  const secret = generateSecret(16);
  const now = ctx.now();
  const expiresAt = new Date(now.getTime() + ctx.config.PAIRING_TTL_SECONDS * 1000);
  await withTransaction(ctx.db, async (tx) => {
    await cancelOpenPairingsOf(tx, companion.id);
    await insertPairing(tx, {
      id,
      companionId: companion.id,
      secretHash: hashSecret(secret),
      now,
      expiresAt,
    });
  });
  return { pairingId: id, secret, expiresAt: expiresAt.toISOString() };
}

/**
 * Step 2 (source): redeem the code scanned from the companion's screen. Succeeds at most once per
 * session. Wrong secrets are counted and the session is cancelled after too many of them, so the
 * secret cannot be guessed online.
 */
export async function redeemPairing(
  ctx: AppContext,
  source: Device,
  pairingId: string,
  secret: string,
  proof: Buffer,
) {
  if (source.role !== 'source') {
    throw errors.forbidden('wrong_role', 'Only a source device can redeem a pairing code.');
  }
  // The transaction returns an outcome instead of throwing so that the failed-attempt counter is
  // committed even when the request is rejected.
  const outcome = await withTransaction(ctx.db, async (tx): Promise<ApiError | PairingSession> => {
    const session = await lockPairing(tx, pairingId);
    if (!session) return notFound();
    if (session.status === 'cancelled') return cancelled();
    if (session.status === 'expired') return expired();
    if (session.expiresAt.getTime() <= ctx.now().getTime()) {
      await setPairingStatus(tx, session.id, 'expired');
      return expired();
    }
    if (session.status !== 'pending') {
      return errors.conflict('pairing_already_redeemed', 'This pairing code has already been used.');
    }
    if (!secretMatches(secret, session.secretHash)) {
      const cancel = session.failedAttempts + 1 >= ctx.config.PAIRING_MAX_FAILED_ATTEMPTS;
      await recordFailedAttempt(tx, session.id, cancel);
      return errors.forbidden('invalid_pairing_secret', 'The pairing code is not valid.');
    }
    await markRedeemed(tx, session.id, source.id, proof, ctx.now());
    return { ...session, status: 'redeemed', sourceId: source.id, sourceProof: proof };
  });
  if (outcome instanceof ApiError) throw outcome;
  const companion = await findDevice(ctx.db, outcome.companionId);
  if (!companion || companion.revokedAt) throw expired();
  ctx.hub.notify(companion.id, { type: 'pairing_updated', pairingId });
  return { pairingId, expiresAt: outcome.expiresAt.toISOString(), companion: toPublicDevice(companion) };
}

function assertParticipant(session: PairingSession, device: Device): 'source' | 'companion' {
  if (session.companionId === device.id) return 'companion';
  if (session.sourceId === device.id) return 'source';
  // Do not reveal that the session exists to anyone else.
  throw notFound();
}

export async function getPairing(ctx: AppContext, device: Device, pairingId: string) {
  const session = await findPairing(ctx.db, pairingId);
  if (!session) throw notFound();
  assertParticipant(session, device);
  const source = session.sourceId ? await findDevice(ctx.db, session.sourceId) : null;
  const companion = await findDevice(ctx.db, session.companionId);
  const isExpired =
    (session.status === 'pending' || session.status === 'redeemed') &&
    session.expiresAt.getTime() <= ctx.now().getTime();
  return {
    pairingId: session.id,
    status: isExpired ? 'expired' : session.status,
    expiresAt: session.expiresAt.toISOString(),
    companion: companion ? toPublicDevice(companion) : null,
    source: source ? toPublicDevice(source) : null,
    /** Opaque to the relay; the companion verifies it with the secret from its own QR code. */
    sourceProof: session.sourceProof ? session.sourceProof.toString('base64url') : null,
    sourceConfirmed: session.sourceConfirmedAt !== null,
    companionConfirmed: session.companionConfirmedAt !== null,
  };
}

/**
 * Step 3 (both devices): each user confirms that the verification code shown on the two screens
 * is the same. The link is created only when both confirmations are present. Any previous link of
 * either device is replaced, together with its undelivered messages.
 */
export async function confirmPairing(ctx: AppContext, device: Device, pairingId: string) {
  const outcome = await withTransaction(
    ctx.db,
    async (tx): Promise<ApiError | { completed: boolean; peers: string[] }> => {
      const session = await lockPairing(tx, pairingId);
      if (!session) return notFound();
      const side = assertParticipant(session, device);
      if (session.status === 'completed') return { completed: true, peers: [] };
      if (session.status === 'cancelled') return cancelled();
      if (session.status === 'expired') return expired();
      if (session.expiresAt.getTime() <= ctx.now().getTime()) {
        await setPairingStatus(tx, session.id, 'expired');
        return expired();
      }
      if (session.status !== 'redeemed' || !session.sourceId) {
        return errors.conflict('pairing_not_redeemed', 'The source phone has not scanned this code yet.');
      }
      const now = ctx.now();
      await markConfirmed(tx, session.id, side, now);
      const sourceConfirmed = side === 'source' || session.sourceConfirmedAt !== null;
      const companionConfirmed = side === 'companion' || session.companionConfirmedAt !== null;
      const peers = [session.sourceId, session.companionId];
      if (!(sourceConfirmed && companionConfirmed)) return { completed: false, peers };

      const replaced: string[] = [];
      for (const id of peers) {
        const old = await deleteLinkFor(tx, id);
        if (old) replaced.push(old.sourceId, old.companionId);
      }
      await insertLink(tx, {
        id: randomUUID(),
        sourceId: session.sourceId,
        companionId: session.companionId,
        now,
      });
      await markCompleted(tx, session.id, now);
      return { completed: true, peers: [...peers, ...replaced] };
    },
  );
  if (outcome instanceof ApiError) throw outcome;
  for (const id of new Set(outcome.peers)) {
    ctx.hub.notify(id, { type: 'pairing_updated', pairingId });
    if (outcome.completed) ctx.hub.notify(id, { type: 'link_changed' });
  }
  if (outcome.completed && outcome.peers.length) ctx.logger.info({ pairingId }, 'pairing completed');
  return { status: outcome.completed ? 'completed' : 'redeemed' };
}

/** Either participant may abandon an unfinished session, e.g. when the codes do not match. */
export async function cancelPairing(ctx: AppContext, device: Device, pairingId: string) {
  const outcome = await withTransaction(ctx.db, async (tx): Promise<ApiError | string[]> => {
    const session = await lockPairing(tx, pairingId);
    if (!session) return notFound();
    assertParticipant(session, device);
    if (session.status === 'completed') {
      return errors.conflict('pairing_completed', 'Pairing already finished. Revoke the device instead.');
    }
    if (session.status === 'pending' || session.status === 'redeemed') {
      await setPairingStatus(tx, session.id, 'cancelled');
    }
    return [session.companionId, ...(session.sourceId ? [session.sourceId] : [])];
  });
  if (outcome instanceof ApiError) throw outcome;
  for (const id of outcome) ctx.hub.notify(id, { type: 'pairing_updated', pairingId });
  return { status: 'cancelled' };
}
