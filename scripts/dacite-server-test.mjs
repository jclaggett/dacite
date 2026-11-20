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
 * 8. `GET /dacite/version` (docs/design/packaging.md §P2): the version,
 *    proto, and pid as JSON, Host-checked, no CORS header, GET and HEAD
 *    only; `probeDacite` reads it and returns null for anything else.
 * 9. Port reuse: the bin on a taken port three ways (§P2 Tests). A plain
 *    HTTP server and a dacite of another version exit 1 with their line,
 *    before nbb loads. Our version with its run file prints the run
 *    file's URL and exits 0.
 *
 * JUSTIFICATION: step 9's reuse case starts the bin, which loads nbb and
 * the peer namespaces (about 1.5 s) before it reads the run file. The
 * other two exit before nbb loads.
 */
import crypto from 'crypto';
import assert from 'assert';
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { WebSocket } from 'ws';
import { PROTO, VERSION, isDaciteInfo, offeredToken, probeDacite, startDacite, tokenOk, versionInfo } from './dacite-server.mjs';

const bin = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'bin', 'dacite.mjs');

/** Run the bin to its exit: {code, out, err}. */
function runBin(args, env, binPath = bin) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [binPath, ...args], {
      env: { ...process.env, ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
    child.on('error', reject);
    child.on('exit', (code) => { clearTimeout(timer); resolve({ code, out, err }); });
  });
}

