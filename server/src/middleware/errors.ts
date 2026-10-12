import type { ErrorRequestHandler, RequestHandler } from 'express';
import type { Logger } from '../security/logger.js';

/** An expected failure with a stable machine-readable code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export const errors = {
  badRequest: (code = 'invalid_request', message = 'The request is not valid.') =>
    new ApiError(400, code, message),
  unauthorized: () => new ApiError(401, 'unauthorized', 'Authentication is required.'),
  forbidden: (code = 'forbidden', message = 'This device may not do that.') =>
    new ApiError(403, code, message),
  notFound: (code = 'not_found', message = 'Not found.') => new ApiError(404, code, message),
  conflict: (code: string, message: string) => new ApiError(409, code, message),
  gone: (code: string, message: string) => new ApiError(410, code, message),
  tooLarge: () => new ApiError(413, 'payload_too_large', 'The request is too large.'),
  tooMany: (code = 'rate_limited', message = 'Too many requests. Try again later.') =>
    new ApiError(429, code, message),
};

export const notFoundHandler: RequestHandler = (_req, _res, next) => next(errors.notFound());

/** Every error leaves the server in one shape: { error: { code, message } }. */
export function errorHandler(logger: Logger): ErrorRequestHandler {
  return (err, req, res, _next) => {
    if (res.headersSent) {
      res.destroy();
      return;
    }
    if (err instanceof ApiError) {
      res.status(err.status).json({ error: { code: err.code, message: err.message } });
      return;
    }
    const type = (err as { type?: string }).type;
    if (type === 'entity.too.large') {
      res.status(413).json({ error: { code: 'payload_too_large', message: 'The request is too large.' } });
      return;
    }
    if (type === 'entity.parse.failed' || err instanceof SyntaxError) {
      res
        .status(400)
        .json({ error: { code: 'invalid_json', message: 'The request body is not valid JSON.' } });
      return;
    }
    // Unexpected: log the error class and stack, never the request body.
    logger.error(
      { err: { name: (err as Error).name, stack: (err as Error).stack }, path: req.route?.path },
      'unhandled error',
    );
    res.status(500).json({ error: { code: 'internal_error', message: 'Something went wrong.' } });
  };
}
