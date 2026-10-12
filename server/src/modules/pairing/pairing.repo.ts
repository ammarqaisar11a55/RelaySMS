import type { Queryable } from '../../database/pool.js';

export type PairingStatus = 'pending' | 'redeemed' | 'completed' | 'cancelled' | 'expired';

export interface PairingSession {
  id: string;
  companionId: string;
  sourceId: string | null;
  secretHash: Buffer;
  status: PairingStatus;
  failedAttempts: number;
  sourceConfirmedAt: Date | null;
  companionConfirmedAt: Date | null;
  createdAt: Date;
  expiresAt: Date;
  sourceProof: Buffer | null;
}

interface Row {
  id: string;
  companion_id: string;
  source_id: string | null;
  secret_hash: Buffer;
  status: PairingStatus;
  failed_attempts: number;
  source_confirmed_at: Date | null;
  companion_confirmed_at: Date | null;
  created_at: Date;
  expires_at: Date;
  source_proof: Buffer | null;
}

const toSession = (r: Row): PairingSession => ({
  id: r.id,
  companionId: r.companion_id,
  sourceId: r.source_id,
  secretHash: r.secret_hash,
  status: r.status,
  failedAttempts: r.failed_attempts,
  sourceConfirmedAt: r.source_confirmed_at,
  companionConfirmedAt: r.companion_confirmed_at,
  createdAt: r.created_at,
  expiresAt: r.expires_at,
  sourceProof: r.source_proof,
});

export async function insertPairing(
  q: Queryable,
  p: { id: string; companionId: string; secretHash: Buffer; now: Date; expiresAt: Date },
): Promise<void> {
  await q.query(
    `INSERT INTO pairing_sessions (id, companion_id, secret_hash, status, created_at, expires_at)
     VALUES ($1, $2, $3, 'pending', $4, $5)`,
    [p.id, p.companionId, p.secretHash, p.now, p.expiresAt],
  );
}

/** Cancels any unfinished sessions of a companion so that only its newest QR code is usable. */
export async function cancelOpenPairingsOf(q: Queryable, companionId: string): Promise<void> {
  await q.query(
    `UPDATE pairing_sessions SET status = 'cancelled' WHERE companion_id = $1 AND status IN ('pending', 'redeemed')`,
    [companionId],
  );
}

export async function findPairing(q: Queryable, id: string): Promise<PairingSession | null> {
  const r = await q.query<Row>('SELECT * FROM pairing_sessions WHERE id = $1', [id]);
  return r.rows[0] ? toSession(r.rows[0]) : null;
}

/** Row-locks the session for the rest of the transaction. */
export async function lockPairing(q: Queryable, id: string): Promise<PairingSession | null> {
  const r = await q.query<Row>('SELECT * FROM pairing_sessions WHERE id = $1 FOR UPDATE', [id]);
  return r.rows[0] ? toSession(r.rows[0]) : null;
}

export async function setPairingStatus(q: Queryable, id: string, status: PairingStatus): Promise<void> {
  await q.query('UPDATE pairing_sessions SET status = $2 WHERE id = $1', [id, status]);
}

export async function recordFailedAttempt(q: Queryable, id: string, cancel: boolean): Promise<void> {
  await q.query(
    `UPDATE pairing_sessions SET failed_attempts = failed_attempts + 1,
            status = CASE WHEN $2 THEN 'cancelled' ELSE status END
      WHERE id = $1`,
    [id, cancel],
  );
}

export async function markRedeemed(
  q: Queryable,
  id: string,
  sourceId: string,
  proof: Buffer,
  now: Date,
): Promise<void> {
  await q.query(
    `UPDATE pairing_sessions SET status = 'redeemed', source_id = $2, redeemed_at = $3, source_proof = $4 WHERE id = $1`,
    [id, sourceId, now, proof],
  );
}

export async function markConfirmed(
  q: Queryable,
  id: string,
  side: 'source' | 'companion',
  now: Date,
): Promise<void> {
  const column = side === 'source' ? 'source_confirmed_at' : 'companion_confirmed_at';
  await q.query(`UPDATE pairing_sessions SET ${column} = COALESCE(${column}, $2) WHERE id = $1`, [id, now]);
}

export async function markCompleted(q: Queryable, id: string, now: Date): Promise<void> {
  await q.query(`UPDATE pairing_sessions SET status = 'completed', completed_at = $2 WHERE id = $1`, [
    id,
    now,
  ]);
}

export async function insertLink(
  q: Queryable,
  l: { id: string; sourceId: string; companionId: string; now: Date },
): Promise<void> {
  await q.query('INSERT INTO links (id, source_id, companion_id, created_at) VALUES ($1, $2, $3, $4)', [
    l.id,
    l.sourceId,
    l.companionId,
    l.now,
  ]);
}

export async function expirePairings(q: Queryable, now: Date): Promise<number> {
  const r = await q.query(
    `UPDATE pairing_sessions SET status = 'expired' WHERE status IN ('pending', 'redeemed') AND expires_at <= $1`,
    [now],
  );
  return r.rowCount ?? 0;
}

/** Finished sessions carry no value after a day; remove them. */
export async function deleteOldPairings(q: Queryable, olderThan: Date): Promise<number> {
  const r = await q.query('DELETE FROM pairing_sessions WHERE expires_at <= $1', [olderThan]);
  return r.rowCount ?? 0;
}
