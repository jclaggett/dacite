#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium launch, three WebRTC handshakes
 * and a fourth relay session (the peer page), and four launches of the
 * nbb peer. Browser startup and ICE are outside the 3 second
 * bound. The value is the small welcome value.
 *
 * `npx dacite` end to end (packaging slice 1): start `bin/dacite.mjs` on a
 * free port, open the Value Browser URL it prints (served by that same
 * process, with its /signal relay, room, and token in the fragment), and
 * fetch the welcome value over peering. The page strips the token from the
 * address bar. Then reload the page and fetch it again: the token comes
 * from sessionStorage, and the peer rejoins the room for a second session.
 * No request to the server carries the hash or the token, and every
 * request goes to the one port. Last, a fresh tab given the URL without
 * the token is refused (`no-token`; the server answers 401).
 *
 * The store (packaging slice 3) is the default one in a temp `DACITE_HOME`
 * the peer creates (0700 on POSIX). The run file (slice 4) holds the
 * printed URL and token, mode 0600 in a 0700 `run/`. `npx dacite` again on
 * the same port prints the same URL and exits 0. A second launch on the
 * store with another port, while the first runs, exits 1 naming the first's
 * pid. Then the first is killed (SIGKILL, so its `LOCK` stays), and a
 * third launch takes the stale `LOCK` over with the warning, prints the
 * same value and entry count (the store kept it; nothing is put again),
 * and a fresh tab fetches it from that peer. Its clean exit (SIGTERM)
 * removes its run file; the killed peer's run file stays.
 *
 * The peer page (slice 5) is opened as `localhost` with the relay, room,
 * and token in its fragment: the relay accepts its token (`session 3
 * connected`), the token leaves its address bar, and none of its requests
 * carries it.
 *
 * Requires `npm run build` first (the browser and peer bundles).
 * `DACITE_E2E_BIN` runs another `bin/dacite.mjs`, such as the one in an
 * installed `npm pack` tarball (the CI pack step); its own
 * `examples/web` must hold the bundles.
 */
import { spawn } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webDir = process.env.DACITE_E2E_BIN
  ? path.join(path.dirname(process.env.DACITE_E2E_BIN), '..', 'examples', 'web')
  : path.join(root, 'examples', 'web');
const bundle = path.join(webDir, 'browser', 'js', 'main.js');
const peerBundle = path.join(webDir, 'peer', 'js', 'main.js');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The default store, in a temp data dir (DACITE_HOME) that the peer
// creates (so its 0700 mode is the peer's doing, not mkdtemp's).
const home = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'dacite-e2e-'));
const dataDir = path.join(home, 'data');
const storeDir = path.join(dataDir, 'store');
const posix = process.platform !== 'win32';
const mode = (p) => fs.statSync(p).mode & 0o777;

// DACITE_E2E_BIN runs a packed and installed `npx dacite` instead of the
// checkout's bin (docs/design/packaging.md §P4, the CI pack step).
const bin = process.env.DACITE_E2E_BIN || path.join(root, 'bin', 'dacite.mjs');

