import { randomBytes } from 'node:crypto';
import request from 'supertest';
import { afterAll, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config/env.js';
import { migrate } from '../src/database/migrate.js';
import { createPool, sslFromUrl } from '../src/database/pool.js';
import { createHarness, pairedDevices, signedMessage, TEST_DATABASE_URL, type Harness } from './helpers.js';

const open: Harness[] = [];
async function harness(overrides: Parameters<typeof createHarness>[0] = {}) {
  const h = await createHarness(overrides);
  open.push(h);
  return h;
}
afterAll(async () => {
  for (const h of open) await h.close();
});

describe('shared database isolation', () => {
  it('keeps every table in its own schema and leaves an existing application untouched', async () => {
    // Another application already owns public.devices and public.messages in this database.
    const admin = createPool(TEST_DATABASE_URL, { schema: 'relaysms', max: 1 });
    const schema = `relaysms_iso_${randomBytes(4).toString('hex')}`;
    try {
      // (Older test runs kept the relay's tables in public; clear those leftovers first.)
      await admin.query(
        'DROP TABLE IF EXISTS public.messages, public.links, public.pairing_sessions, public.devices, public.schema_migrations CASCADE',
      );
      await admin.query('CREATE TABLE public.devices (id int PRIMARY KEY, owner text)');
      await admin.query('CREATE TABLE public.messages (id int PRIMARY KEY, body text)');
      await admin.query(`INSERT INTO public.devices VALUES (1, 'other app')`);
      await admin.query(`INSERT INTO public.messages VALUES (1, 'other app data')`);

      const db = createPool(TEST_DATABASE_URL, { schema, max: 2 });
      try {
        const applied = await migrate(db, schema);
        expect(applied.length).toBeGreaterThanOrEqual(5);
        expect(await migrate(db, schema)).toEqual([]);

        const mine = await admin.query(
          'SELECT table_name FROM information_schema.tables WHERE table_schema = $1 ORDER BY 1',
          [schema],
        );
        expect(mine.rows.map((r) => r.table_name as string)).toEqual([
          'relay_devices',
          'relay_links',
          'relay_messages',
          'relay_pairing_sessions',
          'schema_migrations',
        ]);
        // Unqualified names resolve to the relay's schema on every pooled connection.
        for (let i = 0; i < 4; i++) {
          const where = await db.query(
            'SELECT current_schema() AS s, COUNT(*) AS n FROM relay_devices GROUP BY 1',
          );
          expect(where.rows).toEqual([]);
          expect((await db.query('SELECT current_schema() AS s')).rows[0].s).toBe(schema);
        }
        // With the search path lost, a query cannot land on the other application's tables:
        // the relay's table names do not exist anywhere else.
        const stray = createPool(TEST_DATABASE_URL, { schema: 'relaysms_nowhere', max: 1 });
        await expect(stray.query('UPDATE relay_devices SET role = role')).rejects.toThrow(/does not exist/);
        await stray.end();
        // Row level security is on, so an exposed HTTP API could not read the tables.
        const rls = await admin.query(
          `SELECT relname, relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = $1 AND c.relkind = 'r'`,
          [schema],
        );
        expect(rls.rows.every((r) => r.relrowsecurity === true)).toBe(true);
      } finally {
        await db.end();
      }
      // The other application's tables and rows are exactly as they were.
      const theirs = await admin.query('SELECT owner FROM public.devices');
      expect(theirs.rows).toEqual([{ owner: 'other app' }]);
      const cols = await admin.query(
        `SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'devices' ORDER BY 1`,
      );
      expect(cols.rows.map((r) => r.column_name as string)).toEqual(['id', 'owner']);
      expect((await admin.query('SELECT body FROM public.messages')).rows).toEqual([
        { body: 'other app data' },
      ]);
    } finally {
      await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
      await admin.query('DROP TABLE IF EXISTS public.devices, public.messages');
      await admin.end();
    }
  });

  it('refuses to use a shared or system schema', () => {
    for (const bad of ['public', 'pg_catalog', 'Relay', 'relay-sms', 'a;drop', '']) {
      expect(() => loadConfig({ DATABASE_URL: 'postgres://u@h/db', DATABASE_SCHEMA: bad })).toThrow(
        /DATABASE_SCHEMA/,
      );
    }
    expect(loadConfig({ DATABASE_URL: 'postgres://u@h/db' }).DATABASE_SCHEMA).toBe('relaysms');
    expect(() => createPool('postgres://u@h/db', { schema: 'x; drop' })).toThrow();
  });

  it('reads sslmode the way libpq does and never passes it on', () => {
    expect(sslFromUrl('postgres://u:p@h:5432/db').ssl).toBe(false);
    expect(sslFromUrl('postgres://u:p@h/db?sslmode=disable').ssl).toBe(false);
    const required = sslFromUrl('postgres://u:p@h/db?sslmode=require&application_name=x');
    expect(required.ssl).toEqual({ rejectUnauthorized: false });
    expect(required.url).not.toContain('sslmode');
    expect(required.url).toContain('application_name=x');
    expect(sslFromUrl('postgres://u:p@h/db?sslmode=verify-full').ssl).toEqual({ rejectUnauthorized: true });
    expect(sslFromUrl('postgres://u:p@h/db?sslmode=require', 'PEM').ssl).toEqual({
      ca: 'PEM',
      rejectUnauthorized: true,
    });
    expect(() => sslFromUrl('postgres://u:p@h/db?sslmode=bogus')).toThrow();
    // A percent-encoded password survives untouched.
    expect(sslFromUrl('postgres://u:%40%40x@h/db?sslmode=require').url).toContain('%40%40x@');
  });
});

describe('serverless mode', () => {
  it('tells clients how to get notified', async () => {
    const ws = await harness();
    expect((await request(ws.app).get('/v1/info').expect(200)).body).toEqual({
      apiVersion: 1,
      realtime: 'websocket',
      pollIntervalSeconds: 10,
    });
    const poll = await harness({ REALTIME: 'poll', POLL_INTERVAL_SECONDS: '7' });
    expect((await request(poll.app).get('/v1/info').expect(200)).body).toMatchObject({
      realtime: 'poll',
      pollIntervalSeconds: 7,
    });
  });

  it('derives presence from recent requests when devices poll', async () => {
    const h = await harness({ REALTIME: 'poll' });
    const { source, companion } = await pairedDevices(h.app);
    const peer = async () =>
      (await request(h.app).get('/v1/devices/me').set(source.auth).expect(200)).body.peer.online as boolean;
    expect(await peer()).toBe(true);
    h.advance(200_000);
    expect(await peer()).toBe(false);
    await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(await peer()).toBe(true);
  });

  it('cleans up through the scheduled endpoint only with the secret', async () => {
    const off = await harness();
    await request(off.app).get('/internal/cron/expire').expect(404);

    const h = await harness({ CRON_SECRET: 'cron-secret-0123456789', MESSAGE_TTL_HOURS: '1' });
    const { source, companion } = await pairedDevices(h.app);
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id))
      .expect(201);
    h.advance(3_700_000);
    await request(h.app).get('/internal/cron/expire').expect(404);
    await request(h.app)
      .get('/internal/cron/expire')
      .set('Authorization', 'Bearer wrong-secret-0123456789')
      .expect(404);
    const done = await request(h.app)
      .get('/internal/cron/expire')
      .set('Authorization', 'Bearer cron-secret-0123456789')
      .expect(200);
    expect(done.body.messages).toBeGreaterThanOrEqual(1);
    expect(h.logs()).not.toContain('cron-secret-0123456789');
  });

  it('serves the API through the serverless entry point', async () => {
    const saved = { ...process.env };
    process.env.DATABASE_URL = TEST_DATABASE_URL;
    process.env.LOG_LEVEL = 'silent';
    try {
      const { default: relay } = await import('../src/vercel.js');
      await request(relay).get('/healthz').expect(200, { status: 'ok' });
      await request(relay).get('/readyz').expect(200, { status: 'ready' });
      const info = await request(relay).get('/v1/info').expect(200);
      expect(info.body.realtime).toBe('poll');
      await request(relay).get('/v1/devices/me').expect(401);
    } finally {
      process.env = saved;
    }
  });
});
