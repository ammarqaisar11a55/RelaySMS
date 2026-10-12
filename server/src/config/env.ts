import { z } from 'zod';

const int = (def: number, min: number, max: number) => z.coerce.number().int().min(min).max(max).default(def);

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().min(1).default('0.0.0.0'),
  PORT: int(8080, 1, 65535),
  DATABASE_URL: z
    .string()
    .min(1)
    .refine((v) => /^postgres(ql)?:\/\//.test(v), 'must be a postgres:// connection string'),
  /**
   * PostgreSQL schema that holds every RelaySMS table. The relay never reads or writes outside
   * it, so the database can be shared with other applications.
   */
  DATABASE_SCHEMA: z
    .string()
    .regex(/^[a-z_][a-z0-9_]{0,62}$/, 'must be a lower-case identifier')
    .refine((v) => !['public', 'pg_catalog', 'information_schema'].includes(v), 'must be a dedicated schema')
    .default('relaysms'),
  /** Optional PEM certificate authority for verifying the database server (sslmode=verify-full). */
  DATABASE_CA_CERT: z.string().default(''),
  /** Connections per process. Keep this small on serverless platforms. */
  DB_POOL_MAX: int(10, 1, 100),
  /**
   * How devices learn about new messages. "websocket": a held connection (needs a long-running
   * server). "poll": devices ask every POLL_INTERVAL_SECONDS (serverless platforms).
   */
  REALTIME: z.enum(['websocket', 'poll']).default('websocket'),
  POLL_INTERVAL_SECONDS: int(10, 3, 600),
  /** Bearer secret that authorises the scheduled clean-up endpoint. Empty disables the endpoint. */
  CRON_SECRET: z
    .string()
    .default('')
    .refine((v) => v === '' || v.length >= 16, 'must be at least 16 characters when set'),
  /** Number of reverse proxies in front of the server, used to find the real client IP. */
  TRUST_PROXY: int(0, 0, 10),
  /** Optional shared code a device must present to register. Empty disables the check. */
  REGISTRATION_CODE: z
    .string()
    .default('')
    .refine((v) => v === '' || v.length >= 16, 'must be at least 16 characters when set'),
  PAIRING_TTL_SECONDS: int(300, 30, 3600),
  PAIRING_MAX_FAILED_ATTEMPTS: int(5, 1, 20),
  MESSAGE_TTL_HOURS: int(72, 1, 24 * 30),
  MAX_CIPHERTEXT_BYTES: int(131_072, 256, 1_048_576),
  MAX_PENDING_PER_DEVICE: int(500, 1, 100_000),
  RATE_LIMIT_WINDOW_SECONDS: int(60, 1, 3600),
  RATE_LIMIT_MAX: int(300, 1, 100_000),
  RATE_LIMIT_REGISTRATION_MAX: int(10, 1, 10_000),
  RATE_LIMIT_PAIRING_MAX: int(20, 1, 10_000),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'silent']).default('info'),
});

export type Config = z.infer<typeof schema>;

export class ConfigError extends Error {}

/**
 * Validates environment variables. On failure the error names the offending variables and the
 * rule they broke, never their values, so that credentials cannot leak into logs.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    const problems = parsed.error.issues.map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`);
    throw new ConfigError(`Invalid configuration:\n  ${problems.join('\n  ')}`);
  }
  if (parsed.data.NODE_ENV === 'production' && parsed.data.TRUST_PROXY === 0) {
    // Not fatal: a directly exposed server is unusual but valid. Rate limits use the socket address.
  }
  return parsed.data;
}