function startDacite(port = '0') {
  const child = spawn(process.execPath, [bin, '--port', port], {
    cwd: home,
    env: { ...process.env, DACITE_HOME: dataDir },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const log = { child, out: '', err: '' };
  child.stdout.on('data', (d) => { log.out += d; process.stdout.write(d); });
  child.stderr.on('data', (d) => { log.err += d; process.stderr.write(d); });
  return log;
}

async function waitMatch(log, re, ms, label, stream = 'out') {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const m = log[stream].match(re);
    if (m) return m;
    if (log.child.exitCode != null) break;
    await sleep(50);
  }
  throw new Error(`timed out waiting for ${label}\n--- stdout ---\n${log.out}\n--- stderr ---\n${log.err}`);
}

async function fetchHash(page, hex) {
  await page.fill('#uri', 'dacite://local#urn:dacite:' + hex);
  await page.fill('#urn', '');
  await page.click('#fetch');
  await page.waitForFunction(
    (expected) => {
      const hash = document.getElementById('hash')?.dataset.hash || '';
      const code = document.getElementById('status')?.dataset.code || '';
      return hash === expected && code !== '';
    },
    hex,
    { timeout: 45000 },
  );
  return {
    code: await page.locator('#status').getAttribute('data-code'),
    entries: await page.locator('#entries').getAttribute('data-count'),
    verified: await page.locator('#verified').getAttribute('data-count'),
  };
}

async function main() {
  for (const b of [bundle, peerBundle]) {
    if (!fs.existsSync(b)) throw new Error('missing ' + b + ' — run npm run build');
  }
  const { chromium } = await import('playwright');
  const d = startDacite();
  let browser;
  let third;
  let origin3 = 'none';
  try {
    const open = (await waitMatch(d, /^open (\S+)$/m, 20000, 'open'))[1];
    const hex = (await waitMatch(d, /^value ([0-9a-f]{64})$/m, 1000, 'value'))[1];
    const entries = (await waitMatch(d, /^entries (\d+)$/m, 1000, 'entries'))[1];
    const storeLine = (await waitMatch(d, /^store (lmdb|file) (.+)$/m, 1000, 'store'));
    const origin = new URL(open).origin;
    const frag = new URLSearchParams(new URL(open).hash.slice(1));
    const signal = frag.get('signal');
    const token = frag.get('token');
    if (new URL(open).search) throw new Error('the printed URL has a query: ' + open);
    if (!/^[A-Za-z0-9_-]{43}$/.test(token || '')) throw new Error('no 43-character token in the fragment: ' + open);
    if (signal !== origin.replace(/^http/, 'ws') + '/signal') throw new Error('signal is not on the page port: ' + signal);

    browser = await chromium.launch({
      headless: true,
      args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--no-sandbox', '--disable-dev-shm-usage'],
    });
    const context = await browser.newContext();
    const page = await context.newPage();
    const urls = [];
    page.on('request', (req) => urls.push(req.url()));
    page.on('pageerror', (err) => console.log('pageerror', err));

    const problems = [];
    const eq = (name, a, b) => { if (a !== b) problems.push(`${name}: ${a} !== ${b}`); };
    for (const round of [1, 2]) {
      if (round === 1) await page.goto(open, { waitUntil: 'domcontentloaded' });
      else await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForFunction(
        () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
        null,
        { timeout: 20000 },
      );
      eq(`round ${round} signal field`, await page.inputValue('#signal'), signal);
      eq(`round ${round} token in the address bar`, page.url().includes(token), false);
      const got = await fetchHash(page, hex);
      eq(`round ${round} code`, got.code, 'verified');
      eq(`round ${round} entries`, got.entries, entries);
      eq(`round ${round} verified`, got.verified, entries);
      await waitMatch(d, new RegExp(`^session ${round} connected$`, 'm'), 10000, `session ${round}`, 'err');
    }
    // A fresh tab (no sessionStorage) given the URL without the token.
    frag.delete('token');
    const bare = await context.newPage();
    await bare.goto(`${origin}/app/browser/#${frag}`, { waitUntil: 'domcontentloaded' });
    await bare.waitForFunction(
      () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
      null,
      { timeout: 20000 },
    );
    await bare.fill('#uri', 'dacite://local#urn:dacite:' + hex);
    await bare.fill('#urn', '');
    await bare.click('#fetch');
    await bare.waitForFunction(
      () => (document.getElementById('status')?.dataset.code || '') !== '',
      null,
      { timeout: 20000 },
    );
    eq('no token code', await bare.locator('#status').getAttribute('data-code'), 'no-token');
    await bare.close();

    // The peer page (packaging slice 5) reads signal, room, and token from
    // the fragment as the Value Browser does, strips them from the address
    // bar, and sends the token as the `dacite.token.<t>` subprotocol only to
    // its own host. It is opened as localhost while the relay is 127.0.0.1,
    // so this also checks that loopback names count as one host. The relay
    // refuses a socket without the token, so `session 3 connected` is the
    // proof. The page then reads over the example peer's roots channel,
    // which `npx dacite` does not serve, so the read itself is not checked
    // here (peer:e2e covers it against the example peer).
    await page.close();
    await waitMatch(d, /^session 2 ended/m, 10000, 'session 2 ended', 'err');
    const peerPage = await context.newPage();
    // The peer page names its root in the query by design, so its requests
    // are kept apart from the no-hash-over-HTTP check below.
    const peerUrls = [];
    peerPage.on('request', (req) => peerUrls.push(req.url()));
    const peerFrag = new URLSearchParams({ signal, room: frag.get('room'), token }).toString();
    const local = origin.replace('127.0.0.1', 'localhost');
    await peerPage.goto(`${local}/app/peer/?root=${hex}#${peerFrag}`,
      { waitUntil: 'domcontentloaded' });
    await waitMatch(d, /^session 3 connected$/m, 15000, 'session 3 (peer page, token accepted)', 'err');
    eq('peer page token in the address bar', peerPage.url().includes(token), false);
    eq('peer page token in a request', peerUrls.some((u) => u.includes(token)), false);
    eq('peer page requests off the one port', peerUrls.filter((u) => !u.startsWith(local + '/')).join(' | '), '');
    await peerPage.close();
    await waitMatch(d, /^session 3 ended/m, 10000, 'session 3 ended', 'err');
    eq('store dir', storeLine[2], storeDir);
    // One line: the engine, a space, and its store format version.
    eq('engine marker', fs.readFileSync(path.join(storeDir, 'ENGINE'), 'utf8'),
      `${storeLine[1]} ${{ lmdb: 2, file: 1 }[storeLine[1]]}\n`);
    eq('LOCK holds the pid', fs.readFileSync(path.join(storeDir, 'LOCK'), 'utf8').trim(), String(d.child.pid));

    // The run file (§P2): the URL with its token, 0600 in a 0700 run/.
    const port1 = new URL(open).port;
    const runPath = path.join(dataDir, 'run', `${port1}.json`);
    const runFile = JSON.parse(fs.readFileSync(runPath, 'utf8'));
    eq('run line', (await waitMatch(d, /^run (.+)$/m, 1000, 'run'))[1], runPath);
    eq('run file open', runFile.open, open);
    eq('run file token', runFile.token, token);
    eq('run file pid', runFile.pid, d.child.pid);
    eq('run file store', runFile.store, storeDir);
    if (posix) {
      eq('run file mode', mode(runPath), 0o600);
      eq('run dir mode', mode(path.dirname(runPath)), 0o700);
      eq('data dir mode', mode(dataDir), 0o700);
    }

    // npx dacite again on the same port: the same URL, exit 0 (§P2).
    const reuse = startDacite(port1);
    await new Promise((r) => (reuse.child.exitCode != null ? r() : reuse.child.once('exit', r)));
    eq('reuse exit', reuse.child.exitCode, 0);
    eq('reuse open', (reuse.out.match(/^open (\S+)$/m) || [])[1], open);

    // A second peer on the same store is refused while the first runs.
    const second = startDacite();
    await new Promise((r) => (second.child.exitCode != null ? r() : second.child.once('exit', r)));
    eq('second launch exit', second.child.exitCode, 1);
    if (!second.err.includes(`is in use by pid ${d.child.pid}`)) problems.push('second launch: ' + second.err);

    // Kill the first without cleanup, then start again on the same store.
    const killed = d.child.pid;
    d.child.kill('SIGKILL');
    await new Promise((r) => (d.child.exitCode != null || d.child.signalCode ? r() : d.child.once('exit', r)));
    third = startDacite();
    await waitMatch(third, new RegExp(`^taking over a stale LOCK from pid ${killed}$`, 'm'), 20000, 'takeover', 'err');
    const open3 = (await waitMatch(third, /^open (\S+)$/m, 20000, 'open after restart'))[1];
    eq('value after restart', (await waitMatch(third, /^value ([0-9a-f]{64})$/m, 1000, 'value'))[1], hex);
    eq('entries after restart', (await waitMatch(third, /^entries (\d+)$/m, 1000, 'entries'))[1], entries);
    const again = await context.newPage();
    again.on('request', (req) => urls.push(req.url()));
    await again.goto(open3, { waitUntil: 'domcontentloaded' });
    await again.waitForFunction(
      () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
      null,
      { timeout: 20000 },
    );
    const got3 = await fetchHash(again, hex);
    eq('restart code', got3.code, 'verified');
    eq('restart entries', got3.entries, entries);
    await again.close();
    // A clean exit removes its run file; the killed peer's stays (stale).
    const port3 = new URL(open3).port;
    const runPath3 = path.join(dataDir, 'run', `${port3}.json`);
    eq('third run file', fs.existsSync(runPath3), true);
    third.child.kill('SIGTERM');
    await new Promise((r) => (third.child.exitCode != null ? r() : third.child.once('exit', r)));
    eq('third exit', third.child.exitCode, 0);
    eq('run file removed on a clean exit', fs.existsSync(runPath3), false);
    eq('stale run file of the killed peer', fs.existsSync(runPath), true);
    origin3 = new URL(open3).origin;
    const token3 = new URLSearchParams(new URL(open3).hash.slice(1)).get('token');
    if (urls.some((u) => u.includes(token3))) problems.push('restart token in a request URL');

    const withToken = urls.filter((u) => u.includes(token));
    if (withToken.length) problems.push('token in a request URL: ' + withToken.join(' | '));
    const elsewhere = urls.filter((u) => ![origin, origin3].some((o) => u.startsWith(o + '/')));
    if (elsewhere.length) problems.push('requests off the one port: ' + elsewhere.join(' | '));
    const leaked = urls.filter((u) => u.includes(hex) || u.includes('urn:dacite') || u.includes('/node/'));
    if (leaked.length) problems.push('hash requested over HTTP: ' + leaked.join(' | '));
    if (problems.length) throw new Error(problems.join('\n'));
    console.log('ok', JSON.stringify({
      entries: Number(entries), sessions: 3, peerPageToken: true, refusedWithoutToken: true,
      engine: storeLine[1], reused: true, secondRefused: true, staleLockTakenOver: true,
    }));
  } finally {
    if (browser) await browser.close().catch(() => {});
    for (const p of [d, third]) if (p && p.child.exitCode == null) p.child.kill('SIGTERM');
    if (third) await new Promise((r) => (third.child.exitCode != null ? r() : third.child.once('exit', r)));
    fs.rmSync(home, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
