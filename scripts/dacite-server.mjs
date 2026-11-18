/**
 * The packaged local peer's one port (docs/design/packaging.md, slice 1).
 *
 * One HTTP server on 127.0.0.1 carries:
 *   - the static app code under /app/ (scripts/static-server.mjs, so the
 *     same redirects and containment rules), and
 *   - WebSocket signaling under /signal: ws://<host>:<port>/signal/room/<token>
 *     is the slice-3 room relay (scripts/webrtc-signal-server.mjs).
 * It never serves values or entries. Data moves over peering.
 *
 * Checks:
 *   - Host, on every request and upgrade: only `localhost:<port>` and
 *     `127.0.0.1:<port>`. Anything else is 403, which blocks DNS rebinding.
 *   - Origin, on a /signal upgrade: when the header is present it must be
 *     `http://localhost:<port>` or `http://127.0.0.1:<port>`, else 403.
 *     WebSockets skip CORS, so this is what stops another website from
 *     opening the relay. Browsers always send Origin on a WebSocket
 *     handshake; a client with no Origin is not a browser page (the
 *     in-process nbb peer's `ws` client sends none) and is let in.
 */
import http from 'http';
import { WebSocketServer } from 'ws';
import { staticHandler, webRoot } from './static-server.mjs';
import { createRelay, MAX_MESSAGE } from './webrtc-signal-server.mjs';

export const SIGNAL_PATH = '/signal';

export function allowedHosts(port) {
  return new Set([`localhost:${port}`, `127.0.0.1:${port}`]);
}

export function allowedOrigins(port) {
  return new Set([`http://localhost:${port}`, `http://127.0.0.1:${port}`]);
}

function refuseUpgrade(socket, code, text) {
  try {
    socket.write(`HTTP/1.1 ${code} ${text}\r\nconnection: close\r\ncontent-length: 0\r\n\r\n`);
  } catch { /* already gone */ }
  socket.destroy();
}

/**
 * Start the server. Resolves with `{ port, host, appUrl, signalUrl, close }`.
 * `port` 0 asks the OS for a free port; the Host and Origin checks use the
 * bound port. The host is always 127.0.0.1 in this slice.
 */
export function startDacite({ port = 8790, root = webRoot, log = false } = {}) {
  const host = '127.0.0.1';
  const relay = createRelay({ log });
  const serveStatic = staticHandler({ root });
  let bound = port;
  const hostOk = (req) => allowedHosts(bound).has(String(req.headers.host || '').toLowerCase());

  const server = http.createServer((req, res) => {
    if (!hostOk(req)) {
      res.writeHead(403, { 'content-type': 'text/plain' });
      res.end('host not allowed');
      return;
    }
    serveStatic(req, res);
  });

  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false, maxPayload: MAX_MESSAGE });
  server.on('upgrade', (req, socket, head) => {
    if (!hostOk(req)) return refuseUpgrade(socket, 403, 'Forbidden');
    const origin = req.headers.origin;
    if (origin !== undefined && !allowedOrigins(bound).has(String(origin).toLowerCase())) {
      return refuseUpgrade(socket, 403, 'Forbidden');
    }
    const u = new URL(req.url || '/', 'http://127.0.0.1');
    if (!u.pathname.startsWith(SIGNAL_PATH + '/')) return refuseUpgrade(socket, 404, 'Not Found');
    const rest = req.url.slice(SIGNAL_PATH.length);
    wss.handleUpgrade(req, socket, head, (ws) => relay.connect(ws, rest));
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      bound = server.address().port;
      resolve({
        port: bound,
        host,
        appUrl: `http://${host}:${bound}/app/`,
        signalUrl: `ws://${host}:${bound}${SIGNAL_PATH}`,
        close() {
          relay.close();
          wss.close();
          const done = new Promise((res, rej) => server.close((e) => (e ? rej(e) : res())));
          server.closeAllConnections();
          return done;
        },
      });
    });
  });
}
