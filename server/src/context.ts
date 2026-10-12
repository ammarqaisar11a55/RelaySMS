import type { Config } from './config/env.js';
import type { Db } from './database/pool.js';
import type { Hub } from './realtime/hub.js';
import type { Logger } from './security/logger.js';

/** Everything a request handler may depend on. Injected so tests can control time and limits. */
export interface AppContext {
  db: Db;
  config: Config;
  logger: Logger;
  hub: Hub;
  now: () => Date;
}
