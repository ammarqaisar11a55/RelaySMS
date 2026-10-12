import { createPublicKey, verify } from 'node:crypto';

const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

/**
 * The exact bytes a sender signs for a relayed message. Binding sender, recipient and message id
 * means a ciphertext cannot be replayed under another id or redirected to another device.
 */
export function messageSigningInput(
  senderId: string,
  recipientId: string,
  messageId: string,
  ciphertext: Buffer,
): Buffer {
  const header = Buffer.from(`relaysms/v1/message\n${senderId}\n${recipientId}\n${messageId}\n`, 'utf8');
  return Buffer.concat([header, ciphertext]);
}

export function verifyEd25519(rawPublicKey: Buffer, data: Buffer, signature: Buffer): boolean {
  if (rawPublicKey.length !== 32 || signature.length !== 64) return false;
  try {
    const key = createPublicKey({
      key: Buffer.concat([ED25519_SPKI_PREFIX, rawPublicKey]),
      format: 'der',
      type: 'spki',
    });
    return verify(null, data, key, signature);
  } catch {
    return false;
  }
}
