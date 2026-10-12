import { pino, type Logger } from 'pino';

/**
 * Structured logger. Request bodies are never logged by the application; the redaction list is a
 * second line of defence in case an object containing one of these fields is logged by mistake.
 */
export function createLogger(level: string): Logger {
  return pino({
    level,
    base: null,
    redact: {
      paths: [
        'authorization',
        'token',
        'secret',
        'ciphertext',
        'signature',
        'registrationCode',
        '*.authorization',
        '*.token',
        '*.secret',
        '*.ciphertext',
        '*.signature',
        '*.registrationCode',
        'req.headers.authorization',
        'headers.authorization',
      ],
      censor: '[redacted]',
    },
  });
}

export type { Logger };
