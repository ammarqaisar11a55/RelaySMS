import { z } from 'zod';
import { errors } from './errors.js';

/** Parses untrusted input. Unknown fields are rejected so that mistakes are visible to clients. */
export function parse<T extends z.ZodTypeAny>(schema: T, input: unknown): z.infer<T> {
  const result = schema.safeParse(input);
  if (!result.success) {
    const first = result.error.issues[0];
    const where = first?.path.join('.') || 'body';
    throw errors.badRequest('invalid_request', `Invalid value for ${where}.`);
  }
  return result.data;
}

export const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);

/** base64url without padding, decoded to exactly [bytes] bytes. */
export function base64urlBytes(bytes: number) {
  return z
    .string()
    .regex(/^[A-Za-z0-9_-]+$/)
    .max(Math.ceil((bytes * 4) / 3) + 2)
    .transform((v) => Buffer.from(v, 'base64url'))
    .refine((b) => b.length === bytes);
}

/** base64url without padding, decoded to between 1 and [maxBytes] bytes. */
export function base64urlUpTo(maxBytes: number) {
  return z
    .string()
    .regex(/^[A-Za-z0-9_-]+$/)
    .max(Math.ceil((maxBytes * 4) / 3) + 2)
    .transform((v) => Buffer.from(v, 'base64url'))
    .refine((b) => b.length >= 1 && b.length <= maxBytes);
}
