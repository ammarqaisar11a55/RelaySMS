import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Db } from './pool.js';

const defaultDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../migrations');

/**
 * Applies pending SQL migrations in file-name order, each inside its own transaction. An advisory
 * lock prevents two server instances from migrating at once.
 *
 * @returns names of the migrations applied by this call.
 */
export async function migrate(db: Db, schema: string, dir: string = defaultDir): Promise<string[]> {
  if (!/^[a-z_][a-z0-9_]{0,62}$/.test(schema)) throw new Error('Invalid database schema name');
  const files = (await readdir(dir)).filter((f) => /^\d+_.+\.sql$/.test(f)).sort();
  const client = await db.connect();
  const applied: string[] = [];
  try {
    await client.query('SELECT pg_advisory_lock($1)', [7_231_905]);
    // Everything below happens inside the relay's own schema; nothing else in the database is
    // created, altered or read. The search path is set explicitly in case the pool's was lost.
    await client.query(`CREATE SCHEMA IF NOT EXISTS ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    const where = await client.query<{ s: string }>('SELECT current_schema() AS s');
    if (where.rows[0]?.s !== schema) throw new Error('Could not confine the migration to its schema');
    await client.query(
      'CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())',
    );
    const done = new Set(
      (await client.query<{ name: string }>('SELECT name FROM schema_migrations')).rows.map((r) => r.name),
    );
    for (const file of files) {
      if (done.has(file)) continue;
      const sql = await readFile(path.join(dir, file), 'utf8');
      try {
        await client.query('BEGIN');
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (name) VALUES ($1)', [file]);
        await client.query('COMMIT');
      } catch (err) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw new Error(`Migration ${file} failed: ${(err as Error).message}`);
      }
      applied.push(file);
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [7_231_905]).catch(() => undefined);
    client.release();
  }
  return applied;
}
