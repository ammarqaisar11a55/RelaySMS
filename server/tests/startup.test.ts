import { spawn } from 'node:child_process';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createHarness, TEST_DATABASE_URL, type Harness } from './helpers.js';

const serverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tsx = path.join(serverDir, 'node_modules', 'tsx', 'dist', 'cli.mjs');

/** Runs a source file the way the container runs its compiled form, with exactly [env]. */
function run(
  file: string,
  env: Record<string, string>,
  onStdout?: (text: string, kill: (signal: NodeJS.Signals) => void) => void,
): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [tsx, path.join(serverDir, file)], {
      cwd: serverDir,
      env: { PATH: process.env.PATH ?? '', ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d: Buffer) => {
      stdout += d.toString('utf8');
      onStdout?.(stdout, (signal) => child.kill(signal));
    });
    child.stderr.on('data', (d: Buffer) => (stderr += d.toString('utf8')));
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

describe('process start-up', () => {
  it('refuses to start without a database URL and says which variable is missing', async () => {
    const r = await run('src/server.ts', {});
    expect(r.code).toBe(78);
    expect(r.stderr).toContain('DATABASE_URL');
    expect(r.stdout).toBe('');
  });

  it('reports bad settings by name and never prints their values', async () => {
    const password = 'Zx9-database-password-Zx9';
    const code = 'tooshort-code';
    const r = await run('src/server.ts', {
      DATABASE_URL: `mysql://relaysms:${password}@db/relaysms`,
      REGISTRATION_CODE: code,
      MESSAGE_TTL_HOURS: 'forever',
    });
    expect(r.code).toBe(78);
    for (const name of ['DATABASE_URL', 'REGISTRATION_CODE', 'MESSAGE_TTL_HOURS']) {
      expect(r.stderr).toContain(name);
    }
    for (const value of [password, code, 'forever', 'mysql']) {
      expect(r.stdout + r.stderr).not.toContain(value);
    }
  });

  it('does not print the database password when the database cannot be reached', async () => {
    const password = 'Zx9-unreachable-password-Zx9';
    const r = await run('src/server.ts', {
      // Port 9 (discard) on loopback: nothing listens there.
      DATABASE_URL: `postgres://relaysms:${password}@127.0.0.1:9/relaysms`,
    });
    expect(r.code).toBe(1);
    expect(r.stderr).toContain('fatal');
    expect(r.stdout + r.stderr).not.toContain(password);
  });

  it('starts, applies migrations, and exits cleanly on SIGTERM', async () => {
    // Find a free port: the relay does not accept port 0.
    const probe = createServer();
    await new Promise<void>((resolve) => probe.listen(0, '127.0.0.1', resolve));
    const port = String((probe.address() as AddressInfo).port);
    await new Promise((resolve) => probe.close(resolve));

    let signalled = false;
    const r = await run(
      'src/server.ts',
      { DATABASE_URL: TEST_DATABASE_URL, HOST: '127.0.0.1', PORT: port, LOG_LEVEL: 'info' },
      (stdout, kill) => {
        if (!signalled && stdout.includes('relay listening')) {
          signalled = true;
          kill('SIGTERM');
        }
      },
    );
    expect(signalled).toBe(true);
    expect(r.stdout).toContain('shutting down');
    expect(r.code).toBe(0);
  });
});

describe('container health probe', () => {
  let h: Harness;
  let server: Server;
  beforeAll(async () => {
    h = await createHarness();
    server = createServer(h.app);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  });
  afterAll(async () => {
    await new Promise((resolve) => server.close(resolve));
    await h.close();
  });

  it('succeeds while the relay is ready and fails when it is not reachable', async () => {
    const port = String((server.address() as AddressInfo).port);
    const up = await run('src/healthcheck.ts', { PORT: port });
    expect(up.code).toBe(0);
    expect(up.stdout + up.stderr).toBe('');

    const down = await run('src/healthcheck.ts', { PORT: '9' });
    expect(down.code).toBe(1);
  });

  it('fails when the relay is up but its database is not', async () => {
    const broken = await createHarness();
    const brokenServer = createServer(broken.app);
    await new Promise<void>((resolve) => brokenServer.listen(0, '127.0.0.1', resolve));
    await broken.db.end();
    try {
      const r = await run('src/healthcheck.ts', {
        PORT: String((brokenServer.address() as AddressInfo).port),
      });
      expect(r.code).toBe(1);
    } finally {
      await new Promise((resolve) => brokenServer.close(resolve));
      broken.ctx.hub.close();
    }
  });
});
