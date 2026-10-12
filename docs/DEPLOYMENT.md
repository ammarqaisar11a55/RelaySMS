# Deploying the Relay

How to run your own RelaySMS relay, keep it running, back it up and upgrade it.

> **Verification status.** Docker is not installed on the machine this project was developed on,
> and no public deployment was made. The image build, the Compose stack (database and relay), the
> Caddyfile syntax, the smoke test and the backup commands are exercised by CI on GitHub's
> runners; **Caddy serving real traffic with a real certificate has never been run.**
> [Section 9](#9-what-was-verified) has the details. Treat your first `docker compose up` as a
> test and run the smoke check in section 3 before pairing your phones.

## 1. What gets deployed

| Container | Image | Role |
| --- | --- | --- |
| `proxy` | `caddy:2-alpine` | Terminates TLS on ports 80/443, obtains and renews the certificate, forwards to the relay (including the `/v1/ws` WebSocket) |
| `relay` | built from `server/Dockerfile` | The Node.js relay. Unprivileged user, read-only file system, no published port |
| `db` | `postgres:17-alpine` | Queue and device registry, on a network with no route to the outside |

You need:

- A Linux host with Docker Engine and the Compose plugin (`docker compose version`).
- A DNS name (for example `relay.example.com`) whose A/AAAA record points at that host.
- Ports 80 and 443 reachable from the internet. Port 80 is needed for the certificate challenge
  and the HTTP→HTTPS redirect.

The phones use normal TLS certificate validation and release builds refuse cleartext HTTP, so the
relay needs a real certificate for a real name. A bare IP address or a self-signed certificate
will not work.

## 2. First deployment

```bash
git clone git@github.com:ammarqaisar11a55/RelaySMS.git
cd RelaySMS
cp .env.example .env
chmod 600 .env
```

Edit `.env`:

| Variable | Set it to |
| --- | --- |
| `RELAY_DOMAIN` | Your DNS name, without `https://` |
| `POSTGRES_PASSWORD` | A long random value of letters and digits: `openssl rand -hex 24` |
| `REGISTRATION_CODE` | Recommended: `openssl rand -hex 16`. Phones must then present this code to register, so strangers cannot create devices on your relay |

Start everything:

```bash
docker compose --env-file .env -f infra/compose.yaml up -d --build
docker compose --env-file .env -f infra/compose.yaml ps
```

The relay applies database migrations by itself every time it starts, inside a transaction and
under an advisory lock. To apply them without starting the server:

```bash
docker compose --env-file .env -f infra/compose.yaml run --rm relay node dist/database/migrate-cli.js
```

If a required setting is missing or invalid, Compose refuses to start (`Set POSTGRES_PASSWORD in
.env`) or the relay exits with status 78 and prints the *names* of the bad variables and the rule
they broke. It never prints their values.

The commands below abbreviate the Compose invocation as:

```bash
dc() { docker compose --env-file .env -f infra/compose.yaml "$@"; }
```

## 3. Check that it works

```bash
curl -fsS https://relay.example.com/healthz     # {"status":"ok"}     process is up
curl -fsS https://relay.example.com/readyz      # {"status":"ready"}  database reachable
```

Then run the round-trip check from any machine with Node.js 22 or newer:

```bash
REGISTRATION_CODE=<your code, if set> node scripts/relay-smoke.mjs https://relay.example.com
```

It registers two throwaway devices, pairs them, uploads one signed opaque payload, fetches it as
the companion, acknowledges it and deletes both devices. Expected last line:
`Relay smoke test passed.` The payload is random bytes — the relay never decrypts anything — so
this proves routing, authorisation and storage. The phones' own encryption is tested by the
Android integration tests (`scripts/integration-test.sh`).

Finally, on each phone open RelaySMS, enter `https://relay.example.com` as the relay server (and
the registration code on the pairing screen, if you set one) and pair.

## 4. Day-to-day operation

```bash
dc ps                      # state and health of the three containers
dc logs --tail 100 relay   # application log
dc restart relay
dc down                    # stop; data volumes are kept
dc up -d                   # start again
```

**Logs.** Each container logs to Docker's `json-file` driver, limited to 5 files of 10 MB
(`x-logging` in `compose.yaml`), so logs cannot fill the disk. The relay logs device ids,
pairing ids, event names and error class names. It never logs tokens, pairing secrets,
ciphertext, signatures, public keys or request bodies; this is covered by a test. Caddy's access
log is left off on purpose (it would record client addresses and request paths).

**Metrics.** `GET /metrics` returns aggregate counters only (devices, links, pending messages,
open connections, expiry totals). The proxy answers 404 for it from outside. Read it on the host:

```bash
dc exec relay node -e "fetch('http://127.0.0.1:8080/metrics').then(r=>r.text()).then(console.log)"
```

**Shutdown.** `dc stop` / `dc down` send SIGTERM. The relay stops accepting connections, closes
WebSockets with code 1001, finishes requests in flight, closes its database pool and exits; if
that takes longer than 10 seconds it exits anyway, and Compose waits 20 seconds before killing
it. Nothing is lost by a restart: a message is either stored in PostgreSQL (the phone received
`201`/`200`) or the phone did not get an answer and retries. PostgreSQL gets 60 seconds to shut
down cleanly.

**Expiry.** Every minute the relay deletes ciphertext older than `MESSAGE_TTL_HOURS` and stale
pairing sessions. No cron job is needed.

**Restricting access further.** The relay is designed to face the internet, but you can also
firewall ports 80/443 to the networks your phones use, or put the host on a VPN, as long as both
phones can still reach the DNS name and the certificate can be renewed.

## 5. Upgrading

```bash
dc exec -T db pg_dump -U relaysms -Fc relaysms > relaysms-before-upgrade.dump   # backup first
git pull
dc up -d --build relay
dc ps && curl -fsS https://relay.example.com/readyz
```

New migrations are applied when the new relay starts. Phones reconnect by themselves; messages
queued on the source phone during the restart are uploaded afterwards.

**Rolling back.** `git checkout <previous commit>` and `dc up -d --build relay`. Migrations are
forward-only: if the newer version added a migration, also restore the dump taken before the
upgrade (section 6). Read the commit messages for a migration before upgrading.

**Base images.** `postgres:17-alpine`, `caddy:2-alpine` and `node:22-bookworm-slim` receive
security updates under the same tags. Refresh them periodically:

```bash
dc pull db proxy && dc build --pull relay && dc up -d
```

Moving to a new PostgreSQL *major* version is not an in-place upgrade: take a dump, change the
image tag, start with an empty `db-data` volume and restore.

## 6. Backup and restore

### What a backup contains

The database holds device public keys, hashed device credentials, which two devices are linked,
unfinished pairing sessions, and ciphertext still waiting for delivery. It holds **no message
text, sender addresses or codes**, and no private keys — those exist only on the phones. A dump
therefore cannot be used to read messages. It still contains metadata (section 8) and credential
hashes, so store it with restricted permissions and do not publish it.

What a backup is good for: after losing the server you restore it and the phones keep working
without pairing again. If you would rather pair again than keep backups, you may skip them; the
only loss is messages that were waiting on the relay at that moment (the source phone marks them
as forwarded once the relay accepted them, so it will not send them again).

The `.env` file and the `caddy-data` volume are not in the dump. Keep a private copy of `.env`;
`caddy-data` can be recreated (Caddy requests a new certificate).

### Back up

```bash
umask 077
dc exec -T db pg_dump -U relaysms -Fc relaysms > "relaysms-$(date +%F).dump"
```

`-Fc` is PostgreSQL's compressed custom format. Run it from cron if you want regular backups,
and delete old dumps on a schedule — keeping them forever defeats the relay's short retention.

### Restore

```bash
dc stop relay
dc exec -T db pg_restore -U relaysms -d relaysms --clean --if-exists --no-owner < relaysms-2026-10-12.dump
dc start relay
curl -fsS https://relay.example.com/readyz
```

`--clean --if-exists` drops and recreates the relay's tables, so the same command works on an
empty database and on one that already has data.

Effects of restoring an older dump:

- Messages acknowledged after the dump was taken are queued again and delivered again. The
  companion recognises them by id and does not show them twice.
- A device registered or re-paired after the dump is unknown to the restored relay. Reset that
  phone in the app and pair again.
- A device whose credential was rotated after the dump is refused (`401`). Reset and pair again.

### Losing everything

If the volume is lost and there is no dump: start a fresh deployment, then on each phone use
*Settings → Reset RelaySMS* and pair again. Inbox messages already on the companion are stored on
that phone and are not affected by losing the server; a reset of the companion erases them.

## 7. Configuration reference

All settings are environment variables, validated when the relay starts.

| Variable | Default | Meaning |
| --- | --- | --- |
| `RELAY_DOMAIN` | – (required by Compose) | Public DNS name served by Caddy |
| `POSTGRES_PASSWORD` | – (required by Compose) | Database password; letters and digits only |
| `DATABASE_URL` | – (required; Compose builds it) | `postgres://user:password@host:port/database` |
| `HOST` / `PORT` | `0.0.0.0` / `8080` | Listen address inside the container |
| `TRUST_PROXY` | `0` (Compose: `1`) | Number of reverse proxies in front of the relay; decides which address rate limits apply to |
| `REGISTRATION_CODE` | empty | If set (16+ characters), required to register a device |
| `PAIRING_TTL_SECONDS` | `300` | Lifetime of a pairing code |
| `PAIRING_MAX_FAILED_ATTEMPTS` | `5` | Wrong guesses before a pairing code is cancelled |
| `MESSAGE_TTL_HOURS` | `72` | Undelivered ciphertext is deleted after this long |
| `MAX_CIPHERTEXT_BYTES` | `131072` | Largest accepted ciphertext. If you raise it, also raise `request_body max_size` in the Caddyfile |
| `MAX_PENDING_PER_DEVICE` | `500` | Undelivered messages allowed per recipient |
| `RATE_LIMIT_WINDOW_SECONDS` / `RATE_LIMIT_MAX` | `60` / `300` | Requests per client address, and per device on message endpoints |
| `RATE_LIMIT_REGISTRATION_MAX` | `10` | Device registrations per client address per hour |
| `RATE_LIMIT_PAIRING_MAX` | `20` | Pairing requests per client address per window |
| `LOG_LEVEL` | `info` | `fatal`, `error`, `warn`, `info`, `debug`, `silent`. `debug` adds one content-free line per request |

Rate-limit counters live in the relay's memory: run one relay instance, and expect counters to
reset when it restarts.

## 8. What the relay operator can observe

End-to-end encryption hides message *content* from the relay. It does not hide *metadata*. Anyone
who controls the relay host, its database, its backups or its network traffic can see:

| Observable | Where |
| --- | --- |
| Device ids, roles and public keys | `devices` table |
| Which source is linked to which companion, and since when | `links` table |
| When each device last contacted the relay (to the minute) and whether it is connected now | `devices.last_seen_at`, live connections |
| That a message was relayed, when, its size in bytes, and when it was acknowledged | `messages` table (ciphertext until acknowledged, then a content-free tombstone until the original expiry) |
| Message ids | Random-looking; for SMS they are a one-way hash and reveal nothing about the text |
| Client IP addresses | The network, the proxy and the relay process see them. They are not written to the database or, with the shipped settings, to any log |
| Pairing attempts and their outcome | `pairing_sessions` (deleted one day after expiry) and the application log |

The relay cannot observe message text, the SMS sender's number or name, codes, or anything stored
in the phones' inboxes. Message sizes and timing can still hint at activity (for example "a short
message arrived at 14:02"), and a relay operator can delay or drop messages. Run the relay on a
host you control.

## 9. What was verified

Executed on the development host (Linux, Node.js 22.22.1, PostgreSQL 18.6, no Docker):

- The Dockerfile's steps, run by hand in clean directories: `npm ci` + `npm run build` in a copy
  of the build context, then `npm ci --omit=dev` plus the compiled `dist/` and `migrations/` in a
  second directory laid out like the final image. `node dist/server.js` started there with the
  environment Compose would pass, applied both migrations and served requests;
  `node dist/healthcheck.js` exited 0; SIGTERM produced a clean exit with status 0.
- `scripts/relay-smoke.mjs` against that relay: all 8 steps passed with the registration code,
  and it failed at registration (403) without the code, as intended.
- `pg_dump -Fc` and `pg_restore --clean --if-exists --no-owner` with the flags documented above,
  into an empty database and again over existing data: exit status 0, identical row counts.
- `infra/compose.yaml` parses as YAML and its structure was checked by script.
- Start-up failure modes are covered by automated tests (`server/tests/startup.test.ts`): missing
  and invalid settings exit with status 78 naming only the variables; an unreachable database
  does not print the password; the health probe fails when the database is down.

Executed by CI on GitHub's runners (`.github/workflows/ci.yml`, job `deployment-files`; observed
passing for commits `cf28179` and `fc3c97d`):

