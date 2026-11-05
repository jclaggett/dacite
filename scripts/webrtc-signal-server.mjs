#!/usr/bin/env node
/**
 * M3.2 slice 3: a thin WebSocket relay for WebRTC signaling.
 *
 * Two peers that know a room token exchange offer, answer, and ICE candidates.
 * There is no peer discovery and no authentication beyond the token. The peer
 * wire protocol is not involved: each signaling frame is opaque JSON.
 *
 *   npm run webrtc:signal
 *   npm run webrtc:signal -- --port 8787 --host 127.0.0.1
 *
 * Connect:  ws://<host>:<port>/room/<token>[?role=offerer|answerer]
 * Token:    1..128 characters from [A-Za-z0-9._~-]
 *
 * Server → client (JSON text):
 *   {"type":"joined","role":"offerer"|"answerer","room":"<token>"}
 *   {"type":"ready"}                 both peers are in the room
 *   {"type":"signal", ...}           the other peer's message, unchanged
 *   {"type":"peer-left"}             the other socket closed
 *   {"type":"error","reason":"...","message":"..."}
 *       reasons: bad-room, bad-role, room-full, role-taken, bad-message
 *       the socket is closed after an error
 *
 * Client → server:
 *   {"type":"signal", "description"?: {type, sdp}, "candidate"?: {candidate, mid},
 *                    "candidates"?: [...], "complete"?: true}
 *   The server does not read those fields. Any other type is bad-message.
 *
 * Room policy: one offerer and one answerer. A third socket gets room-full.
 * A socket that asks for a role already taken gets role-taken. This server
 * does not replace a peer that is still connected. Omit ?role= and the first
 * peer is the offerer, the second the answerer. Signals sent before the other
 * peer arrives are queued (up to 64) and flushed on join. When a peer leaves,
 * the queue is dropped, the other peer gets peer-left, and the free slot can
 * be taken again. Nothing is stored after the process exits.
 *
 * The client helper is scripts/webrtc-signal-client.mjs. nbb uses
 * dacite.peer.transport.webrtc/signal-over-ws!.
 */
import fs from 'fs';
import http from 'http';
import path from 'path';
import { pathToFileURL } from 'url';
import { WebSocketServer, WebSocket } from 'ws';

const TOKEN = /^[A-Za-z0-9._~-]{1,128}$/;
const MAX_PENDING = 64;
const MAX_MESSAGE = 64 * 1024;

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  if (i < 0) return fallback;
  const v = process.argv[i + 1];
  if (!v || v.startsWith('-')) return fallback;
  return v;
}

export function parseRoomRequest(rawUrl) {
  let u;
  try {
    u = new URL(rawUrl, 'http://127.0.0.1');
  } catch {
    return { error: 'bad-room', message: 'connect to /room/<token>' };
  }
  const m = u.pathname.match(/^\/room\/([^/]+)$/);
  if (!m) return { error: 'bad-room', message: 'connect to /room/<token>' };
  let room;
  try {
    room = decodeURIComponent(m[1]);
  } catch {
    return { error: 'bad-room', message: 'room token is not valid percent-encoding' };
  }
  if (!TOKEN.test(room)) {
    return {
      error: 'bad-room',
      message: 'room token must be 1 to 128 characters: letters, digits, dot, underscore, tilde, hyphen',
    };
  }
  const asked = u.searchParams.get('role');
  if (asked && asked !== 'offerer' && asked !== 'answerer') {
    return { error: 'bad-role', message: 'role must be offerer or answerer' };
  }
  return { room, role: asked || null };
}

function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function fail(ws, reason, message) {
  send(ws, { type: 'error', reason, message });
  try { ws.close(1008, reason); } catch { /* already closing */ }
}

function freshRoom() {
  return {
    slots: { offerer: null, answerer: null },
    pending: { offerer: [], answerer: [] },
  };
}

/**
 * Start the relay. Resolves with `{ port, host, url, close }`.
 * `port` 0 asks the OS for a free port. `log` prints join and leave lines
 * to stderr (the CLI turns it on).
 */