function request(port, p, method, hostHeader) {
  return new Promise((resolve, reject) => {
    const headers = hostHeader === undefined ? {} : { host: hostHeader };
    const req = http.request({ host: '127.0.0.1', port, path: p, method, headers }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

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

  // GET /dacite/version.
  r = await get(port, '/dacite/version');
  assert.strictEqual(r.status, 200);
  assert.match(r.headers['content-type'], /application\/json/);
  assert.strictEqual(r.headers['cache-control'], 'no-store');
  assert.strictEqual(r.headers['access-control-allow-origin'], undefined, 'no CORS');
  assert.strictEqual(r.headers['referrer-policy'], 'no-referrer');
  assert.deepStrictEqual(JSON.parse(r.body), { dacite: VERSION, proto: 1, pid: process.pid });
  assert.deepStrictEqual(versionInfo(), { dacite: VERSION, proto: PROTO, pid: process.pid });
  assert.strictEqual(VERSION, JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version);
  r = await get(port, '/dacite/version?x=1', `localhost:${port}`);
  assert.strictEqual(r.status, 200);
  r = await get(port, '/dacite/version', 'evil.example');
  assert.strictEqual(r.status, 403, 'version is Host-checked');
  r = await request(port, '/dacite/version', 'HEAD');
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body, '');
  r = await request(port, '/dacite/version', 'POST');
  assert.strictEqual(r.status, 405);
  assert.deepStrictEqual(await probeDacite(port), { dacite: VERSION, proto: 1, pid: process.pid });
  assert.strictEqual(isDaciteInfo({ dacite: '1', proto: 1, pid: 0 }), false);
  assert.strictEqual(isDaciteInfo({ dacite: 1, proto: 1, pid: 2 }), false);
  assert.strictEqual(isDaciteInfo(null), false);

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
// probeDacite on things that are not a dacite.
{
  const plain = http.createServer((req, res) => {
    if (req.url === '/dacite/version') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"name": "something else"}');
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  const silent = http.createServer(() => { /* never answers */ });
  const notJson = http.createServer((req, res) => { res.writeHead(200); res.end('<html>'); });
  const servers = [plain, silent, notJson];
  for (const sv of servers) await new Promise((r) => sv.listen(0, '127.0.0.1', r));
  try {
    assert.strictEqual(await probeDacite(plain.address().port), null, 'another JSON shape');
    const t0 = Date.now();
    assert.strictEqual(await probeDacite(silent.address().port, { timeoutMs: 200 }), null, 'no answer');
    assert.ok(Date.now() - t0 < 1000, 'the probe times out');
    assert.strictEqual(await probeDacite(notJson.address().port), null, 'not JSON');
    const closed = plain.address().port;
    await new Promise((r) => plain.close(r));
    assert.strictEqual(await probeDacite(closed), null, 'nothing listening');
  } finally {
    for (const sv of servers) { sv.closeAllConnections?.(); sv.close(); }
  }
}

// Port reuse: the bin on a taken port, three ways. DACITE_HOME is a temp
// dir, never the real data dir.
{
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-reuse-'));
  const env = { DACITE_HOME: home };
  const plain = http.createServer((req, res) => { res.writeHead(404); res.end(); });
  await new Promise((r) => plain.listen(0, '127.0.0.1', r));
  const other = await startDacite({ port: 0, token: TOKEN, version: '0.0.1-other' });
  const ours = await startDacite({ port: 0, token: TOKEN });
  try {
    // A plain HTTP server.
    let b = await runBin(['--port', String(plain.address().port)], env);
    assert.strictEqual(b.code, 1, b.err);
    assert.strictEqual(b.err.trim(), `port ${plain.address().port} is in use by another program; pass --port.`);
    assert.strictEqual(b.out, '');

    // A dacite of another version.
    b = await runBin(['--port', String(other.port)], env);
    assert.strictEqual(b.code, 1, b.err);
    assert.strictEqual(b.err.trim(),
      `dacite 0.0.1-other (pid ${process.pid}) is running on port ${other.port}; this is ${VERSION}. Stop it or pass --port.`);

    // Our version, with the run file a peer on that port writes.
    const open = `http://127.0.0.1:${ours.port}/app/browser/#signal=x&room=r&token=${TOKEN}`;
    fs.mkdirSync(path.join(home, 'run'), { recursive: true });
    fs.writeFileSync(path.join(home, 'run', `${ours.port}.json`), JSON.stringify({
      ...versionInfo(), port: ours.port, url: ours.appUrl, signal: ours.signalUrl, room: 'r',
      token: TOKEN, open, store: path.join(home, 'store'), engine: 'lmdb', value: 'ab12',
    }));
    b = await runBin(['--port', String(ours.port)], env);
    assert.strictEqual(b.code, 0, b.err);
    assert.deepStrictEqual(b.out.trim().split('\n'), [
      `dacite already running ${ours.appUrl} (pid ${process.pid})`,
      `store lmdb ${path.join(home, 'store')}`,
      'value ab12',
      `open ${open}`,
      'paste dacite://local#urn:dacite:ab12',
    ]);
    assert.ok(!fs.existsSync(path.join(home, 'store')), 'reuse opens no store');
  } finally {
    plain.close();
    await other.close();
    await ours.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
}
// A checkout with no app bundle (§P4: bundles are built, not committed):
// the bin says how to build it and exits 1. A copy of the bin's files
// without examples/web/browser/js/main.js; node_modules is linked.
{
  const pkg = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-nobundle-'));
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-nobundle-home-'));
  const src = path.join(path.dirname(bin), '..');
  try {
    for (const f of ['package.json', 'bin/dacite.mjs', 'bin/dacite-peer.cljs', 'scripts/dacite-args.mjs',
      'scripts/dacite-server.mjs', 'scripts/static-server.mjs', 'scripts/webrtc-signal-server.mjs']) {
      fs.mkdirSync(path.dirname(path.join(pkg, f)), { recursive: true });
      fs.copyFileSync(path.join(src, f), path.join(pkg, f));
    }
    fs.mkdirSync(path.join(pkg, 'examples', 'web', 'browser'), { recursive: true });
    fs.symlinkSync(path.join(src, 'node_modules'), path.join(pkg, 'node_modules'));
    const b = await runBin(['--port', '0', '--memory'], { DACITE_HOME: home }, path.join(pkg, 'bin', 'dacite.mjs'));
    assert.strictEqual(b.code, 1, b.err);
    assert.strictEqual(b.err.trim(), 'no app bundle: run npm run build (needs Java 21)');
    assert.strictEqual(b.out, '');
    assert.deepStrictEqual(fs.readdirSync(home), [], 'nothing written');
  } finally {
    fs.rmSync(pkg, { recursive: true, force: true });
    fs.rmSync(home, { recursive: true, force: true });
  }
}
console.log('dacite-server ok');
