import type { IncomingMessage, Server } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocketServer, type WebSocket } from 'ws';
import type { Device } from '../modules/devices/devices.repo.js';
import type { Logger } from '../security/logger.js';

/** Content-free hints pushed to connected devices. The data itself is always fetched over HTTPS. */
export type HubEvent =
  | { type: 'messages_available' }
  | { type: 'pairing_updated'; pairingId: string }
  | { type: 'link_changed' }
  | { type: 'delivery_updated' };

const HEARTBEAT_MS = 30_000;
const MAX_SOCKETS_PER_DEVICE = 3;

/** Tracks authenticated WebSocket connections per device. */
export class Hub {
  private readonly sockets = new Map<string, Set<WebSocket>>();
  private readonly alive = new WeakSet<WebSocket>();
  private readonly wss = new WebSocketServer({ noServer: true, maxPayload: 1024 });
  private timer: NodeJS.Timeout | undefined;

  isOnline(deviceId: string): boolean {
    return (this.sockets.get(deviceId)?.size ?? 0) > 0;
  }

  notify(deviceId: string, event: HubEvent): void {
    const set = this.sockets.get(deviceId);
    if (!set) return;
    const data = JSON.stringify(event);
    for (const ws of set) if (ws.readyState === ws.OPEN) ws.send(data);
  }

  /** Closes every connection of a device, e.g. after revocation. */
  disconnect(deviceId: string): void {
    for (const ws of this.sockets.get(deviceId) ?? []) ws.close(4401, 'revoked');
  }

  /**
   * Handles HTTP upgrade requests on [path]. The connection is authenticated from the
   * Authorization header before the WebSocket handshake completes. Credentials are never read
   * from the URL, so they cannot end up in proxy logs.
   */
  attach(
    server: Server,
    path: string,
    authenticate: (req: IncomingMessage) => Promise<Device | null>,
    onConnected: (device: Device) => void,
    logger: Logger,
    /** Rate limit for connection attempts, checked before any database work. */
    admit: (req: IncomingMessage) => boolean = () => true,
  ): void {
    server.on('upgrade', (req: IncomingMessage, socket: Duplex, head: Buffer) => {
      const reject = (status: number, text: string) => {
        socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
        socket.destroy();
      };
      if ((req.url ?? '').split('?')[0] !== path) return reject(404, 'Not Found');
      if (!admit(req)) return reject(429, 'Too Many Requests');
      authenticate(req)
        .then((device) => {
          if (!device) return reject(401, 'Unauthorized');
          this.wss.handleUpgrade(req, socket, head, (ws) => {
            this.register(device.id, ws);
            onConnected(device);
          });
        })
        .catch((err: unknown) => {
          logger.error({ err: { name: (err as Error).name } }, 'websocket upgrade failed');
          reject(500, 'Internal Server Error');
        });
    });
    this.timer = setInterval(() => this.heartbeat(), HEARTBEAT_MS);
    this.timer.unref();
  }

  private register(deviceId: string, ws: WebSocket): void {
    let set = this.sockets.get(deviceId);
    if (!set) this.sockets.set(deviceId, (set = new Set()));
    // A device needs one connection; drop the oldest beyond a small allowance for reconnect overlap.
    while (set.size >= MAX_SOCKETS_PER_DEVICE) {
      const oldest = set.values().next().value as WebSocket;
      set.delete(oldest);
      oldest.terminate();
    }
    set.add(ws);
    this.alive.add(ws);
    ws.on('pong', () => this.alive.add(ws));
    ws.on('message', () => undefined); // Clients have nothing to say; input is ignored.
    ws.on('error', () => ws.terminate());
    ws.on('close', () => {
      const current = this.sockets.get(deviceId);
      current?.delete(ws);
      if (current && current.size === 0) this.sockets.delete(deviceId);
    });
  }

  private heartbeat(): void {
    for (const set of this.sockets.values()) {
      for (const ws of set) {
        if (!this.alive.has(ws)) {
          ws.terminate();
          continue;
        }
        this.alive.delete(ws);
        ws.ping();
      }
    }
  }

  connectionCount(): number {
    let n = 0;
    for (const set of this.sockets.values()) n += set.size;
    return n;
  }

  close(): void {
    if (this.timer) clearInterval(this.timer);
    for (const set of this.sockets.values()) for (const ws of set) ws.close(1001, 'server shutting down');
    this.wss.close();
  }
}
