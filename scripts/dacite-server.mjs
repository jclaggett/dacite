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
 *     in-process nbb peer's `ws` client sends none).
 *   - Path: an upgrade must be under /signal/, else 404.
 *   - Token (docs/design/packaging.md §P1), on a /signal upgrade, after
 *     the three checks above and with or without an Origin: the
 *     `Sec-WebSocket-Protocol` list must carry `dacite.token.<token>` equal
 *     to this server's token (`crypto.timingSafeEqual`; another length is a
 *     failure). Else 401, and the socket is destroyed before the relay sees
 *     it. The server answers with the subprotocol `dacite.v1` only, so the
 *     token is never echoed.
 *   - Every HTTP response carries `Referrer-Policy: no-referrer`.
 *
 * `GET /dacite/version` (docs/design/packaging.md §P2), after the Host
 * check, with no token and no CORS headers, answers
 * `{"dacite": "<package version>", "proto": 1, "pid": <pid>}`. A second
 * `npx dacite` on a taken port asks it with `probeDacite`.
 */
import crypto from 'crypto';
import fs from 'fs';
import http from 'http';
import { WebSocketServer } from 'ws';
import { staticHandler, webRoot } from './static-server.mjs';
import { createRelay, MAX_MESSAGE } from './webrtc-signal-server.mjs';

export const SIGNAL_PATH = '/signal';
export const VERSION_PATH = '/dacite/version';
/** The `/dacite/version` protocol number (`dacite.cli.run/proto`). */
export const PROTO = 1;
/** This package's version, from package.json. */
export const VERSION = JSON.parse(
  fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'),
).version;

/** What `/dacite/version` answers for this process. */
export function versionInfo({ version = VERSION, proto = PROTO, pid = process.pid } = {}) {
  return { dacite: version, proto, pid };
}

/** True when `info` is a `/dacite/version` answer (any version). */
export function isDaciteInfo(info) {
  return info !== null && typeof info === 'object' && typeof info.dacite === 'string'
    && Number.isInteger(info.proto) && Number.isInteger(info.pid) && info.pid > 0;
}

/**
 * Ask `http://127.0.0.1:<port>/dacite/version`. Resolves with the answer
 * when it is a dacite one, else null (no answer within `timeoutMs`, not
 * 200, not JSON, or not that shape). Never rejects.
 */
export function probeDacite(port, { timeoutMs = 500 } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    const req = http.get({ host: '127.0.0.1', port, path: VERSION_PATH, timeout: timeoutMs }, (res) => {
      if (res.statusCode !== 200) { res.resume(); finish(null); return; }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (d) => { body += d; if (body.length > 4096) req.destroy(); });
      res.on('end', () => {
        try {
          const info = JSON.parse(body);
          finish(isDaciteInfo(info) ? info : null);
        } catch { finish(null); }
      });
      res.on('error', () => finish(null));
    });
    req.on('timeout', () => { req.destroy(); finish(null); });
    req.on('error', () => finish(null));
  });
}
export const SIGNAL_PROTOCOL = 'dacite.v1';
export const TOKEN_PREFIX = 'dacite.token.';

/** The token entry of a `Sec-WebSocket-Protocol` header, or null. */
export function offeredToken(header) {
  for (const p of String(header || '').split(',')) {
    const v = p.trim();
    if (v.startsWith(TOKEN_PREFIX)) return v.slice(TOKEN_PREFIX.length);
  }
  return null;
}

/** Constant-time token compare; a missing token or another length is false. */
export function tokenOk(expected, offered) {
  if (typeof offered !== 'string') return false;
  const a = Buffer.from(expected, 'utf8');
  const b = Buffer.from(offered, 'utf8');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

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
 * bound port. The host is always 127.0.0.1. `token` is the `/signal`
 * secret (`bin/dacite.mjs` makes one per run); it is required. `version`
 * is what `/dacite/version` reports (default this package's; tests fake
 * another).
 */
export function startDacite({ port = 8790, root = webRoot, log = false, token, version = VERSION } = {}) {
  if (typeof token !== 'string' || token.length === 0) {
    return Promise.reject(new Error('startDacite needs a /signal token'));
  }
  const host = '127.0.0.1';
  const relay = createRelay({ log });
  const serveStatic = staticHandler({ root });
  let bound = port;
  const hostOk = (req) => allowedHosts(bound).has(String(req.headers.host || '').toLowerCase());

  const server = http.createServer((req, res) => {
    res.setHeader('referrer-policy', 'no-referrer');
    if (!hostOk(req)) {
      res.writeHead(403, { 'content-type': 'text/plain' });
      res.end('host not allowed');
      return;
    }
    if (new URL(req.url || '/', 'http://127.0.0.1').pathname === VERSION_PATH) {
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        res.writeHead(405, { allow: 'GET, HEAD', 'content-type': 'text/plain' });
        res.end('method not allowed');
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(req.method === 'HEAD' ? undefined : JSON.stringify(versionInfo({ version })));
      return;
    }
    serveStatic(req, res);
  });

  const wss = new WebSocketServer({
    noServer: true,
    perMessageDeflate: false,
    maxPayload: MAX_MESSAGE,
    // Answer `dacite.v1` only, never the token entry.
    handleProtocols: (protocols) => (protocols.has(SIGNAL_PROTOCOL) ? SIGNAL_PROTOCOL : false),
  });
  server.on('upgrade', (req, socket, head) => {
    if (!hostOk(req)) return refuseUpgrade(socket, 403, 'Forbidden');
    const origin = req.headers.origin;
    if (origin !== undefined && !allowedOrigins(bound).has(String(origin).toLowerCase())) {
      return refuseUpgrade(socket, 403, 'Forbidden');
    }
    const u = new URL(req.url || '/', 'http://127.0.0.1');
    if (!u.pathname.startsWith(SIGNAL_PATH + '/')) return refuseUpgrade(socket, 404, 'Not Found');
    if (!tokenOk(token, offeredToken(req.headers['sec-websocket-protocol']))) {
      return refuseUpgrade(socket, 401, 'Unauthorized');
    }
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
