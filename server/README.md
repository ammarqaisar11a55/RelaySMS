# RelaySMS relay server

Node.js 22 / TypeScript / Express 5 / PostgreSQL. Routes opaque ciphertext between two paired
devices and never sees plaintext. API reference: [docs/API.md](../docs/API.md).

## Layout

| Path | Responsibility |
| --- | --- |
| `src/config` | Environment validation (fails fast, never prints values) |
| `src/database` | Connection pool, transactions, SQL migration runner |
| `src/security` | Token hashing, Ed25519 verification, redacting logger |
| `src/middleware` | Authentication, rate limits, validation, error shape |
| `src/modules/*` | `routes` (HTTP), `service` (rules), `repo` (SQL) per feature |
| `src/realtime` | Authenticated WebSocket hub |
| `src/jobs` | Expiry of ciphertext and pairing sessions |
| `migrations` | Ordered `.sql` files |

Database access uses `pg` directly with parameterised queries and hand-written SQL migrations.
The schema is four small tables, so an ORM would add more surface than it removes.

## Commands

```bash
npm ci
../scripts/dev-db.sh start          # private throwaway PostgreSQL on 127.0.0.1:54329
export DATABASE_URL=postgres://relaysms@127.0.0.1:54329/relaysms
npm run migrate:dev                 # apply migrations (also applied automatically at startup)
npm run dev                         # start with reload
npm test                            # integration tests (uses the relaysms_test database)
npm run typecheck && npm run lint && npm run format:check
npm run build && npm start
```

Tests read `TEST_DATABASE_URL` (default `postgres://relaysms@127.0.0.1:54329/relaysms_test`) and
truncate that database.
