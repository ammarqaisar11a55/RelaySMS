/**
 * Container health probe: exits 0 when the relay on this host answers its readiness check
 * (process up and database reachable), 1 otherwise. Used by the Dockerfile HEALTHCHECK so the
 * image needs no curl or wget. Prints nothing.
 */
const port = Number(process.env.PORT ?? 8080);
const controller = new AbortController();
const timer = setTimeout(() => controller.abort(), 4000);
try {
  const res = await fetch(`http://127.0.0.1:${port}/readyz`, { signal: controller.signal });
  process.exitCode = res.ok ? 0 : 1;
} catch {
  process.exitCode = 1;
} finally {
  clearTimeout(timer);
}
