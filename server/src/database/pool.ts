import pg from 'pg';

export type Db = pg.Pool;
export type DbClient = pg.PoolClient;
/** Anything that can run a parameterised query: the pool or a transaction client. */
export type Queryable = Pick<pg.Pool, 'query'>;

export interface PoolOptions {
  /** Schema that holds the relay's tables. Every connection is confined to it. */
  schema?: string;
  max?: number;
  /** PEM certificate authority used when the URL asks for verify-ca or verify-full. */
  caCert?: string;
}

const SCHEMA_RE = /^[a-z_][a-z0-9_]{0,62}$/;

/**
 * TLS settings from the URL's sslmode, with libpq's meaning: "require" encrypts without checking
 * the certificate chain (what managed poolers with a private CA expect), "verify-ca" and
 * "verify-full" check it, "disable" or no sslmode means no TLS.
 */
export function sslFromUrl(
  connectionString: string,
  caCert = '',
): { url: string; ssl: pg.PoolConfig['ssl'] } {
  const url = new URL(connectionString);
  const mode = url.searchParams.get('sslmode');
  url.searchParams.delete('sslmode');
  const ca = caCert.trim() === '' ? undefined : caCert;
  let ssl: pg.PoolConfig['ssl'];
  switch (mode) {
    case null:
    case 'disable':
    case 'allow':
    case 'prefer':
      ssl = false;
      break;
    case 'require':
      ssl = ca ? { ca, rejectUnauthorized: true } : { rejectUnauthorized: false };
      break;
    case 'verify-ca':
    case 'verify-full':
      ssl = { ...(ca ? { ca } : {}), rejectUnauthorized: true };
      break;
    default:
      throw new Error('Unsupported sslmode in DATABASE_URL');
  }
  return { url: url.toString(), ssl };
}

export function createPool(connectionString: string, options: PoolOptions = {}): Db {
  const schema = options.schema ?? 'relaysms';
  if (!SCHEMA_RE.test(schema)) throw new Error('Invalid database schema name');
  const { url, ssl } = sslFromUrl(connectionString, options.caCert);
  const pool = new pg.Pool({
    connectionString: url,
    ssl,
    max: options.max ?? 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 8_000,
    statement_timeout: 10_000,
    // Unqualified table names resolve only inside the relay's own schema. Sent as a startup
    // parameter, and again on connect for poolers that drop startup parameters.
    options: `-c search_path=${schema}`,
  });
  pool.on('connect', (client) => {
    client.query(`SET search_path TO ${schema}`).catch(() => undefined);
  });
  return pool;
}

export async function withTransaction<T>(db: Db, fn: (tx: DbClient) => Promise<T>): Promise<T> {
  const client = await db.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw err;
  } finally {
    client.release();
  }
}
