#!/usr/bin/env node
/**
 * Checks for scripts/dacite-server.mjs, the `npx dacite` port.
 *
 *   npm run dacite:server-test
 *
 * 1. Static files and /signal share one port.
 * 2. Host: only localhost:<port> and 127.0.0.1:<port>, on requests and on
 *    upgrades (DNS rebinding). Anything else is 403.
 * 3. Origin on a /signal upgrade: a foreign Origin is 403; our own Origin,
 *    or none (not a browser), joins the room.
 * 4. Two sockets in one room relay a signal through /signal.
 * 5. A symlink out of the served root is 403.
 */
import assert from 'assert';
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { WebSocket } from 'ws';
import { startDacite } from './dacite-server.mjs';

function get(port, p, hostHeader) {
  return new Promise((resolve, reject) => {
    const headers = hostHeader === undefined ? {} : { host: hostHeader };
    const req = http.request({ host: '127.0.0.1', port, path: p, method: 'GET', headers }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

/** Resolves {ok, ws, first} on the first message, or {ok:false, status} if refused. */
function openWs(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers, perMessageDeflate: false });
    ws.once('unexpected-response', (_req, res) => {
      resolve({ ok: false, status: res.statusCode });
      ws.terminate();
    });
    ws.once('message', (d) => resolve({ ok: true, ws, first: JSON.parse(d.toString()) }));
    ws.once('error', (e) => { if (!/Unexpected server response/.test(String(e))) reject(e); });
  });
}

function nextMessage(ws) {
  return new Promise((resolve) => ws.once('message', (d) => resolve(JSON.parse(d.toString()))));
}

const srv = await startDacite({ port: 0 });
const { port } = srv;
const opened = [];
try {
  assert.strictEqual(srv.host, '127.0.0.1');
  assert.strictEqual(srv.signalUrl, `ws://127.0.0.1:${port}/signal`);

  // Static on the same port, both local Host spellings.
  let r = await get(port, '/app/browser/');
  assert.strictEqual(r.status, 200);
  assert.match(r.headers['content-type'], /text\/html/);
  r = await get(port, '/app/browser/', `localhost:${port}`);
  assert.strictEqual(r.status, 200);
  r = await get(port, '/app', `127.0.0.1:${port}`);
  assert.strictEqual(r.status, 301);

  // Host checks.
  for (const h of ['evil.example', `evil.example:${port}`, `127.0.0.1:${port + 1}`, `localhost`, `[::1]:${port}`]) {
    r = await get(port, '/app/browser/', h);
    assert.strictEqual(r.status, 403, `Host ${h}`);
  }

  const base = `ws://127.0.0.1:${port}/signal/room/t1`;
  // Origin and Host checks on the upgrade.
  let w = await openWs(base, { origin: 'http://evil.example' });
  assert.deepStrictEqual(w, { ok: false, status: 403 });
  w = await openWs(base, { origin: `http://127.0.0.1:${port + 1}` });
  assert.deepStrictEqual(w, { ok: false, status: 403 });
  w = await openWs(base, { host: 'evil.example' });
  assert.deepStrictEqual(w, { ok: false, status: 403 });
  w = await openWs(`ws://127.0.0.1:${port}/elsewhere`);
  assert.deepStrictEqual(w, { ok: false, status: 404 });

  // Our own Origin (a page from this port) and no Origin (the nbb peer).
  const page = await openWs(base + '?role=offerer', { origin: `http://localhost:${port}` });
  assert.ok(page.ok);
  opened.push(page.ws);
  assert.deepStrictEqual(page.first, { type: 'joined', role: 'offerer', room: 't1' });
  const pagePeerReady = nextMessage(page.ws);
  const peer = await openWs(base + '?role=answerer');
  assert.ok(peer.ok);
  opened.push(peer.ws);
  assert.strictEqual(peer.first.role, 'answerer');
  assert.deepStrictEqual(await pagePeerReady, { type: 'ready' });
  const relayed = nextMessage(peer.ws);
  page.ws.send(JSON.stringify({ type: 'signal', description: { type: 'offer', sdp: 'x' } }));
  let m = await relayed;
  if (m.type === 'ready') m = await nextMessage(peer.ws);
  assert.deepStrictEqual(m, { type: 'signal', description: { type: 'offer', sdp: 'x' } });
} finally {
  for (const ws of opened) ws.terminate();
  await srv.close();
}

// Symlink out of the served root.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-server-'));
try {
  const site = path.join(tmp, 'site');
  fs.mkdirSync(site);
  fs.writeFileSync(path.join(tmp, 'secret.txt'), 'secret');
  fs.symlinkSync(path.join(tmp, 'secret.txt'), path.join(site, 'leak.txt'));
  const s2 = await startDacite({ port: 0, root: site });
  try {
    const r = await get(s2.port, '/app/leak.txt');
    assert.strictEqual(r.status, 403);
    assert.ok(!r.body.includes('secret'));
  } finally {
    await s2.close();
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
console.log('dacite-server ok');
