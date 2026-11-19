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
 *    or none (not a browser), joins the room with the token.
 * 4. The /signal token (docs/design/packaging.md §P1): no token, a wrong
 *    token, a short or long token, and a token without `dacite.` framing
 *    are 401, with or without an Origin; the right token joins, and the
 *    server answers `dacite.v1` only, never the token entry.
 * 5. Two sockets in one room relay a signal through /signal.
 * 6. A symlink out of the served root is 403.
 * 7. Every HTTP response carries `Referrer-Policy: no-referrer`.
 */
import crypto from 'crypto';
import assert from 'assert';
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { WebSocket } from 'ws';
import { offeredToken, startDacite, tokenOk } from './dacite-server.mjs';

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

const TOKEN = crypto.randomBytes(32).toString('base64url');
const withToken = (t) => ['dacite.v1', 'dacite.token.' + t];

/**
 * Resolves {ok, ws, first, protocol, answered} on the first message
 * (`answered` is the raw Sec-WebSocket-Protocol response header), or
 * {ok:false, status} if refused. `protocols` defaults to the right token.
 */
function openWs(url, headers = {}, protocols = withToken(TOKEN)) {
  return new Promise((resolve, reject) => {
    const ws = protocols
      ? new WebSocket(url, protocols, { headers, perMessageDeflate: false })
      : new WebSocket(url, { headers, perMessageDeflate: false });
    let answered;
    ws.once('upgrade', (res) => { answered = res.headers['sec-websocket-protocol']; });
    ws.once('unexpected-response', (_req, res) => {
      resolve({ ok: false, status: res.statusCode });
      ws.terminate();
    });
    ws.once('message', (d) => resolve({ ok: true, ws, first: JSON.parse(d.toString()), protocol: ws.protocol, answered }));
    ws.once('error', (e) => { if (!/Unexpected server response/.test(String(e))) reject(e); });
  });
}

function nextMessage(ws) {
  return new Promise((resolve) => ws.once('message', (d) => resolve(JSON.parse(d.toString()))));
}

// The token helpers.
assert.strictEqual(offeredToken('dacite.v1, dacite.token.abc'), 'abc');
assert.strictEqual(offeredToken('dacite.token.abc,dacite.v1'), 'abc');
assert.strictEqual(offeredToken('dacite.v1'), null);
assert.strictEqual(offeredToken(undefined), null);
assert.strictEqual(tokenOk(TOKEN, TOKEN), true);
assert.strictEqual(tokenOk(TOKEN, null), false);
assert.strictEqual(tokenOk(TOKEN, TOKEN.slice(1)), false);
assert.strictEqual(tokenOk(TOKEN, TOKEN + 'x'), false);
assert.strictEqual(TOKEN.length, 43);

// No token, no server.
await assert.rejects(startDacite({ port: 0 }), /needs a \/signal token/);

const srv = await startDacite({ port: 0, token: TOKEN });
const { port } = srv;
const opened = [];
try {
  assert.strictEqual(srv.host, '127.0.0.1');
  assert.strictEqual(srv.signalUrl, `ws://127.0.0.1:${port}/signal`);

  // Static on the same port, both local Host spellings.
  let r = await get(port, '/app/browser/');
  assert.strictEqual(r.status, 200);
  assert.match(r.headers['content-type'], /text\/html/);
  assert.strictEqual(r.headers['referrer-policy'], 'no-referrer');
  r = await get(port, '/app/browser/', `localhost:${port}`);
  assert.strictEqual(r.status, 200);
  r = await get(port, '/app', `127.0.0.1:${port}`);
  assert.strictEqual(r.status, 301);

  // Host checks.
  for (const h of ['evil.example', `evil.example:${port}`, `127.0.0.1:${port + 1}`, `localhost`, `[::1]:${port}`]) {
    r = await get(port, '/app/browser/', h);
    assert.strictEqual(r.status, 403, `Host ${h}`);
    assert.strictEqual(r.headers['referrer-policy'], 'no-referrer', `Host ${h}`);
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

  // A foreign Origin is 403 even with the right token; a wrong Host too.
  w = await openWs(base, { origin: 'http://evil.example' }, withToken(TOKEN));
  assert.deepStrictEqual(w, { ok: false, status: 403 });

  // The token: 401 without the right one, with our Origin or with none.
  const own = { origin: `http://localhost:${port}` };
  const wrong = crypto.randomBytes(32).toString('base64url');
  for (const [label, headers] of [['own Origin', own], ['no Origin', {}]]) {
    for (const [what, protocols] of [
      ['no subprotocol', null],
      ['dacite.v1 only', ['dacite.v1']],
      ['a wrong token', withToken(wrong)],
      ['a short token', withToken(TOKEN.slice(0, 42))],
      ['a long token', withToken(TOKEN + 'A')],
      ['an empty token', ['dacite.v1', 'dacite.token.']],
      ['the bare token', ['dacite.v1', TOKEN]],
    ]) {
      w = await openWs(base, headers, protocols);
      assert.deepStrictEqual(w, { ok: false, status: 401 }, `${label}, ${what}`);
    }
  }

  // Our own Origin (a page from this port) and no Origin (the nbb peer),
  // each with the token. The answer is dacite.v1, never the token entry.
  const page = await openWs(base + '?role=offerer', { origin: `http://localhost:${port}` });
  assert.ok(page.ok);
  opened.push(page.ws);
  assert.strictEqual(page.protocol, 'dacite.v1');
  assert.strictEqual(page.answered, 'dacite.v1');
  assert.deepStrictEqual(page.first, { type: 'joined', role: 'offerer', room: 't1' });
  const pagePeerReady = nextMessage(page.ws);
  const peer = await openWs(base + '?role=answerer');
  assert.ok(peer.ok);
  opened.push(peer.ws);
  assert.strictEqual(peer.answered, 'dacite.v1');
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
  const s2 = await startDacite({ port: 0, root: site, token: TOKEN });
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
