import type { Queryable } from '../../database/pool.js';

export type DeviceRole = 'source' | 'companion';

export interface Device {
  id: string;
  role: DeviceRole;
  hpkePublicKey: Buffer;
  signingPublicKey: Buffer;
  tokenHash: Buffer;
  createdAt: Date;
  lastSeenAt: Date;
  revokedAt: Date | null;
}

interface DeviceRow {
  id: string;
  role: DeviceRole;
  hpke_public_key: Buffer;
  signing_public_key: Buffer;
  token_hash: Buffer;
  created_at: Date;
  last_seen_at: Date;
  revoked_at: Date | null;
}

const toDevice = (r: DeviceRow): Device => ({
  id: r.id,
  role: r.role,
  hpkePublicKey: r.hpke_public_key,
  signingPublicKey: r.signing_public_key,
  tokenHash: r.token_hash,
  createdAt: r.created_at,
  lastSeenAt: r.last_seen_at,
  revokedAt: r.revoked_at,
});

export async function insertDevice(
  q: Queryable,
  d: {
    id: string;
    role: DeviceRole;
    hpkePublicKey: Buffer;
    signingPublicKey: Buffer;
    tokenHash: Buffer;
    now: Date;
  },
): Promise<void> {
  await q.query(
    `INSERT INTO devices (id, role, hpke_public_key, signing_public_key, token_hash, created_at, last_seen_at)
     VALUES ($1, $2, $3, $4, $5, $6, $6)`,
    [d.id, d.role, d.hpkePublicKey, d.signingPublicKey, d.tokenHash, d.now],
  );
}

export async function findDevice(q: Queryable, id: string): Promise<Device | null> {
  const r = await q.query<DeviceRow>('SELECT * FROM devices WHERE id = $1', [id]);
  return r.rows[0] ? toDevice(r.rows[0]) : null;
}

export async function touchDevice(q: Queryable, id: string, now: Date): Promise<void> {
  await q.query('UPDATE devices SET last_seen_at = $2 WHERE id = $1', [id, now]);
}

export async function updateTokenHash(q: Queryable, id: string, tokenHash: Buffer): Promise<void> {
  await q.query('UPDATE devices SET token_hash = $2 WHERE id = $1', [id, tokenHash]);
}

/** Marks the device revoked and removes everything that could still be delivered to or from it. */
export async function revokeDevice(q: Queryable, id: string, now: Date): Promise<void> {
  await q.query('UPDATE devices SET revoked_at = $2 WHERE id = $1 AND revoked_at IS NULL', [id, now]);
  await q.query('DELETE FROM links WHERE source_id = $1 OR companion_id = $1', [id]);
  await q.query('DELETE FROM messages WHERE sender_id = $1 OR recipient_id = $1', [id]);
  await q.query('DELETE FROM pairing_sessions WHERE companion_id = $1 OR source_id = $1', [id]);
}

/**
 * Removes the rows of devices revoked before [olderThan]. A revoked device can never authenticate
 * again, so its public keys and credential hash have no further use.
 */
export async function deleteRevokedDevices(q: Queryable, olderThan: Date): Promise<number> {
  const r = await q.query('DELETE FROM devices WHERE revoked_at IS NOT NULL AND revoked_at <= $1', [
    olderThan,
  ]);
  return r.rowCount ?? 0;
}

export interface Link {
  id: string;
  sourceId: string;
  companionId: string;
  createdAt: Date;
}

export async function findLinkFor(q: Queryable, deviceId: string): Promise<Link | null> {
  const r = await q.query<{ id: string; source_id: string; companion_id: string; created_at: Date }>(
    'SELECT * FROM links WHERE source_id = $1 OR companion_id = $1',
    [deviceId],
  );
  const row = r.rows[0];
  return row
    ? { id: row.id, sourceId: row.source_id, companionId: row.companion_id, createdAt: row.created_at }
    : null;
}

export const peerOf = (link: Link, deviceId: string): string =>
  link.sourceId === deviceId ? link.companionId : link.sourceId;

/** Removes the link of [deviceId] and all undelivered ciphertext between the two devices. */
export async function deleteLinkFor(q: Queryable, deviceId: string): Promise<Link | null> {
  const link = await findLinkFor(q, deviceId);
  if (!link) return null;
  await q.query('DELETE FROM links WHERE id = $1', [link.id]);
  await q.query(
    'DELETE FROM messages WHERE (sender_id = $1 AND recipient_id = $2) OR (sender_id = $2 AND recipient_id = $1)',
    [link.sourceId, link.companionId],
  );
  return link;
}