export function startServer({ port = 8787, host = '127.0.0.1', log = false } = {}) {
  const rooms = new Map();
  const httpServer = http.createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('ok');
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('dacite webrtc signal relay\n');
  });
  const wss = new WebSocketServer({
    server: httpServer,
    perMessageDeflate: false,
    maxPayload: MAX_MESSAGE,
  });

  const note = (...a) => { if (log) console.error(...a); };

  wss.on('connection', (ws, req) => {
    const parsed = parseRoomRequest(req.url || '/');
    if (parsed.error) {
      fail(ws, parsed.error, parsed.message);
      return;
    }
    const { room } = parsed;
    let rec = rooms.get(room);
    if (!rec) {
      rec = freshRoom();
      rooms.set(room, rec);
    }
    if (rec.slots.offerer && rec.slots.answerer) {
      fail(ws, 'room-full', 'room already has two peers');
      return;
    }
    const role = parsed.role || (rec.slots.offerer ? 'answerer' : 'offerer');
    if (rec.slots[role]) {
      fail(ws, 'role-taken', `room already has an ${role}`);
      return;
    }
    const otherRole = role === 'offerer' ? 'answerer' : 'offerer';
    rec.slots[role] = ws;
    note('join', room, role);

    ws.on('message', (data, isBinary) => {
      if (rec.slots[role] !== ws) return;
      if (isBinary || data.length > MAX_MESSAGE) {
        fail(ws, 'bad-message', 'signaling messages are JSON text under 64 KiB');
        return;
      }
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        fail(ws, 'bad-message', 'not JSON');
        return;
      }
      if (!msg || msg.type !== 'signal' || typeof msg !== 'object') {
        fail(ws, 'bad-message', 'only type "signal" is relayed');
        return;
      }
      const dest = rec.slots[otherRole];
      if (dest && dest.readyState === WebSocket.OPEN) {
        send(dest, msg);
      } else {
        const q = rec.pending[role];
        if (q.length >= MAX_PENDING) {
          fail(ws, 'bad-message', 'too many signals before the other peer joined');
          return;
        }
        q.push(msg);
      }
    });

    ws.on('close', () => {
      if (rec.slots[role] !== ws) return;
      rec.slots[role] = null;
      rec.pending[role] = [];
      rec.pending[otherRole] = [];
      note('leave', room, role);
      const peer = rec.slots[otherRole];
      if (peer) send(peer, { type: 'peer-left' });
      else rooms.delete(room);
    });

    // Handlers are on before any byte goes out, so a peer that answers
    // `joined` immediately is not dropped.
    send(ws, { type: 'joined', role, room });
    const other = rec.slots[otherRole];
    if (other) {
      send(ws, { type: 'ready' });
      send(other, { type: 'ready' });
      const forMe = rec.pending[otherRole];
      const forThem = rec.pending[role];
      rec.pending[otherRole] = [];
      rec.pending[role] = [];
      for (const m of forMe) send(ws, m);
      for (const m of forThem) send(other, m);
    }
  });

  return new Promise((resolve, reject) => {
    const onErr = (err) => reject(err);
    httpServer.once('error', onErr);
    httpServer.listen(port, host, () => {
      httpServer.off('error', onErr);
      const addr = httpServer.address();
      const bound = typeof addr === 'object' && addr ? addr.port : port;
      resolve({
        port: bound,
        host,
        url: `ws://${host}:${bound}`,
        close() {
          for (const rec of rooms.values()) {
            for (const sock of Object.values(rec.slots)) {
              if (sock) {
                try { sock.terminate(); } catch { /* already gone */ }
              }
            }
          }
          rooms.clear();
          wss.close();
          return new Promise((res, rej) => {
            httpServer.close((err) => (err ? rej(err) : res()));
          });
        },
      });
    });
  });
}

function isDirectRun() {
  const arg = process.argv[1];
  if (!arg) return false;
  return import.meta.url === pathToFileURL(path.resolve(arg)).href;
}

if (isDirectRun()) {
  const port = Number(arg('--port', '8787'));
  const host = arg('--host', '127.0.0.1');
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    console.error('--port must be an integer from 0 to 65535');
    process.exit(1);
  }
  try {
    const srv = await startServer({ port, host, log: true });
    fs.writeSync(1, `listening ${srv.url}\n`);
  } catch (err) {
    console.error(err);
    process.exit(1);
  }
}
