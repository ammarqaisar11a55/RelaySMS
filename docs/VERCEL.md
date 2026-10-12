# Deploying the Relay on Vercel with a Shared PostgreSQL Database

The relay can run as a Vercel serverless function backed by a managed PostgreSQL database such as
Supabase, including a database that other applications already use. This is an alternative to the
container deployment in [DEPLOYMENT.md](DEPLOYMENT.md).

## 1. What is different on Vercel

| Topic | Long-running server (Docker) | Vercel |
| --- | --- | --- |
| New-message signal | WebSocket hint, then fetch | **No WebSocket.** The app asks the relay every `POLL_INTERVAL_SECONDS` (10 by default) while it is open or its background connection is on |
| Typical delay to the companion | About a second | Up to one poll interval, plus function start-up |
| Expiry of old ciphertext | Timer every minute | Vercel Cron once a day (`/internal/cron/expire`). Expired messages are already invisible to clients before they are deleted |
| Database migrations | At process start | In the build step (`npm run build && npm run migrate`) |
| Rate limits | One process, exact | Per function instance, so approximate. The pairing-code guess limit is stored in the database and stays exact |
| Presence ("online") | Exact | "Made a request in the last 2.5 minutes" |

The app detects the mode by itself from `GET /v1/info`; nothing has to be configured on the
phones. The status line then reads "Checking for messages" instead of "Connected".

Cost to be aware of: a companion with *Stay connected in the background* turned on makes one
small request every poll interval, around 8,600 a day at 10 seconds. Raise
`POLL_INTERVAL_SECONDS` if that matters more to you than speed.

## 2. Sharing a database safely

- Every table lives in its own schema (`DATABASE_SCHEMA`, default `relaysms`), created by the
  migration if missing. `public`, `pg_catalog` and `information_schema` are refused.
- Each database connection is confined to that schema, and the migration verifies it is inside
  the schema before it creates anything.
- The tables are named `relay_devices`, `relay_links`, `relay_messages` and
  `relay_pairing_sessions`. If a connection ever lost its schema setting, a query could not land on
  another application's `devices` or `messages` table; it could only fail.
- Row level security is enabled on all of them with no policies, so an auto-generated HTTP API
  (Supabase's, for instance) cannot read them even if the schema were exposed by mistake.
- The relay never issues a statement against any other schema. `server/tests/serverless.test.ts`
  proves this against a database that already contains `public.devices` and `public.messages`.

Use the **session** pooler address (port 5432) for Supabase. The transaction pooler (6543) resets
session settings between statements and is not supported.

`sslmode=require` in the URL means "encrypt, do not verify the certificate chain", as in libpq;
this is what Supabase's pooler expects by default. For full verification use
`sslmode=verify-full` and put the provider's CA certificate (PEM) in `DATABASE_CA_CERT`.

## 3. Set-up

1. In Vercel, create a project from this GitHub repository with **Root Directory** `server`.
   `server/vercel.json` supplies the install and build commands, the function and the cron job.
2. Add environment variables (Production, and Preview if you use it):

   | Variable | Value |
   | --- | --- |
   | `DATABASE_URL` | Your PostgreSQL URL (session pooler, with `?sslmode=require`). **Secret.** |
   | `CRON_SECRET` | A random string of 16+ characters, e.g. `openssl rand -hex 24`. Vercel sends it with the scheduled request. **Secret.** |
   | `REGISTRATION_CODE` | Optional, 16+ characters. Recommended on a public URL: phones must present it to register |
   | `DATABASE_SCHEMA` | Optional, default `relaysms` |
   | `POLL_INTERVAL_SECONDS` | Optional, default `10` |

   `REALTIME=poll`, `TRUST_PROXY=1` and a pool of 2 connections are applied automatically.
   Never put these values in the repository.
3. Deploy. The build fails with a named variable if the configuration is wrong, and creates or
   upgrades the `relaysms` schema.
4. Check:

   ```bash
   curl https://<your-project>.vercel.app/healthz      # {"status":"ok"}
   curl https://<your-project>.vercel.app/readyz       # {"status":"ready"}  (database reachable)
   curl https://<your-project>.vercel.app/v1/info      # {"apiVersion":1,"realtime":"poll",...}
   node scripts/relay-smoke.mjs https://<your-project>.vercel.app   # full register/pair/send/ack round trip
   ```

5. In RelaySMS on the companion phone, enter `https://<your-project>.vercel.app` as the relay
   address when pairing.

## 4. Removing RelaySMS from the database

Everything RelaySMS created is inside its schema:

```sql
DROP SCHEMA relaysms CASCADE;
```

Nothing else in the database is affected.

## 5. Limits of this mode

- Delivery is slower than with a held connection and costs a request per poll.
- In-memory rate limits reset whenever Vercel starts a new instance.
- A function is limited to 15 seconds; normal requests take milliseconds.
- Vercel and the database provider can see request metadata (addresses, times, sizes), as any
  relay host can. They cannot read messages: those are encrypted on the phones.
