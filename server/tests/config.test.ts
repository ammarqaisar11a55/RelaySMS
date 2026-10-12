import { describe, expect, it } from 'vitest';
import { ConfigError, loadConfig } from '../src/config/env.js';

describe('configuration', () => {
  it('applies safe defaults', () => {
    const c = loadConfig({ DATABASE_URL: 'postgres://u:p@localhost/db' });
    expect(c.PORT).toBe(8080);
    expect(c.MESSAGE_TTL_HOURS).toBe(72);
    expect(c.PAIRING_TTL_SECONDS).toBe(300);
    expect(c.REGISTRATION_CODE).toBe('');
  });

  it('fails clearly when the database URL is missing', () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
    expect(() => loadConfig({})).toThrow(/DATABASE_URL/);
  });

  it('names the bad variable but never prints its value', () => {
    const secret = 'postgresql-super-secret-password';
    try {
      loadConfig({
        DATABASE_URL: `mysql://user:${secret}@host/db`,
        PORT: '99999',
        REGISTRATION_CODE: 'short',
      });
      expect.unreachable();
    } catch (e) {
      const message = (e as Error).message;
      expect(message).toContain('DATABASE_URL');
      expect(message).toContain('PORT');
      expect(message).toContain('REGISTRATION_CODE');
      expect(message).not.toContain(secret);
      expect(message).not.toContain('short');
    }
  });
});
