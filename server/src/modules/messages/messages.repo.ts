import type { Queryable } from '../../database/pool.js';

export interface PendingMessage {
  messageId: string;
  senderId: string;
  ciphertext: Buffer;
  signature: Buffer;
  createdAt: Date;
}

/** @returns true if the row was inserted, false if this (sender, messageId) already existed. */
export async function insertMessage(
  q: Queryable,
  m: {
    senderId: string;
    messageId: string;
    recipientId: string;
    ciphertext: Buffer;
    signature: Buffer;
    now: Date;
    expiresAt: Date;
  },
): Promise<boolean> {
  const r = await q.query(
    `INSERT INTO relay_messages (sender_id, message_id, recipient_id, ciphertext, signature, size_bytes, created_at, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
     ON CONFLICT (sender_id, message_id) DO NOTHING`,
    [
      m.senderId,
      m.messageId,
      m.recipientId,
      m.ciphertext,
      m.signature,
      m.ciphertext.length,
      m.now,
      m.expiresAt,
    ],
  );
  return r.rowCount === 1;
}

export async function countPendingFor(q: Queryable, recipientId: string, now: Date): Promise<number> {
  const r = await q.query<{ n: string }>(
    'SELECT COUNT(*) AS n FROM relay_messages WHERE recipient_id = $1 AND acked_at IS NULL AND expires_at > $2',
    [recipientId, now],
  );
  return Number(r.rows[0]?.n ?? 0);
}

export async function countPendingFrom(
  q: Queryable,
  senderId: string,
  recipientId: string,
  now: Date,
): Promise<number> {
  const r = await q.query<{ n: string }>(
    'SELECT COUNT(*) AS n FROM relay_messages WHERE sender_id = $1 AND recipient_id = $2 AND acked_at IS NULL AND expires_at > $3',
    [senderId, recipientId, now],
  );
  return Number(r.rows[0]?.n ?? 0);
}

export async function listPending(
  q: Queryable,
  recipientId: string,
  now: Date,
  limit: number,
): Promise<PendingMessage[]> {
  const r = await q.query<{
    message_id: string;
    sender_id: string;
    ciphertext: Buffer;
    signature: Buffer;
    created_at: Date;
  }>(
    `SELECT message_id, sender_id, ciphertext, signature, created_at
       FROM relay_messages
      WHERE recipient_id = $1 AND acked_at IS NULL AND expires_at > $2 AND ciphertext IS NOT NULL
      ORDER BY created_at ASC, message_id ASC
      LIMIT $3`,
    [recipientId, now, limit],
  );
  return r.rows.map((row) => ({
    messageId: row.message_id,
    senderId: row.sender_id,
    ciphertext: row.ciphertext,
    signature: row.signature,
    createdAt: row.created_at,
  }));
}

/**
 * Acknowledges delivery: the ciphertext is erased immediately and a tombstone is kept until the
 * original expiry time so that a late duplicate upload is recognised. Only the recipient's own
 * messages are affected, whatever ids are supplied.
 */
export async function acknowledge(
  q: Queryable,
  recipientId: string,
  messageIds: string[],
  now: Date,
): Promise<number> {
  const r = await q.query(
    `UPDATE relay_messages SET acked_at = $3, ciphertext = NULL
      WHERE recipient_id = $1 AND message_id = ANY($2::uuid[]) AND acked_at IS NULL`,
    [recipientId, messageIds, now],
  );
  return r.rowCount ?? 0;
}

export async function deleteExpiredMessages(q: Queryable, now: Date): Promise<number> {
  const r = await q.query('DELETE FROM relay_messages WHERE expires_at <= $1', [now]);
  return r.rowCount ?? 0;
}