- `docker compose config` with `.env.example`, and `caddy validate` on the Caddyfile.
- `docker compose up --build --wait db relay`: the image builds, PostgreSQL 17 and the relay
  start with the hardening options (`read_only`, `cap_drop`, `no-new-privileges`) and report
  healthy; the relay runs as a non-root user.
- `scripts/relay-smoke.mjs` from a container on the Compose network, the `pg_dump` /
  `pg_restore` commands of section 6, and a stop of the relay with exit status 0.
- The relay's own test suite against PostgreSQL 17 (job `server`).

**Never executed anywhere:** the `proxy` container with real traffic — certificate issuance and
renewal, WebSocket pass-through, the `/metrics` block and the body-size limit as Caddy applies
them; log rotation over time; an upgrade or rollback of a running deployment; a deployment on a
public host.

## 10. Running without Docker

The relay is an ordinary Node.js program and can run under any process manager next to an
existing PostgreSQL server (developed and tested against 18; the Compose file uses 17). Put a TLS-terminating reverse proxy in front of it and set
`TRUST_PROXY` to the number of proxies.

```bash
cd server
npm ci
npm run build
npm ci --omit=dev                       # optional: drop development dependencies
export DATABASE_URL=postgres://relaysms:<password>@127.0.0.1:5432/relaysms
export NODE_ENV=production HOST=127.0.0.1 PORT=8080 TRUST_PROXY=1
npm run migrate                         # optional; start applies migrations as well
npm start
```

Stop it with SIGTERM. For local development and tests see [server/README.md](../server/README.md).
