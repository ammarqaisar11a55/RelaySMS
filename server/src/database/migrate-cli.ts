import { ConfigError, loadConfig } from '../config/env.js';
import { createLogger } from '../security/logger.js';
import { migrate } from './migrate.js';
import { createPool } from './pool.js';

const logger = createLogger('info');
try {
  const config = loadConfig();
  const db = createPool(config.DATABASE_URL);
  const applied = await migrate(db);
  logger.info({ applied }, applied.length ? 'migrations applied' : 'database already up to date');
  await db.end();
} catch (err) {
  logger.fatal(
    err instanceof ConfigError ? err.message : { err: (err as Error).message },
    'migration failed',
  );
  process.exitCode = 1;
}
